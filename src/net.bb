(ns net
  (:require [babashka.process :refer [shell]]
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