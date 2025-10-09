(ns command
  "Provides functions for working with operating system commands (i.e. programs, scripts, etc)."
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]))

(defn exists?
  "Checks if the user can run a command. Returns true or false."
  [prog]
  (some? (fs/which prog)))

(defn running?
  "Checks if a command is already running. Returns true or false."
  [cmd]
  (let [res (shell {:out      :string
                    :err      :string
                    :continue true}
                   "pgrep" "-f" "-l" (str "\\b" cmd "(\\s|$)"))]
    (zero? (:exit res))))