(ns cljnix.test-helpers
  (:require
    [clojure.tools.cli.api :as tools]
    [clojure.tools.deps.alpha :as deps]
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

