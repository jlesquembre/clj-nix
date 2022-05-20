(ns cljnix.core-test
  (:require
    [clojure.test :refer [deftest is use-fixtures testing]]
    [babashka.fs :as fs]
    [clojure.string :as string]
    [cljnix.test-helpers :as h]
    [cljnix.core :as c]
    [clojure.tools.deps.alpha.specs :as deps.spec]
    [clojure.spec.alpha :as s]
    [matcher-combinators.test]
    [matcher-combinators.matchers :as m]))

(def all-deps '{:deps {org.clojure/clojure {:mvn/version "1.11.1"}
                       clj-kondo/clj-kondo {:mvn/version "2022.04.26-20220502.201054-5"}
                       cider/piggieback    {:mvn/version "0.4.1-SNAPSHOT"}
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


(deftest all-aliases-combinations
  (let [aliases-combinations #'c/aliases-combinations]
    (is (= [["deps.edn" nil]]
           (aliases-combinations ["deps.edn" nil])))

    (is (match? (m/in-any-order
                  [["deps.edn" nil]
                   ["deps.edn" :test]])
                (aliases-combinations ["deps.edn" [:test]])))

    (is (match? (m/in-any-order
                  [["deps.edn" nil]
                   ["deps.edn" :test]
                   ["deps.edn" :build]])
                (aliases-combinations ["deps.edn" [:test :build]])))

    (is (match? (m/in-any-order
                  [["deps.edn" nil]
                   ["deps.edn" :build]
                   ["deps.edn" :test]
                   ["deps.edn" :foo]])
                (aliases-combinations ["deps.edn" [:test :build :foo]])))))

(deftest maven-deps-test

  (let [mvn-deps (c/maven-deps (h/basis {:deps {'cider/piggieback {:mvn/version "0.4.1-20190222.154954-1"}}}))]
    (is (match?
          {:hash "sha256-PvlYv5KwGYHd1MCIQiMNRoVAJRmWLF7FuEM9OMh0FOk=",
           :lib 'cider/piggieback,
           :mvn-path "cider/piggieback/0.4.1-SNAPSHOT/piggieback-0.4.1-20190222.154954-1.jar",
           :mvn-repo "https://repo.clojars.org/",
           :snapshot "piggieback-0.4.1-SNAPSHOT.jar",}
          (first (filter #(= 'cider/piggieback (:lib %)) mvn-deps))))
    (is (match?
          (m/embeds [{:hash "sha256-PvlYv5KwGYHd1MCIQiMNRoVAJRmWLF7FuEM9OMh0FOk=",
                      :lib 'cider/piggieback,
                      :mvn-path "cider/piggieback/0.4.1-SNAPSHOT/piggieback-0.4.1-20190222.154954-1.jar",
                      :mvn-repo "https://repo.clojars.org/",
                      :snapshot "piggieback-0.4.1-SNAPSHOT.jar",}
                     {:hash "sha256-rEsytjVma2/KsuMh2s/dPJzhDJ8XqLkaQmIUFEnWIjU=",
                      :mvn-path "cider/piggieback/0.4.1-SNAPSHOT/piggieback-0.4.1-20190222.154954-1.pom",
                      :mvn-repo "https://repo.clojars.org/",
                      :snapshot "piggieback-0.4.1-SNAPSHOT.pom",}])
          mvn-deps)))

  (let [mvn-deps (c/maven-deps (h/basis {:deps {'cider/piggieback {:mvn/version "0.4.1-SNAPSHOT"}}}))]
    (is (match?
          {:hash "sha256-PvlYv5KwGYHd1MCIQiMNRoVAJRmWLF7FuEM9OMh0FOk=",
           :lib 'cider/piggieback,
           :mvn-path "cider/piggieback/0.4.1-SNAPSHOT/piggieback-0.4.1-20190222.154954-1.jar",
           :mvn-repo "https://repo.clojars.org/",
           :snapshot "piggieback-0.4.1-SNAPSHOT.jar",}
          (first (filter #(= 'cider/piggieback (:lib %)) mvn-deps))))
    (is (match?
          (m/embeds [{:hash "sha256-PvlYv5KwGYHd1MCIQiMNRoVAJRmWLF7FuEM9OMh0FOk=",
                      :lib 'cider/piggieback,
                      :mvn-path "cider/piggieback/0.4.1-SNAPSHOT/piggieback-0.4.1-20190222.154954-1.jar",
                      :mvn-repo "https://repo.clojars.org/",
                      :snapshot "piggieback-0.4.1-SNAPSHOT.jar",}
                     {:hash "sha256-rEsytjVma2/KsuMh2s/dPJzhDJ8XqLkaQmIUFEnWIjU=",
                      :mvn-path "cider/piggieback/0.4.1-SNAPSHOT/piggieback-0.4.1-20190222.154954-1.pom",
                      :mvn-repo "https://repo.clojars.org/",
                      :snapshot "piggieback-0.4.1-SNAPSHOT.pom",}])
          mvn-deps)))


  (testing "Latest SNAPSHOT version is used"
    (let [mvn-deps (c/maven-deps (h/basis {:deps {'clj-kondo/clj-kondo {:mvn/version "2022.04.26-SNAPSHOT"}}}))]
      (is (match?
            {:mvn-path "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-20220517.201936-14.jar",
             :mvn-repo "https://repo.clojars.org/",
             :snapshot "clj-kondo-2022.04.26-SNAPSHOT.jar",}
            (first (filter #(= 'clj-kondo/clj-kondo (:lib %)) mvn-deps))))
      (is (match?
            {:mvn-path "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-20220517.201936-14.pom",
             :mvn-repo "https://repo.clojars.org/",
             :snapshot "clj-kondo-2022.04.26-SNAPSHOT.pom",}
            (first (filter (every-pred #(string/starts-with? (:mvn-path %) "clj-kondo/clj-kondo")
                                       #(string/ends-with? (:mvn-path %) ".pom"))
                           mvn-deps))))
      (is (match?
            (m/embeds [{:mvn-path "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-20220517.201936-14.jar",
                        :mvn-repo "https://repo.clojars.org/",
                        :snapshot "clj-kondo-2022.04.26-SNAPSHOT.jar",}
                       {:mvn-path "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-20220517.201936-14.pom",
                        :mvn-repo "https://repo.clojars.org/",
                        :snapshot "clj-kondo-2022.04.26-SNAPSHOT.pom"}])
            mvn-deps))))

  (testing "Exact SNAPSHOT version is used"
    (let [mvn-deps (c/maven-deps (h/basis {:deps {'clj-kondo/clj-kondo {:mvn/version "2022.04.26-20220502.201054-5"}}}))]
      (is (match?
            {:mvn-path "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-20220502.201054-5.jar",
             :mvn-repo "https://repo.clojars.org/",
             :version "2022.04.26-20220502.201054-5"
             :snapshot "clj-kondo-2022.04.26-SNAPSHOT.jar",}
            (first (filter #(= 'clj-kondo/clj-kondo (:lib %)) mvn-deps))))
      (is (match?
            {:mvn-path "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-20220502.201054-5.pom",
             :mvn-repo "https://repo.clojars.org/",
             :snapshot "clj-kondo-2022.04.26-SNAPSHOT.pom",}
            (first (filter (every-pred #(string/starts-with? (:mvn-path %) "clj-kondo/clj-kondo")
                                       #(string/ends-with? (:mvn-path %) ".pom"))
                           mvn-deps))))
      (is (match?
            (m/embeds [{:mvn-path "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-20220502.201054-5.jar",
                        :mvn-repo "https://repo.clojars.org/",
                        :snapshot "clj-kondo-2022.04.26-SNAPSHOT.jar",}
                       {:mvn-path "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-20220502.201054-5.pom",
                        :mvn-repo "https://repo.clojars.org/",
                        :snapshot "clj-kondo-2022.04.26-SNAPSHOT.pom"}])
            mvn-deps)))))
