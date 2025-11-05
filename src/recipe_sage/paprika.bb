(ns recipe-sage.paprika
  (:require [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import (java.io File)
           (java.util.zip GZIPInputStream ZipEntry ZipFile)))

;; Example usage:
;;   (with-recipes "/tmp/Recipes.paprikarecipes" print-handler)

(defn- print-handler
  "Prints the keys of a recipe document. This is just an example of how to handle a recipe."
  [name doc]
  (println name)
  (println (keys doc)))

(defn- process-recipe
  "Read the recipe from the gzipped entry `e` in zip file `zf`, and call `handler` with the entry name and recipe."
  [^ZipFile zf ^ZipEntry e handler]
  (let [name (.getName e)]
    (with-open [raw-in (.getInputStream zf e)
                gz-in (GZIPInputStream. raw-in)
                rdr (io/reader gz-in)]
      (let [doc (json/parse-stream rdr keyword)]
        (handler name doc)))))

(defn with-recipes
  "Call function `recipe-handler` on each recipe from .paprikarecipes file `paprika-file`.
  The `recipe-handler` function is given the file name of the recipe, and a map of its contents."
  [paprika-file recipe-handler]
  (let [pf (io/file paprika-file)]
    (when-not (.exists ^File pf)
      (throw (ex-info "Paprika recipes file not found" {:path paprika-file})))
    (with-open [zf (ZipFile. pf)]
      (let [entries (enumeration-seq (.entries zf))]
        (loop [es entries
               acc {:processed 0 :failed 0}]
          (if-let [e (first es)]
            (let [acc'
                  (try
                    (process-recipe zf e recipe-handler)
                    (update acc :processed inc)
                    (catch Exception ex
                      (binding [*out* *err*]
                        (println "ERROR processing recipe:" (.getName e) (.getMessage ex))
                        (update acc :failed inc))))]
              (recur (rest es) acc'))
            acc))))))