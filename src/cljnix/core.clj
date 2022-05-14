(ns cljnix.core
  (:require
    [clojure.string :as string]
    [clojure.tools.deps.alpha.util.maven :as mvn]
    [clojure.tools.cli.api :as tools]
    [clojure.tools.deps.alpha :as deps]
    [clojure.tools.gitlibs.config :as gitlibs-config]
    [clojure.math.combinatorics :as combo]
    [clojure.data.json :as json]
    [babashka.fs :as fs]
    [cljnix.utils :refer [throw+] :as utils]
    [cljnix.nix :refer [nix-hash]]
    [clojure.tools.deps.alpha.util.dir :as tools-deps.dir])
  (:import
    [java.io FileReader]
    [org.apache.maven.model.io.xpp3 MavenXpp3Reader]))


(defn- mvn?
  [[_ {:keys [mvn/version]}]]
  (boolean version))

(defn- git?
  [[_ {:keys [git/url]}]]
  (boolean url))

(defn- jar->pom
  [s]
  (let [end (- (count s) 3)]
    (str (subs s 0 end)
         "pom")))

(defn- artifact->pom
  [path]
  (str (first (fs/glob (fs/parent path) "*.pom"))))

(defn get-parent
 [pom-path]
 (when (= "pom" (fs/extension pom-path)) get-parent
   (let [f (FileReader. pom-path)
         pom (.read (MavenXpp3Reader.) f)
         parent (.getParent pom)]
     (when parent
       (let [parent-path
             (fs/path
               @mvn/cached-local-repo
               (string/replace (.getGroupId parent) "." "/")
               (.getArtifactId parent)
               (.getVersion parent)
               (format "%s-%s.pom" (.getArtifactId parent) (.getVersion parent)))]
         (when (fs/exists? parent-path)
           (str parent-path)))))))


(defn maven-deps
  [basis]
  (into []
        (comp
          (filter mvn?)
          (map (fn [[lib {:keys [mvn/version paths]}]]
                 (when-not (= 1 (count paths))
                   (throw+ "Maven deps can have only 1 path" {:lib lib :paths paths}))
                 (let [local-path (first paths)
                       {:keys [mvn-path mvn-repo]} (utils/mvn-repo-info local-path)]
                   {:lib lib
                    :version version
                    :mvn-path mvn-path
                    :mvn-repo mvn-repo
                    :local-path local-path})))
          (mapcat (juxt identity
                        (fn [{:keys [local-path]}]
                          (let [pom-path (artifact->pom local-path)]
                            (assoc (utils/mvn-repo-info pom-path)
                                   :local-path pom-path)))))
          (mapcat (juxt identity
                        (fn [{:keys [local-path]}]
                          (when-let [parent-pom-path (get-parent local-path)]
                            (assoc (utils/mvn-repo-info parent-pom-path)
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



(defn make-maven-cache!
  [deps cache-path]
  (doseq [{:keys [mvn-path local-path]} deps
          :let [new-path (fs/path cache-path mvn-path)]
          :when (not (fs/exists? new-path))]
     (fs/create-dirs (fs/parent new-path))
     (fs/copy local-path new-path)))

(defn make-git-cache!
  [deps cache-path]
  (doseq [{:keys [lib rev git-dir local-path]} deps
          :let [new-path (fs/path cache-path "libs" (str lib) rev)
                config (fs/path cache-path git-dir "config")]
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
  [& {:keys [mvn-deps git-deps cache-dir deps-path]}]
  (let [[mvn-cache git-cache] (map #(fs/path cache-dir %) ["mvn" "git"])]
    (when mvn-deps
      (make-maven-cache! mvn-deps mvn-cache))
    (when git-deps
      (make-git-cache! git-deps git-cache))

    (with-redefs [mvn/cached-local-repo (delay (str mvn-cache))
                  gitlibs-config/CONFIG (delay #:gitlibs{:dir (str git-cache)
                                                         :command "git"
                                                         :debug true
                                                         :terminal false})]
      (tools/prep {:user nil :project deps-path}))


    [mvn-cache git-cache]))

(comment
  (make-cache! :deps-path "/home/jlle/projects/clojure-lsp/cli/deps.edn"
               :cache-dir "/tmp/clj-cache"))


(defn missing-mvn-deps
  [deps cache-dir]
  (let [deps-set (into #{} (map :mvn-path) deps)]
    (into []
          (comp
            (filter fs/regular-file?)
            (filter (comp #{"pom" "jar"} fs/extension))
            (map (partial fs/relativize cache-dir))
            (map str)
            (remove deps-set)
            (map (fn [mvn-path]
                   (let [full-path (fs/path cache-dir mvn-path)]
                     (assoc (utils/mvn-repo-info full-path cache-dir)
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
  (let [deps-set (into #{} (map git-dep-id) deps)]
    (into []
          (comp
            (remove
              #(deps-set (git-dep-id %)))
            (map #(assoc % :hash (nix-hash (:local-path %)))))
          (git-deps-seq cache-dir))))


; (comment
;   (def data (main "/home/jlle/projects/clojure-lsp/cli/deps.edn"))
;   (missing-git-deps (:git-deps data) (:git-cache data)))



(defn get-deps!
  "Given a deps.edn file, a cache dir, and optionally, some aliases, return a
   list of git and maven dependecies.
   Cache dir is populated with .m2 and .gitlibs deps."
  ([deps-path cache-dir]
   (get-deps! deps-path cache-dir nil))
  ([deps-path cache-dir aliases]

   (println "Processing '" deps-path "' with aliases " (string/join ", " aliases))
   (tools-deps.dir/with-dir (fs/file (fs/parent deps-path))
     (let [options {:user nil :project deps-path :aliases aliases}]
       ; Make sure evething is in the cache
       (tools/prep options)

       (let [basis (deps/create-basis options)
             mvn-deps (maven-deps basis)
             git-deps (git-deps basis)
             ; cache-dir (fs/create-temp-dir {:prefix "clj-cache"})
             [mvn-cache git-cache] (make-cache! :mvn-deps mvn-deps
                                                :git-deps git-deps
                                                :cache-dir cache-dir
                                                :deps-path deps-path)]

         #_(sorted-map :mvn (->> (concat mvn-deps (missing-mvn-deps mvn-deps mvn-cache))
                                 (distinct)
                                 (sort-by :mvn-path)
                                 (mapv #(into (sorted-map) (select-keys % [:mvn-repo :mvn-path :hash]))))
                       :git-deps (->> (concat git-deps (missing-git-deps git-deps git-cache))
                                      (distinct)
                                      (map #(update % :lib str))
                                      (sort-by :lib)
                                      (mapv #(into (sorted-map) (select-keys % [:lib :rev :url :git-dir :hash])))))
         {:mvn mvn-deps
          :git git-deps})))))

(defn- aliases-combinations
  [deps-file aliases]
  (let [deps-file (str deps-file)]
    (into [[deps-file nil]]
          (comp
            (mapcat #(combo/permuted-combinations aliases %))
            (map #(vector deps-file %)))
          (range 1 (inc (count aliases))))))


(defn main
  [project-dir]
  (fs/with-temp-dir [cache-dir {:prefix "clj-cache"}]
    #_(into []
            (comp
              (filter #(= "deps.edn" (fs/file-name %)))
              (map (juxt identity #(-> % deps/slurp-deps :aliases keys)))
              (mapcat #(apply aliases-combinations %))
              (map (fn [[deps-path aliases]] (get-deps! deps-path cache-dir aliases))))
            (file-seq (fs/file project-dir)))
    (transduce
      (comp
        (filter #(= "deps.edn" (fs/file-name %)))
        (map (juxt identity #(-> % deps/slurp-deps :aliases keys)))
        (mapcat #(apply aliases-combinations %))
        (map (fn [[deps-path aliases]] (get-deps! deps-path cache-dir aliases))))
      (completing
        (fn [acc {:keys [mvn git]}] (-> acc
                                      (update :mvn conj mvn)
                                      (update :git conj git))))
      {:mvn [] :git []}
      (file-seq (fs/file project-dir)))))
(comment
  (main "/home/jlle/projects/clojure-lsp"))


(comment

  (def data (main "/home/jlle/projects/clojure-lsp/cli/deps.edn"))
  (missing-mvn-deps (:mvn-deps data) (:mvn-cache data))

  (:mvn-deps data)
  (identity data)

  (with-redefs [mvn/cached-local-repo (delay (str (:mvn-cache data)))
                gitlibs-config/CONFIG (delay #:gitlibs{:dir (str (:git-cache data))
                                                       :command "git"
                                                       :debug false
                                                       :terminal false})]
    (deps/create-basis {:user nil
                        :project "/home/jlle/projects/clojure-lsp/cli/deps.edn"})
    (tools/prep {:log :debug
                 :user nil
                 :project "/home/jlle/projects/clojure-lsp/cli/deps.edn"})

    (maven-deps
      (deps/create-basis {:user nil
                          :project "/home/jlle/projects/clojure-lsp/cli/deps.edn"})))

  (aliases-combinations "deps.edn" nil)
  (aliases-combinations "deps.edn" [:build])
  (aliases-combinations "deps.edn" [:build :test :foo]))
