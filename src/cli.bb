(ns cli
  (:require [clojure.tools.cli :as ctcli]))

(defn parse-opts
  "Parses command line options opts. Calls err-fn with errors."
  [args opts err-fn]
  (let [parsed-opts (ctcli/parse-opts args opts)
        {:keys [errors]} parsed-opts]
    (when errors
      (err-fn errors))
    parsed-opts))