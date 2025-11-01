#!/usr/bin/env bb

(ns new-bb
  "
  Creates a new Babashka project.

  USAGE:
     new-bb [OPTIONS]

  OPTIONS:
     -h, --help           Display the help message.
     -d, --directory DIR  Directory for the project.
     -v, --version        Display the version.
  "
  (:require [babashka.fs :as fs]
            [cli :as dscli]
            [clojure.pprint :as pp]
            [logging :as log]
            [net]
            [script]))

(def ^:const version "0.0.2")

(def ^:const cli-opts [["-h" "--help" "Display the help message"]
                       ["-d", "--directory DIR" "Directory for the Babashka project"]
                       ["-v" "--version" "Display the version"]])

(def ^:const bb-edn
  {:paths ["src" "scripts"]
   :pods {}
   :tasks
   {'build
    '(do
       (shell "rm -rf out")
       (shell "mkdir out")
       (shell "bb uberscript out/main scripts/main.bb")
       (shell "sed -i '1i #!/usr/bin/env bb' out/main")
       (shell "chmod +x out/main"))}})

(def ^:const main-ns
  '(ns main
     (:require [script])))

(def ^:const main-fn
  '(defn -main
     [& args]
     (println "Hello, Babashka!")))

(def ^:const run-main
  '(script/run -main *command-line-args*))

(defn- help
  "Prints help message."
  []
  (println (log/ns-doc 'new-bb)))

(defn- exit-success []
  (System/exit 0))

(defn- exit-error []
  (System/exit 1))

(defn- create-project
  "Creates a new project"
  [dir]
  (let [bb-file (fs/path dir "bb.edn")
        src-dir (fs/path dir "src")
        script-file (fs/path src-dir "script.bb")
        scripts-dir (fs/path dir "scripts")
        main-file (fs/path scripts-dir "main.bb")]
    (fs/delete-tree (fs/path dir))
    (fs/create-dirs (fs/parent bb-file))
    (fs/create-dirs src-dir)
    (spit
      (fs/file script-file)
      (with-out-str
        (pp/pprint '(ns script))
        (pp/pprint '(defn run
                      "Ensure that function f is only called when a script is run directly, not when it's required by another script."
                      [f args]
                      (when (= *file* (System/getProperty "babashka.file"))
                        (apply f args))))))
    (fs/create-dirs scripts-dir)
    (spit (fs/file bb-file) (with-out-str (pp/pprint bb-edn)))
    (spit
      (fs/file main-file)
      (with-out-str
        (println "#!/usr/bin/env bb")
        (pp/pprint main-ns)
        (pp/pprint main-fn)
        (pp/pprint run-main)))))

(defn -main [& args]
  (let [parsed-opts (cli/parse-opts args cli-opts #(dscli/handle-cli-errors % exit-error))
        opts (:options parsed-opts)]

    (when (:help opts)
      (help)
      (exit-success))

    (when (:version opts)
      (println version)
      (exit-success))

    (when (:directory opts)
      (create-project (:directory opts))
      (net/download-file "https://raw.githubusercontent.com/github/gitignore/refs/heads/main/Global/JetBrains.gitignore" (str (fs/path (:directory opts) ".gitignore")))
      (exit-success))

    (binding [*out* *err*]
      (println "Incorrect usage.")
      (help)
      (exit-error))
    ))

(script/run -main *command-line-args*)
