(ns cljnix.bootstrap
  (:require
    [clojure.tools.deps.alpha.util.maven :as mvn]
    [clojure.tools.cli.api :as tools]
    [clojure.tools.deps.alpha :as deps]
    [clojure.tools.gitlibs.config :as gitlibs-config]
    [clojure.data.json :as json]
    [babashka.fs :as fs]
    [cljnix.utils :refer [throw+ *mvn-repos*] :as utils]
    [cljnix.nix :refer [nix-hash]]))


(def mvn-path (deref mvn/cached-local-repo))
(def gitlib-path (fs/path (:gitlibs/dir @gitlibs-config/CONFIG) "libs"))


(defn- map-comparator
  [a b]
  (let [m {:url 1
           :rev 2
           :paths 3
           :hash 4}]
    (compare (get m a 1000)
             (get m b 1000))))

(defn nixify-mvn
  [path]
  (sorted-map-by map-comparator
                 :url (:url (utils/mvn-repo-info path {}))
                 :hash (nix-hash path)))

(defn nixify-git
  "For a path in the gitlibs local cache, e.g.:
   ~/.gitlibs/libs/com.github.babashka/fs/dc73460e63ff10c701c353227f2689b3d7c33a43/src
   return the git data"
  [path]
  (let [[group artifact rev & src-path] (fs/components (fs/relativize gitlib-path path))
        repo-root (fs/path gitlib-path group artifact rev)]
    (sorted-map-by map-comparator
                   :rev (str rev)
                   :paths [(str (apply fs/path src-path))]
                   :hash (nix-hash repo-root)
                   :url (utils/git-remote-url repo-root))))


(defn nixify-dep
  [path]
  (cond
    (fs/starts-with? path mvn-path) (nixify-mvn path)
    (fs/starts-with? path gitlib-path) (nixify-git path)
    :else (throw+ "Unknow dependecy" {:dependency path})))

(defn- same-git-dep?
  [a b]
  (= (select-keys a [:rev :url])
     (select-keys b [:rev :url])))


(defn nixify-classpath
  [deps-path]
  (let [options {:user nil :project deps-path}
        _ (tools/prep options)
        {:keys [classpath-roots mvn/repos]} (deps/create-basis options)]

    (with-redefs [*mvn-repos* repos]
      (transduce
        (comp
          (filter fs/absolute?)
          (map nixify-dep))
        (completing
          (fn [acc v]
            (if (same-git-dep? (peek acc) v)
              (update-in acc [(dec (count acc)) :paths] #(into % (:paths v)))
              (conj acc v)))
          #(into [] (sort-by :url %)))
        []
        classpath-roots))))

(defn as-json
  [{:keys [deps-path]}]
  (println (json/write-str (nixify-classpath deps-path)
                           :escape-slash false
                           :escape-unicode false
                           :escape-js-separators false)))

(comment
  (nixify-classpath "deps.edn")
  (as-json "deps.edn"))
