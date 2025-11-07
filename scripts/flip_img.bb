#!/usr/bin/env bb

(ns flip-img
  "
   Flips an image.

   Flips an image from either a local file or URL passed on the command line or to stdin, and prints
   the flipped image file path to stdout.

   USAGE:
     flip-img [OPTIONS] [FILE|URL]

   OPTIONS:
     -h, --help        Show the help message.
     -o, --output      The flipped image file to create. REQUIRED.
     -t, --vertical    Flip the image vertically.
     -v, --version     Show the version.
     -z, --horizontal  Flip the image horizontally (default).

   EXAMPLE:
     flip-img -o ./flipped-example.jpg ./example.jpg
     flip-img -o ./flipped2.jpg http://example.com/example.jpg
     echo http://example.com/example.jpg | flip-img -o ./flipped3.jpg

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
            [user-utils]))

(def ^:const version "0.0.1")

(def ^:const cli-opts
  [["-h" "--help" "Show the help message"]
   ["-o" "--output FILE" "The flipped image file to create"]
   ["-t" "--vertical" "Flip vertically"]
   ["-v" "--version" "Show the version"]
   ["-z" "--horizontal" "Flip horizontally"]])

(def ^:const commands ["convert"])

(defn exit-fn [code] (fn [] (System/exit code)))
(def exit-success (exit-fn 0))
(def exit-fail (exit-fn 1))

(defn -main
  [& args]

  ;; Configure logging
  (let [log-file (if (= (user-utils/uid) 0)
                   (str "/var/log/flip-img.log")
                   (str "/tmp/flip-img.log"))]
    (log/configure! {:file log-file}))

  (let [parsed-opts (cli/parse-opts args cli-opts)
        options (:options parsed-opts)
        image-path (first (:arguments parsed-opts))]

    ;; User passed --help
    (when (:help options)
      (println (log/ns-doc 'flip-img))
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
          (log/error (str c " is required to use flip-img, but was not found."))
          (exit-fail)))

      ;; Input image file path or URL provided?
      (when-not image-opt
        (log/error "An input image is required by flip-img.")
        (exit-fail))

      ;; url can be read?
      (when (and (net/url? image-opt) (not (net/url-valid? image-opt)))
        (log/error (str image-opt " cannot be read."))
        (exit-fail))

      ;; output file path provided?
      (when-not (:output options)
        (log/error "The --output option is required by flip-img.")
        (exit-fail))

      ;;; --------------------------------------------------------------------------------------------------------------
      ;;; Flip the image
      ;;; --------------------------------------------------------------------------------------------------------------

      ;; download the image or use it from the file system.
      (let [dwnld-img-path (img/download-image! image-opt)
            input-img-path (or dwnld-img-path image-opt)]

        (when-not (img/image-valid? input-img-path)
          (log/error (str image-opt " is not a valid image for flip-img."))
          (exit-fail))

        ;; Flip (vertically) or flop (horizontally) the image.
        (let [direction (if (:vertical options) "-flip" "-flop")
              {:keys [err exit]} (shell {:out :string :err :string} "convert" direction input-img-path (:output options))]

          ;; We don't want downloaded images to consume disk space.
          (when (net/url? image-opt)
            (fs/delete-if-exists dwnld-img-path))

          (when (> exit 0)
            (log/error err)
            (exit-fail))

          (println (:output options)))

        (exit-success)))))

(script/run -main *command-line-args*)


