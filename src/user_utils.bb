(ns user-utils
  "Provides functions for working with operating system users."
  (:require [clojure.string :as str]
            [babashka.process :refer [sh]]))

(defn uid
  "Gets the user id of the current process"
  []
  (try
    (Integer/parseInt (str/trim (:out (sh "id" "-u"))))
    (catch Exception _ -1)))

(defn superuser?
  "Determines if the current user is a superuser (i.e. running as root or using sudo)."
  []
  (= (uid) 0))