(ns script)

(defn run
  "Ensure that function f is only called when a script is run directly, not when it's required by another script."
  [f]
  (when (= *file* (System/getProperty "babashka.file"))
    (apply f *command-line-args*)))
