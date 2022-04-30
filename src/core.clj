(ns core
  (:require
    [clojure.edn :as edn]
    [clojure.string :as string]
    [clojure.set :refer [rename-keys]]
    [clojure.tools.deps.alpha :as deps]
    [clojure.tools.deps.alpha.util.maven :as mvn]
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.data.json :as json]
    [clojure.tools.build.api :as b]
    [babashka.fs :as fs]
    [com.rpl.specter :as s]

    [clojure.tools.deps.alpha.extensions :as ext]
    [clojure.tools.deps.alpha.util.session :as session]
    [clojure.tools.deps.alpha.util.dir :as dir])
  (:import
    [java.io File]
    [java.util Base64]
    [java.nio.file Files]
    [java.security MessageDigest]
    [org.eclipse.aether.resolution ArtifactRequest]
    [org.eclipse.aether.internal.impl Maven2RepositoryLayoutFactory]
    [org.eclipse.aether.repository RemoteRepository]))

(def VERSION 1)

(defn sri-hash
  [path]
  (let [path (.toPath (io/file path))
        data (Files/readAllBytes path)
        digester (doto (MessageDigest/getInstance "SHA-256")
                   (.update data))]
    (str
      "sha256-"
      (.encodeToString
        (Base64/getEncoder)
        (.digest digester)))))

;; TODO implement in pure clojure
(defn- file->nix-path
  [^File file]
  (->> file
       .getAbsolutePath
       (sh/sh "nix" "store" "add-file")
       :out
       string/trim))

(defn network-exception? [e]
  (let [msg (.getMessage e)]
    (boolean
     (or (re-find #"(?i)Connect.*timed out" msg)
         (re-find #"(?i)Could not resolve host" msg)))))

(defn retry-on-network-exception [f]
  (loop [sleep-ms 2000
         n 5]
    (if (zero? n)
      (f)
      (let [result (try
                     (f)
                     (catch Exception e
                       (if (network-exception? e)
                         ::retry
                         (throw e))))]
        (if (not= ::retry result)
          result
          (do
            (Thread/sleep sleep-ms)
            (recur (* 2 sleep-ms) (dec n))))))))

(defn maven-connector
  "Returns a fn to resolve a maven dep to an url"
  ([local-repo]
   (maven-connector local-repo mvn/standard-repos))
  ([local-repo remote-repos]
   (let [system (mvn/make-system)
         settings (mvn/get-settings)
         session (mvn/make-session system settings local-repo)
         repos (mvn/remote-repos remote-repos settings)]
     (fn maven-resolver [lib coord]
       (let [request (doto (ArtifactRequest.)
                       (.setArtifact (mvn/coord->artifact lib coord))
                       (.setRepositories repos))

             result (retry-on-network-exception
                     #(.resolveArtifact system session request))
             artifact (.getArtifact result)
             repo ^RemoteRepository (.getRepository result)

             path (-> (.newInstance (Maven2RepositoryLayoutFactory.) session repo)
                      (.getLocation artifact true)
                      (.toString))]
         {:url (str ^String (.getUrl repo) path)
          :path path
          :lib-name lib
          :repo (str ^String (.getUrl repo))
          :type :mvn
          :nix-path (file->nix-path (.getFile artifact))
          :hash (sri-hash (.getFile artifact))})))))


(def map-key-walker
  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (s/recursive-path [akey] p
                    (s/cond-path
                      (s/pred akey) akey
                      coll? [s/ALL p])))

(defn- canonical-path
  [deps-path path]
  (.getCanonicalPath
    (io/file
     (.getParent (io/file deps-path))
     path)))

(defn to-absolute-paths
  [deps-path]
  (s/transform
    [(map-key-walker :local/root) fs/relative?]
    (partial canonical-path deps-path)
    (deps/slurp-deps (io/file deps-path))))


(defn remove-timestamp!
  [root-dir lib-name]
  (let [f (io/file root-dir "META-INF/maven" (str lib-name) "pom.properties")]
    (->> (slurp f)
        string/split-lines
        (remove #(string/starts-with? % "#"))
        (string/join "\n")
        (spit f))))


(def dep-keys #{:deps :replace-deps :extra-deps :override-deps :default-deps})

(def ^:dynamic *project-root* nil)
(def project-root-placeholder "@projectRoot@")

(defn- throw+
  [msg data]
  (throw (ex-info (prn-str msg data) data)))

(defn find-git-root
  [path]
  (let [f (some-> path fs/canonicalize)]
    (cond
      (nil? f)
      nil

      (not (fs/exists? f))
      nil

      (fs/regular-file? f)
      (find-git-root (fs/parent f))

      (fs/directory? f)
      (if (fs/exists? (fs/path f ".git"))
        (str f)
        (find-git-root (fs/parent f))))))

(defn get-edn-map
  "Slurp a deps.edn file and merge it with root deps.edn (without aliases) file"
  [deps-path]
  (let [{:keys [root-edn]} (deps/find-edn-maps)
        deps-map (deps/slurp-deps (io/file deps-path))]
    (deps/merge-edns [(dissoc root-edn :aliases) deps-map])))

(defn- flat-deps
  [deps-map]
  (into #{}
    (mapcat
      vec
      (vals (select-keys deps-map dep-keys)))))

(defn expand-relative-local-deps
  "Canonicalize all relative paths for :local/root deps"
  [x root]
  (s/transform
    [(map-key-walker :local/root) fs/relative?]
    #(if (string? root)
       (.getCanonicalPath (io/file root %))
       (str (fs/canonicalize (fs/path root %))))
    x))

(defn get-all-deps
  "Get all dependecies (optionally including aliases) in a deps.edn file.
   Canonicalize local deps"
  ([deps-path]
   (get-all-deps deps-path false))
  ([deps-path alias?]
   (let [{:keys [aliases] :as config} (get-edn-map deps-path)
         main-deps (flat-deps config)]
     (-> main-deps
       (cond-> alias? (into (mapcat flat-deps (vals aliases))))
       (expand-relative-local-deps (fs/parent deps-path))))))

(defn canonicalize
  "Simplified version of ext/canonicalize"
  [[lib coord]]
  (retry-on-network-exception
   #(ext/canonicalize lib coord {:mvn/repos mvn/standard-repos})))

(defn coord-deps
  "Simplified version of ext/coord-deps.
   Canonicalize local deps"
  [[lib coord]]
  (let [{:deps/keys [root manifest] :as coord'} (ext/manifest-type lib coord nil)]
    (cond-> (ext/coord-deps
              lib
              (if root coord' coord)
              manifest
              {:mvn/repos mvn/standard-repos})
      root (expand-relative-local-deps root))))

(defn coord-paths
  "Simplified version of ext/coord-paths"
  [[lib coord]]
  (let [{:deps/keys [root manifest] :as coord'} (ext/manifest-type lib coord nil)]
    (ext/coord-paths
      lib
      (if root coord' coord)
      manifest
      {:mvn/repos mvn/standard-repos})))

(defn expand-deps-recursively
  "Get all deps (including aliases) for a deps.edn file and expand to get all
  the dependecies. Aliases are considered only for the top deps.edn file."
  [deps-path]
  (session/with-session
    (loop [q (into (clojure.lang.PersistentQueue/EMPTY)
                   (get-all-deps deps-path true))
           deps #{}]
      (if-let [next-dep (first q)]
        (let [deps' (conj deps next-dep)]
          (recur
            (into (pop q) (remove deps' (coord-deps next-dep)))
            deps'))
        deps))))


(defmulti get-nix-info
  "Takes a dependency and returns a map with nix related data,
   like download URL, hash, nix path, etc"
  (fn [[_ coord]] (ext/coord-type coord)))

(defmethod get-nix-info :local
  [_]
  nil)

(defmethod get-nix-info :mvn
  [[lib coord]]
  (let [tmp-dir (fs/create-temp-dir {:prefix "mvn-deps"})
        maven-resolver (maven-connector (str tmp-dir))
        dep (maven-resolver lib coord)]
   (fs/delete-tree tmp-dir)
   (select-keys dep [:url :nix-path :hash :lib-name :type])))

(defmethod get-nix-info :git
  [dep]
  (let [[lib {:git/keys [url sha]}] (canonicalize dep)]
    [url sha]
   ;; TODO implement in pure clojure
   (-> (sh/sh "nix-prefetch-git" url sha)
      :out
      (json/read-str :key-fn keyword)
      (select-keys [:url :rev :sha256 :path])
      (rename-keys {:path :nix-path})
      (assoc :type :git
             :lib-name lib))))

(defmulti nixify-dep
  "Takes a map entry generated by add-static-attrs and nixifies it.
   For example, replaces the paths with nix store paths"
  (fn [[_ {:keys [canonicalize]}]]
    (ext/coord-type (second canonicalize))))

(defmethod nixify-dep :mvn
  [[dep {:keys [nix] :as data}]]
  (vector dep
          (assoc data :coord-paths [(:nix-path nix)])))

(defmethod nixify-dep :git
  [[dep {:keys [nix manifest-type coord-paths] :as data}]]
  (let [{:deps/keys [root]} manifest-type
        {:keys [nix-path]} nix]
    (vector dep
            (-> data
              (assoc-in [:manifest-type :deps/root] nix-path)
              ; TODO Maybe this update is not needed
              (update :nix (fn [m] (assoc m
                                          :paths
                                          (mapv
                                            #(subs (string/replace-first % root "") 1)
                                            coord-paths))))
              (update :coord-paths (partial mapv #(string/replace-first % root nix-path)))))))

(defmethod nixify-dep :local
  [[dep data]]
  (when-not (fs/starts-with? (get-in dep [1 :local/root]) *project-root*)
    (throw (ex-info "Local deps must be inside the project"
                    {:project-root *project-root*
                     :dep dep})))
  (let [update-path (fn [p] (string/replace-first p *project-root* project-root-placeholder))]
    (vector (update-in dep [1 :local/root] update-path)
            (-> data
                (dissoc :nix)
                (update :coord-paths (partial mapv update-path))
                (update-in [:manifest-type :deps/root] update-path)
                (update-in [:canonicalize 1 :local/root] update-path)))))

(defn add-static-attrs
  "Given a list of dependecies, collects data generated with tools.deps to be
   used at nix build time, avoiding network requests"
  [deps]
  (let [config {:mvn/repos mvn/standard-repos}
        deps (into deps (map canonicalize deps))]
    (into {}
          (comp
            (map
              (fn [[lib coord :as dep]]
                (vector dep {:canonicalize (canonicalize dep)
                             :manifest-type (ext/manifest-type lib coord config)
                             :coord-deps (coord-deps dep)
                             :coord-paths (coord-paths dep)
                             :nix (get-nix-info dep)})))
            (map nixify-dep))
          deps)))

(defn deps-lock-data
  "Prints lock file for a given edn file"
  [{:keys [deps-path]}]
  (if-let [project-root (find-git-root deps-path)]
    (let [deps-data (binding [*project-root* project-root]
                      (-> deps-path
                         expand-deps-recursively
                         add-static-attrs))]
      {:version VERSION
       :nix-info (sort-by
                   :lib-name
                   (into []
                         (comp
                           (map val)
                           (map :nix)
                           (remove nil?)
                           (map #(update % :lib-name str))
                           (distinct))
                        deps-data))
       :clj-runtime (prn-str deps-data)})
    (throw+ "Project root not found!" {:deps-path deps-path})))


(defn deps-lock
  "Prints lock file for a given edn file"
  [args]
  (-> (deps-lock-data args)
      (json/write-str :escape-slash false
                      :escape-unicode false
                      :escape-js-separators false)
      println))


(defn- override-multi!
  [lock-map]
  (let [lock-no-exclusion (into {} (map
                                     (fn [[[lib coord] v]]
                                       [[lib (dissoc coord :exclusions)] v])
                                     lock-map))
        get-in-lock (fn [lib coord k]
                      (if-let [result (get-in lock-map [(vector lib coord) k])]
                        result
                        (if-let [result (get-in lock-no-exclusion [(vector lib coord) k])]
                          result
                          (throw+ "Dependecy data not found!"
                                  {:lib lib
                                   :coord coord
                                   :method k}))))]
    (defmethod ext/canonicalize :mvn
      [lib coord _config]
      (get-in-lock lib coord :canonicalize))
    (defmethod ext/canonicalize :git
      [lib coord _config]
      (get-in-lock lib coord :canonicalize))
    (defmethod ext/canonicalize :local
      [lib coord _config]
      (get-in-lock lib coord :canonicalize))

    (defmethod ext/manifest-type :git
      [lib coord _config]
      (get-in-lock lib coord :manifest-type))
    (defmethod ext/manifest-type :local
      [lib coord _config]
      (get-in-lock lib coord :manifest-type))

    (defmethod ext/coord-deps :mvn
      [lib coord _manifest-type _config]
      #_(get-in-lock lib coord :coord-deps)
      (get-in-lock lib (select-keys coord [:mvn/version]) :coord-deps))
    (defmethod ext/coord-deps :deps
      [lib coord _manifest-type _config]
      #_(get-in-lock lib coord :coord-deps)
      (get-in-lock lib (dissoc coord :deps/manifest :deps/root :parents) :coord-deps))

    (defmethod ext/coord-paths :mvn
      [lib coord _manifest-type _config]
      (get-in-lock lib (select-keys coord [:mvn/version]) :coord-paths))
    (defmethod ext/coord-paths :deps
      [lib coord _manifest-type _config]
      (get-in-lock lib (dissoc coord :deps/manifest :deps/root :parents :dependents) :coord-paths))))


(defn create-basis'
  "Modified version of clojure.tools.deps.alpha/create-basis.
   See https://github.com/clojure/tools.deps.alpha/blob/5314a8347388f6ee1246f827035bd888dd1972f6/src/main/clojure/clojure/tools/deps/alpha.clj#L764"
  [project-edn aliases]
  (let [{:keys [root-edn]} (deps/find-edn-maps)
        edn-maps [root-edn project-edn]
        alias-data (->> edn-maps
                     (map :aliases)
                     (remove nil?)
                     (apply merge-with merge))
        argmap-data (->> aliases
                      (remove nil?)
                      (map #(get alias-data %)))
        argmap (apply #'deps/merge-alias-maps argmap-data)
        project-tooled-edn (deps/tool project-edn argmap)
        master-edn (deps/merge-edns [root-edn project-tooled-edn])]
    (deps/calc-basis master-edn {:resolve-args argmap :classpath-args argmap})))


(defn- load-lock
  [lock-path]
  (let [lock (-> lock-path
                slurp
                (json/read-str :key-fn keyword))]
    (if (not= (:version lock) VERSION)
      (throw+ "deps-lock.json was generated with a different clj-nix version, try to generate it again"
              {:lock-version (:version lock)
               :expected-version VERSION})
      (-> lock
          :clj-runtime
          edn/read-string))))

(defn- str->kw
  [s]
  (keyword
    (string/replace s ":" "")))

(defn create-basis-nix
  [{:keys [lock-path deps-path aliases project-dir] :or {aliases []}}]
  (let [deps-path (or deps-path (str project-dir "/deps.edn"))
        lock-path (or lock-path (str project-dir "/deps-lock.json"))
        lock (s/transform
               (s/walker #(string/starts-with? % project-root-placeholder))
               #(string/replace % project-root-placeholder project-dir)
               (load-lock lock-path))]
    lock
    (override-multi! lock)
    (-> deps-path
      to-absolute-paths
      (create-basis' (mapv str->kw aliases)))))


(defn classpath
  [args]
  (:classpath-roots (create-basis-nix args)))


(defn- get-paths
  "Get paths from deps.edn file"
  [deps]
  (-> deps
      ; expand-deps
      :paths
      (or ["src"])))

(defn jar [{:keys [project-dir lib-name version main-ns java-opts ns-compile ns-compile-extra] :as args}]
  (let [src-dirs (mapv (partial str project-dir "/")
                       (get-paths (str project-dir "/deps.edn")))
        class-dir (str project-dir "/target/classes")
        basis (create-basis-nix args)
        filter-nses (apply vector
                           (if (empty? ns-compile)
                             (first (string/split main-ns #"\."))
                             ns-compile)
                           ns-compile-extra)

        lib-name (if (qualified-symbol? (symbol lib-name))
                   (symbol lib-name)
                   (symbol lib-name lib-name))
        main main-ns
        jar-file (format "%s/target/%s-%s.jar"
                         project-dir
                         (name lib-name)
                         version)]


      (b/copy-dir {:src-dirs src-dirs
                   :target-dir class-dir})
      ; TODO Add compile java step
      (b/compile-clj (cond-> {:basis basis
                              :src-dirs src-dirs
                              :filter-nses filter-nses
                              :class-dir class-dir}
                       (seq java-opts) (assoc :java-opts java-opts)))
      (b/write-pom {:class-dir class-dir
                    :lib lib-name
                    :version version
                    :basis basis})
                    ; :src-dirs src-dirs})

      (remove-timestamp! class-dir lib-name)

      (b/jar {:class-dir class-dir
              :main main
              :jar-file jar-file})
      (print jar-file)))



(defn classpath-prn
  [args]
  (let [cp (classpath args)]
    (->> cp
         (string/join ":")
         println)
    (->> cp
         (filter fs/absolute?)
         (filter #(string/starts-with? % "/nix/store"))
         (string/join ":")
         println)))


(defn -main
  [& args]
  (println "I do nothing"))


(comment
  (to-absolute-paths "/home/jlle/projects/clj-demo-project/deps.edn")

  (let [data (-> "/home/jlle/projects/clj-demo-project/deps-lock.json"
                slurp
                (json/read-str :key-fn keyword)
                :clj-runtime
                edn/read-string)]
    (s/transform
      (s/walker #(string/starts-with? % project-root-placeholder))
      #(string/replace % project-root-placeholder "XXXXX")
      data)

    (s/transform
      (s/walker #(string/starts-with? % project-root-placeholder))
      #(string/replace % project-root-placeholder "XXXXX"))))

(comment
  (deps-lock-data {:deps-path "/home/jlle/projects/clojure-lsp/cli/deps.edn"})
  (find-git-root "/home/jlle/projects/clojure-lsp/cli/deps.edn")

  (get-edn-map "/home/jlle/projects/clojure-lsp/cli/deps.edn")

  (get-all-deps "/home/jlle/projects/clojure-lsp/cli/deps.edn")

  (expand-deps-recursively "/home/jlle/projects/clojure-lsp/deps.edn")

  (get-nix-info ['org.clojure/core.specs.alpha #:mvn{:version "0.2.56"}])
  (get-nix-info ['org.clojure/core.specs.alpha #:mvn{:version "LATEST"}])
  (get-nix-info ['io.github.babashka/fs {:git/tag "v0.1.4"
                                         :git/sha "2bf527f"}])


  (nixify-dep (first {['org.clojure/core.specs.alpha #:mvn{:version "0.2.56"}]
                      {:canonicalize
                       ['org.clojure/core.specs.alpha #:mvn{:version "0.2.56"}],
                       :manifest-type #:deps{:manifest :mvn},
                       :coord-deps [],
                       :coord-paths
                       ["/home/jlle/.m2/repository/org/clojure/core.specs.alpha/0.2.56/core.specs.alpha-0.2.56.jar"],
                       :nix
                       {:url
                        "https://repo1.maven.org/maven2/org/clojure/core.specs.alpha/0.2.56/core.specs.alpha-0.2.56.jar",
                        :nix-path
                        "/nix/store/6f3fq54dhbxcv3qg76r7aqqg43b777sz-core.specs.alpha-0.2.56.jar",
                        :hash "sha256-/PRCveArBKhj8vzFjuaiowxM8Mlw99q4VjTwq3ERZrY=",
                        :lib-name 'org.clojure/core.specs.alpha}}}))


  (binding [*project-root* "/home/jlle/projects/clojure-lsp"]
    (nixify-dep (first {['clojure-lsp/lib #:local{:root "/home/jlle/projects/clojure-lsp/lib"}]
                        {:canonicalize
                         ['clojure-lsp/lib
                          #:local{:root "/home/jlle/projects/clojure-lsp/lib"}],
                         :manifest-type
                         #:deps{:manifest :deps, :root "/home/jlle/projects/clojure-lsp/lib"},
                         :coord-deps [['org.clojure/clojure #:mvn{:version "1.11.1"}]],
                         :coord-paths
                         ["/home/jlle/projects/clojure-lsp/lib/src"
                          "/home/jlle/projects/clojure-lsp/lib/resources"],
                         :nix nil},})))

  (nixify-dep (first {['io.github.babashka/fs #:git{:tag "v0.1.4", :sha "2bf527f"}]
                      {:canonicalize
                       ['io.github.babashka/fs
                        #:git{:tag "v0.1.4",
                              :sha "2bf527f797d69b3f14247940958e0d7b509f3ce2",
                              :url "https://github.com/babashka/fs.git"}],
                       :manifest-type
                       #:deps{:manifest :deps,
                              :root
                              "/home/jlle/.gitlibs/libs/io.github.babashka/fs/2bf527f797d69b3f14247940958e0d7b509f3ce2"},
                       :coord-deps [['org.clojure/clojure #:mvn{:version "1.10.3"}]],
                       :coord-paths
                       ["/home/jlle/.gitlibs/libs/io.github.babashka/fs/2bf527f797d69b3f14247940958e0d7b509f3ce2/src"],
                       :nix
                       {:url "https://github.com/babashka/fs.git",
                        :rev "2bf527f797d69b3f14247940958e0d7b509f3ce2",
                        :sha256 "0ax63cr9q08vrx3c0a66yjbia8l19cc59wzhc2d18q7v2arijnb8",
                        :nix-path "/nix/store/r2csznkiv9i3z1wrw8ckyhdydcdbdqhh-fs-2bf527f",
                        :type :git,
                        :lib-name 'io.github.babashka/fs}},}))


  (coord-deps ['io.github.babashka/fs {:git/sha "03c55063bea4df658dfa2edd3f9b4259d1c4144c"}])
  (coord-deps ['io.github.babashka/fs {:git/tag "v0.1.4"
                                       :git/sha "2bf527f"}])
  (coord-deps ['org.clojure/clojure {:mvn/version "1.10.3"}])

  (get-edn-map  "/home/jlle/projects/clojure-lsp/cli/deps.edn")

  (get-all-deps "/home/jlle/projects/clj-nix/deps.edn"))

(comment

  ;; methods with side effects
  ; ext/canonicalize   -> Can have side effects for mvn and git.
  ;                       For local deps returns the canonical path (absolute path)

  ; ext/dep-id         -> Impure, calls ext/canonicalize

  ; ext/manifest-type  -> Pure for maven. Impure for git and local  returns :root (absolute path)
  ;                       Also returns :deps/manifest, used to dispatch ext/coord-deps and ext/coord-paths

  ; ext/coord-deps     -> Impure, save these dependencies recursively
  ; ext/coord-paths    -> Impure for maven, returns path to jar
  ;                       For deps (git and local) seems pure

  ;; Maven deps
  (ext/canonicalize
    'com.rpl/specter
    {:mvn/version "1.1.4-SNAPSHOT"}
    nil)
  (ext/canonicalize
    'org.eclipse.lsp4j/org.eclipse.lsp4j
    {:mvn/version "0.12.0"  :exclusions ['org.eclipse.xtend/org.eclipse.xtend.lib
                                         'com.google.code.gson/gson]}
    nil)

  (ext/dep-id
    'com.rpl/specter
    {:mvn/version "1.1.4"}
    nil)
  (ext/dep-id
    'org.eclipse.lsp4j/org.eclipse.lsp4j
    {:mvn/version "0.12.0"  :exclusions ['org.eclipse.xtend/org.eclipse.xtend.lib
                                         'com.google.code.gson/gson]}
    nil)

  (ext/manifest-type
    'com.rpl/specter
    {:mvn/version "1.1.4"}
    nil)

  (ext/coord-deps
    'com.rpl/specter
    {:mvn/version "1.1.4"}
    :mvn
    {:mvn/repos mvn/standard-repos})

  (ext/coord-deps
    'com.rpl/specter
    {:mvn/version "LATEST"}
    :mvn
    {:mvn/repos mvn/standard-repos})

  (ext/coord-deps
    'nasus/nasus
    {:mvn/version "0.1.7"}
    :mvn
    {:mvn/repos mvn/standard-repos})
  (ext/coord-deps
    'org.clojure/clojure
    {:mvn/version "1.10.0"}
    :mvn
    {:mvn/repos mvn/standard-repos})

  (ext/coord-paths
    'com.rpl/specter
    {:mvn/version "1.1.4"}
    :mvn
    {:mvn/repos mvn/standard-repos})


  ;; Git deps
  (ext/canonicalize
    'io.github.babashka/fs,
    {:git/sha "03c55063bea4df658dfa2edd3f9b4259d1c4144c",
     :git/url "https://github.com/babashka/fs.git",}
    nil)
  (ext/dep-id
    'io.github.babashka/fs,
    {:git/sha "03c55063bea4df658dfa2edd3f9b4259d1c4144c",
     :git/url "https://github.com/babashka/fs.git",}
    nil)
  (ext/manifest-type 'org.clojure/spec.alpha
    {:git/url "https://github.com/jlesquembre/clj-nix.git" :git/sha "7fdb0b261b19ad0ae5c3ce4c911b0b433f6a88be"}
    nil)
  (ext/manifest-type 'org.clojure/spec.alpha
    {:git/url "https://github.com/clojure/spec.alpha.git" :git/sha "739c1af56dae621aedf1bb282025a0d676eff713"}
    nil)
  (ext/manifest-type
    'io.github.babashka/fs,
    {:git/sha "03c55063bea4df658dfa2edd3f9b4259d1c4144c",
     :git/url "https://github.com/babashka/fs.git",}
    nil)

  (ext/manifest-type
    'io.github.babashka/fs
    {:git/tag "v0.1.4"
     :git/sha "2bf527f"}
    nil)


  (ext/coord-deps
      'io.github.babashka/fs,
      {:deps/manifest :deps,
       :deps/root "/home/jlle/.gitlibs/libs/io.github.babashka/fs/03c55063bea4df658dfa2edd3f9b4259d1c4144c"}
      :deps,
      nil)

  (ext/coord-paths
    'io.github.babashka/fs,
    {:deps/root "/home/jlle/.gitlibs/libs/io.github.babashka/fs/03c55063bea4df658dfa2edd3f9b4259d1c4144c"}
    :deps
    nil)

  (dir/with-dir (io/file "/home/jlle/projects/clojure-lsp/cli")
    (ext/canonicalize
      'clojure-lsp/lib
      {:local/root "../lib"}
      nil))
  ;; Local deps
  (ext/canonicalize
    'clojure-lsp/lib
    {:local/root "../lib"}
    nil)
  (ext/dep-id
    'clojure-lsp/lib
    {:local/root "../lib"}
    nil)
  (ext/manifest-type
    'clojure-lsp/lib
    {:local/root "/home/jlle/projects/clojure-lsp/lib"}
    nil)

  ;; create new session to avoid caching
  (session/with-session
    (ext/coord-paths
      'clojure-lsp/cli
      {:deps/root "/home/jlle/projects/clojure-lsp/cli"}
      :deps
      nil))

  (ext/coord-type {:git/url "https://github.com/clojure/spec.alpha.git" :sha "739c1af56dae621aedf1bb282025a0d676eff713"})
  (ext/coord-type {:mvn/version "1.1.4"})
  (ext/coord-type {:mvn/version "0.12.0"  :exclusions ['org.eclipse.xtend/org.eclipse.xtend.lib]}))
