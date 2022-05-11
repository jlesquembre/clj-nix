(ns cljnix.utils-test
  (:require
    [cljnix.utils :as utils]
    [clojure.java.io :as io]
    [clojure.tools.deps.alpha.util.maven :as mvn]
    [babashka.fs :as fs]
    [clojure.tools.cli.api :as tools]
    [clojure.test :refer [deftest is use-fixtures]]))

(def cache-dir "/tmp/cljnix-test/cache")

(defn deps-cache-fixture [f]
  (tools/prep {:user nil
               :project (str (fs/file (io/resource "resources/test-deps.edn")))})
  (f))

(use-fixtures :once deps-cache-fixture)

(deftest mvn-repo-info-test
  (is (= {:mvn-path "org/clojure/clojure/1.11.1/clojure-1.11.1.jar"
          :mvn-repo "https://repo1.maven.org/maven2/"
          :url "https://repo1.maven.org/maven2/org/clojure/clojure/1.11.1/clojure-1.11.1.jar"}
         (utils/mvn-repo-info
           (fs/path @mvn/cached-local-repo "org/clojure/clojure/1.11.1/clojure-1.11.1.jar")
           @mvn/cached-local-repo)))

  (is (= {:mvn-path "org/clojure/pom.contrib/1.1.0/pom.contrib-1.1.0.pom"
          :mvn-repo "https://repo1.maven.org/maven2/"
          :url "https://repo1.maven.org/maven2/org/clojure/pom.contrib/1.1.0/pom.contrib-1.1.0.pom"}
         (utils/mvn-repo-info
           (fs/path @mvn/cached-local-repo "org/clojure/pom.contrib/1.1.0/pom.contrib-1.1.0.pom")
           @mvn/cached-local-repo)))

  (is (= {:mvn-path "babashka/fs/0.1.5/fs-0.1.5.jar"
          :mvn-repo "https://repo.clojars.org/"
          :url "https://repo.clojars.org/babashka/fs/0.1.5/fs-0.1.5.jar"}
         (utils/mvn-repo-info
           (fs/path @mvn/cached-local-repo "babashka/fs/0.1.5/fs-0.1.5.jar")
           @mvn/cached-local-repo))))
