#!/usr/bin/env bb

(ns scramble
  "
  Encrypts or decrypts a file with a password using GnuPG. When encrypting, the resulting encrypted file name will end
  in a `.scramble` extension. When decrypting, the resulting unencrypted file name will not end in a `.scramble` extension.

  USAGE:
    scramble [OPTIONS] [FILE]

  OPTIONS:
    -h, --help   Show the help message
    -k, --keep   Don't delete FILE when done
  "
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [cli]
            [clojure.string :as str]
            [command :as cmd]
            [file]
            [logging :as log]
            [script])
  (:import [java.nio.file.attribute PosixFilePermission]))

(def ^:const version "0.0.1")

(def ^:const cli-options
  [["-h" "--help" "Show the help message"]
   ["-k" "--keep" "Don't delete the input file"]
   ["-v" "--version" "Show the version"]])

(def ^:const shell-commands ["file" "gpg"])
(def ^:const cipher "AES256")

(defn- exit-fn
  [status]
  (fn []
    (System/exit status)))

(def exit-success (exit-fn 0))
(def exit-fail (exit-fn 1))

(defn- encrypted?
  "Determines if a file is encrypted or not."
  [path]
  (let [descr (file/description path)]
    (str/includes? descr "encrypted")))

(defn remove-write-permissions
  [path]
  (let [perms (fs/posix-file-permissions path)
        write-perms [PosixFilePermission/OWNER_WRITE
                     PosixFilePermission/GROUP_WRITE
                     PosixFilePermission/OTHERS_WRITE]]
    (fs/set-posix-file-permissions path (reduce disj (set perms) write-perms))))

(defn encrypt
  "Encrypt a file, adding a `.scramble` extension to the file name."
  [path]
  (let [out-path (str path ".scramble")]
    (into
      (shell
        {:out :string :err :string :continue true}
        "gpg" "--cipher-algo" cipher "--quiet" "--output" out-path "--symmetric" path)
      {:path out-path})))

(defn decrypt
  "Decrypt a file, removing a `.scramble` extension from the file name."
  [path]
  (let [out-path (str/replace path #"(.*)\.scramble$" "$1")]
    (shell
      {:out :string :err :string :continue true}
      "gpg" "--cipher-algo" cipher "--quiet" "--output" out-path "--decrypt" path)))

(defn -main
  [& args]

  ;; Make sure all the required shell commands are installed.
  (doseq [c shell-commands]
    (when-not (cmd/exists? c)
      (log/error (str "The " c " command is not installed"))
      (exit-fail)))

  (let [parsed-opts (cli/parse-opts args cli-options)
        options (:options parsed-opts)
        file-path (first (:arguments parsed-opts))]

    (when (:help options)
      (println (log/ns-doc 'scramble))
      (exit-success))

    (when (:version options)
      (println version)
      (exit-success))

    (when (empty? file-path)
      (log/error "No file was provided")
      (exit-fail))

    (when-not (and (fs/exists? file-path) (fs/readable? file-path))
      (log/error (str file-path " cannot be read"))
      (exit-fail))

    (if (encrypted? file-path)
      (let [result (decrypt file-path)]
        (when (> (:exit result) 0)
          (log/error (:err result))
          (exit-fail))
        (when-not (:keep options)
          (fs/delete file-path)))
      (let [result (encrypt file-path)]
        (when (> (:exit result) 0)
          (log/error (:err result))
          (exit-fail))
        ;; Don't allow the encrypted file to be modified by anyone.
        (remove-write-permissions (:path result))
        (when-not (:keep options)
          (fs/delete file-path))))

    (exit-success)))

(script/run -main *command-line-args*)

