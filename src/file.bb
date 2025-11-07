(ns file
  (:require
    [babashka.process :refer [sh]]
    [clojure.string :as string]))

(defn mime-type
  "Return the `file` mime type or throw if file isn't available."
  [path]
  (let [{:keys [out err exit]} (sh "file" "-b" "--mime-type" path)]
    (if (zero? exit)
      (string/trim out)
      (throw (ex-info "file command failed" {:path path :err err :exit exit})))))

(defn description
  "Return the `file` description (string) or throw if file isn't available."
  [path]
  (let [{:keys [out err exit]} (sh "file" "-b" path)]
    (if (zero? exit)
      (string/trim out)
      (throw (ex-info "file command failed" {:path path :err err :exit exit})))))