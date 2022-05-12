(ns cljnix.core-test
  (:require
    [clojure.test :refer [deftest is use-fixtures testing]]
    [babashka.fs :as fs]
    [cljnix.test-helpers :as h]
    [cljnix.core :as c]
    [clojure.tools.deps.alpha.specs :as deps.spec]
    [clojure.spec.alpha :as s]
    [matcher-combinators.test]
    [matcher-combinators.matchers :as m]))

(def all-deps '{:deps {org.clojure/clojure {:mvn/version "1.11.1"}
                       io.github.babashka/fs {:git/sha "7adcefeb773bd786408cdc788582f145f79626a6"}
                       io.github.weavejester/medley {:git/sha "0044c6aacc0b23eafa3b58091f49c794f5a1f5aa"}}})

(defn- dissoc-dep
  [m dep]
  (update m :deps #(dissoc % dep)))

(defn deps-cache-fixture [f]
  (h/prep-deps all-deps)
  (f))

(use-fixtures :once deps-cache-fixture)

(defn- missing-git-deps
  [deps deps-in-cache]
  {:pre [(s/valid? ::deps.spec/deps-map deps)
         (s/valid? ::deps.spec/deps-map deps-in-cache)]}
  (fs/with-temp-dir [cache-dir {:prefix "gitdeps_cache"}]
    (let [git-deps (c/git-deps (h/basis deps))]
      (c/make-git-cache! (c/git-deps (h/basis deps-in-cache))
                         cache-dir)
      (c/missing-git-deps git-deps cache-dir))))


(deftest missing-git-deps-test
  (testing "git cache is empty"
    (is (= []
           (fs/with-temp-dir [cache-dir {:prefix "gitdeps_cache"}]
             (c/missing-git-deps (c/git-deps (h/basis all-deps))
                                 cache-dir)))))

  (testing "No missing git deps"
    (is (= []
           (missing-git-deps all-deps all-deps))))

  (testing "Some missing git deps"
    (is (match? [{:git-dir "_repos/https/github.com/babashka/fs",
                  :hash "sha256-L+tsBCOxr2kJpIEPJ0A+s8/Ud2jLgfiDQIB+U3/PcG0=",
                  :lib 'io.github.babashka/fs,
                  :rev "7adcefeb773bd786408cdc788582f145f79626a6",
                  :url "https://github.com/babashka/fs.git"}]
                (missing-git-deps
                  (dissoc-dep all-deps 'io.github.babashka/fs)
                  all-deps))))

  (testing "Should get all deps"
    (is (match? (m/in-any-order
                  [{:git-dir "_repos/https/github.com/weavejester/medley",
                    :hash "sha256-drh0opl3JjrpGadg74wIdOcDTaP2GT31X3O1PGXkvqk=",
                    :lib 'io.github.weavejester/medley,
                    :rev "0044c6aacc0b23eafa3b58091f49c794f5a1f5aa",
                    :url "https://github.com/weavejester/medley.git"}
                   {:git-dir "_repos/https/github.com/babashka/fs",
                    :hash "sha256-L+tsBCOxr2kJpIEPJ0A+s8/Ud2jLgfiDQIB+U3/PcG0=",
                    :lib 'io.github.babashka/fs,
                    :rev "7adcefeb773bd786408cdc788582f145f79626a6",
                    :url "https://github.com/babashka/fs.git"}])
                (missing-git-deps
                  {}
                  all-deps)))))
