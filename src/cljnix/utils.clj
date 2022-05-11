(ns cljnix.utils
  (:require
    [clojure.java.shell :as sh]
    [clojure.string :as string]
    [clojure.tools.deps.alpha.util.maven :as mvn]
    [clojure.tools.gitlibs.config :as gitlibs-config]
    [clojure.tools.gitlibs.impl :as gli]
    [babashka.fs :as fs]))


(defn throw+
  [msg data]
  (throw (ex-info (prn-str msg data) data)))

(def ^:dynamic *mvn-repos* mvn/standard-repos)

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
  ([path]
   (mvn-repo-info path @mvn/cached-local-repo))
  ([path cached-local-repo]
   (let [repo-name (get-mvn-repo-name path)
         repo-url (get-in *mvn-repos* [repo-name :url])
         repo-url (cond
                    (nil? repo-url)
                    (throw+ "Maven repo not found"
                            {:mvn-repos *mvn-repos*
                             :jar path})
                    ((complement string/ends-with?) repo-url "/") (str repo-url "/")
                    :else repo-url)]
      {:mvn-repo repo-url
       :mvn-path (str (fs/relativize cached-local-repo path))
       :url (str repo-url (fs/relativize cached-local-repo path))})))

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

  (git-dir
    (git-remote-url (fs/expand-home "~/.gitlibs/libs/babashka/fs/2bf527f797d69b3f14247940958e0d7b509f3ce2"))))
