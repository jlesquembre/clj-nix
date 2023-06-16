(ns cljnix.core
  (:require
    [clojure.string :as string]
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.edn :as edn]
    [clojure.pprint :as pp]
    [clojure.tools.deps.util.maven :as mvn]
    [clojure.tools.deps.cli.api :as tools]
    [clojure.tools.deps :as deps]
    [clojure.tools.gitlibs.config :as gitlibs-config]
    [clojure.data.json :as json]
    [babashka.fs :as fs]
    [cljnix.utils :refer [throw+] :as utils]
    [cljnix.nix :refer [nix-hash]]
    [cljnix.build :as build]
    [cljnix.check :as check]
    [clojure.tools.deps.util.dir :as tools-deps.dir]
    [clojure.tools.deps.util.io :refer [printerrln]]))


(def LOCK-VERSION 3)

(def add-to-nix-store?
  (and
    (fs/exists? "/nix/store")
    (System/getenv "CLJNIX_ADD_NIX_STORE")))

(defn- mvn?
  [[_ {:keys [mvn/version]}]]
  (boolean version))

(defn- git?
  [[_ {:keys [git/url]}]]
  (boolean url))

(defn- artifact->pom
  [path]
  (str (->> (fs/glob (fs/parent path) "*.pom")
            (sort (fn [x y]
                    (cond
                      (string/includes? (fs/file-name x) "SNAPSHOT") -1
                      (string/includes? (fs/file-name y) "SNAPSHOT") 1
                      :else (compare (fs/file-name x) (fs/file-name y)))))
            first)))

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
          (map (fn [[lib {:keys [git/sha git/url deps/root git/tag]}]]
                 {:lib lib
                  :rev sha
                  :url url
                  :tag tag
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
          :let [new-path (fs/path cache-path git-cache-subdir "libs" (str lib) rev)]
          :when (not (fs/exists? new-path))]
     (fs/create-dirs (fs/parent new-path))
     (fs/copy-tree local-path new-path)

     (let [system-git-repo (fs/path (:gitlibs/dir @gitlibs-config/CONFIG) "_repos" git-dir)
           temp-git-repo (fs/path cache-path git-cache-subdir "_repos" git-dir)]
       (when (and
               (fs/exists? system-git-repo)
               (not (fs/exists? temp-git-repo)))
         (fs/create-dirs (fs/parent temp-git-repo))
         (fs/copy-tree system-git-repo temp-git-repo)))))


(defn make-cache!
  "Creates a cache in cache-dir (usually cache-dir is a dir in /tmp) for a
   given deps.edn file.
   First populates the cache with the data from mvn-deps and git-deps (if provided)
   Afterwards calls clojure.tools.cli.api/prep to make sure that all
   dependecies are installed"
  [{:keys [mvn-deps git-deps cache-dir prep-options]}]
  (when mvn-deps
    (make-maven-cache! mvn-deps cache-dir))
  (when git-deps
    (make-git-cache! git-deps cache-dir))

  (with-redefs [mvn/cached-local-repo (delay (str (fs/path cache-dir mvn-cache-subdir)))
                gitlibs-config/CONFIG (delay #:gitlibs{:dir (str (fs/path cache-dir git-cache-subdir))
                                                       :command "git"
                                                       :debug false
                                                       :terminal false})]
    (tools/prep prep-options)))


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
            (map (partial fs/relativize cache-dir))
            (map str)
            (remove deps-set)
            (map (fn [mvn-path]
                   (let [full-path (fs/path cache-dir mvn-path)]
                     (assoc (utils/mvn-repo-info full-path {:cache-dir cache-dir})
                            :hash (nix-hash full-path))))))

          (fs/glob cache-dir "**.{pom,jar}"))))

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
       ; Make sure evething is in the real system cache
       (tools/prep options)

       (let [basis (deps/create-basis options)
             mvn-deps (maven-deps basis)
             git-deps (git-deps basis)
             _ (make-cache! {:mvn-deps mvn-deps
                             :git-deps git-deps
                             :cache-dir cache-dir
                             :prep-options options})]

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
           :tag 13
           :git-dir 14

           :hash 20

           :lock-version 100
           :clojure-version 101
           :git-deps 102
           :mvn-deps 103}]
    (compare (get m a 1000)
             (get m b 1000))))

(defn- same-git-dep?
  [a b]
  (let [id (juxt :lib :url :rev)]
    (= (id a) (id b))))

(defn- lein-project-profiles
  []
  (->> (:out (sh/sh "lein" "show-profiles"))
       (string/split-lines)
       (remove #{"leiningen/default"
                 "leiningen/test"
                 "uberjar"
                 "update"
                 "offline"
                 "debug"})
       (string/join ",")))

(defn- download-lein-deps
  [cache-dir]
  (let [lein-home (str (fs/path cache-dir "lein"))]
    (fs/create-dir lein-home)
    (spit (str (fs/path lein-home "profiles.clj"))
          {:user {:local-repo (str (fs/path cache-dir mvn-cache-subdir))}})
    (let [profiles (lein-project-profiles)]
      (if (empty? profiles)
        (sh/sh "lein" "deps" :env {"LEIN_HOME" lein-home})
        (sh/sh "lein" "with-profiles" profiles "deps" :env {"LEIN_HOME" lein-home})))))

(defn- add-to-nix-store!
  [{:keys [local-path lib rev] :as dep}]
  (when (and add-to-nix-store? (seq local-path))
    (try
      (if (fs/regular-file? local-path)
        (sh/sh "nix" "store" "add-file" (str local-path))
        (fs/with-temp-dir [tmp-dir {}]
          (let [dir-name (str (fs/path
                                tmp-dir
                                (format "%s-%s" (fs/file-name lib) (subs rev 0 7))))]
            (fs/copy-tree local-path dir-name)
            (fs/delete (fs/path dir-name ".git"))
            (sh/sh "nix" "store" "add-path" dir-name))))
      (catch Exception _)))
  dep)


(defn lock-file
  ([project-dir]
   (lock-file project-dir {}))
  ([project-dir {:keys [extra-mvn extra-git deps-ignore aliases-ignore lein?]
                 :or {extra-mvn []
                      extra-git []
                      deps-ignore []
                      aliases-ignore []}
                 :as opts}]
   (fs/with-temp-dir [cache-dir {:prefix "clj-cache"}]
     (transduce
      (comp
       ;; NOTE: the globbing below return $PREFIXdeps.edn paths, we need to filter still
       (filter #(= "deps.edn" (fs/file-name %)))
       (remove #(some (partial fs/ends-with? %) deps-ignore))
       (map fs/file)
       (map (juxt identity #(-> % deps/slurp-deps :aliases keys
                                (->> (remove (set aliases-ignore))))))
       (mapcat aliases-combinations)
       (map (fn [[deps-path aliases]] (get-deps! deps-path cache-dir aliases))))
      (completing
       (fn [acc {:keys [mvn git]}]
         (-> acc
             (update :mvn into mvn)
             (update :git into git)))
       (fn [{:keys [mvn git]}]
         (when lein?
           (download-lein-deps cache-dir))
         (sorted-map-by
          map-comparator
          :lock-version LOCK-VERSION
          :mvn-deps (->> (concat mvn (missing-mvn-deps mvn cache-dir))
                         (sort-by :mvn-path)
                         (map add-to-nix-store!)
                         (map #(into (sorted-map-by map-comparator)
                                     (select-keys % [:mvn-repo :mvn-path :hash :snapshot])))
                         (distinct)
                         (into []))
          :git-deps (->> (concat git (missing-git-deps git cache-dir))
                         (map #(update % :lib str))
                         (sort-by :lib)
                         (map #(cond-> %
                                 (nil? (:tag %))
                                 (dissoc :tag)))
                         (map add-to-nix-store!)
                         (map #(into (sorted-map-by map-comparator)
                                     (select-keys % [:tag :lib :rev :url :git-dir :hash])))
                         (distinct)
                         (reduce (fn [acc v]
                                   (if (same-git-dep? (peek acc) v)
                                     (conj (pop acc) (merge v (peek acc)))
                                     (conj acc v)))
                                 [])))))

      {:mvn extra-mvn
       :git extra-git}
      (fs/glob project-dir "**deps.edn")))))

(defn- check-main-class
  [& [_ value & more :as args]]
  (or
   (check/main-gen-class
    (interleave
     [:lib-name :version :main-ns]
     (apply vector value more)))
   (throw (ex-info "main-ns class does not specify :gen-class" {:args args}))))

(defn parse-options [args]
  (loop [[arg & next-args] args
         options {:lein? false
                  :aliases-ignore []
                  :deps-ignore []}]
    (case arg
      nil              options
      "--lein"         (recur next-args
                              (assoc options :lein? true))
      "--ignore-alias" (recur (next next-args)
                              (update options :aliases-ignore conj
                                      (keyword (first next-args))))
      (recur next-args
             (update options :deps-ignore conj arg)))))

(defn -main
  [& [flag value & more :as args]]
  (cond
    (= flag "--patch-git-sha")
    (utils/expand-shas! value)

    (= flag "--jar")
    (build/jar
      (interleave
        [:lib-name :version]
        (apply vector value more)))

    (= flag "--uber")
    (do
      (apply check-main-class args)
      (build/uber
       (interleave
        [:lib-name :version :main-ns :java-opts]
        (apply vector value more))))

    (= flag "--check-main")
    (apply check-main-class args)

    :else
    (println (json/write-str (lock-file
                              (str (fs/canonicalize "."))
                              (merge
                               {:extra-mvn (-> (io/resource "clojure-deps.edn")
                                               slurp
                                               edn/read-string)}
                               (parse-options args)))
                             :escape-slash false
                             :escape-unicode false
                             :escape-js-separators false)))
  (shutdown-agents))

; We need all clojure versions in nixpkgs, in case the flake consumer wants to
; use a different nixpkgs version
; Minimum supported version is 1.10.3
(def clojure-versions ["1.10.3" "1.11.0" "1.11.1"])

(defn clojure-deps
  []
  (fs/with-temp-dir [tmp-project {:prefix "clojure_deps"}]
    (spit
      (str (fs/path tmp-project "deps.edn"))
      {:aliases
       (into {}
             (map (juxt #(str "clojure-" (string/replace % "." "_"))
                        (fn [v] {:override-deps
                                 {'org.clojure/clojure {:mvn/version v}}})))
             clojure-versions)})
    (:mvn-deps (lock-file (str tmp-project)))))

(defn clojure-deps-str
  [_]
  (pp/pprint (clojure-deps)))


(comment

  (clojure-deps)
  (lock-file "/home/jlle/projects/clojure-lsp")
  (lock-file "/home/jlle/projects/clj-demo-project"
             {:extra-mvn (-> (io/resource "clojure-deps.edn")
                             slurp
                             edn/read-string)})

  (let [deps-path (fs/expand-home "~/projects/clojure-lsp/cli/deps.edn")]
    (tools-deps.dir/with-dir (fs/file (fs/parent deps-path))
      (maven-deps
        (deps/create-basis {:user nil
                            :project (str deps-path)}))))

  (add-to-nix-store!
    {:local-path
     (fs/expand-home "~/.m2/repository/org/clojure/clojure/1.11.1/clojure-1.11.1.jar")})
  (add-to-nix-store!
    {:local-path
     (fs/expand-home "~/.gitlibs/libs/io.github.clojure/tools.build/7d40500863818c6f9a6e077b18db305d02149384/")
     :hash "sha256-nuPBuNQ4su6IAh7rB9kX/Iwv5LsV+FOl/uHro6VcL7c="
     :url "https://github.com/clojure/tools.build"
     :rev "7d40500863818c6f9a6e077b18db305d02149384"
     :lib "io.github.clojure/tools.build"}))
