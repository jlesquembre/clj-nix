(ns cljnix.core
  (:require
    [clojure.string :as string]
    [clojure.tools.deps.alpha.util.maven :as mvn]
    [clojure.tools.cli.api :as tools]
    [clojure.tools.deps.alpha :as deps]
    [clojure.tools.gitlibs.config :as gitlibs-config]
    [clojure.data.json :as json]
    [babashka.fs :as fs]
    [cljnix.utils :refer [throw+] :as utils]
    [cljnix.nix :refer [nix-hash]]
    [clojure.tools.deps.alpha.util.dir :as tools-deps.dir]
    [clojure.tools.deps.alpha.util.io :refer [printerrln]]))


(defn- mvn?
  [[_ {:keys [mvn/version]}]]
  (boolean version))

(defn- git?
  [[_ {:keys [git/url]}]]
  (boolean url))

(defn- artifact->pom
  [path]
  (str (first (fs/glob (fs/parent path) "*.pom"))))


(defn maven-deps
  [basis]
  (into []
        (comp
          (filter mvn?)
          (map (fn [[lib {:keys [mvn/version paths]}]]
                 (when-not (= 1 (count paths))
                   (throw+ "Maven deps can have only 1 path" {:lib lib :paths paths}))
                 (let [local-path (first paths)]
                   (assoc (utils/mvn-repo-info local-path {:exact-version version})
                          :lib lib
                          :version version
                          :local-path local-path))))
          ; Add POM
          (mapcat (juxt identity
                        (fn [{:keys [local-path version]}]
                          (let [pom-path (artifact->pom local-path)]
                            (assoc (utils/mvn-repo-info pom-path {:exact-version version})
                                   :version version
                                   :local-path pom-path)))))
          ; Add parent POM
          (mapcat (juxt identity
                        (fn [{:keys [local-path]}]
                          (when-let [parent-pom-path (utils/get-parent local-path)]
                            (assoc (utils/mvn-repo-info parent-pom-path {})
                                   :local-path parent-pom-path)))))
          (remove nil?)
          (distinct)
          (map #(assoc % :hash (nix-hash (:local-path %)))))
        (:libs basis)))


(defn git-deps
  [basis]
  (into []
        (comp
          (filter git?)
          (map (fn [[lib {:keys [git/sha git/url deps/root]}]]
                 {:lib lib
                  :rev sha
                  :url url
                  :git-dir (utils/git-dir url)
                  :hash (nix-hash root)
                  :local-path root})))
        (:libs basis)))


(def mvn-cache-subdir "mvn")
(def git-cache-subdir "git")

(defn- copy-if-needed
  "Copy if dest doesn't exist"
  [src dest]
  (let [dest (cond-> dest (fs/directory? dest) (fs/path (fs/file-name src)))]
    (when-not (fs/exists? (cond-> dest))
      (fs/copy src dest))))


(defn make-maven-cache!
  [deps cache-path]
  (doseq [{:keys [mvn-path local-path snapshot]} deps
          :let [new-path (fs/path cache-path mvn-cache-subdir mvn-path)]
          :when (not (fs/exists? new-path))]
     (fs/create-dirs (fs/parent new-path))
     (fs/copy local-path new-path)
     (copy-if-needed
       (fs/path (fs/parent local-path) "_remote.repositories")
       (fs/parent new-path))

     (when snapshot
       (copy-if-needed
         (fs/path (fs/parent local-path) snapshot)
         (fs/parent new-path)))
     #_(doseq [path (fs/glob (fs/parent local-path) "maven-metadata*.xml")]
          (copy-if-needed path (fs/parent new-path)))))


(defn make-git-cache!
  [deps cache-path]
  (doseq [{:keys [lib rev git-dir local-path]} deps
          :let [new-path (fs/path cache-path git-cache-subdir "libs" (str lib) rev)
                config (fs/path cache-path git-cache-subdir "_repos" git-dir "config")]
          :when (not (fs/exists? new-path))]
     (fs/create-dirs (fs/parent new-path))
     (fs/copy-tree local-path new-path)

     (fs/create-dirs (fs/parent config))
     (fs/create-file config)))


(defn make-cache!
  "Creates a cache in cache-dir (usually cache-dir is a dir in /tmp) for a
   given deps.edn file.
   First populates the cache with the data from mvn-deps and git-deps (if provided)
   Afterwards calls clojure.tools.cli.api/prep to make sure that all
   dependecies are installed"
  [{:keys [mvn-deps git-deps cache-dir deps-path]}]
  (when mvn-deps
    (make-maven-cache! mvn-deps cache-dir))
  (when git-deps
    (make-git-cache! git-deps cache-dir))

  (with-redefs [mvn/cached-local-repo (delay (str (fs/path cache-dir mvn-cache-subdir)))
                gitlibs-config/CONFIG (delay #:gitlibs{:dir (str (fs/path cache-dir git-cache-subdir))
                                                       :command "git"
                                                       :debug false
                                                       :terminal false})]
    (tools/prep {:user nil :project deps-path})))


(defn missing-mvn-deps
  [deps cache-dir]
  (let [cache-dir (fs/path cache-dir mvn-cache-subdir)
        deps-set (into #{} (mapcat (fn [{:keys [snapshot mvn-path]}]
                                     (if-not snapshot
                                       [mvn-path]
                                       [mvn-path (str (fs/path (fs/parent mvn-path) snapshot))])))
                           deps)]
    (into []
          (comp
            (filter fs/regular-file?)
            (filter (comp #{"pom" "jar"} fs/extension))
            (map (partial fs/relativize cache-dir))
            (map str)
            (remove deps-set)
            (map (fn [mvn-path]
                   (let [full-path (fs/path cache-dir mvn-path)]
                     (assoc (utils/mvn-repo-info full-path {:cache-dir cache-dir})
                            :hash (nix-hash full-path))))))

          (file-seq (fs/file cache-dir)))))

(defn- git-dep-id
  [git-dep]
  (string/join "/" ((juxt :lib :rev) git-dep)))


(defn- git-deps-seq
  [cache-dir]
  (if (not (fs/exists? (fs/path cache-dir "libs")))
    []
    (let [repo-level 3]
      (into []
            (comp
              (filter #(= repo-level (:level %)))
              (map :dir)
              (map (fn [local-path]
                     (let [url (utils/git-remote-url local-path)]
                       {:url url
                        :git-dir (utils/git-dir url)
                        :local-path (str local-path)
                        :rev (fs/file-name local-path)
                        :lib (->> (fs/parent local-path)
                                  (fs/components)
                                  (take-last 2)
                                  (map str)
                                  (apply symbol))}))))

            (tree-seq
              (fn [{:keys [_ level]}] (not= repo-level level))
              (fn [{:keys [dir level]}] (map #(hash-map :dir % :level (inc level))
                                             (fs/list-dir dir)))
              {:dir (fs/path cache-dir "libs")
               :level 0})))))

(defn missing-git-deps
  [deps cache-dir]
  (let [cache-dir (fs/path cache-dir git-cache-subdir)
        deps-set (into #{} (map git-dep-id) deps)]
    (into []
          (comp
            (remove
              #(deps-set (git-dep-id %)))
            (map #(assoc % :hash (nix-hash (:local-path %)))))
          (git-deps-seq cache-dir))))


(defn get-deps!
  "Given a deps.edn file, a cache dir, and optionally, some aliases, return a
   list of git and maven dependecies.
   Cache dir is populated with .m2 and .gitlibs deps."
  ([deps-path cache-dir]
   (get-deps! deps-path cache-dir nil))
  ([deps-path cache-dir deps-alias]

   (if deps-alias
     (printerrln "Processing" deps-path "with alias" deps-alias)
     (printerrln "Processing" deps-path "without aliases"))

   (tools-deps.dir/with-dir (fs/file (fs/parent deps-path))
     (let [options (cond-> {:user nil :project deps-path}
                     deps-alias (assoc :aliases [deps-alias]))]
       ; Make sure evething is in the cache
       (tools/prep options)

       (let [basis (deps/create-basis options)
             mvn-deps (maven-deps basis)
             git-deps (git-deps basis)
             _ (make-cache! {:mvn-deps mvn-deps
                             :git-deps git-deps
                             :cache-dir cache-dir
                             :deps-path deps-path})]

         {:mvn mvn-deps
          :git git-deps})))))

(defn- aliases-combinations
  [[deps-file aliases]]
  (let [deps-file (str deps-file)]
    (into [[deps-file nil]]
          (map #(vector deps-file %))
          aliases)))

(defn- map-comparator
  [a b]
  (let [m {:mvn-path 1
           :snapshot 2
           :mvn-repo 3

           :lib 10
           :url 11
           :rev 12
           :git-dir 13

           :hash 20}]
    (compare (get m a 1000)
             (get m b 1000))))


(defn lock-file
  [project-dir]
  (fs/with-temp-dir [cache-dir {:prefix "clj-cache"}]
    (transduce
      (comp
        (filter #(= "deps.edn" (fs/file-name %)))
        (map (juxt identity #(-> % deps/slurp-deps :aliases keys)))
        (mapcat aliases-combinations)
        (map (fn [[deps-path aliases]] (get-deps! deps-path cache-dir aliases))))
      (completing
        (fn [acc {:keys [mvn git]}]
          (-> acc
            (update :mvn into mvn)
            (update :git into git)))
        (fn [{:keys [mvn git]}]
          (sorted-map :mvn-deps (->> (concat mvn (missing-mvn-deps mvn cache-dir))
                                     (distinct)
                                     (sort-by :mvn-path)
                                     (mapv #(into (sorted-map-by map-comparator)
                                                  (select-keys % [:mvn-repo :mvn-path :hash :snapshot]))))
                      :git-deps (->> (concat git (missing-git-deps git cache-dir))
                                     (distinct)
                                     (map #(update % :lib str))
                                     (sort-by :lib)
                                     (mapv #(into (sorted-map-by map-comparator)
                                                  (select-keys % [:lib :rev :url :git-dir :hash])))))))

      {:mvn [] :git []}
      (file-seq (fs/file project-dir)))))

(defn -main
  [& args]
  (println (json/write-str (lock-file ".")
                           :escape-slash false
                           :escape-unicode false
                           :escape-js-separators false))
  (shutdown-agents))

(comment

  (lock-file "/home/jlle/projects/clojure-lsp")

  (let [deps-path (fs/expand-home "~/projects/clojure-lsp/cli/deps.edn")]
    (tools-deps.dir/with-dir (fs/file (fs/parent deps-path))
      (maven-deps
        (deps/create-basis {:user nil
                            :project (str deps-path)})))))
