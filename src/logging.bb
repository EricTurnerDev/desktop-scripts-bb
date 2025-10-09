(ns logging
  "Provides functions for logging messages to stdout, stderr, and log files."
  (:import (java.time LocalDateTime ZoneId)
           (java.time.format DateTimeFormatter)))

(defonce log-file (atom "/tmp/default.log"))

(defn configure! [{:keys [file]}]
  (reset! log-file file))

(defn get-log-file
  "Returns the current log file path."
  []
  @log-file)

(defn- now-ts
  "Gets the current timestamp"
  []
  (.format (LocalDateTime/now (ZoneId/systemDefault))
           (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")))

(defn- try-spit [f s]
  (try (spit f s :append true)
       (catch Exception _ (binding [*out* *err*] (println (str "WARN: failed to write log file " @log-file))))))

(defn info [msg]
  (let [ts (now-ts)]
    (println msg)
    (try-spit @log-file (str ts " [INFO] " msg "\n"))))

(defn error [msg]
  (let [ts (now-ts)]
    (.println System/err msg)
    (try-spit @log-file (str ts " [ERROR] " msg "\n"))))