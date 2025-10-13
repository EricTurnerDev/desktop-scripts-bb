(ns pod.babashka.postgresql)

(defn execute!
  "IDE-only stub so Cursive doesn't flag pg/execute! as unresolved."
  [& _])

(defmacro with-transaction
  "Executes the body within a database transaction.
   If an exception is thrown, the transaction will be rolled back.
   Otherwise, it will be committed.

   Usage:
   (with-transaction [tx db-spec]
     (pg/execute! tx [\"insert into foo ...\"])
     (pg/execute! tx [\"update bar ...\"]))"
  [[tx-binding db-spec] & body])