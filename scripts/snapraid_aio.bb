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

  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :refer [sh]]
            [snapraid]
            [snapraid-config :as srconf]
            [drive]
            [exit-codes :as excd]
            [logging :as log]
            [lock]
            [permissions :as perms]))

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
        (System/exit (:preflight-fail excd/codes))))
    parsed-args))


(defn -main [& args]
  ;; Configure logging. Root logs to /var/log/, everyone else to /tmp/ .
  (let [log-file (if (= (uid) 0)
                   (str "/var/log/" script-name ".log")
                   (str "/tmp/" script-name ".log"))]
    (log/configure! {:file log-file}))

  (let [parsed-args (parse-opts args cli-options)
        options (:options parsed-args)
        config-path (srconf/resolve-path (:config options))
        config (-> config-path slurp srconf/parse)]

    (when-not config
      (log/error "No readable snapraid.conf was found.")
      (System/exit (:preflight-fail excd/codes)))

    ;;; ----------------------------------------------------------------------------
    ;;; Handle --help or --version if they were used.
    ;;; ----------------------------------------------------------------------------

    ;; Show the help message
    (when (:help options)
      (println (:doc (meta (the-ns 'snapraid-aio))))
      (System/exit (:success excd/codes)))

    ;; Show the version
    (when (:version options)
      (println script-name "version" version)
      (System/exit (:success excd/codes)))

    ;;; ----------------------------------------------------------------------------
    ;;; Prevent other instances of the script from running.
    ;;; ----------------------------------------------------------------------------

    (lock/add-release-hook! lock-file lock-state)

    ;; Obtain the lock
    (when-not (lock/obtain-lock! lock-file lock-state)
      (log/error "Another instance is already running. Exiting.")
      (System/exit (:lock-fail excd/codes)))

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
        (System/exit (:preflight-fail excd/codes))))

    ;; Check that the snapraid command exists
    (when-not (program-exists? "snapraid")
      (log/error "SnapRAID not found")
      (System/exit (:snapraid-fail excd/codes)))

    ;; Check that the mountpoint command exists
    (when-not (program-exists? "mountpoint")
      (log/error "mountpoint command was not found")
      (System/exit (:preflight-fail excd/codes)))

    ;; Check that the findmnt command exists
    (when-not (program-exists? "findmnt")
      (log/error "findmnt command was not found")
      (System/exit (:preflight-fail excd/codes)))

    ;; Check that the getfacl command exists
    (when-not (program-exists? "getfacl")
      (log/error "getfacl command was not found")
      (System/exit (:preflight-fail excd/codes)))

    ;; Check that the zip command exists
    (when-not (program-exists? "zip")
      (log/error "zip command was not found")
      (System/exit (:preflight-fail excd/codes)))

    ;; Check that all the data drives are mounted
    (let [data-drives (:data config)]
      (when-not (every? drive/mounted? (mapv :path data-drives))
        (log/error "Not all of the data drives are mounted")
        (System/exit (:preflight-fail excd/codes))))

    ;; Check that all the parity drives are mounted
    (let [parity-files (:parity config)
          parity-drives (mapv fs/parent parity-files)]
      (when-not (every? drive/mounted? parity-drives)
        (log/error "Not all of the parity drives are mounted")
        (System/exit (:preflight-fail excd/codes))))

    ;;; ----------------------------------------------------------------------------
    ;;; Make sure the script isn't already being run.
    ;;; ----------------------------------------------------------------------------

    (when (snapraid/running?)
      (log/error "Another snapraid process is running")
      (System/exit (:preflight-fail excd/codes)))

    ;;; ----------------------------------------------------------------------------
    ;;; Run the S.M.A.R.T. Disk Checks.
    ;;; ----------------------------------------------------------------------------

    (let [data-drives (mapv :path (:data config))
          devices (mapv drive/mount-source data-drives)]
      (when-not (every? drive/smart-healthy? devices)
        (log/error "Some drives are unhealthy")
        (when-not (:ignore-smart options)
          (System/exit (:smart-fail excd/codes)))))

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

    (let [diff-result (snapraid/diff config-path)]
      (condp = (:exit diff-result)
        1 (do
            (log/error "snapraid diff failed")
            (System/exit (:diff-fail excd/codes)))
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
          (perms/backup! {:drives (:data config)} perms-archive-retention-count))
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
          (let [sync-result (snapraid/sync! config-path)]
            (if (= (:exit sync-result) 1)
              (do
                (log/error "snapraid sync failed")
                (System/exit (:sync-fail excd/codes))))))

        (log/info "Skipping snapraid sync")))

    ;;; ----------------------------------------------------------------------------
    ;;; Run snapraid scrub.
    ;;; ----------------------------------------------------------------------------

    (if-not (:skip-scrub options)
      (do
        (log/info "Running snapraid scrub...")
        (let [scrub-result (snapraid/scrub config-path options)]
          (if (= (:exit scrub-result) 1)
            (do
              (log/error "snapraid scrub failed")
              (System/exit (:scrub-fail excd/codes))))))
      (log/info "Skipping scrub"))

    ;;; ----------------------------------------------------------------------------
    ;;; Finish.
    ;;; ----------------------------------------------------------------------------

    (log/info "Done")
    (System/exit (:success excd/codes))))

;; Ensure -main is only called when this script is run directly,
;; not when it's required by another script.
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))