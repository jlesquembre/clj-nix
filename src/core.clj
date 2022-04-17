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

             result (.resolveArtifact system session request)
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


;; TODO implement in pure clojure
(defn- clj-git-dep->nix-dep
  [[lib-name {:keys [git/sha git/url deps/root paths]}]]
  (-> (sh/sh "nix-prefetch-git" url sha)
     :out
     (json/read-str :key-fn keyword)
     (select-keys [:url :rev :sha256 :path])
     (rename-keys {:path :nix-path})
     (assoc :type :git
            :lib-name lib-name
            :paths (let [i (-> root count inc)]
                     (mapv #(subs % i) paths)))))

(defn- clj-mvn-deps->nix-deps
  [libs repos]
  (let [tmp-dir (fs/create-temp-dir {:prefix "mvn-deps"})
        maven-resolver (maven-connector (str tmp-dir) repos)
        deps (mapv #(apply maven-resolver %) libs)]
   (fs/delete-tree tmp-dir)
   deps))


(defn split-deps
  [libs]
  {:mvn-deps (into {} (filter (comp :mvn/version val) libs))
   :git-deps (into {} (filter (comp :git/url val) libs))})

(defn- runtime-basis
  [deps-map]
  (let [{:keys [root-edn]} (deps/find-edn-maps)]
    (deps/calc-basis
      (deps/merge-edns [root-edn deps-map]))))


(defn- canonical-path
  [deps-path path]
  (.getCanonicalPath
    (io/file
     (.getParent (io/file deps-path))
     path)))

(defn to-absolute-paths
  [deps-path]
  (s/transform
    [(map-key-walker :local/root) #(string/starts-with? % ".")]
    (partial canonical-path deps-path)
    (deps/slurp-deps (io/file deps-path))))


(defn clj-deps->nix-deps
  [deps]
  (let [{:keys [libs mvn/repos classpath classpath-roots]} (runtime-basis deps)
        {:keys [mvn-deps git-deps]} (split-deps libs)]
    {:nix-info (cond-> []
                       (seq mvn-deps) (into (clj-mvn-deps->nix-deps mvn-deps repos))
                       (seq git-deps) (into (map clj-git-dep->nix-dep git-deps)))
     :clj-runtime {:libs libs
                   :mvn/repos repos
                   :classpath classpath
                   :classpath-roots classpath-roots}}))


(defn dep-paths
  "Returns the paths for a clj dependency"
  [{:keys [nix-path paths] :as dep}]
  (case (:type dep)
    :mvn [nix-path]
    :git (mapv #(str nix-path "/" %) paths)
    (throw (ex-info "Only maven and git deps are supported"
                    {:dep dep}))))


(defn- local-dep
  [[_ {:keys [local/root] :or {root "/"}}]]
  (when-not (string/starts-with? root "/")
    root))

(defn expand-deps
  "In case we have dependecies as git submodules (:local/root)"
  [deps-file]
  (let [deps (-> deps-file io/file deps/slurp-deps)
        local-deps (->> (:deps deps)
                        (map local-dep)
                        (remove nil?))]

    (reduce
      (fn [deps local-dep]
        (let [child-deps
              (deps/slurp-deps (io/file (.getParent (io/file deps-file))
                                       local-dep
                                       "deps.edn"))]
          (-> deps
            (update :paths into (map
                                  #(str (io/file local-dep %))
                                  (get child-deps :paths ["src"])))
            (update :deps merge (:deps child-deps)))))
      deps
      local-deps)))


(defn nix-deps
  [{:keys [in]}]
  (let [lock (-> (or in "deps.edn")
                 io/file
                 deps/slurp-deps
                 clj-deps->nix-deps)
        dep->paths (into {} (map (juxt :lib-name dep-paths)
                                 (:nix-info lock)))
        dep->path-root (into {} (map (juxt :lib-name :nix-path)
                                     (:nix-info lock)))]
    (-> lock
      (assoc-in [:clj-runtime :classpath-roots]
                (into [] (mapcat val dep->paths)))

      (assoc-in [:clj-runtime :classpath]
                (apply hash-map
                   (mapcat (fn [[k v]] (interleave v (repeat {:lib-name k})))
                           dep->paths)))
      (update-in [:clj-runtime :libs]
                 #(reduce-kv
                    (fn [m k v]
                      (cond-> (assoc-in m [k :paths] (dep->paths k))
                        (:deps/root v) (assoc-in [k :deps/root] (dep->path-root k))))
                    %
                    %))
      (update :nix-info #(mapv
                           (fn [m] (update m :lib-name str))
                           %)))))

(defn- get-paths
  "Get paths from deps.edn file"
  [deps]
  (-> deps
      expand-deps
      :paths
      (or ["src"])))
;;;;
;;;;
;; External API, used by nix
;;;;
;;;;

(defn deps-lock
  "Generate and print lock file for a given edn file"
  [{:keys [in out]}]
  (println (json/write-str (-> {:in in}
                               nix-deps
                               (update :clj-runtime prn-str))
                          :escape-slash false
                          :escape-unicode false
                          :escape-js-separators false)))
(defn -main
  [& args]
  (let [[in out] args]
    (deps-lock {:in in})))

(defn paths
  "Extract paths from deps.edn file
   Optionally format it as :json"
  [{:keys [deps fmt]}]
  (let [paths (get-paths deps)]
    (println
      (case fmt
        :json (json/write-str paths
                              :escape-slash false
                              :escape-unicode false
                              :escape-js-separators false)
        (string/join " " paths)))))

(defn remove-timestamp!
  [root-dir lib-name]
  (let [f (io/file root-dir "META-INF/maven" (str lib-name) "pom.properties")]
    (->> (slurp f)
        string/split-lines
        (remove #(string/starts-with? % "#"))
        (string/join "\n")
        (spit f))))


(defn jar [{:keys [project-dir lib-name version main-ns java-opts]}]
  (let [src-dirs (mapv (partial str project-dir "/")
                       (get-paths (str project-dir "/deps.edn")))
        class-dir (str project-dir "/target/classes")
        basis (-> (str project-dir "/deps-lock.json")
                  slurp
                  (json/read-str :key-fn keyword)
                  :clj-runtime
                  edn/read-string)
        basis-extra (-> basis
                        (update :classpath-roots #(into src-dirs %))
                        (update :classpath
                                #(into % (for [d src-dirs] [d {:path-key :paths}]))))

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
      (b/compile-clj (cond-> {:basis basis-extra
                              :src-dirs src-dirs
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


(comment
  (sri-hash "/home/jlle/.m2/repository/org/clojure/clojure/1.10.3/clojure-1.10.3.jar")

  (runtime-basis '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}}})
                          ; io.github.clojure/tools.build {:git/tag "v0.7.7" :git/sha "1474ad6"}}})


  (clj-deps->nix-deps '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}}})
                               ; io.github.clojure/tools.build {:git/tag "v0.7.7" :git/sha "1474ad6"}}})

  (json/read-str
    (with-out-str
      (deps-lock {:in "/home/jlle/projects/clj-demo-project/deps.edn"})))

  (nix-deps {:in "/home/jlle/projects/clj-demo-project/deps.edn"})

  (:libs (runtime-basis "/home/jlle/projects/clj-demo-project/deps.edn"))
  (runtime-basis "deps.edn")



  (paths {:deps "/home/jlle/projects/clj-demo-project/deps.edn"})

  (with-out-str
    (paths {:deps "/home/jlle/projects/clj-demo-project/deps.edn"}))

  (expand-deps "/home/jlle/projects/babashka/deps2.edn"))



;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; NEW
;;;; NEW
;;;; NEW
;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;

(def dep-keys #{:deps :replace-deps :extra-deps :override-deps :default-deps})

(def ^:dynamic *project-root* nil)

(defn find-git-root
  [path]
  (let [f (io/file path)]
    (cond
      (nil? f) nil
      (not (.exists f)) nil
      (.isFile f) (find-git-root (.getParent f))
      (.isDirectory f) (if (.exists (io/file f ".git"))
                         (str f)
                         (find-git-root (.getParent f))))))

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
    #_[(map-key-walker :local/root) (complement #(string/starts-with? % "/"))]
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
  (ext/canonicalize lib coord {:mvn/repos mvn/standard-repos}))

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
  [[dep {:keys [nix manifest-type] :as data}]]
  (let [{:deps/keys [root]} manifest-type
        {:keys [nix-path]} nix]
    (vector dep
            (-> data
              (update :coord-paths (partial mapv #(string/replace-first % root nix-path)))
              (assoc-in [:manifest-type :deps/root] nix-path)))))

(defmethod nixify-dep :local
  [[dep data]]
  (when-not (fs/starts-with? (get-in dep [1 :local/root]) *project-root*)
    (throw (ex-info "Local deps must be inside the project"
                    {:project-root *project-root*
                     :dep dep})))
  (let [update-path (fn [p] (string/replace-first p *project-root* "@projectRoot@"))]
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
  [deps-path]
  (let [deps-data (binding [*project-root* (find-git-root deps-path)]
                    (-> deps-path
                       expand-deps-recursively
                       add-static-attrs))]
    {:nix-info (into []
                     (comp
                       (map val)
                       (map :nix)
                       (remove nil?)
                       (distinct))
                     deps-data)
     :clj-runtime (prn-str deps-data)}))


(defn deps-lock2
  "Prints lock file for a given edn file"
  [{:keys [deps-path]}]
  (-> deps-path
      deps-lock-data
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
                          (let [info {:lib lib
                                      :coord coord
                                      :method k}]
                            (prn info)
                            (throw
                              (ex-info "Dependecy data not found!" info))))))]
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
      #_(get-in-lock lib coord :coord-paths)
      (get-in-lock lib (dissoc coord :deps/manifest :deps/root :parents) :coord-paths))))

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

(defn classpath
  [{:keys [lock-path deps-path aliases] :or {aliases []}}]
  (let [lock (-> lock-path
                slurp
                (json/read-str :key-fn keyword)
                :clj-runtime
                edn/read-string)]
    (override-multi! lock)
    (-> deps-path
      to-absolute-paths
      (create-basis' aliases)
      :classpath-roots)))

(comment
  (deps-lock-data "/home/jlle/projects/clojure-lsp/cli/deps.edn")

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

