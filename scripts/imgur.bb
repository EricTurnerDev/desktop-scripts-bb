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
            [cli :as dscli]
            [clojure.string :as str]
            [command :as cmd]
            [image :as img]
            [logging :as log]
            [net]
            [script]
            [user-utils]
            [imgur.exit-codes :as excd])
  (:import (java.nio.file Files)))

(def ^:const version "0.1.0")
(def ^:const script-name "imgur")

(def ^:const cli-options
  [["-h" "--help" "Show the help message"]
   ["-v" "--version" "Show the version"]])

(defn- exit-fn [code] (fn [] (System/exit code)))
(def exit-success (exit-fn (:success excd/codes)))
(def exit-fail (exit-fn (:fail excd/codes)))

(defn- image->imgur!
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

    ;;; ----------------------------------------------------------------------------------------------------------------
    ;;; Handle --help or --version if they were used.
    ;;; ----------------------------------------------------------------------------------------------------------------

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

      ;;; --------------------------------------------------------------------------------------------------------------
      ;;; Run preflight checks.
      ;;; --------------------------------------------------------------------------------------------------------------

      (doseq [c ["curl" "identify" "xargs" "jq"]]
        (when-not (cmd/exists? c)
          (log/error (str c " is required to use imgur, but could not be found."))
          (exit-fail)))

      ;; Image file path or URL was provided.
      (when-not image-opt
        (log/error "An input image is required by imgur.")
        (exit-fail))

      ;; url can be read.
      (when (and (net/url? image-opt) (not (net/url-valid? image-opt)))
        (log/error (str image-opt " cannot be read."))
        (exit-fail))

      ;;; --------------------------------------------------------------------------------------------------------------
      ;;; Upload the image to Imgur
      ;;; --------------------------------------------------------------------------------------------------------------

      (let [downloaded-file (img/download-image! image-opt)
            image (or downloaded-file image-opt)]

        (when-not (img/image-valid? image)
          (log/error (str image-opt " is not a valid image for imgur."))
          (exit-fail))

        (let [imgur-url (image->imgur! image)]
          (println imgur-url))

        ;; We don't want downloaded images in /tmp to consume disk space.
        (when (net/url? image-opt)
          (fs/delete-if-exists downloaded-file))

        (exit-success)))))

(script/run -main *command-line-args*)