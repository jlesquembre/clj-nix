(ns cljnix.bootstrap
  (:require
    [clojure.string :as string]
    [clojure.tools.deps.alpha.util.maven :as mvn]
    [clojure.tools.cli.api :as tools]
    [clojure.tools.deps.alpha :as deps]
    [clojure.tools.gitlibs.config :as gitlibs-config]
    [clojure.math.combinatorics :as combo]
    [clojure.data.json :as json]
    [babashka.fs :as fs]
    [cljnix.utils :refer [throw+ *mvn-repos*] :as utils]
    [cljnix.nix :refer [nix-hash]]))


(def mvn-path (deref mvn/cached-local-repo))
(def gitlib-path (fs/path (:gitlibs/dir @gitlibs-config/CONFIG) "libs"))


(defn nixify-mvn
  [path]
  {:url (:url (utils/mvn-repo-info path))
   :hash (nix-hash path)})

(defn nixify-git
  "For a path in the gitlibs local cache, e.g.:
   ~/.gitlibs/libs/com.github.babashka/fs/dc73460e63ff10c701c353227f2689b3d7c33a43/src
   return the git data"
  [path]
  (let [[group artifact rev & src-path] (fs/components (fs/relativize gitlib-path path))
        repo-root (fs/path gitlib-path group artifact rev)]
    {:rev (str rev)
     :paths [(str (apply fs/path src-path))]
     :hash (nix-hash repo-root)
     :url (utils/git-remote-url repo-root)}))


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
              (conj acc v))))
        []
        classpath-roots))))

(comment
  (nixify-classpath "/home/jlle/projects/clojure-lsp/cli/deps.edn"))


(comment
  (System/getenv "JAVA_TOOL_OPTIONS")
  (System/getProperty "user.home")
  (System/setProperty "user.home" "/tmp/bar")

  (System/setProperty "clojure.gitlibs.dir" "/tmp/bar/gitlibs")

  (tools/prep {:log :debug
               :project "/home/jlle/projects/clojure-lsp/cli/deps.edn"})

  ; (.getAbsolutePath (io/file (System/getProperty "user.home") ".m2" "repository"))

  (deref gitlibs-config/CONFIG)

  (type mvn/cached-local-repo)
  (reset! mvn/cached-local-repo "/tmp/bar")


  (alter-var-root (var mvn/cached-local-repo)
                  (constantly (delay "/tmp/bar")))

  (alter-var-root (var gitlibs-config/CONFIG)
                  (fn [config]
                    (delay
                      (assoc @config :gitlibs/dir "/tmp/bar/gitlibs"))))



  (for [al [:foo :bar :xxx]]
    al)

  (def aliases [:build :alias :foo])

  (combo/permutations aliases)
  (count aliases)
  (combo/permuted-combinations aliases 1)
  (combo/permuted-combinations aliases 2)
  (combo/permuted-combinations aliases 3)

  (mapcat
    #(combo/permuted-combinations aliases %)
    (range 1 4))

  (combo/combinations aliases 1)
  (combo/combinations aliases 2)
  (combo/combinations aliases 3))
