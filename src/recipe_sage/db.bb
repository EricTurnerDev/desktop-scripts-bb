(ns recipe-sage.db
  (:require [pod.babashka.postgresql :as pg]))

(def ^:private allowed-keys
  #{:dbtype :dbname :host :port :user :password})

;; -- Database Connection ----------------------------------------------------------------------------------------------

(def ^:private default-db-spec {:dbtype "postgresql"
                                :dbname "recipesage_selfhost"
                                :host   "127.0.0.1"
                                :port   5432
                                :user   "recipesage_selfhost"})

(defonce ^:private db-spec* (atom default-db-spec))

(defn current-spec
  "Return the current DB spec map (for pg/execute! etc)."
  []
  @db-spec*)

(defn configure!
  "Merge overrides into the current spec. Accepts a map or k/v pairs.
   Returns the updated spec."
  ([m]
   (let [m' (if (map? m) (select-keys m allowed-keys)
                         (throw (ex-info "configure! expects a map" {:got (type m)})))]
     (swap! db-spec* merge m')))
  ([k v & kvs]
   ;; k/v arity convenience: (configure! :host \"db\" :port 5433)
   (let [m (apply hash-map k v kvs)]
     (configure! m))))

(defn reset-spec!
  "Reset to defaults, or merge defaults with provided overrides."
  ([] (reset! db-spec* default-db-spec))
  ([overrides]
   (reset! db-spec* (merge default-db-spec (select-keys overrides allowed-keys)))))

;; -- Database Query Functions -----------------------------------------------------------------------------------------

(defn get-label
  "Gets a recipe from the RecipeSage PostgreSQL database."
  [label]
  (pg/execute! (current-spec) ["SELECT * FROM \"Labels\" WHERE title=? LIMIT 1" label]))

(defn get-recent-user
  "Gets the user of the most recently-created recipe in RecipeSage."
  []
  (let [query ["SELECT u.id FROM \"Recipes\" r INNER JOIN \"Users\" u ON r.\"userId\"=u.id ORDER BY r.\"createdAt\" DESC LIMIT 1"]]
    (first (pg/execute! (current-spec) query))))

;; -- Database Modification Functions ----------------------------------------------------------------------------------

(defn create-label!
  "Create a new label in RecipeSage."
  [label]
  (let [lbl (get-label label)]
    (if (empty? lbl)
      (let [{:Users/keys [id]} (get-recent-user)
            query ["INSERT INTO \"Labels\" (id, \"updatedAt\", \"userId\",\"title\") VALUES (gen_random_uuid(),CURRENT_TIMESTAMP(6),?,?)" id label]
            results (pg/execute! (current-spec) query)
            result (first results)]
        (or (:next.jdbc/update-count result) 0))
      0)))

(defn delete-recipes-by-label!
  "Delete all Recipes from RecipeSage associated with the given label title. Returns the number of deleted recipes."
  [label]
  (let [query ["WITH deleted AS (
                  DELETE FROM \"Recipes\" r
                  USING \"Recipe_Labels\" rl, \"Labels\" l
                  WHERE rl.\"recipeId\"=r.id AND
                        rl.\"labelId\"=l.id AND
                        l.title=?
                  RETURNING 1
               )
               SELECT COUNT(*)::int AS deleted FROM deleted" label]]

    (pg/with-transaction [tx (current-spec)]
                         (let [row (first (pg/execute! tx query))]
                           (or (:deleted row) 0)))))

(defn add-label-to-recent-recipes!
  "Assigns a label to recipes that were created in RecipeSage within the last n minutes. Returns the nubmer of recipes updated."
  [label n]
  (let [lbl (first (get-label label))]
    (if (not-empty lbl)
      (let [query [(str "INSERT INTO \"Recipe_Labels\" (id, \"recipeId\", \"labelId\", \"updatedAt\")
                   SELECT gen_random_uuid(), id, ?, now() FROM \"Recipes\"
                   WHERE \"createdAt\" >= NOW() - INTERVAL '" n " minute'") (:Labels/id lbl)]
            results (pg/execute! (current-spec) query)
            result (first results)]
        (or (:next.jdbc/update-count result) 0))
      0)))

(defn update-ratings!
  "Sets the ratings on each recipe in RecipeSage. RecipeSage's import has a bug that doesn't set the recipe ratings."
  []
  (doseq [n (range 1 6)]
    (let [query ["UPDATE \"Recipes\"
                  SET \"rating\"=?
                  WHERE \"notes\" LIKE ?"
                 n
                 (str "%Rating: " n "%")]]
      (pg/execute! (current-spec) query))))

(defn update-creation-date!
  "Sets the creation date on a recipe in RecipeSage to the value from the Paprika recipe."
  [paprika-recipe]
  (let [query ["UPDATE \"Recipes\"
                SET \"createdAt\"=(?::timestamp AT TIME ZONE 'America/New_York')
                WHERE \"title\"=?"
               (:created paprika-recipe)
               (:name paprika-recipe)]]
    (pg/execute! (current-spec) query)))