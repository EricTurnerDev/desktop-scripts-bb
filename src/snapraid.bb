(ns snapraid
  (:require [clojure.string :as str]
            [babashka.process :refer [shell]]))

(defn- parse-diff-output
  "Parses the output of running snapraid diff into EDN."
  [out]
  (let [pattern #"^\s*(\d+)\s+(\w+)$"]
    (->> (str/split-lines out)
         (keep #(when-let [[_ num key] (re-matches pattern %)]
                  [(keyword key) (parse-long num)]))
         (into {}))))

(defn running?
  "Checks if SnapRAID is already running."
  []
  (let [res (shell {:out      :string
                    :err      :string
                    :continue true}
                   "pgrep" "-f" "-l" "\\bsnapraid(\\s|$)")]
    (zero? (:exit res))))

(defn diff
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

(defn sync!
  "Runs snapraid sync using the SnapRAID configuration file at conf.
  Use with caution. Once you sync, deleted files are effectively gone from the parity, making them unrecoverable."
  [conf]
  (let [{:keys [out err exit]} (shell {:out      :string
                                       :err      :string
                                       :continue true}
                                      "snapraid" "--conf" conf "--quiet" "sync")
        result {:out out :err err :exit exit}]
    result))

(defn scrub
  "Runs snapraid scrub using the SnapRAID configuration file at conf, and the :scrub-percent from opt."
  [conf opt]
  (let [{:keys [out err exit]} (shell {:out      :string
                                       :err      :string
                                       :continue true}
                                      "snapraid" "--conf" conf "--plan" (or (:scrub-percent opt) 10) "scrub")
        result {:out out :err err :exit exit}]
    result))