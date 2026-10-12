(ns cljnix.test-helpers
  (:require
    [clojure.pprint :as pp]
    [clojure.string :as str]
    [clojure.data.json :as json]
    [clojure.tools.deps.cli.api :as tools]
    [clojure.tools.deps :as deps]
    [babashka.fs :as fs]))

(defn- deps-file
  [content]
  (let [f (str (fs/create-temp-file {:prefix "deps_" :suffix ".edn"}))]
    (spit f content)
    f))


(defn prep-deps
  [deps]
  (let [f (deps-file deps)]
    (tools/prep {:user nil :project f})
    (fs/delete f)))

(defn basis
  [deps]
  (let [f (deps-file deps)
        basis (deps/create-basis {:user nil :project f})]
    (fs/delete f)
    basis))

(defn make-spit-helper
  [project-dir]
  (fn spit-helper
    [path content & {:keys [json?]}]
    (binding [*print-namespace-maps* false]
      (spit (str (fs/path project-dir path))
            (if json?
              (json/write-str content
                :escape-slash false
                :escape-unicode false
                :escape-js-separators false)
              (with-out-str (pp/pprint content)))))))

(defn deps-cache-fixture
  "Returns a :once fixture that preps `deps` (downloads them to the local cache)."
  [deps]
  (fn [f]
    (prep-deps deps)
    (f)))

(defn find-dep
  [lib deps]
  (first (filter #(= lib (:lib %)) deps)))

(defn find-pom
  [lib-prefix deps]
  (first (filter #(and (str/starts-with? (:mvn-path %) lib-prefix)
                       (str/ends-with? (:mvn-path %) ".pom"))
                 deps)))
