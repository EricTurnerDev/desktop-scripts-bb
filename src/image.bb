(ns image
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :refer [shell]]
            [clojure.string :as str])
  (:import
    (java.util UUID)))

(defn- extension-from-content-type
  "Converts content type to file extension."
  [ctype]
  (case (some-> ctype str/lower-case)
    "image/jpeg" ".jpg"
    "image/jpg" ".jpg"
    "image/png" ".png"
    "image/gif" ".gif"
    "image/webp" ".webp"
    "image/bmp" ".bmp"
    "image/svg+xml" ".svg"
    ;; fallback
    ".img"))

(defn image?
  "Determines if file f is an image. Returns true or false."
  [f]
  (if (empty? f)
    false
    (let [{:keys [out err exit]} (shell {:out      :string
                                         :err      :string
                                         :continue true}
                                        "identify" f)
          result {:exit exit :out out :err err}]
      (= (:exit result) 0))))

(defn image-valid?
  "Checks if file f is an image that can be read. Returns true or false."
  [f]
  (and (fs/exists? f) (fs/readable? f) (image? f)))

(defn download-image!
  "Download an image from url to a directory (or /tmp). Returns the filesystem path to the image, or nil if it could not be downloaded."
  ([url] (download-image! url "/tmp"))
  ([url dir]
   (try (let [resp (http/get url {:as               :bytes
                                  :follow-redirects :always
                                  :throw            false})
              status (:status resp)
              ctype (some-> (get-in resp [:headers "content-type"]) str/lower-case)]
          (if (and status (< status 400) ctype (str/starts-with? ctype "image/"))
            ;; Looks like an image, so write it out to dir.
            (let [basename (some-> url (str/split #"/") last not-empty)
                  ext (or (some->> basename (re-find #"\.[A-Za-z0-9]+$"))
                          (extension-from-content-type ctype))
                  fname (or basename (str (UUID/randomUUID) ext))
                  target (fs/path dir fname)]
              (fs/write-bytes target (:body resp))
              (str target))
            ;; Not an image, so return nil.
            nil))
        (catch Exception _
          ;; Couldn't download it, so return nil.
          nil))))
