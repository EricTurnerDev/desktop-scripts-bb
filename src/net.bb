(ns net
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :refer [shell]]
            [clojure.java.io :as io]
            [command :as cmd])
  (:import (java.net URL)))

(defn url?
  "Checks if string s is a URL. Returns true or false."
  [s]
  (try
    (do
      ;; Attempt to create a java.net.URL object from the string.
      ;; The object is created successfully for valid URLs.
      (URL. s)
      true)
    (catch Exception _
      ;; If an exception is caught, the string is not a valid URL.
      false)))

(defn url-valid?
  "Checks if URL u can be reached. Returns true or false."
  [u]
  (if (or (empty? u) (not (cmd/exists? "curl")))
    false
    ;; Read the first byte from the URL
    (let [{:keys [out err exit]} (shell {:out      :string
                                         :err      :string
                                         :continue true}
                                        "curl" "--insecure" "--output" "/dev/null" "--silent" "--fail" "-r" "0-0" u)
          result {:exit exit :out out :err err}]
      (= (:exit result) 0))))

(defn download-file
  "Downloads the file from url into dest-path, creating the parent directory if needed. Returns the path where it was
  downloaded if successful, nil otherwise."
  [url dest-path]
  (try
    (fs/create-dirs (fs/parent dest-path))
    (with-open [in (:body (http/get url {:as :stream :follow-redirects true}))
                out (io/output-stream dest-path)]
      (io/copy in out)
      dest-path)
    (catch Exception e
      (binding [*out* *err*]
        (println "Unable to download " url ": " (.getMessage e)))
      nil)))