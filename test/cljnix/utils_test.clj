(ns cljnix.utils-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [cljnix.utils :as utils]
    [clojure.tools.deps.alpha.util.maven :as mvn]
    [babashka.fs :as fs]
    [cljnix.test-helpers :as h]))

(def my-deps '{:deps {org.clojure/clojure {:mvn/version "1.11.1"}
                      babashka/fs {:mvn/version "0.1.5"}}})

(defn deps-cache-fixture [f]
  (h/prep-deps my-deps)
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
