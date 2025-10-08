(ns snapraid.permissions
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [snapraid.commands :as cmd]
            [drive]
            [snapraid.exit-codes :as excd]
            [logging :as log]
            [lock])
  (:import (java.time LocalDateTime ZoneId)
           (java.time.format DateTimeFormatter)))

(defn- now-tag []
  (.format (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
           (LocalDateTime/now (ZoneId/of "America/New_York"))))

(defn- hostname []
  (-> (shell {:out :string :err :string :continue true} "hostname")
      :out str/trim))

(defn- sanitize-name [s]                                     ; safe for filenames
  (-> s (str/replace #"[^A-Za-z0-9._-]+" "_")))

(defn- list-archives [drive-path]
  (when (fs/exists? drive-path)
    (->> (fs/list-dir drive-path)
         (map str)
         (filter #(re-find #"acl.*\.zip$" %))
         (sort))))

(defn- prune-old-archives!
  "Removes old backups from drive-path, retaining retention-count versions of the backups."
  [drive-path retention-count]
  (let [archives (vec (list-archives drive-path))]
    (when (> (count archives) retention-count)
      (doseq [old (take (- (count archives) retention-count) archives)]
        (try (fs/delete-if-exists old)
             (catch Exception _))))))

(defn backup!
  "Dumps ACLs for each drive and copies a single zip archive to all drives.
   Options:
   - drives: vector of {:name :path}
   Removes all but the last retention-count backups.
   Returns the path to the temp zip archive."
  [{:keys [drives]} retention-count]

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
            (System/exit (:permissions-fail excd/codes))) ; TODO: I don't like that backup! is responsible for handling the failure. Caller of the function should.
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
            (System/exit (:permissions-fail excd/codes)))))) ; TODO: I don't like that backup! is responsible for handling the failure. Caller of the function should.

    ;; 4) copy the single archive to each drive under /.snapraid-perms/
    (doseq [{:keys [path]} drives]
      (let [dest (fs/path path (fs/file-name archive))]
        (fs/copy archive dest {:replace-existing true})
        (prune-old-archives! path retention-count)))

    (str archive)))