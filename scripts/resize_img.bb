#!/usr/bin/env bb

(ns resize-img
  "
   Resizes an image.

   Resizes an image from either a local file or URL passed on the command line or to stdin, and prints
   the resized image file path to stdout.

   USAGE:
     resize-img [OPTIONS] [FILE|URL]

   OPTIONS:
     -h, --help         Show the help message.
     -o, --output       The resized image file to create.
     -p, --percent PERC The percent to resize the image by.
     -v, --version      Show the version.
     -w, --width WIDTH  The width of the output image. Aspect ratio of the image is maintained.

   EXAMPLE:
     resize-img --width 1024 -o ./example-1024w.jpg ./example.jpg
     resize-img -p 50 -o ./example-50pct.jpg http://example.com/example.jpg
     echo http://example.com/example.jpg | resize-img -w 256 -o ./example-256w.jpg

   AUTHOR:
     jittery-name-ninja@duck.com
  "
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [clojure.string :as str]
            [cli]
            [command :as cmd]
            [image :as img]
            [logging :as log]
            [net]
            [script]
            [user-utils :as user]))

(def ^:const version "0.0.1")

(def ^:const cli-opts
  [["-h" "--help" "Show the help message"]
   ["-o" "--output FILE" "The resized image file to create"]
   ["-p" "--percent PERC" "The percent to resize the image"]
   ["-v" "--version" "Show the version"]
   ["-w" "--width WIDTH" "The width of the output image"]])

(def ^:const commands ["convert"])

(defn exit-fn [code] (fn [] (System/exit code)))
(def exit-success (exit-fn 0))
(def exit-fail (exit-fn 1))

(defn -main
  [& args]

  ;; Configure logging
  (let [log-file (if (user/superuser?)
                   (str "/var/log/resize-img.log")
                   (str "/tmp/resize-img.log"))]
    (log/configure! {:file log-file}))

  (let [parsed-opts (cli/parse-opts args cli-opts)
        options (:options parsed-opts)
        image-path (first (:arguments parsed-opts))]

    ;; User passed --help
    (when (:help options)
      (println (log/ns-doc 'resize-img))
      (exit-success))

    ;; User passed --version
    (when (:version options)
      (println version)
      (exit-success))

    ;; Image option can be provided by the argument or from stdin.
    (let [image-opt (or image-path
                        (some-> (slurp *in*) str/trim not-empty))]

      ;;; --------------------------------------------------------------------------------------------------------------
      ;;; Run preflight checks.
      ;;; --------------------------------------------------------------------------------------------------------------

      ;; Required shell commands installed?
      (doseq [c commands]
        (when-not (cmd/exists? c)
          (log/error (str c " is required to use resize-img, but was not found."))
          (exit-fail)))

      ;; Input image file path or URL provided?
      (when-not image-opt
        (log/error "An input image is required by resize-img.")
        (exit-fail))

      ;; url can be read?
      (when (and (net/url? image-opt) (not (net/url-valid? image-opt)))
        (log/error (str image-opt " cannot be read."))
        (exit-fail))

      ;; output file path provided?
      (when-not (:output options)
        (log/error "The --output option is required by resize-img.")
        (exit-fail))

      ;; Width or percent provided?
      (when-not (or (:width options) (:percent options))
        (log/error "The --width or --percent options are required by resize-img.")
        (exit-fail))

      ;; Either width or percent (but not both) provided?
      (when (and (:width options) (:percent options))
        (log/error "Use either the --width or --percent options to resize-img, but not both.")
        (exit-fail))

      ;;; --------------------------------------------------------------------------------------------------------------
      ;;; Resize the image
      ;;; --------------------------------------------------------------------------------------------------------------

      ;; download the image or use it from the file system.
      (let [dwnld-img-path (img/download-image! image-opt)
            input-img-path (or dwnld-img-path image-opt)]

        (when-not (img/image-valid? input-img-path)
          (log/error (str image-opt " is not a valid image for resize-img."))
          (exit-fail))

        ;; Resize
        (let [width (if (:percent options) (str (:percent options) "%") (:width options))
              {:keys [err exit]} (shell {:out :string :err :string} "convert" "-resize" width input-img-path (:output options))]

          ;; We don't want downloaded images to consume disk space.
          (when (net/url? image-opt)
            (fs/delete-if-exists dwnld-img-path))

          (when (> exit 0)
            (log/error err)
            (exit-fail))

          (println (:output options)))

        (exit-success)))))

(script/run -main *command-line-args*)


