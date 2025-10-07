(ns exit-codes)

(def ^:const codes
  {:success          0
   :fail             1
   :preflight-fail   2
   :smart-fail       3
   :snapraid-fail    4
   :sync-fail        5
   :scrub-fail       6
   :lock-fail        7
   :diff-fail        8
   :permissions-fail 9})