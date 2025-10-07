(ns lock
  (:require [clojure.java.io :as io])
  (:import (java.io RandomAccessFile)))

(defn obtain-lock!
  "Try to obtain an exclusive lock on lock file lf managed by a lock state atom ls.
   Returns true on success, nil on failure."
  [lf ls]
  (try
    (let [raf (RandomAccessFile. lf "rw")
          chan (.getChannel raf)
          lock (.tryLock chan)]
      (if (nil? lock)
        (do
          (.close chan)
          (.close raf)
          nil)
        (do
          (reset! ls {:raf raf :chan chan :lock lock})
          true)))
    (catch Exception _ nil)))

(defn add-release-hook!
  "Adds a shutdown hook to release lock file lf managed by a lock state atom ls when the script ends."
  [lf ls]
  ;; Remove the lock when the script finishes
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. (fn []
                               (when-let [{:keys [raf chan lock]} @ls]
                                 (try (.release lock) (catch Exception _))
                                 (try (.close chan) (catch Exception _))
                                 (try (.close raf) (catch Exception _))
                                 (try (io/delete-file lf true) (catch Exception _)))))))