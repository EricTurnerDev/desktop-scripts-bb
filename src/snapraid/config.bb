(ns snapraid.config
  (:require
    [clojure.string :as str]
    [babashka.fs :as fs]))

(defn- unquote-token
  "Removes quotes from a string."
  [s]
  (let [s (if (char? s) (str s) s)]                         ; Be robust if a Character sneaks in
    (if (and (string? s)
             (>= (count s) 2)
             (= \" (nth s 0))
             (= \" (nth s (dec (count s)))))
      (subs s 1 (dec (count s)))
      s)))

(defn- tokenize-line
  "Splits a line from a SnapRAID configuration file into tokens."
  [line]
  (let [token-regex #"(?:\S+|\"[^\"]*\")"
        tokens (map unquote-token (re-seq token-regex line))]
    (->> tokens
         (take-while #(not (str/starts-with? % "#")))       ; Ignore comment lines
         (remove str/blank?)
         vec)))

(defn- add-kv
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

(defn- resolve-file-path
  "Returns the first file path from file-paths that exists and is readable."
  [file-paths]
  (let [candidates (remove nil? file-paths)]
    (some #(when (and (fs/exists? %) (fs/readable? %)) %) candidates)))

(defn resolve-path
  "Resolve SnapRAID config path from the command line or defaults. Returns nil if the config path cannot be resolved."
  [opt]
  (let [config-path (resolve-file-path [opt "/usr/local/etc/snapraid.conf" "/etc/snapraid.conf"])]
    (if (not config-path)
      nil
      config-path)))

(defn parse
  "Parses SnapRAID configuration file contents s into a Clojure map."
  [s]
  (let [lines (str/split-lines s)]
    (->> lines
         (map tokenize-line)
         (remove empty?)
         (reduce add-kv {:parity [] :data [] :content [] :exclude []}))))