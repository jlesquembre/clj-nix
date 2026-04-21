(ns cljnix.build.jvm
  "JVM-specific build implementation using tools.build."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.tools.deps :as deps]
    [clojure.tools.build.api :as b]
    [cljnix.build.core :as build-core]))

;; JVM-specific constants and helpers

(def class-dir "target/classes")

(defn remove-timestamp!
  "Remove timestamp from Maven pom.properties for reproducible builds."
  [root-dir lib-name]
  (let [f (io/file root-dir "META-INF/maven" (str lib-name) "pom.properties")]
    (->> (slurp f)
        string/split-lines
        (remove #(string/starts-with? % "#"))
        (string/join "\n")
        (spit f))))

(defn- get-paths
  "Get paths from deps.edn file"
  [deps]
  (-> deps
      io/file
      deps/slurp-deps
      :paths
      (or ["src"])))

(defn- parse-compile-clj-opts
  "Transform JSON string to the expected Clojure data type (keywords, symbols, ...)"
  [opts]
  (cond-> opts
    (:ns-compile opts)
    (update :ns-compile #(mapv symbol %))

    (:sort opts)
    (update :sort keyword)

    (get-in opts [:compile-opts :elide-meta])
    (update-in [:compile-opts :elide-meta] #(mapv keyword %))

    (:filter-nses opts)
    (update :filter-nses #(mapv symbol %))

    (:use-cp-file opts)
    (update :use-cp-file keyword)))

(defn common-compile-options
  "Build common options for JVM compilation."
  [{:keys [lib-name version]}]
  (let [lib-name (build-core/normalize-lib-name lib-name)]
    {:src-dirs (get-paths "deps.edn")
     :basis (b/create-basis {:project "deps.edn"})
     :lib-name lib-name
     :output-jar (build-core/output-jar-path lib-name version)}))

;; Public JVM build functions

(defn uber
  "Build an uberjar for JVM Clojure projects.

  Required opts:
  - :lib-name - Library name
  - :version - Version string
  - :main-ns - Main namespace with -main function

  Optional opts:
  - :compile-clj-opts - Options for compile-clj
  - :javac-opts - Options for javac
  - :uber-opts - Options for uber"
  [{:keys [main-ns compile-clj-opts javac-opts uber-opts] :as opts}]
  (build-core/validate-build-opts opts [:lib-name :version :main-ns])
  (let [{:keys [src-dirs basis output-jar]}
        (common-compile-options opts)]
    (b/copy-dir {:src-dirs src-dirs
                 :target-dir class-dir})
    (when javac-opts
      (b/javac (merge
                 javac-opts
                 {:basis basis
                  :class-dir class-dir})))
    (b/compile-clj (cond-> {:basis basis
                            :src-dirs src-dirs
                            :class-dir class-dir}
                     compile-clj-opts (merge (parse-compile-clj-opts compile-clj-opts))))

    (b/uber (cond-> {:class-dir class-dir
                     :uber-file output-jar
                     :basis basis
                     :main main-ns}
              uber-opts (merge uber-opts)))))

(defn jar
  "Build a library JAR for JVM Clojure projects.

  Required opts:
  - :lib-name - Library name
  - :version - Version string"
  [{:keys [version] :as opts}]
  (build-core/validate-build-opts opts [:lib-name :version])
  (let [{:keys [src-dirs basis lib-name output-jar]}
        (common-compile-options opts)]
    (b/write-pom {:class-dir class-dir
                  :lib lib-name
                  :version version
                  :basis basis
                  :src-dirs src-dirs})
    (b/copy-dir {:src-dirs src-dirs
                 :target-dir class-dir})
    (remove-timestamp! class-dir lib-name)
    (b/jar {:class-dir class-dir
            :jar-file output-jar})))

;; Builder protocol implementation for JVM

(defrecord JvmBuilder []
  build-core/Builder

  (build-jar [_this opts]
    (jar opts))

  (build-uberjar [_this opts]
    (uber opts))

  (get-src-paths [_this project-file]
    (get-paths project-file)))

(defn create-builder
  "Create a JVM builder instance."
  []
  (->JvmBuilder))
