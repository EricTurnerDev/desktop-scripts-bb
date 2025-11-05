#!/usr/bin/env bb

(ns paprika-to-recipesage
  "
  Import recipes from a Paprika recipes file (.paprikarecipes) into a self-hosted RecipeSage instance.

  WARNING: All existing recipes in RecipeSage that were assigned the paprika label will be deleted! This effectively replaces
  all Paprika recipes that have previously been imported into RecipeSage with recipes from the .paprikarecipes file you
  are importing.

  The new .paprikarecipes file will be imported, and those recipes will be assigned the paprika label.

  USAGE:
    paprika-to-recipesage [OPTIONS]

  OPTIONS:
    -d, --database       The name of the PostgreSQL database. Default is recipesage_selfhost.
    -h, --help           Show the help message.
    -H, --host           The host that the RecipeSage PostgreSQL database is running on. Default is 127.0.0.1.
    -f, --file           The .paprikarecipes file to import. Required.
    -l, --label          The label of the existing recipes to delete, and to assign to the newly-imported recipes. Default is paprika.
    -p, --port           The port that the RecipeSage PostgreSQL database is listening on. Default is 5432.
    -P, --password       The RecipeSage PostgreSQL database password. Default is recipesage_selfhost.
    -u, --user           The RecipeSage PostgreSQL database username. Default is recipesage_selfhost.
    -v, --version        Show the version.
    -w, --web-base-url   The base URL that the RecipeSage web is running on. Default is http://127.0.0.1:7270.
    --web-user           The RecipeSage website username. Required.
    --web-password       The RecipeSage website password. Required.

  EXAMPLES:
    paprika-to-recipesage --paprika-file /path/to/my-recipes.paprikarecipes --web-user \"me@example.com\" --web-password \"examplepass\"
    paprika-to-recipesage --help
    paprika-to-recipesage --version

  AUTHOR:
    jittery-name-ninja@duck.com
  "
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [cli :as dscli]
            [lock]
            [logging :as log]
            [recipe-sage.paprika :as paprika]
            [recipe-sage.db :as db]
            [script]
            [user-utils])
  (:import (java.io File)))

(def ^:const cli-options
  [["-d", "--database DATABASE", "The name of the RecipeSage PostgreSQL database"
    :default "recipesage_selfhost"]
   ["-h", "--help", "Show the help message"]
   ["-H", "--host HOST", "The RecipeSage PostgreSQL host"
    :default "127.0.0.1"]
   ["-f", "--file FILE", "The .paprikarecipes file to import"
    :required "The .paprikarecipes file"]
   ["-l", "--label LABEL", "The label of the recipes to delete and import"
    :default "paprika"]
   ["-p", "--port PORT", "The RecipeSage PostgreSQL port"
    :default 5432]
   ["-P", "--password PASS", "The RecipeSage PostgreSQL password"
    :default "recipesage_selfhost"]
   ["-u", "--user USER", "The RecipeSage PostgreSQL username"
    :default "recipesage_selfhost"]
   ["-v", "--version", "Show the version"]
   ["-w", "--web-base-url URL", "The base URL of the RecipeSage website"
    :default "http://127.0.0.1:7270"]
   [nil, "--web-user USER", "The RecipeSage website username"
    :required "The RecipeSage web username"]
   [nil, "--web-password PASSWORD", "The RecipeSage website password"
    :required "The RecipeSage web password"]])

(def ^:const version "0.0.1")
(def ^:const script-name "paprika-to-recipesage")
(def ^:const exit-codes {:success 0 :fail 1})
(def ^:const lock-file (str (fs/path "/tmp/" (str script-name ".lock"))))

(defonce lock-state (atom nil))

(defn- exit-success []
  (System/exit (:success exit-codes)))

(defn- exit-fail []
  (System/exit (:fail exit-codes)))

(defn- strip-trailing-slash [s]
  (str/replace s #"/$" ""))

(defn login!
  "Logs in to RecipeSage like the browser does. Returns the session token string or throws ex-info on failure."
  [base-url email password]
  (let [url (str (strip-trailing-slash base-url) "/api/users/login?false=false")
        resp (http/post url
                        {:headers {"Content-Type" "application/json"
                                   "Accept"       "application/json, text/plain, */*"}
                         :body    (json/generate-string {:email    email
                                                         :password password})
                         ;; bb.http-client prefers :string; we'll parse ourselves
                         :as      :string
                         :throw   false})
        body (try (json/parse-string (:body resp) true)
                  (catch Exception _ (:body resp)))]
    (cond
      (<= 200 (:status resp) 299)
      (if-let [token (:token body)]
        token
        (throw (ex-info "Login succeeded but response lacked :token"
                        {:status (:status resp) :body body})))

      :else
      (throw (ex-info "Login failed"
                      {:status (:status resp) :body body})))))


(defn import-paprika!
  "POST a .paprikarecipes file to RecipeSage's import endpoint.
   - base-url: e.g. \"http://localhost:7270\" (no trailing slash)
   - token:    session token string
   - file:     path to the .paprikarecipes

   Returns {:ok? bool :status int :body any|nil :error any|nil}."
  [base-url token file]
  (let [f (fs/file file)
        url (str (strip-trailing-slash base-url)
                 "/api/data/import/paprika?token=" token)
        resp (http/post url
                        {:multipart [{:name         "paprikadb" ;; from HAR
                                      :filename     (fs/file-name f)
                                      :content-type "application/octet-stream"
                                      :content      (File. (str f))}]
                         :headers   {"Accept" "application/json, text/plain, */*"}
                         ;; IMPORTANT: bb.http-client doesn’t accept :as :json here
                         :as        :string
                         :throw     false})
        parsed (try
                 (some-> (:body resp) (json/parse-string true))
                 (catch Exception _ (:body resp)))]
    (if (<= 200 (:status resp) 299)
      {:ok? true :status (:status resp) :body parsed}
      {:ok? false :status (:status resp) :error parsed})))

(defn -main [& args]

  ;; Configure logging
  (let [log-file (if (= (user-utils/uid) 0)
                   (str "/var/log/" script-name ".log")
                   (str "/tmp/" script-name ".log"))]
    (log/configure! {:file log-file}))

  (let [parsed-opts (dscli/parse-opts args cli-options #(dscli/handle-cli-errors % exit-fail))
        options (:options parsed-opts)]

    ;; -----------------------------------------------------------------------------------------------------------------
    ;; handle --version and --help
    ;; -----------------------------------------------------------------------------------------------------------------

    (when (:help options)
      (println (log/ns-doc 'paprika-to-recipesage))
      (exit-success))

    (when (:version options)
      (println script-name "version" version)
      (exit-success))

    ;; -----------------------------------------------------------------------------------------------------------------
    ;; Prevent multiple instances of this script running against the same RecipeSasage database.
    ;; -----------------------------------------------------------------------------------------------------------------

    (when-not (lock/obtain-lock! lock-file lock-state)
      (log/error "Another instance is already running.")
      (exit-fail))

    ;; -----------------------------------------------------------------------------------------------------------------
    ;; Pre-flight checks.
    ;; -----------------------------------------------------------------------------------------------------------------

    (when (empty? (:file options))
      (log/error "Filename is required.")
      (exit-fail))

    (when (empty? (:web-user options))
      (log/error "RecipeSage username is required.")
      (exit-fail))

    (when (empty? (:web-password options))
      (log/error "RecipeSage password is required.")
      (exit-fail))

    ;; -----------------------------------------------------------------------------------------------------------------
    ;; Update the RecipeSage database.
    ;; -----------------------------------------------------------------------------------------------------------------

    (db/configure! {:dbname   (:database options)
                    :host     (:host options)
                    :port     (:port options)
                    :user     (:user options)
                    :password (:password options)})

    ;; This does nothing if the label already exists.
    (db/create-label! (:label options))

    ;; Delete the existing recipes so we don't end up with duplicates.
    (db/delete-recipes-by-label! (:label options))

    ;; Log in and import the recipes into RecipeSage
    (try
      (let [file (:file options)
            url (:web-base-url options)
            user (:web-user options)
            pwd (:web-password options)]
        (let [token (login! url user pwd)]
          (import-paprika! url token file)))
      (catch Exception _
        (log/error "Unable to log in to RecipeSage")
        (exit-fail)))

    (db/add-label-to-recent-recipes! (:label options) 1)

    ;; RecipeSage's import feature has a bug that puts ratings in the notes field
    ;; instead of setting the rating of the recipe. This works around that bug.
    (db/update-ratings!)

    ;; Change the creation dates of the recipes to the values from the recipes in the .paprikarecipes file.
    (paprika/with-recipes (:file options) (fn [_ recipe] (recipe-sage.db/update-creation-date! recipe)))

    (log/info "Done")
    (exit-success)))

(script/run -main *command-line-args*)

