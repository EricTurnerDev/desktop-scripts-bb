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
     snapraid-aio [OPTIONS]

   OPTIONS:
     -c, --config CONFIG       Path to the SnapRAID configuration file. Defaults to /usr/local/etc/snapraid.conf
                               followed by /etc/snapraid.conf.
     -h, --help                Show the help message
     -i, --ignore-smart        Continue even when S.M.A.R.T. tests indicate problems
     -o, --older-than DAYS     Scrub blocks of the array older than DAYS. Defaults to 10.
     -p, --scrub-percent PERC  The percentage of blocks for SnapRAID to scrub. Defaults to 10.
     -b, --standby             Put the drives into standby mode when done
     -d, --skip-diff           Don't run SnapRAID diff (forces sync)
     -s, --skip-scrub          Don't run SnapRAID scrub
     -v, --version             Show the version

   AUTHOR:
     jittery-name-ninja@duck.com"

  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [cli :as dscli]
            [command :as cmd]
            [drive]
            [logging :as log]
            [lock]
            [script]
            [user-utils]
            [snapraid.commands :as srcmd]
            [snapraid.config :as srconf]
            [snapraid.exit-codes :as excd]
            [snapraid.permissions :as perms]))

;;; ----------------------------------------------------------------------------
;;; Constants.
;;; ----------------------------------------------------------------------------
(def ^:const version "0.1.1")

(def ^:const cli-options
  [["-c" "--config FILE" "SnapRAID configuration file"]
   ["-h" "--help" "Show the help message"]
   ["-o" "--older-than DAYS" "Scrub blocks of the array older than DAYS"
    :parse-fn (fn [s] (try (Long/parseLong s) (catch Exception _ nil)))
    :validate [#(and (number? %) (<= 0 %)) "Must be an integer 0 or greater"]]
   ["-i" "--ignore-smart" "Continue even when S.M.A.R.T. tests indicate problems"]
   ["-p"
    "--scrub-percent PERCENT"
    "Percentage of blocks for SnapRAID to scrub (0-100)"
    :parse-fn (fn [s] (try (Long/parseLong s) (catch Exception _ nil)))
    :validate [#(and (number? %) (<= 0 % 100)) "Must be an integer between 0 and 100"]]
   ["-b" "--standby" "Put drives into standby mode when done"]
   ["-d" "--skip-diff" "Don't run SnapRAID diff"]
   ["-s" "--skip-scrub" "Don't run SnapRAID scrub"]
   ["-v" "--version" "Show the version"]])

(def ^:const script-name "snapraid-aio")
(def ^:const perms-archive-retention-count 1)
(def ^:const lock-file (str "/tmp/" script-name ".lock"))
(def ^:const diff-keys [:equal :added :removed :updated :moved :copied :restored])

(defonce lock-state (atom nil))

;;; ----------------------------------------------------------------------------
;;; General file and shell functions.
;;; ----------------------------------------------------------------------------

(defn- exit-fn [code]
  (fn [] (System/exit code)))

(def exit-success (exit-fn (:success excd/codes)))
(def exit-diff-fail (exit-fn (:diff-fail excd/codes)))
(def exit-lock-fail (exit-fn (:lock-fail excd/codes)))
(def exit-preflight-fail (exit-fn (:preflight-fail excd/codes)))
(def exit-scrub-fail (exit-fn (:scrub-fail excd/codes)))
(def exit-smart-fail (exit-fn (:smart-fail excd/codes)))
(def exit-sync-fail (exit-fn (:sync-fail excd/codes)))
(def exit-snapraid-fail (exit-fn (:snapraid-fail excd/codes)))

(defn- save-permissions [config]
  (log/info "Saving permissions...")
  (perms/backup! {:drives (:data config)} perms-archive-retention-count))

(defn- changed?
  "Determines if the result from snapraid diff indicates anything has changed."
  [diff-result]
  (and (= (:exit diff-result) 2)
       (some #(pos? (long (or (% diff-result) 0)))
             [:added :removed :updated :moved :copied :restored])))

(defn- run-diff
  "Runs snapraid diff, logs and returns the result."
  [config-path]
  (log/info "Running snapraid diff...")
  (let [diff-result (srcmd/diff config-path)]
    (condp = (:exit diff-result)
      1 (do
          (log/error "snapraid diff failed")
          (exit-diff-fail))
      2 (doseq [k diff-keys]
          (when-let [v (get diff-result k)]
            (log/info (str (str/capitalize (name k)) ": " v))))
      (log/info "No differences detected"))
    diff-result))

(defn- run-sync [config-path]
  (log/info "Running snapraid sync...")
  (let [sync-result (srcmd/sync! config-path)]
    (if (= (:exit sync-result) 1)
      (do
        (log/error "snapraid sync failed")
        (exit-sync-fail)))))

(defn- run-scrub [config-path options]
  (log/info "Running snapraid scrub...")
  (let [scrub-result (srcmd/scrub config-path options)]
    (if (= (:exit scrub-result) 1)
      (do
        (log/error "snapraid scrub failed")
        (exit-scrub-fail)))))

(defn- get-options
  [args]
  (let [parsed-opts (dscli/parse-opts args cli-options #(dscli/handle-cli-errors % exit-preflight-fail))
        options (:options parsed-opts)]
    options))

(defn- get-config-path
  [options]
  (srconf/resolve-path (:config options)))

(defn- get-config
  [options]
  (let [config-path (get-config-path options)
        config (-> config-path slurp srconf/parse)]
    config))

(defn -main [& args]
  ;; Configure logging. Root logs to /var/log/, everyone else to /tmp/ .
  (let [log-file (if (= (user-utils/uid) 0)
                   (str "/var/log/" script-name ".log")
                   (str "/tmp/" script-name ".log"))]
    (log/configure! {:file log-file}))

  (let [options (get-options args)
        config-path (get-config-path options)
        config (get-config options)]

    (when-not config
      (log/error "No readable snapraid.conf was found.")
      (exit-preflight-fail))

    ;;; ----------------------------------------------------------------------------
    ;;; Handle --help or --version if they were used.
    ;;; ----------------------------------------------------------------------------

    ;; Show the help message
    (when (:help options)
      (println (log/ns-doc 'snapraid-aio))
      (exit-success))

    ;; Show the version
    (when (:version options)
      (println script-name "version" version)
      (exit-success))

    ;;; ----------------------------------------------------------------------------
    ;;; Prevent other instances of the script from running.
    ;;; ----------------------------------------------------------------------------

    (when-not (lock/obtain-lock! lock-file lock-state)
      (log/error "Another instance is already running. Exiting.")
      (exit-lock-fail))

    ;;; ----------------------------------------------------------------------------
    ;;; Run preflight checks.
    ;;; ----------------------------------------------------------------------------

    ;; Make sure the script is run with root privileges
    ;; TODO: Instead of forcing to run as root, check permissions on the resources we need to access, and error if
    ;; the current user doesn't have permissions to access those resources. That way the system admin can choose how to
    ;; run this script however they see fit.
    (let [id (user-utils/uid)]
      (when-not (= 0 id)
        (log/error "Error: This script must be run as root (use sudo).")
        (exit-preflight-fail)))

    ;; Check that required commands exist
    (doseq [cmd ["snapraid" "mountpoint" "findmnt" "getfacl" "hdparm" "zip"]]
      (when-not (cmd/exists? cmd)
        (log/error (str cmd " not found"))
        (exit-preflight-fail)))

    ;; Check that all the drives are mounted
    (let [data-drives (mapv :path (:data config))
          parity-files (:parity config)
          parity-drives (mapv fs/parent parity-files)
          all-drives (into data-drives parity-drives)]
      (println all-drives)
      (when-not (every? drive/mounted? all-drives)
        (log/error "Not all the drives are mounted")
        (exit-preflight-fail)))

    ;;; ----------------------------------------------------------------------------
    ;;; Make sure the script isn't already being run.
    ;;; ----------------------------------------------------------------------------

    (when (cmd/running? "snapraid")
      (log/error "Another snapraid process is running")
      (exit-preflight-fail))

    ;;; ----------------------------------------------------------------------------
    ;;; Run the S.M.A.R.T. Disk Checks.
    ;;; ----------------------------------------------------------------------------

    (let [data-drives (mapv :path (:data config))
          devices (mapv drive/mount-source data-drives)]
      (when-not (every? drive/smart-healthy? devices)
        (log/error "Some drives are unhealthy")
        (when-not (:ignore-smart options)
          (exit-smart-fail))))

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
    (when-let [days-old (:older-than options)]
      (log/info (str "Will scrub blocks older than " days-old " " (if (= 1 days-old) "day" "days") " old")))
    (when-let [pct (:scrub-percent options)]
      (log/info (str "Will scrub " pct "% of blocks")))

    ;;; ----------------------------------------------------------------------------
    ;;; Run snapraid diff to determine if permissions need to be saved and sync
    ;;; needs to be run.
    ;;; ----------------------------------------------------------------------------

    (when (:skip-diff options)
      (log/info "Skipping diff"))

    (let [diff-result (if (:skip-diff options) {} (run-diff config-path))]

      ;;; ----------------------------------------------------------------------------
      ;;; Back up permissions.
      ;;; ----------------------------------------------------------------------------

      ;; Back up permissions if diff was skipped, or if differences were detected.
      (if (or (:skip-diff options) (changed? diff-result))
        (save-permissions config)
        (log/info "Skipping saving permissions"))

      ;;; ----------------------------------------------------------------------------
      ;;; Run snapraid sync.
      ;;; ----------------------------------------------------------------------------

      ;; Run snapraid sync if diff was skipped, or if differences were detected.
      (if (or (:skip-diff options) (changed? diff-result))
        (run-sync config-path)
        (log/info "Skipping snapraid sync")))

    ;;; ----------------------------------------------------------------------------
    ;;; Run snapraid scrub.
    ;;; ----------------------------------------------------------------------------

    (if-not (:skip-scrub options)
      (run-scrub config-path options)
      (log/info "Skipping scrub"))

    ;;; ----------------------------------------------------------------------------
    ;;; Put the drives into standby
    ;;; ----------------------------------------------------------------------------

    (when (:standby options)
      (log/info "Putting drives into standby mode")
      (let [data-devs (mapv #(drive/mount-source (:path %)) (:data config))
            parity-drives (mapv #(str (fs/parent %)) (:parity config))
            parity-devs (mapv drive/mount-source parity-drives)
            all-devs (into data-devs parity-devs)]
        (doseq [dev all-devs]
          (drive/standby dev))))

    ;;; ----------------------------------------------------------------------------
    ;;; Finish.
    ;;; ----------------------------------------------------------------------------

    (log/info "Done")
    (exit-success)))

(script/run -main *command-line-args*)