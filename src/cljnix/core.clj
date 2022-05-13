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
    [cljnix.nix :refer [nix-hash]]))



(comment
  ; (load-cache
  ;   "/home/jlle/projects/clojure-lsp/cli/deps.edn")

  (tools/list {:format :edn
               :user nil
               :project "/home/jlle/projects/clojure-lsp/cli/deps.edn"})


  (:classpath-roots (deps/create-basis {:log :debug
                                        :user nil
                                        :project "/home/jlle/projects/clojure-lsp/cli/deps.edn"}))

  (:libs (deps/create-basis {:log :debug
                             :user nil
                             :project "/home/jlle/projects/clojure-lsp/cli/deps.edn"})))

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


;; TODO Expand parent POMs
;; use libs and not basis???
(defn maven-deps
  [basis]
  (into []
        (comp
          (filter mvn?)
          (map (fn [[lib {:keys [mvn/version paths]}]]
                 (when-not (= 1 (count paths))
                   (throw+ "Maven deps can have only 1 path" {:lib lib :paths paths}))
                 (let [local-path (first paths)
                       {:keys [mvn-path mvn-repo]} (utils/mvn-repo-info local-path @mvn/cached-local-repo)]
                   {:lib lib
                    :version version
                    :mvn-path mvn-path
                    :mvn-repo mvn-repo
                    :hash (nix-hash local-path)
                    :local-path local-path})))
          (mapcat (fn [{:keys [local-path mvn-path mvn-repo] :as m}]
                    (let [local-path (jar->pom local-path)]
                      [m {:local-path local-path
                          :mvn-repo mvn-repo
                          :mvn-path (jar->pom mvn-path)
                          :hash (nix-hash local-path)}]))))
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
          :let [new-path (fs/path cache-path mvn-path)]]
     (fs/create-dirs (fs/parent new-path))
     (fs/copy local-path new-path)))

(defn make-git-cache!
  [deps cache-path]
  (doseq [{:keys [lib rev git-dir local-path]} deps
          :let [new-path (fs/path cache-path "libs" (str lib) rev)
                config (fs/path cache-path git-dir "config")]]
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


(comment
  (def data (main "/home/jlle/projects/clojure-lsp/cli/deps.edn"))
  (missing-git-deps (:git-deps data) (:git-cache data)))

(defn main
  [deps-path]

  ; Make sure evething is in the cache
  (tools/prep {:user nil :project deps-path})

  (let [basis (deps/create-basis {:user nil :project deps-path})
        mvn-deps (maven-deps basis)
        git-deps (git-deps basis)
        cache-dir (fs/create-temp-dir {:prefix "clj-cache"})
        [mvn-cache git-cache] (make-cache! :mvn-deps mvn-deps
                                           :git-deps git-deps
                                           :cache-dir cache-dir
                                           :deps-path deps-path)]

    (sorted-map :mvn (->> (concat mvn-deps (missing-mvn-deps mvn-deps mvn-cache))
                          (sort-by :mvn-path)
                          (mapv #(into (sorted-map) (select-keys % [:mvn-repo :mvn-path :hash]))))
                :git-deps git-deps
                :git-cache git-cache)))



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
                 :project "/home/jlle/projects/clojure-lsp/cli/deps.edn"})))
