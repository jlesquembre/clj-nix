(ns cljnix.utils
  (:require
    [clojure.java.shell :as sh]
    [clojure.string :as string]
    [clojure.java.io :as io]
    [clojure.edn :as edn]
    [clojure.data.json :as json]
    [clojure.tools.deps.util.maven :as mvn]
    [clojure.tools.gitlibs.config :as gitlibs-config]
    [clojure.tools.gitlibs.impl :as gli]
    [clojure.tools.deps :as deps]
    [babashka.fs :as fs]
    [version-clj.core :as version]
    [clojure.zip :as zip]
    [borkdude.rewrite-edn :as r]
    [clojure.tools.deps.util.io :refer [printerrln]]
    [clojure.tools.deps.extensions.pom :refer [read-model-file]])
  (:import
    [org.apache.maven.model.io.xpp3 MavenXpp3Reader]
    [org.apache.maven.model Model Repository]))

(defn throw+
  [msg data]
  (throw (ex-info (prn-str msg data) data)))

(defn- snapshot?
  [path]
  (-> path
      fs/parent
      fs/file-name
      str
      (string/lower-case)
      (string/includes? "snapshot")))


; https://maven.apache.org/ref/3.6.3/maven-model/apidocs/index.html
(defn- pom
  ^Model [pom-path]
  (when (= "pom" (fs/extension pom-path))
    (let [f (io/input-stream (str pom-path))]
      (.read (MavenXpp3Reader.) f))))

;; Snapshot jar can be
;; foo-123122312.jar
;; foo-SNAPSHOT.jar
(defn- snapshot-info
  [path]
  (let [[artifact-id snapshot-version]
        (-> (str (fs/strip-ext path) ".pom")
            (pom)
            ((juxt
               (memfn ^Model getArtifactId)
               (memfn ^Model getVersion))))]
    {:artifact-id artifact-id
     :snapshot-version snapshot-version
     :version (subs
                (fs/file-name (fs/strip-ext path))
                (inc (count artifact-id)))}))
(defn- latest-snapshot
  [path]
  (->> (fs/glob (fs/parent path) "*.pom")
       (map (comp :version snapshot-info))
       (remove version/snapshot?)
       (version/version-sort)
       (last)))

(defn- resolve-snapshot
  [path exact-version]
  (if-not (snapshot? path)
    {:resolved-path (str path)}
    (let [ext (fs/extension path)
          {:keys [artifact-id version snapshot-version]} (snapshot-info path)]
      {:resolved-path
        (if-not (version/snapshot? version) ; Maybe not needed, but who knows with maven...
          path
          (str (fs/path (fs/parent path) (str artifact-id
                                              "-"
                                              (if (version/snapshot? exact-version)
                                                (latest-snapshot path)
                                                exact-version)
                                              "."
                                              ext))))
       :snapshot (str artifact-id "-" snapshot-version "." ext)})))


(defn get-parent
 [pom-path]
 (when-let [parent (some-> (pom pom-path) (.getParent))]
    (let [parent-path
          (fs/path
            @mvn/cached-local-repo
            (string/replace (.getGroupId parent) "." "/")
            (.getArtifactId parent)
            (.getVersion parent)
            (format "%s-%s.pom" (.getArtifactId parent) (.getVersion parent)))]
      (when (fs/exists? parent-path)
        (str parent-path)))))


(defn- get-mvn-repo-name
  [path mvn-repos]
  (let [info-file (fs/path (fs/parent path) "_remote.repositories")
        file-name (fs/file-name path)
        repo-name-finder (fn [s] (second (re-find
                                           (re-pattern (str file-name #">(\S+)="))
                                           s)))]
    (some #(let [repo-name (repo-name-finder %)]
             (when (contains? mvn-repos repo-name)
               repo-name))
          (fs/read-all-lines info-file))))

(defn mvn-repo-info
  "Given a path for a jar in the maven local repo, e.g:
   $REPO/babashka/fs/0.1.4/fs-0.1.4.jar
   return the maven repository url and the dependecy url"
  [path & {:keys [cache-dir exact-version mvn-repos]
           :or {cache-dir @mvn/cached-local-repo
                mvn-repos mvn/standard-repos}}]
  {:pre [(if (snapshot? path)
           (not (nil? exact-version))
           true)]}
  (let [{:keys [resolved-path snapshot]} (resolve-snapshot path exact-version)
        repo-name (get-mvn-repo-name resolved-path mvn-repos)
        repo-url (get-in mvn-repos [repo-name :url])
        repo-url (cond
                   (nil? repo-url) (throw+ "Maven repo not found"
                                           {:mvn-repos mvn-repos
                                            :repo-name repo-name
                                            :file path})
                   ((complement string/ends-with?) repo-url "/") (str repo-url "/")
                   :else repo-url)]
     (cond-> {:mvn-repo repo-url
              :mvn-path (str (fs/relativize cache-dir resolved-path))
              :url (str repo-url (fs/relativize cache-dir resolved-path))}
       snapshot (assoc :snapshot snapshot))))


(defn git-remote-url
  [repo-root-path]
  (string/trim
    (:out
      (sh/with-sh-dir (str repo-root-path)
        (sh/sh "git" "remote" "get-url" "origin")))))

; See
; https://github.com/clojure/tools.gitlibs/blob/v2.4.172/src/main/clojure/clojure/tools/gitlibs/impl.clj#L83
(defn git-dir
  "Relative path to the git dir for a given URL"
  [url]
  (str (fs/relativize
         (fs/path (:gitlibs/dir @gitlibs-config/CONFIG) "_repos")
         (gli/git-dir url))))


(defn paths-to-gitdeps
  [deps-data]
  (some->> deps-data
           (zip/zipper coll? seq nil)
           (iterate zip/next)
           (take-while (complement zip/end?))
           (filter (comp (some-fn :sha :git/sha) zip/node))
           (mapv #(some->> (zip/path %)
                           (filter map-entry?)
                           (mapv key)))))

(defn- full-sha?'
  [sha]
  (boolean (and sha (= 40 (count sha)))))

(defn full-sha?
  [git-dep]
  (or (full-sha?' (:sha git-dep))
      (full-sha?' (:git/sha git-dep))))

(def partial-sha? (complement full-sha?))


(defn- expand-hash
  [git-deps lib node]
  (let [node-data (r/sexpr node)
        tag (or (:tag node-data)
                (:git/tag node-data))
        full-sha (:rev (first (filter #(and
                                         (= (str lib) (:lib %))
                                         (= tag (:tag %)))
                                      git-deps)))]
    (if full-sha
      (cond-> node
        (:sha node-data)     (r/assoc :sha full-sha)
        (:git/sha node-data) (r/assoc :git/sha full-sha)
        ; Remove :git/tag to avoid network call
        :always              (-> (r/dissoc :tag) (r/dissoc :git/tag)))
      (do
        (printerrln "Can't expand full sha, ignoring"
                    {:lib lib
                     :node (edn/read-string (str node))})
        node))))

(defn- deps-file?
  "Returns true if filename is deps.edn or bb.edn "
  [file]
  (contains? #{"bb.edn" "deps.edn"}
             (fs/file-name file)))

(defn get-deps-files
  "Returns all deps.edn files in a directory.
   Optionally, includes bb.edn files and filter some files"
  [dir {:keys [deps-include deps-exclude bb?]}]
  (if-not (empty? deps-include)
    (map fs/canonicalize deps-include)
    (let [exclude? (fn [f] (some #(fs/same-file? f %) deps-exclude))]
      (filter (every-pred deps-file? (complement exclude?))
        (concat
          (fs/glob dir "**deps.edn")
          (when bb? (fs/glob dir "**bb.edn")))))))


(defn- valid-deps-file?
  [f]
  (try
    (deps/slurp-deps (fs/file f))
    true
    (catch clojure.lang.ExceptionInfo _
      (printerrln "Ignoring invalid deps.edn file:" (fs/file f))
      false)))

(defn expand-shas!
  [project-dir]
  (let [dep-paths (get-deps-files project-dir {:bb? true})
        {:keys [git-deps]} (json/read-str
                            (slurp (str (fs/path project-dir "deps-lock.json")))
                            :key-fn keyword)]
    (doseq [my-deps (->> dep-paths
                         (filter valid-deps-file?)
                         (mapv fs/file))
            :let [deps (deps/slurp-deps my-deps)
                  git-deps-paths (paths-to-gitdeps deps)
                  partial-sha-paths (filter #(partial-sha? (get-in deps %))
                                            git-deps-paths)]]
        (as-> (r/parse-string (slurp my-deps)) nodes
          (reduce
            (fn [acc path] (r/update-in acc path (partial expand-hash git-deps (last path))))
            nodes
            partial-sha-paths)
          (spit my-deps (str nodes))))))

(defn str->keyword
  [s]
  (-> s
    (string/replace  ":" "")
    (string/split #"/")
    (->> (apply keyword))))


(defn mvn?
  [[_ {:keys [mvn/version]}]]
  (boolean version))

(defn git?
  [[_ {:keys [git/url]}]]
  (boolean url))

(defn artifact->pom
  [path]
  (str (->> (fs/glob (fs/parent path) "*.pom")
            (sort (fn [x y]
                    (cond
                      (string/includes? (fs/file-name x) "SNAPSHOT") -1
                      (string/includes? (fs/file-name y) "SNAPSHOT") 1
                      :else (compare (fs/file-name x) (fs/file-name y)))))
            first)))

(defn get-repos
  "For a given path to a pom, returns all maven repos"
  [pom-path]
  (try
    (let [pom (io/file pom-path)
          model (read-model-file pom nil)]
      (into {}
            (map (fn [^Repository repo] (vector (.getId repo)
                                                {:url (.getUrl repo)})))
            (.getRepositories model)))
    (catch Exception _ {})))


(defn get-maven-repos
  "Given a basis, returns all maven repos"
  [basis]
  (into mvn/standard-repos
        (comp
          (filter mvn?)
          (map val)
          (mapcat :paths)
          (map artifact->pom)
          (map get-repos))
        (:libs basis)))


(comment

  (get-maven-repos
    (deps/create-basis {:user nil
                        :project (str (fs/expand-home "~/projects/clj-demo-project/deps.edn"))}))

  (expand-shas! (fs/expand-home "~/projects/clojure-lsp"))

  (mvn-repo-info (fs/expand-home "~/.m2/repository/org/clojure/clojure/1.11.1/clojure-1.11.1.jar"))
  (mvn-repo-info (fs/expand-home "~/.m2/repository/org/clojure/pom.contrib/1.1.0/pom.contrib-1.1.0.pom"))


  (=
    (mvn-repo-info
      (fs/expand-home "~/.m2/repository/clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-SNAPSHOT.jar")
      {:exact-version "2022.04.26-SNAPSHOT"})

    (mvn-repo-info
      (fs/expand-home "~/.m2/repository/clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-20220502.201054-5.jar")
      {:exact-version "2022.04.26-20220502.201054-5"}))


  (get-deps-files (fs/canonicalize ".") {:bb? true :deps-exclude ["templates/default/deps.edn"]})
  (get-deps-files "." {:bb? true :deps-include [""]})
  (get-deps-files "." {:bb? true :deps-exclude []})
  (get-deps-files "." {:bb? true :deps-include ["templates/default/deps.edn"]}))
