#!/usr/bin/env bb

(ns snapraid-aio
  ;; The namespace docstring is also output when the --help option is used.
  "
   All-in-one SnapRAID management script.

   This script reads the SnapRAID configuration, performs pre-flight checks (e.g. all drives mounted, dependencies
   present, etc.), then runs SnapRAID commands:
   - diff is run to determine if any changes have been made.
   - Permissions are backed up only if there are changes.
   - sync is only run if there are changes.
   - scrub is run to check data and parity for errors.

   USAGE:
     snapraid_aio.bb [OPTIONS]

   OPTIONS:
     -c, --config          Path to the SnapRAID configuration file
     -h, --help            Show the help message
     -i, --ignore-smart    Continue even when S.M.A.R.T. tests indicate problems
     -p, --scrub-percent   The percentage of blocks for SnapRAID to scrub
     -s, --skip-scrub      Don't run SnapRAID scrub
     -v, --version         Show the version

   AUTHOR:
     jittery-name-ninja@duck.com"

  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :refer [shell sh]]
            [snapraid-config :as sconf]
            [drive :as drive]
            [logging :as log]
            [lock :as lock])
  (:import (java.time LocalDateTime ZoneId)
           (java.time.format DateTimeFormatter)))

;;; ----------------------------------------------------------------------------
;;; Constants.
;;; ----------------------------------------------------------------------------

(def ^:const cli-options
  [["-c" "--config FILE" "SnapRAID configuration file"]
   ["-h" "--help" "Show the help message"]
   ["-i" "--ignore-smart" "Continue even when S.M.A.R.T. tests indicate problems"]
   ["-p"
    "--scrub-percent PERCENT"
    "Percentage of blocks for SnapRAID to scrub (0-100)"
    :parse-fn (fn [s] (try (Long/parseLong s) (catch Exception _ nil)))
    :validate [#(and (number? %) (<= 0 % 100)) "Must be an integer between 0 and 100"]]
   ["-s" "--skip-scrub" "Don't run SnapRAID scrub"]
   ["-v" "--version" "Version"]])

(def ^:const version "0.0.6")
(def ^:const script-name "snapraid_aio.bb")
(def ^:const perms-archive-retention-count 1)
(def ^:const lock-file (str "/tmp/" script-name ".lock"))
(def ^:const diff-keys [:equal :added :removed :updated :moved :copied :restored])

(def ^:const exit-codes
  {:success          0
   :fail             1
   :preflight-fail   2
   :smart-fail       3
   :snapraid-fail    4
   :sync-fail        5
   :scrub-fail       6
   :lock-fail        7
   :diff-fail        8
   :permissions-fail 9})

(defonce lock-state (atom nil))

;;; ----------------------------------------------------------------------------
;;; General file and shell functions.
;;; ----------------------------------------------------------------------------

(defn uid
  "Gets the user id of the current process"
  []
  (try
    (Integer/parseInt (str/trim (:out (sh "id" "-u"))))
    (catch Exception _ -1)))

;(defn resolve-file-path
;  "Returns the first file path from file-paths that exists and is readable."
;  [file-paths]
;  (let [candidates (remove nil? file-paths)]
;    (some #(when (and (fs/exists? %) (fs/readable? %)) %) candidates)))

(defn program-exists?
  "Checks if a program exists."
  [prog]
  (some? (fs/which prog)))

(defn parse-opts
  "Parses command line options."
  [args opts]
  (let [parsed-args (cli/parse-opts args opts)
        {:keys [errors]} parsed-args]
    (when errors
      (binding [*out* *err*]
        (doseq [e errors] (log/error e))
        (System/exit (:preflight-fail exit-codes))))
    parsed-args))

(defn snapraid-running?
  "Checks if SnapRAID is already running."
  []
  (let [res (shell {:out      :string
                    :err      :string
                    :continue true}
                   "pgrep" "-f" "-l" "\\bsnapraid(\\s|$)")]
    (zero? (:exit res))))

;;; ----------------------------------------------------------------------------
;;; SnapRAID diff functions.
;;; ----------------------------------------------------------------------------

(defn parse-diff-output
  "Parses the output of running snapraid diff into EDN."
  [out]
  (let [pattern #"^\s*(\d+)\s+(\w+)$"]
    (->> (str/split-lines out)
         (keep #(when-let [[_ num key] (re-matches pattern %)]
                  [(keyword key) (parse-long num)]))
         (into {}))))

(defn snapraid-diff
  "Runs snapraid diff using the SnapRAID configuration at conf, and returns the counts."
  [conf]
  (let [{:keys [out err exit]} (shell {:out      :string
                                       :err      :string
                                       :continue true}
                                      "snapraid" "--conf" conf "--quiet" "--quiet" "--quiet" "diff")
        result {:exit exit :out out :err err}]

    ;; If sync required, exit is 2. if error, 1. otherwise 0.
    (if (= exit 2)
      (merge (parse-diff-output out) result)
      result)))

;;; ----------------------------------------------------------------------------
;;; Permissions back-up functions.
;;; ----------------------------------------------------------------------------

(defn now-tag []
  (.format (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
           (LocalDateTime/now (ZoneId/of "America/New_York"))))

(defn hostname []
  (-> (shell {:out :string :err :string :continue true} "hostname")
      :out str/trim))

(defn sanitize-name [s]                                     ; safe for filenames
  (-> s (str/replace #"[^A-Za-z0-9._-]+" "_")))

(defn list-archives [drive-path]
  (when (fs/exists? drive-path)
    (->> (fs/list-dir drive-path)
         (map str)
         (filter #(re-find #"acl.*\.zip$" %))
         (sort))))

(defn prune-old-archives! [drive-path]
  (let [archives (vec (list-archives drive-path))]
    (when (> (count archives) perms-archive-retention-count)
      (doseq [old (take (- (count archives) perms-archive-retention-count) archives)]
        (try (fs/delete-if-exists old)
             (catch Exception _))))))

(defn backup-permissions!
  "Dumps ACLs for each drive and copies a single zip archive to every drive.
   Options:
   - drives: vector of {:name :path}
   Returns the path to the temp zip archive."
  [{:keys [drives]}]

  (let [tmp-root (fs/create-temp-dir "snapraid-perms-")
        ts (now-tag)
        host (hostname)
        archive (fs/path tmp-root (format "acl-%s-%s.zip" host ts))
        manifest (fs/path tmp-root "manifest.edn")]

    ;; 1) dump .facl per drive into tmp
    (doseq [{:keys [name path]} drives]
      (let [safe-name (sanitize-name name)
            out-file (fs/path tmp-root (str safe-name ".facl"))]
        ;; --one-file-system prevents crossing into other mounts
        ;; --absolute-names includes absolute paths so --restore works from anywhere
        ;; -n uses numeric IDs (stable even if usernames differ later)
        (let [cmd ["bash" "-lc" (format "getfacl -R --absolute-names --one-file-system -n %s" (pr-str path))]
              res (apply shell {:out :string :err :string :continue true} cmd)]
          (when-not (zero? (:exit res))
            (log/error (str "Unable to get ACLs on " path ": " (:err res)))
            (System/exit (:permissions-fail exit-codes)))
          (spit (str out-file) (:out res)))))

    ;; 2) write a small manifest for convenience
    (spit (str manifest) (with-out-str (pp/pprint {:created  ts
                                                   :hostname host
                                                   :drives   (map #(select-keys % [:name :path]) drives)})))

    ;; 3) zip all .facl + manifest into one archive
    (let [to-zip (->> (fs/list-dir tmp-root)
                      (map str)
                      (filter #(re-find #"\.(facl|edn)$" %))
                      (into []))]
      (when (seq to-zip)
        (let [res (apply shell {:out :string :err :string :continue true} "zip" "-j" (str archive) to-zip)]
          (when-not (zero? (:exit res))
            (log/error (str "Unable to create zip file of permissions: " (:err res)))
            (System/exit (:permissions-fail exit-codes))))))

    ;; 4) copy the single archive to each drive under /.snapraid-perms/
    (doseq [{:keys [path]} drives]
      (let [dest (fs/path path (fs/file-name archive))]
        (fs/copy archive dest {:replace-existing true})
        (prune-old-archives! path)))

    (str archive)))

;;; ----------------------------------------------------------------------------
;;; SnapRAID sync functions.
;;; ----------------------------------------------------------------------------

(defn snapraid-sync!
  "Runs snapraid sync using the SnapRAID configuration file at conf.
  Use with caution. Once you sync, deleted files are effectively gone from the parity, making them unrecoverable."
  [conf]
  (let [{:keys [out err exit]} (shell {:out      :string
                                       :err      :string
                                       :continue true}
                                      "snapraid" "--conf" conf "--quiet" "sync")
        result {:out out :err err :exit exit}]
    result))

;;; ----------------------------------------------------------------------------
;;; SnapRAID scrub functions.
;;; ----------------------------------------------------------------------------

(defn snapraid-scrub
  "Runs snapraid scrub using the SnapRAID configuration file at conf, and the :scrub-percent from opt."
  [conf opt]
  (let [{:keys [out err exit]} (shell {:out      :string
                                       :err      :string
                                       :continue true}
                                      "snapraid" "--conf" conf "--plan" (or (:scrub-percent opt) 10) "scrub")
        result {:out out :err err :exit exit}]
    result))

;;; ----------------------------------------------------------------------------
;;; Main processing.
;;; ----------------------------------------------------------------------------

(defn -main [& args]

  ;; Configure logging. Root logs to /var/log/, everyone else to /tmp/ .
  (let [log-file (if (= (uid) 0)
                   (str "/var/log/" script-name ".log")
                   (str "/tmp/" script-name ".log"))]
    (log/configure! {:file log-file}))

  (let [parsed-args (parse-opts args cli-options)
        options (:options parsed-args)
        config-path (sconf/resolve-path (:config options))
        config (-> config-path slurp sconf/parse)]

    (when-not config
      (log/error "No readable snapraid.conf was found.")
      (System/exit (:preflight-fail exit-codes)))

    ;;; ----------------------------------------------------------------------------
    ;;; Handle --help or --version if they were used.
    ;;; ----------------------------------------------------------------------------

    ;; Show the help message
    (when (:help options)
      (println (:doc (meta (the-ns 'snapraid-aio))))
      (System/exit (:success exit-codes)))

    ;; Show the version
    (when (:version options)
      (println script-name "version" version)
      (System/exit (:success exit-codes)))

    ;;; ----------------------------------------------------------------------------
    ;;; Prevent other instances of the script from running.
    ;;; ----------------------------------------------------------------------------

    (lock/add-release-hook! lock-file lock-state)

    ;; Obtain the lock
    (when-not (lock/obtain-lock! lock-file lock-state)
      (log/error "Another instance is already running. Exiting.")
      (System/exit (:lock-fail exit-codes)))

    ;;; ----------------------------------------------------------------------------
    ;;; Run preflight checks.
    ;;; ----------------------------------------------------------------------------

    ;; Make sure the script is run with root privileges
    ;; TODO: Instead of forcing to run as root, check permissions on the resources we need to access, and error if
    ;; the current user doesn't have permissions to access those resources. That way the system admin can choose how to
    ;; run this script however they see fit.
    (let [id (uid)]
      (when-not (= 0 id)
        (log/error "Error: This script must be run as root (use sudo).")
        (System/exit (:preflight-fail exit-codes))))

    ;; Check that the snapraid command exists
    (when-not (program-exists? "snapraid")
      (log/error "SnapRAID not found")
      (System/exit (:snapraid-fail exit-codes)))

    ;; Check that the mountpoint command exists
    (when-not (program-exists? "mountpoint")
      (log/error "mountpoint command was not found")
      (System/exit (:preflight-fail exit-codes)))

    ;; Check that the findmnt command exists
    (when-not (program-exists? "findmnt")
      (log/error "findmnt command was not found")
      (System/exit (:preflight-fail exit-codes)))

    ;; Check that the getfacl command exists
    (when-not (program-exists? "getfacl")
      (log/error "getfacl command was not found")
      (System/exit (:preflight-fail exit-codes)))

    ;; Check that the zip command exists
    (when-not (program-exists? "zip")
      (log/error "zip command was not found")
      (System/exit (:preflight-fail exit-codes)))

    ;; Check that all the data drives are mounted
    (let [data-drives (:data config)]
      (when-not (every? drive/mounted? (mapv :path data-drives))
        (log/error "Not all of the data drives are mounted")
        (System/exit (:preflight-fail exit-codes))))

    ;; Check that all the parity drives are mounted
    (let [parity-files (:parity config)
          parity-drives (mapv fs/parent parity-files)]
      (when-not (every? drive/mounted? parity-drives)
        (log/error "Not all of the parity drives are mounted")
        (System/exit (:preflight-fail exit-codes))))

    ;;; ----------------------------------------------------------------------------
    ;;; Make sure the script isn't already being run.
    ;;; ----------------------------------------------------------------------------

    (when (snapraid-running?)
      (log/error "Another snapraid process is running")
      (System/exit (:preflight-fail exit-codes)))

    ;;; ----------------------------------------------------------------------------
    ;;; Run the S.M.A.R.T. Disk Checks.
    ;;; ----------------------------------------------------------------------------

    (let [data-drives (mapv :path (:data config))
          devices (mapv drive/mount-source data-drives)]
      (when-not (every? drive/smart-healthy? devices)
        (log/error "Some drives are unhealthy")
        (when-not (:ignore-smart options)
          (System/exit (:smart-fail exit-codes)))))

    ;;; ----------------------------------------------------------------------------
    ;;; Log the startup.
    ;;; ----------------------------------------------------------------------------

    (log/info (str "Running " script-name " ..."))
    (log/info (str script-name " version " version))
    (log/info (str "Using configuration from " config-path))
    (log/info (str "Logging to " (log/get-log-file)))
    (log/info (str "Lock file " lock-file))
    (log/info (str "Data drives " (mapv :path (:data config))))
    (log/info (str "Parity drives " (mapv #(str (fs/parent %)) (:parity config))))
    (when-let [pct (:scrub-percent options)]
      (log/info (str "Will scrub " pct "% of blocks")))

    ;;; ----------------------------------------------------------------------------
    ;;; Run snapraid diff to determine if permissions need to be saved and sync
    ;;; needs to be run.
    ;;; ----------------------------------------------------------------------------

    (log/info "Running snapraid diff...")

    (let [diff-result (snapraid-diff config-path)]
      (condp = (:exit diff-result)
        1 (do
            (log/error "snapraid diff failed")
            (System/exit (:diff-fail exit-codes)))
        2 (doseq [k diff-keys]
            (when-let [v (get diff-result k)]
              (log/info (str (str/capitalize (name k)) ": " v))))
        (log/info "No differences detected"))

      ;;; ----------------------------------------------------------------------------
      ;;; Back up permissions.
      ;;; ----------------------------------------------------------------------------

      ;; Only back up permissions if differences were detected.
      (if (and (= (:exit diff-result) 2)
               (some #(pos? (long (or (% diff-result) 0)))
                     [:added :removed :updated :moved :copied :restored]))
        (do
          (log/info "Saving permissions...")
          (backup-permissions! {:drives (:data config)}))
        (log/info "Skipping saving permissions"))

      ;;; ----------------------------------------------------------------------------
      ;;; Run snapraid sync.
      ;;; ----------------------------------------------------------------------------

      ;; Only run snapraid sync if differences were detected.
      (if (and (= (:exit diff-result) 2)
               (some #(pos? (long (or (% diff-result) 0)))
                     [:added :removed :updated :moved :copied :restored]))
        (do
          (log/info "Running snapraid sync...")
          (let [sync-result (snapraid-sync! config-path)]
            (if (= (:exit sync-result) 1)
              (do
                (log/error "snapraid sync failed")
                (System/exit (:sync-fail exit-codes))))))

        (log/info "Skipping snapraid sync")))

    ;;; ----------------------------------------------------------------------------
    ;;; Run snapraid scrub.
    ;;; ----------------------------------------------------------------------------

    (if-not (:skip-scrub options)
      (do
        (log/info "Running snapraid scrub...")
        (let [scrub-result (snapraid-scrub config-path options)]
          (if (= (:exit scrub-result) 1)
            (do
              (log/error "snapraid scrub failed")
              (System/exit (:scrub-fail exit-codes))))))
      (log/info "Skipping scrub"))

    ;;; ----------------------------------------------------------------------------
    ;;; Finish.
    ;;; ----------------------------------------------------------------------------

    (log/info "Done")
    (System/exit (:success exit-codes))))

;; Ensure -main is only called when this script is run directly,
;; not when it's required by another script.
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))