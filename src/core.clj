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
    [babashka.fs :as fs])
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
