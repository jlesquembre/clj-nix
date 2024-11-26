(ns simple.package
  (:require
    [babashka.fs :as fs]
    [helpers :as h]))


(defn build
  [{:keys [out deps]}]
  (let [bin-path (fs/file out "bin")
        path (fs/file bin-path "simple")
        bash (:bash deps)]
    (fs/create-dirs bin-path)
    (h/create-exe-file path)
    (fs/write-lines
      path
      [(format "#!%s" (fs/file bash "bin/bash"))
       (format "export PATH=%s" (h/make-bin-path (dissoc deps :bash)))
       "hello"
       "hello --version"])))


(def pkg
  {:name "simple"
   :deps [:bash :bb/hello-override]
   :version "DEV"
   :build build})
