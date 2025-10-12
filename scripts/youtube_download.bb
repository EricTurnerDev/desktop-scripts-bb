#!/usr/bin/env bb

(ns youtube-download
  "
  Download YouTube videos.

  USAGE:
    youtube-download [OPTIONS]

  OPTIONS:
    -d, --directory DIR   The directory to output the downloaded files into. Defaults to the user's current working directory.
    -h, --help            Show the help message
    -u, --url URL         The URL of the YouTube channel or video to download
    -v, --version         Show the version

  AUTHOR:
     jittery-name-ninja@duck.com
  "
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [cli :as dscli]
            [command :as cmd]
            [logging :as log]
            [net]
            [script]
            [user-utils]))

(def ^:const exit-codes {:success 0 :fail 1})
(def ^:const script-name "youtube-download")
(def ^:const version "0.0.1")
(def ^:const default-directory (fs/path (fs/cwd)))

(def ^:const cli-options
  [["-d" "--directory DIR" "The directory to output the downloaded files into"]
   ["-h" "--help" "Show the help message"]
   ["-u", "--url URL" "URL of the YouTube video or channel"]
   ["-v" "--version" "Show the version"]])

(defn- exit-success [] (System/exit (:success exit-codes)))

(defn- exit-fail [] (System/exit (:fail exit-codes)))

(defn- handle-cli-errors [errors]
  "Handles errors from parsing command-line options."
  (binding [*out* *err*]
    (doseq [e errors] (log/error e))
    (exit-fail)))

;; TODO: Need an option to ignore the download archive or override the configured location of the archive.
;; TODO: if the video is already listed in the archive preventing download, we need to tell the user that.
(defn download [{:keys [url directory]}]
  (shell {:out      :string
          :err      :string
          :continue true}
         "yt-dlp" "--output" (fs/path directory "%(channel)s" "%(upload_date)s - %(fulltitle)s" "%(upload_date)s - %(fulltitle)s") url))

(defn -main [& args]

  ;; Configure logging. Root logs to /var/log/, everyone else to /tmp/ .
  (let [log-file (if (= (user-utils/uid) 0)
                   (str "/var/log/" script-name ".log")
                   (str "/tmp/" script-name ".log"))]
    (log/configure! {:file log-file}))

  ;; --------------------------------------------------------------------------
  ;; Process command line arguments
  ;; --------------------------------------------------------------------------
  (let [parsed-opts (dscli/parse-opts args cli-options handle-cli-errors)
        options (:options parsed-opts)]

    ;; ------------------------------------------------------------------------
    ;; Handle --help and --version options
    ;; ------------------------------------------------------------------------

    ;; Show the help message
    (when (:help options)
      (println (:doc (meta (the-ns 'youtube-download))))
      (exit-success))

    ; Show the version
    (when (:version options)
      (println script-name "version" version)
      (exit-success))

    ;; ------------------------------------------------------------------------
    ;; Preflight checks
    ;; ------------------------------------------------------------------------

    ;; yt-dlp can be run
    (when-not (cmd/exists? "yt-dlp")
      (log/error "yt-dlp not found.")
      (exit-fail))

    ;; Check that required options are provided

    (when (empty? (:url options))
      (log/error "URL isn't set.")
      (exit-fail))

    ;; Check that the URL is valid.

    (let [url (:url options)]
      (when-not (and (net/url? url) (net/url-valid? url))
        (log/error (str url " cannot be used."))
        (exit-fail)))

    ;; Check that the output directory is valid.
    (let [dir (or (:directory options) default-directory)]
      (when-not (and (fs/exists? dir) (fs/writable? dir))
        (log/error (str "Cannot write to " dir))
        (exit-fail)))

    ;; TODO: Read the ~/.config/yt-dlp/config file to find out where the cookies file is, then make sure it is recent.

    ;; ------------------------------------------------------------------------
    ;; Download
    ;; ------------------------------------------------------------------------

    (let [dir (or (:directory options) default-directory)
          url (:url options)
          {:keys [err exit]} (download {:url url :directory dir})]
      (if (not (= exit 0))
        (log/error (str "Unable to download from " url ": " err))))

    ;; TODO: log/info the directory the video files were downloaded into
    (exit-success)))

(script/run -main)