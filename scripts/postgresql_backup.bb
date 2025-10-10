#!/usr/bin/env bb

(ns postgresql-backup
  "
    Backs up a PostgreSQL database.

    USAGE:
      postgresql_backup [OPTIONS]

    OPTIONS:
      -d, --database    The PostgreSQL database. REQUIRED.
      -h, --help        Display the help message.
      -H, --host        The PostgreSQL host. Defaults to 127.0.0.1.
      -k, --keep        The number of backups to keep. Defaults to 5.
      -o, --output-dir  The directory to save the backup file in. REQUIRED.
      -p, --port        The PostgreSQL port. Defaults to 5432.
      -u, --user        The PostgreSQL database user. REQUIRED.
      -v, --version     Display the version of the script.

    PREREQUISITES:
      - Runs on Linux (tested on Linux Mint)
      - Babashka is installed and on the PATH
      - pg_dump is installed
      - A .pgpass file exists in the user's home directory, is readable, and contains a line like: 127.0.0.1:5432:*:db_user:my-secret-password

    AUTHOR:
      jittery-name-ninja@duck.com
  "

  (:require [clojure.tools.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [command :as cmd]
            [logging :as log]
            [lock]
            [script]
            [user-utils])
  (:import (java.time LocalDateTime ZoneId)
           (java.time.format DateTimeFormatter)))

(def ^:const cli-options
  [["-d" "--database" "The PostgreSQL database"
    :required "The database"]
   ["-h" "--help" "Show help"]
   ["-H" "--host HOST" "The PostgreSQL host"
    :default "127.0.0.1"]
   ["-k" "--keep COUNT" "The number of backups to keep"
    :parse-fn (fn [s] (try (Long/parseLong s) (catch Exception _ nil)))
    :validate [#(and (number? %) (< 0 %)) "Must be an integer greater than 0"]
    :default 5]
   ["-o" "--output-dir DIR" "The directory to save the backup file in"
    :required "The directory to save the backup file in"]
   ["-p" "--port PORT" "The PostgreSQL port"
    :parse-fn (fn [s] (try (Long/parseLong s) (catch Exception _ nil)))
    :validate [#(and (number? %) (< 0 %)) "Must be an integer greater than 0"]
    :default 5432]
   ["-u" "--user USER" "The PostgreSQL database user"
    :required "The database user"]
   ["-v" "--version" "Show version."]])

(def ^:const version "0.0.1")
(def ^:const script-name "postgresql_backup")

;; TODO: lock-file name should include the database name in it (somehow) because it's okay for this script to be already running
;; if it's backing up a different database.
(def ^:const lock-file (str (fs/path "/tmp/" (str script-name ".lock"))))

(def ^:const exit-codes
  {:success 0
   :fail    1})

(defonce lock-state (atom nil))

;;; ---------------------------------------------------------------------------
;;; Supporting functions
;;; ---------------------------------------------------------------------------

(defn exit-fail []
  (System/exit (:fail exit-codes)))

(defn exit-success []
  (System/exit (:success exit-codes)))

(defn parse-opts
  "Parses command line options."
  [args opts]
  (let [parsed-opts (cli/parse-opts args opts)
        {:keys [errors]} parsed-opts]
    (when errors
        (doseq [e errors] (log/error e))
        (exit-fail))
    parsed-opts))

(defn now-formatted
  "Gets the current date/time, formatted."
  [fmt]
  (.format (LocalDateTime/now (ZoneId/systemDefault))
           (DateTimeFormatter/ofPattern fmt)))

(defn now-tag [] (now-formatted "yyyyMMdd-HHmmss"))

;;; ----------------------------------------------------------------------------
;;; Back up the database
;;; ----------------------------------------------------------------------------

(defn backup-database
  "Dumps a PostgreSQL database."
  [host port user database output-dir]
  (let [dumpfile (str database "_postgresql_" (now-tag) ".dump")
        file (str (fs/path output-dir dumpfile))
        {:keys [err exit]} (proc/shell
                             {:out :string :err :string :continue true}
                             "pg_dump" "--no-password" "-h" host "-p" port "-U" user "-d" database "-f" file "-Fc")]
    (log/info (str "Backing up the database to " file))
    (when-not (zero? exit)
      (log/error (str "Unable to back up the database: " err))
      (exit-fail))))

;;; ----------------------------------------------------------------------------
;;; Prune the backups
;;; ----------------------------------------------------------------------------

(defn list-backups
  "Lists the postgresql dump backups"
  [backup-dir database]
  (when (fs/exists? backup-dir)
    (->> (fs/list-dir backup-dir)
         (filter fs/regular-file?)
         (map str)
         (filter #(re-find (re-pattern (str database "_postgresql_\\d{8}-\\d{6}\\.dump$")) (fs/file-name %)))
         (sort))))

(defn prune-old-backups!
  "Removes old backup files."
  [backup-dir database keep]
  (let [backups (vec (list-backups backup-dir database))]
    (when (> (count backups) keep)
      (doseq [old (take (- (count backups) keep) backups)]
        (try (fs/delete-if-exists old)
             (catch Exception e
               (log/error (str "Cannot delete backup. " (.getMessage e)))))))))

(defn -main
  [& args]

  ;; Configure logging. Root logs to /var/log/, everyone else to /tmp/ .
  ;; TODO: log-file name should have the database name in it since we probably want a different log per database.
  (let [log-file (if (= (user-utils/uid) 0)
                   (str "/var/log/" script-name ".log")
                   (str "/tmp/" script-name ".log"))]
    (log/configure! {:file log-file}))


  (let [parsed-opts (parse-opts args cli-options)
        options (:options parsed-opts)]

    ;;; ----------------------------------------------------------------------------
    ;;; Handle --help or --version if they were used.
    ;;; ----------------------------------------------------------------------------

    ;; Show the help message
    (when (:help options)
      (println (:doc (meta (the-ns 'snapraid-aio))))
      (exit-success))

    ;; Show the version
    (when (:version options)
      (println script-name "version" version)
      (exit-success))

    ;;; ---------------------------------------------------------------------------
    ;;; Make sure the script isn't already being run
    ;;; ---------------------------------------------------------------------------

    (lock/add-release-hook! lock-file lock-state)

    (when-not (lock/obtain-lock! lock-file lock-state)
      (log/error "Another instance is already running. Exiting.")
      (exit-fail))

    ;;; ----------------------------------------------------------------------------
    ;;; Preflight checks
    ;;; ----------------------------------------------------------------------------

    ;; Check that required options lacking a default value have been set.
    (when (empty? (:output-dir options))
      (log/error "Output directory isn't set.")
      (exit-fail))

    (when (empty? (:database options))
      (log/error "Database isn't set.")
      (exit-fail))

    (when (empty? (:user options))
      (log/error "User isn't set.")
      (exit-fail))

    ;; Check that the pg_dump command exists.
    (when-not (cmd/exists? "pg_dump")
      (log/error "pg_dump not found")
      (exit-fail))

    ;; Check that the output directory can be written to.
    (let [dir (:output-dir options)]
      (when-not (and (fs/exists? dir) (fs/writable? dir))
        (log/error (str "Cannot write to output directory " dir))
        (exit-fail)))

    ;; Check that the user's ~/.pgpass file can be read.
    (let [pgpass (fs/expand-home "~/.pgpass")]
      (when-not (and (fs/exists? pgpass) (fs/readable? pgpass))
        (log/error ".pgpass could not be read")
        (exit-fail)))

    (let [{:keys [user host port database output-dir keep]} options]

      ;;; ----------------------------------------------------------------------------
      ;;; Log the startup
      ;;; ----------------------------------------------------------------------------

      (log/info (str "Running " script-name " ..."))
      (log/info (str "Version " version))
      (log/info (str "Logging to " (log/get-log-file)))
      (log/info (str "Lock file " lock-file))
      (log/info (str "Database URL postgresql://" user ":********@" host ":" port "/" database))

      (backup-database host port user database output-dir)

      (log/info (str "Deleting old backups (keeping " keep ")."))
      (prune-old-backups! output-dir database keep))

    (log/info "Done")
    (exit-success)))

(script/run -main)