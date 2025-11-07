#!/usr/bin/env bb

(ns imgur
  ;; The namespace docstring is also output when the --help option is used.
  "
   Upload images to Imgur.

   The image can be provided through the --image option, or from stdin. The Imgur URL of the image is printed to stdout.

   USAGE:
     imgur [OPTIONS] [FILE|URL]

   OPTIONS:
     -h, --help            Show the help message
     -v, --version         Show the version

   EXAMPLES:
     imgur http://example.com/image.jpg
     echo http://example.com/image.jpg | imgur

   AUTHOR:
     jittery-name-ninja@duck.com
   "

  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :refer [shell]]
            [cli :as dscli]
            [clojure.string :as str]
            [command :as cmd]
            [logging :as log]
            [net]
            [script]
            [user-utils]
            [imgur.exit-codes :as excd])
  (:import (java.nio.file Files)
           (java.util UUID)))

(def ^:const version "0.1.0")
(def ^:const script-name "imgur")

(def ^:const cli-options
  [["-h" "--help" "Show the help message"]
   ["-v" "--version" "Show the version"]])


(defn- exit-success []
  (System/exit (:success excd/codes)))

(defn- exit-fail []
  (System/exit (:fail excd/codes)))

(defn- image?
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

(defn- image-valid?
  "Checks if file f is an image that can be read. Returns true or false."
  [f]
  (and (fs/exists? f) (fs/readable? f) (image? f)))

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

(defn download-image!
  "Download an image from url to /tmp. Returns the filesystem path to the image, or nil if it could not be downloaded."
  [url]
  (try (let [resp (http/get url {:as               :bytes
                                 :follow-redirects :always
                                 :throw            false})
             status (:status resp)
             ctype (some-> (get-in resp [:headers "content-type"]) str/lower-case)]
         (if (and status (< status 400) ctype (str/starts-with? ctype "image/"))
           ;; Looks like an image, so write it out to /tmp.
           (let [basename (some-> url (str/split #"/") last not-empty)
                 ext (or (some->> basename (re-find #"\.[A-Za-z0-9]+$"))
                         (extension-from-content-type ctype))
                 fname (or basename (str (UUID/randomUUID) ext))
                 target (fs/path "/tmp" fname)]
             (fs/write-bytes target (:body resp))
             (str target))
           ;; Not an image, so return nil.
           nil))
       (catch Exception _
         ;; Couldn't download it, so return nil.
         nil)))

(defn upload-image!
  "Upload an image file f from the filesystem to Imgur. Returns the Imgur URL of the image, or nil if the image could not be uploaded."
  [^String f]
  (try
    (let [path (fs/path f)
          content-type (Files/probeContentType path)
          filename (fs/file-name f)]

      (let [resp (http/post "https://imgur.com/upload"
                            {:insecure? true
                             :headers   {"Referer" "http://imgur.com/upload"
                                         "Accept"  "application/json"}
                             :multipart [{:name         "Filedata"
                                          :filename     filename
                                          :content      (io/file f)
                                          :content-type content-type}]})
            {:keys [status body]} resp]
        (if (= 200 status)
          (let [m (json/parse-string (str body) true)
                {:keys [hash ext]} (:data m)]
            (when (and hash ext)
              (format "https://i.imgur.com/%s%s" hash ext)))
          nil)))
    (catch Exception _
      nil)))

(defn -main
  ""
  [& args]

  ;; Configure logging. Root logs to /var/log/, everyone else to /tmp/ .
  (let [log-file (if (= (user-utils/uid) 0)
                   (str "/var/log/" script-name ".log")
                   (str "/tmp/" script-name ".log"))]
    (log/configure! {:file log-file}))

  (let [parsed-opts (dscli/parse-opts args cli-options #(dscli/handle-cli-errors % exit-fail))
        options (:options parsed-opts)
        file-path (first (:arguments parsed-opts))]

    ;;; ----------------------------------------------------------------------------
    ;;; Handle --help or --version if they were used.
    ;;; ----------------------------------------------------------------------------

    ;; Show the help message.
    (when (:help options)
      (println (log/ns-doc 'imgur))
      (exit-success))

    ;; Show the version.
    (when (:version options)
      (println version)
      (exit-success))

    ;; Image option can be provided by the argument, or from stdin.
    (let [image-opt (or file-path
                        (some-> (slurp *in*) str/trim not-empty))]

      ;;; ----------------------------------------------------------------------------
      ;;; Run preflight checks.
      ;;; ----------------------------------------------------------------------------

      (doseq [c ["curl" "identify" "xargs" "jq"]]
        (when-not (cmd/exists? c)
          (log/error (str c " not found."))
          (exit-fail)))

      ;; Image file path or URL was provided.
      (when-not image-opt
        (log/error "Image is required.")
        (exit-fail))

      ;; url can be read.
      (when (and (net/url? image-opt) (not (net/url-valid? image-opt)))
        (log/error (str image-opt " cannot be read."))
        (exit-fail))

      ;;; ----------------------------------------------------------------------------
      ;;; Upload the image to Imgur
      ;;; ----------------------------------------------------------------------------

      (let [downloaded-file (download-image! image-opt)
            image (or downloaded-file image-opt)]

        (when-not (image-valid? image)
          (log/error (str image-opt " is not a valid image."))
          (exit-fail))

        (let [imgur-url (upload-image! image)]
          (println imgur-url))

        ;; We don't want downloaded images in /tmp to consume disk space.
        (when (net/url? image-opt)
          (fs/delete-if-exists downloaded-file))

        (exit-success)))))

(script/run -main *command-line-args*)