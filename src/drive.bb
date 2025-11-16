(ns drive
  "Provides functions for working with drives."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :refer [shell]]))

(defn mounted?
  "Checks if a drive is mounted."
  [path]
  (zero? (:exit (shell {:out      :string
                        :err      :string
                        :continue true}                     ; Don't throw exception on exit != 0
                       "mountpoint" "-q" path))))

(defn mount-source
  "Returns the device backing a mount point.
   On failure or if not mounted, returns nil."
  [mountpoint]
  (let [{:keys [out exit]} (shell {:out      :string
                                   :err      :string
                                   :continue true}
                                  "findmnt" "-no" "SOURCE" mountpoint)]
    (when (zero? exit)
      (let [src (str/trim out)]
        (when-not (str/blank? src)
          (try
            (str (fs/real-path src))
            (catch Exception _ src)))))))

(defn smart-healthy?
  "Returns true if smartctl reports overall health as PASSED, else false."
  [device]
  (let [{:keys [out exit]} (shell {:out      :string
                                   :err      :string
                                   :continue true}
                                  "smartctl" "-H" device)]
    (and (zero? exit)
         (or (str/includes? out "PASSED")
             (str/includes? out "OK")))))

(defn smart-managed?
  "Returns true if smartctl is managing the device, else false. Throws an exception if not run as root."
  [device]
  (let [{:keys [out exit]} (shell {:out :string
                                   :err :string}
                                  "smartctl" "-i" device)]
    (and (zero? exit)
         (re-find #"SMART support is:\s+Enabled" out))))

(defn standby!
  "Puts a disk into standby mode."
  [device]
  (let [{:keys [exit]} (shell {:out      :string
                               :err      :string
                               :continue true}
                              "hdparm" "-y" device)]
    (zero? exit)))