(ns cli
  (:require [clojure.tools.cli :as ctcli]
            [logging :as log]))

(defn- exit-fail
  []
  (System/exit 1))

(defn handle-cli-errors
  "Logs errors from parsing command-line options, then calls fn."
  [errors fn]
  (binding [*out* *err*]
    (doseq [e errors] (log/error e))
    (fn)))

(defn parse-opts
  ([args opts] (parse-opts args opts #(handle-cli-errors % exit-fail)))
  ([args opts err-fn]
   (let [parsed-opts (ctcli/parse-opts args opts)
         {:keys [errors]} parsed-opts]
     (when errors
       (err-fn errors))
     parsed-opts)))