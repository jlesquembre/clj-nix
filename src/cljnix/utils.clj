(ns cljnix.utils
  (:require
    [clojure.java.shell :as sh]
    [clojure.string :as string]
    [clojure.tools.deps.alpha.util.maven :as mvn]
    [clojure.tools.gitlibs.config :as gitlibs-config]
    [clojure.tools.gitlibs.impl :as gli]
    [babashka.fs :as fs]
    [version-clj.core :as version])
  (:import
    [java.io FileReader]
    [org.apache.maven.model.io.xpp3 MavenXpp3Reader]
    [org.apache.maven.model Model]))

(defn throw+
  [msg data]
  (throw (ex-info (prn-str msg data) data)))

(def ^:dynamic *mvn-repos* mvn/standard-repos)

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
    (let [f (FileReader. (str pom-path))]
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
  [path]
  (let [info-file (fs/path (fs/parent path) "_remote.repositories")
        file-name (fs/file-name path)
        repo-name-finder (fn [s] (second (re-find
                                           (re-pattern (str file-name #">(\S+)="))
                                           s)))]
    (some #(let [repo-name (repo-name-finder %)]
             (when (contains? *mvn-repos* repo-name)
               repo-name))
          (fs/read-all-lines info-file))))

(defn mvn-repo-info
  "Given a path for a jar in the maven local repo, e.g:
   $REPO/babashka/fs/0.1.4/fs-0.1.4.jar
   return the maven repository url and the dependecy url"
  [path {:keys [cache-dir exact-version]
         :or {cache-dir @mvn/cached-local-repo}}]
  {:pre [(if (snapshot? path)
           (not (nil? exact-version))
           true)]}
  (let [{:keys [resolved-path snapshot]} (resolve-snapshot path exact-version)
        repo-name (get-mvn-repo-name resolved-path)
        repo-url (get-in *mvn-repos* [repo-name :url])
        repo-url (cond
                   (nil? repo-url) (throw+ "Maven repo not found"
                                           {:mvn-repos *mvn-repos*
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
         (:gitlibs/dir @gitlibs-config/CONFIG)
         (gli/git-dir url))))

(comment
  (mvn-repo-info "/home/jlle/.m2/repository/org/clojure/clojure/1.11.1/clojure-1.11.1.jar"
                 @mvn/cached-local-repo)
  (mvn-repo-info "/home/jlle/.m2/repository/org/clojure/pom.contrib/1.1.0/pom.contrib-1.1.0.pom"
                 @mvn/cached-local-repo)
  (mvn-repo-info "/tmp/clj-cache7291733112505590287/mvn/org/clojure/pom.contrib/1.1.0/pom.contrib-1.1.0.pom"
                 @mvn/cached-local-repo)


  (=
    (mvn-repo-info
      "/home/jlle/.m2/repository/clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-SNAPSHOT.jar"
      {:exact-version "2022.04.26-SNAPSHOT"})
    (mvn-repo-info
      "/home/jlle/.m2/repository/clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-20220502.201054-5.jar"
      {:exact-version "2022.04.26-20220502.201054-5"})))
