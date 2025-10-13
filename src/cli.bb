(ns cli
  (:require [clojure.tools.cli :as ctcli]
            [logging :as log]))

(defn parse-opts
  "Parses command line options opts. Calls err-fn with errors."
  [args opts err-fn]
  (let [parsed-opts (ctcli/parse-opts args opts)
        {:keys [errors]} parsed-opts]
    (when errors
      (err-fn errors))
    parsed-opts))

(defn handle-cli-errors
  "Logs errors from parsing command-line options, then calls fn."
  [errors fn]
  (binding [*out* *err*]
    (doseq [e errors] (log/error e))
    (fn)))