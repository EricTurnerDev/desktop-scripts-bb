#!/usr/bin/env bb

(ns snapraid-aio
  "Manage a DAS (Direct Attached Storage) with SnapRAID.

   Usage:
     ./snapraid_aio.bb [options]

   Options:
     -h, --help   Show this help message and exit
     -c, --config Path to the SnapRAID configuration file"

  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.cli :refer [parse-opts]]
            [babashka.fs :as fs]
            [babashka.process :refer [shell sh]])
  (:import (java.io RandomAccessFile)
           (java.time LocalDateTime ZoneId)
           (java.time.format DateTimeFormatter)))

(def ^:const version "0.0.2")
(def ^:const lock-file "/tmp/snapraid-aio.bb.lock")
(def ^:const script-name "snapraid_aio.bb")

(def ^:const exit-codes
  {:success          0
   :preflight-fail   2                                      ; mounts, RO, parity missing
   :smart-fail       3
   :snapraid-missing 4
   :sync-fail        5
   :scrub-fail       6
   :lock-fail        7
   :diff-fail        8})

(defonce lock-state (atom nil))

;;;; ---------------------------------------------------------------------------
;;;; snapraid_aio.bb - Manage a DAS (Direct Attached Storage)
;;;; ---------------------------------------------------------------------------
;;;; Author: Eric Turner
;;;;  Date: 2025-09-23

;; TODO:
;; - Support --help command line option
;; - Option to continue if SMART fails
;; - Specify date/time format and tag for the log entries
;; - Better log management (logrotate)?
;; - Make scrub optional
;; - Save file permissions somewhere so they can be restored after a snapraid fix
;; - Spin down disks with hd-idle
;; - Support notifications: email, healthchecks.io, telegram, discord
;; - Configure retention days
;; - Check if newer version of script is available

;;; ----------------------------------------------------------------------------
;;; General file and shell functions
;;; ----------------------------------------------------------------------------

(defn uid
  "Gets the user id of the current process"
  []
  (try
    (Integer/parseInt (str/trim (:out (sh "id" "-u"))))
    (catch Exception _ -1)))

(defn resolve-file-path
  "Returns the first file path from file-paths that exists and is readable."
  [file-paths]
  (let [candidates (remove nil? file-paths)]
    (some #(when (and (fs/exists? %) (fs/readable? %)) %) candidates)))

(defn program-exists?
  "Checks if a program exists."
  [prog]
  (some? (fs/which prog)))

(defn mounted?
  "Checks if a drive is mounted."
  [path]
  (zero? (:exit (shell {:out      :string
                        :err      :string
                        :continue true}                     ; Don't throw exception on exit != 0
                       "mountpoint" "-q" path))))

(defn mount-source
  "Returns the device backing a mount point.
   On failure or if not mounted, returns nil."
  [mountpoint]
  (let [{:keys [out exit]} (shell {:out      :string
                                   :err      :string
                                   :continue true}
                                  "findmnt" "-no" "SOURCE" mountpoint)]
    (when (zero? exit)
      (let [src (str/trim out)]
        (when-not (str/blank? src)
          (try
            (str (fs/real-path src))
            (catch Exception _ src)))))))

;;; ----------------------------------------------------------------------------
;;; Set up logging
;;; ----------------------------------------------------------------------------
(defn now-ts
  "Gets the current timestamp"
  []
  (.format (LocalDateTime/now (ZoneId/systemDefault))
           (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")))

(defn- try-spit [f s]
  (try (spit f s :append true)
       (catch Exception _ (binding [*out* *err*] (println "WARN: failed to write log")))))

;; Root logs to /var/log/snapraid-aio.bb.log, everyone else to /tmp/snapraid-aio.bb.log
(def log-file (if (= (uid) 0) "/var/log/snapraid-aio.bb.log" "/tmp/snapraid-aio.bb.log"))

(defn log-info [msg]
  (let [ts (now-ts)]
    (println msg)
    (try-spit log-file (str ts " [INFO] " msg "\n"))))

(defn log-error [msg]
  (let [ts (now-ts)]
    (.println System/err msg)
    (try-spit log-file (str ts " [ERROR] " msg "\n"))))

;;; ----------------------------------------------------------------------------
;;; Process command-line arguments
;;; ----------------------------------------------------------------------------

(def cli-options
  [["-c" "--config FILE" "SnapRAID configuration file"]
   ["-p"
    "--scrub-percent PERCENT"
    "Percentage of blocks for SnapRAID to scrub (0-100)"
    :parse-fn (fn [s] (try (Long/parseLong s) (catch Exception _ nil)))
    :validate [#(and (number? %) (<= 0 % 100)) "Must be an integer between 0 and 100"]]
   ["-v" "--version" "Version"]])

(def parsed-args (parse-opts *command-line-args* cli-options))

;; Check for command line errors
(let [{:keys [errors]} parsed-args]
  (when errors
    (binding [*out* *err*]
      (doseq [e errors] (log-error e))
      (System/exit (:preflight-fail exit-codes)))))

(def options (:options parsed-args))

;;; ----------------------------------------------------------------------------
;;; Show the version
;;; ----------------------------------------------------------------------------
(when (:version options)
  (println script-name "version" version)
  (System/exit 0))

;;; ----------------------------------------------------------------------------
;;; Make sure the script isn't already being run
;;; ----------------------------------------------------------------------------

(defn obtain-lock!
  "Try to obtain an exclusive lock on lock-file.
   Returns true on success, nil on failure."
  []
  (try
    (let [raf (RandomAccessFile. lock-file "rw")
          chan (.getChannel raf)
          lock (.tryLock chan)]
      (if (nil? lock)
        (do
          (.close chan)
          (.close raf)
          nil)
        (do
          (reset! lock-state {:raf raf :chan chan :lock lock})
          true)))
    (catch Exception _ nil)))

(.addShutdownHook (Runtime/getRuntime)
                  (Thread. (fn []
                             (when-let [{:keys [raf chan lock]} @lock-state]
                               (try (.release lock) (catch Exception _))
                               (try (.close chan) (catch Exception _))
                               (try (.close raf) (catch Exception _))
                               (try (io/delete-file lock-file true) (catch Exception _))))))

(when-not (obtain-lock!)
  (log-error "Another instance is already running. Exiting.")
  (System/exit (:lock-fail exit-codes)))

;;; ----------------------------------------------------------------------------
;;; Parse configuration file
;;; ----------------------------------------------------------------------------

(defn unquote-token
  "Removes quotes from a string."
  [s]
  (let [s (if (char? s) (str s) s)]                         ; Be robust if a Character sneaks in
    (if (and (string? s)
             (>= (count s) 2)
             (= \" (nth s 0))
             (= \" (nth s (dec (count s)))))
      (subs s 1 (dec (count s)))
      s)))

(defn tokenize-line
  "Splits a line from a SnapRAID configuration file into tokens."
  [line]
  (let [token-regex #"(?:\S+|\"[^\"]*\")"
        tokens (map unquote-token (re-seq token-regex line))]
    (->> tokens
         (take-while #(not (str/starts-with? % "#")))       ; Ignore comment lines
         (remove str/blank?)
         vec)))

(defn add-kv
  "Adds a key/value pair from a SnapRAID configuration file to an accumulator."
  [acc [k & args]]
  (case (some-> k str/lower-case)
    "parity" (update acc :parity (fnil conj []) (first args))
    "content" (update acc :content (fnil conj []) (first args))
    ;; data and disk entries are typically: data <name> <path>
    "data" (let [[name path] args]
             (update acc :data (fnil conj []) {:name name :path path}))
    ;; It looks like SnapRAID changed disk to data at some point, so throw disk in with data if found in the config.
    "disk" (let [[name path] args]
             (update acc :data (fnil conj []) {:name name :path path}))

    ;; filters
    "exclude" (update acc :exclude (fnil conj []) (str/join " " args))

    ;; fallback: keep anything we don’t explicitly recognize
    (update acc :other (fnil conj []) {:key k :args args})))

(defn parse-snapraid-config
  "Parses a SnapRAID configuration file into a Clojure map."
  [s]
  (let [lines (str/split-lines s)]
    (->> lines
         (map tokenize-line)
         (remove empty?)
         (reduce add-kv {:parity [] :data [] :content [] :exclude []}))))

(defn resolve-config-path
  "Resolve SnapRAID config path from the command line or defaults."
  [opt]
  (resolve-file-path [opt "/usr/local/etc/snapraid.conf" "/etc/snapraid.conf"]))

(def config-path (resolve-config-path (:config options)))

;;; ----------------------------------------------------------------------------
;;; Preflight checks
;;; ----------------------------------------------------------------------------

(if (not config-path)
  (do
    (log-error "No readable snapraid.conf was found.")
    (System/exit (:preflight-fail exit-codes))))

(def config (-> config-path slurp parse-snapraid-config))

;; Make sure the script is run with root privileges

(let [id (uid)]
  (when-not (= 0 id)
    (log-error "Error: This script must be run as root (use sudo).")
    (System/exit (:preflight-fail exit-codes))))

;; Check that snapraid exists
(when-not (program-exists? "snapraid")
  (log-error "SnapRAID not found")
  (System/exit (:snapraid-missing exit-codes)))

;; Check that the mountpoint command exists
(when-not (program-exists? "mountpoint")
  (log-error "mountpoint command was not found")
  (System/exit (:preflight-fail exit-codes)))

;; Check that findmnt command exists
(when-not (program-exists? "findmnt")
  (log-error "findmnt command was not found")
  (System/exit (:preflight-fail exit-codes)))

;; Check that all the data drives are mounted
(let [data-drives (:data config)]
  (when-not (every? mounted? (mapv :path data-drives))
    (log-error "Not all of the data drives are mounted")
    (System/exit (:preflight-fail exit-codes))))

;; Check that all the parity drives are mounted
(let [parity-files (:parity config)
      parity-drives (mapv fs/parent parity-files)]
  (when-not (every? mounted? parity-drives)
    (log-error "Not all of the parity drives are mounted")
    (System/exit (:preflight-fail exit-codes))))

;; Check that snapraid isn't already running.
(defn snapraid-running? []
  (let [res (shell {:out      :string
                    :err      :string
                    :continue true}
                   "pgrep" "-f" "-l" "\\bsnapraid(\\s|$)")]
    (zero? (:exit res))))

(when (snapraid-running?)
  (log-error "Another snapraid process is running")
  (System/exit (:preflight-fail exit-codes)))

;; Check that disk drives are all healthy.
(defn smart-healthy?
  "Returns true if smartctl reports overall health as PASSED, else false."
  [device]
  (let [{:keys [out exit]} (shell {:out      :string
                                   :err      :string
                                   :continue true}
                                  "smartctl" "-H" device)]
    (and (zero? exit)
         (or (str/includes? out "PASSED")
             (str/includes? out "OK")))))

(let [data-drives (mapv :path (:data config))
      devices (mapv mount-source data-drives)]
  (when-not (every? smart-healthy? devices)
    (log-error "Some drives are unhealthy")
    (System/exit (:smart-fail exit-codes))))

;;; ----------------------------------------------------------------------------
;;; Log the startup
;;; ----------------------------------------------------------------------------

(log-info "Running snapraid_aio.bb...")
(log-info (str script-name " version " version))
(log-info (str "Using configuration from " config-path))
(log-info (str "Logging to " log-file))
(log-info (str "Lock file " lock-file))
(log-info (str "Data drives " (mapv :path (:data config))))
(log-info (str "Parity drives " (mapv #(str (fs/parent %)) (:parity config))))
(when-let [pct (:scrub-percent options)]
  (log-info (str "Will scrub " pct "% of blocks")))

;;; ----------------------------------------------------------------------------
;;; Run snapraid diff
;;; ----------------------------------------------------------------------------

(defn parse-diff-output [out]
  (let [pattern #"^\s*(\d+)\s+(\w+)$"]
    (->> (str/split-lines out)
         (keep #(when-let [[_ num key] (re-matches pattern %)]
                  [(keyword key) (parse-long num)]))
         (into {}))))

(defn snapraid-diff
  "Runs snapraid diff, and returns the counts."
  []
  (let [{:keys [out err exit]} (shell {:out      :string
                                       :err      :string
                                       :continue true}
                                      "snapraid" "--conf" config-path "--quiet" "--quiet" "--quiet" "diff")
        result {:exit exit :out out :err err}]

    ;; If sync required, exit is 2. if error, 1. otherwise 0.
    (if (= exit 2)
      (merge (parse-diff-output out) result)
      result)))

(log-info "Running snapraid diff...")
(def diff-result (snapraid-diff))

(def ^:const diff-keys [:equal :added :removed :updated :moved :copied :restored])

(condp = (:exit diff-result)
  1 (do
      (log-error "snapraid diff failed")
      (System/exit (:diff-fail exit-codes)))
  2 (doseq [k diff-keys]
      (when-let [v (get diff-result k)]
        (log-info (str (str/capitalize (name k)) ": " v))))
  (log-info "No differences detected"))

;;; ----------------------------------------------------------------------------
;;; Run snapraid sync
;;; ----------------------------------------------------------------------------

(defn snapraid-sync
  "Runs snapraid sync."
  []
  (let [{:keys [out err exit]} (shell {:out      :string
                                       :err      :string
                                       :continue true}
                                      "snapraid" "--conf" config-path "--quiet" "sync")
        result {:out out :err err :exit exit}]
    result))

;; Only run snapraid sync if differences were detected.

(if (and (= (:exit diff-result) 2)
         (some #(pos? (long (or (% diff-result) 0)))
               [:added :removed :updated :moved :copied :restored]))
  (do
    (log-info "Running snapraid sync...")
    (let [sync-result (snapraid-sync)]
      (if (= (:exit sync-result) 1)
        (do
          (log-error "snapraid sync failed")
          (System/exit (:sync-fail exit-codes))))))

  (log-info "Skipping snapraid sync"))

;;; ----------------------------------------------------------------------------
;;; Run snapraid scrub
;;; ----------------------------------------------------------------------------

(defn snapraid-scrub
  "Runs snapraid scrub."
  []
  (let [{:keys [out err exit]} (shell {:out      :string
                                       :err      :string
                                       :continue true}
                                      "snapraid" "--conf" config-path "--plan" (or (:scrub-percent options) 10) "scrub")
        result {:out out :err err :exit exit}]
    result))

(log-info "Running snapraid scrub...")
(def scrub-result (snapraid-scrub))

(if (= (:exit scrub-result) 1)
  (do
    (log-error "snapraid scrub failed")
    (System/exit (:scrub-fail exit-codes))))

(log-info "Done")
(System/exit 0)
