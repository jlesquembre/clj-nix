(ns cljnix.core-test
  (:require
    [clojure.test :refer [deftest is use-fixtures testing]]
    [babashka.fs :as fs]
    [clojure.string :as string]
    [cljnix.test-helpers :as h]
    [cljnix.core :as c]
    [clojure.tools.deps.specs :as deps.spec]
    [clojure.tools.deps.util.maven :as mvn]
    [clojure.spec.alpha :as s]
    [matcher-combinators.test]
    [matcher-combinators.matchers :as m]))

(def all-deps '{:deps {org.clojure/clojure {:mvn/version "1.11.1"}
                       clj-kondo/clj-kondo {:mvn/version "2022.04.26-20220502.201054-5"}
                       cider/piggieback    {:mvn/version "0.4.1-SNAPSHOT"}
                       io.github.babashka/fs {:git/sha "7adcefeb773bd786408cdc788582f145f79626a6"}
                       io.github.weavejester/medley {:git/tag "1.4.0"
                                                     :git/sha "0044c6a"}}})

(defn- dissoc-dep
  [m dep]
  (update m :deps #(dissoc % dep)))

(defn- maven-deps
  [deps-map]
  (c/maven-deps
    (h/basis deps-map)
    mvn/standard-repos))

(use-fixtures :once (h/deps-cache-fixture all-deps))

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
    (is (match? [{:git-dir "https/github.com/babashka/fs",
                  :hash "sha256-L+tsBCOxr2kJpIEPJ0A+s8/Ud2jLgfiDQIB+U3/PcG0=",
                  :lib 'io.github.babashka/fs,
                  :rev "7adcefeb773bd786408cdc788582f145f79626a6",
                  :url "https://github.com/babashka/fs"}]
                (h/normalize-git-dep-urls
                  (missing-git-deps
                    (dissoc-dep all-deps 'io.github.babashka/fs)
                    all-deps)))))

  (testing "Should get all deps"
    (is (match? (m/in-any-order
                  [{:git-dir "https/github.com/weavejester/medley",
                    :hash "sha256-drh0opl3JjrpGadg74wIdOcDTaP2GT31X3O1PGXkvqk=",
                    :lib 'io.github.weavejester/medley,
                    :rev "0044c6aacc0b23eafa3b58091f49c794f5a1f5aa",
                    :url "https://github.com/weavejester/medley"}
                   {:git-dir "https/github.com/babashka/fs",
                    :hash "sha256-L+tsBCOxr2kJpIEPJ0A+s8/Ud2jLgfiDQIB+U3/PcG0=",
                    :lib 'io.github.babashka/fs,
                    :rev "7adcefeb773bd786408cdc788582f145f79626a6",
                    :url "https://github.com/babashka/fs"}])
                (h/normalize-git-dep-urls
                  (missing-git-deps
                    {}
                    all-deps))))))


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
  (testing "Specific SNAPSHOT timestamp version"
    (let [mvn-deps (maven-deps {:deps {'cider/piggieback {:mvn/version "0.4.1-20190222.154954-1"}}})]
      (is (match?
            {:hash "sha256-PvlYv5KwGYHd1MCIQiMNRoVAJRmWLF7FuEM9OMh0FOk=",
             :lib 'cider/piggieback,
             :mvn-path "cider/piggieback/0.4.1-SNAPSHOT/piggieback-0.4.1-20190222.154954-1.jar",
             :mvn-repo "https://repo.clojars.org/",
             :snapshot "piggieback-0.4.1-SNAPSHOT.jar",}
            (h/find-dep 'cider/piggieback mvn-deps)))
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
            mvn-deps))))

  (testing "SNAPSHOT version resolves to latest timestamp"
    (let [mvn-deps (maven-deps {:deps {'cider/piggieback {:mvn/version "0.4.1-SNAPSHOT"}}})]
      (is (match?
            {:hash "sha256-PvlYv5KwGYHd1MCIQiMNRoVAJRmWLF7FuEM9OMh0FOk=",
             :lib 'cider/piggieback,
             :mvn-path "cider/piggieback/0.4.1-SNAPSHOT/piggieback-0.4.1-20190222.154954-1.jar",
             :mvn-repo "https://repo.clojars.org/",
             :snapshot "piggieback-0.4.1-SNAPSHOT.jar",}
            (h/find-dep 'cider/piggieback mvn-deps)))
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
            mvn-deps))))


  (testing "Latest SNAPSHOT version is used"
    (let [mvn-deps (maven-deps {:deps {'clj-kondo/clj-kondo {:mvn/version "2022.04.26-SNAPSHOT"}}})
          snapshot-resolved-version "2022.04.26-20220526.212013-27"]
      (is (match?
            {:mvn-path (str "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-" snapshot-resolved-version ".jar",)
             :mvn-repo "https://repo.clojars.org/",
             :snapshot "clj-kondo-2022.04.26-SNAPSHOT.jar",}
            (h/find-dep 'clj-kondo/clj-kondo mvn-deps)))
      (is (match?
            {:mvn-path (str "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-" snapshot-resolved-version ".pom")
             :mvn-repo "https://repo.clojars.org/",
             :snapshot "clj-kondo-2022.04.26-SNAPSHOT.pom",}
            (h/find-pom "clj-kondo/clj-kondo" mvn-deps)))
      (is (match?
            (m/embeds [{:mvn-path (str "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-" snapshot-resolved-version ".jar",)
                        :mvn-repo "https://repo.clojars.org/",
                        :snapshot "clj-kondo-2022.04.26-SNAPSHOT.jar",}
                       {:mvn-path (str "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-" snapshot-resolved-version ".pom")
                        :mvn-repo "https://repo.clojars.org/",
                        :snapshot "clj-kondo-2022.04.26-SNAPSHOT.pom"}])
            mvn-deps))))

  (testing "Exact SNAPSHOT version is used"
    (let [mvn-deps (maven-deps {:deps {'clj-kondo/clj-kondo {:mvn/version "2022.04.26-20220502.201054-5"}}})]
      (is (match?
            {:mvn-path "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-20220502.201054-5.jar",
             :mvn-repo "https://repo.clojars.org/",
             :version "2022.04.26-20220502.201054-5"
             :snapshot "clj-kondo-2022.04.26-SNAPSHOT.jar",}
            (h/find-dep 'clj-kondo/clj-kondo mvn-deps)))
      (is (match?
            {:mvn-path "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-20220502.201054-5.pom",
             :mvn-repo "https://repo.clojars.org/",
             :snapshot "clj-kondo-2022.04.26-SNAPSHOT.pom",}
            (h/find-pom "clj-kondo/clj-kondo" mvn-deps)))
      (is (match?
            (m/embeds [{:mvn-path "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-20220502.201054-5.jar",
                        :mvn-repo "https://repo.clojars.org/",
                        :snapshot "clj-kondo-2022.04.26-SNAPSHOT.jar",}
                       {:mvn-path "clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-20220502.201054-5.pom",
                        :mvn-repo "https://repo.clojars.org/",
                        :snapshot "clj-kondo-2022.04.26-SNAPSHOT.pom"}])
            mvn-deps)))))

(deftest expand-sha-tests
  (fs/with-temp-dir [project-dir {:prefix "dummy_project"}]
    (let [spit-helper (h/make-spit-helper project-dir)]
      (spit-helper "deps.edn" {:deps {'io.github.babashka/fs
                                      {:git/tag "v0.1.6"
                                       :git/sha "31f8b93"}}})
      (is (= [{:lib "io.github.babashka/fs",
                :url "https://github.com/babashka/fs",
                :rev "31f8b93638530f8ea7148c22b008ce1d0ccd4b87",
                :tag "v0.1.6"
                :git-dir "https/github.com/babashka/fs",
                :hash "sha256-rlC+1cPnDYNP4UznIWH9MC2xSVQn/XbvKE10tbcsNNI="}]
             (:git-deps (h/normalize-lock-file-git-urls (c/lock-file project-dir)))))

      (spit-helper "deps.edn" {:deps {'io.github.cognitect-labs/test-runner  {:git/tag "v0.5.0",
                                                                              :git/sha "b3fd0d2"}}})
      (is (= [{:git-dir "https/github.com/cognitect-labs/test-runner",
                :hash "sha256-NZ9/S82Ae1aq0gnuTLOYg/cc7NcYIoK2JP6c/xI+xJE=",
                :lib "io.github.cognitect-labs/test-runner",
                :rev "48c3c67f98362ba1e20526db4eeb6996209c050a",
                :tag "v0.5.0",
                :url "https://github.com/cognitect-labs/test-runner"}]
             (:git-deps (h/normalize-lock-file-git-urls (c/lock-file project-dir)))))

      (spit-helper "deps.edn" {:deps {'io.github.cognitect-labs/test-runner {:git/tag "v0.5.0",
                                                                             :git/sha "b3fd0d2"}}
                               :aliases
                               {:foo
                                {:extra-deps
                                 {'io.github.cognitect-labs/test-runner {:git/sha "48c3c67f98362ba1e20526db4eeb6996209c050a"}}}}})

      (is (= [{:git-dir "https/github.com/cognitect-labs/test-runner",
                :hash "sha256-NZ9/S82Ae1aq0gnuTLOYg/cc7NcYIoK2JP6c/xI+xJE=",
                :lib "io.github.cognitect-labs/test-runner",
                :rev "48c3c67f98362ba1e20526db4eeb6996209c050a",
                :tag "v0.5.0",
                :url "https://github.com/cognitect-labs/test-runner"}]
             (:git-deps (h/normalize-lock-file-git-urls (c/lock-file project-dir))))))))

;; Phase 1 Tests: Language-agnostic dependency handling
;; These tests ensure dependency resolution works independently of compilation

(deftest maven-deps-independent-of-compilation
  (testing "Maven dependency resolution doesn't depend on compilation"
    (let [deps-with-non-jvm-lib {:deps {'org.clojure/clojurescript {:mvn/version "1.11.60"}}}
          mvn-deps (maven-deps deps-with-non-jvm-lib)]
      (is (seq mvn-deps)
          "Should resolve Maven deps regardless of target platform")
      (is (some #(= 'org.clojure/clojurescript (:lib %)) mvn-deps)
          "Should include ClojureScript as a Maven dependency"))))

(deftest git-deps-independent-of-compilation
  (testing "Git dependency resolution doesn't depend on compilation"
    (let [deps-with-git {:deps {'io.github.babashka/fs {:git/url "https://github.com/babashka/fs"
                                                        :git/sha "7adcefeb773bd786408cdc788582f145f79626a6"}}}]
      (h/prep-deps deps-with-git)
      (let [git-deps (c/git-deps (h/basis deps-with-git))]
        (is (seq git-deps)
            "Should resolve Git deps regardless of target platform")
        (is (= "https://github.com/babashka/fs" (h/normalize-git-url (:url (first git-deps))))
            "Should correctly extract git URL")))))

(deftest mixed-clj-cljs-dependencies
  (testing "Lock file can handle mixed Clojure/ClojureScript dependencies"
    (fs/with-temp-dir [project-dir {:prefix "mixed_project"}]
      (let [spit-helper (h/make-spit-helper project-dir)]
        (spit-helper "deps.edn" {:deps {'org.clojure/clojure {:mvn/version "1.11.1"}
                                        'org.clojure/clojurescript {:mvn/version "1.11.60"}
                                        'reagent/reagent {:mvn/version "1.2.0"}}})
        (let [lock-data (c/lock-file project-dir)]
          (is (seq (:mvn-deps lock-data))
              "Should have Maven dependencies")
          (is (some #(clojure.string/includes? (:mvn-path %) "clojure/clojure") (:mvn-deps lock-data))
              "Should include Clojure")
          (is (some #(clojure.string/includes? (:mvn-path %) "clojure/clojurescript") (:mvn-deps lock-data))
              "Should include ClojureScript"))))))

(deftest dependency-filtering-by-type
  (testing "Dependencies can be filtered by type (mvn vs git)"
    (let [deps-map {:deps {'org.clojure/clojure {:mvn/version "1.11.1"}
                           'io.github.babashka/fs {:git/sha "7adcefeb773bd786408cdc788582f145f79626a6"}}}
          basis (h/basis deps-map)
          mvn-repos mvn/standard-repos
          mvn-deps (c/maven-deps basis mvn-repos)
          git-deps (c/git-deps basis)]
      (is (every? :mvn-path mvn-deps)
          "All Maven deps should have :mvn-path")
      (is (not-any? :git-dir mvn-deps)
          "Maven deps should not have :git-dir")
      (is (every? :git-dir git-deps)
          "All Git deps should have :git-dir")
      (is (not-any? :mvn-path git-deps)
          "Git deps should not have :mvn-path"))))

(deftest lock-file-structure-language-agnostic
  (testing "Lock file structure doesn't assume JVM compilation"
    (fs/with-temp-dir [project-dir {:prefix "agnostic_project"}]
      (let [spit-helper (h/make-spit-helper project-dir)]
        (spit-helper "deps.edn" {:deps {'org.clojure/clojurescript {:mvn/version "1.11.60"}}})
        (let [lock-data (c/lock-file project-dir)]
          (is (contains? lock-data :lock-version)
              "Lock file should have version")
          (is (contains? lock-data :mvn-deps)
              "Lock file should have mvn-deps")
          (is (contains? lock-data :git-deps)
              "Lock file should have git-deps")
          (is (vector? (:mvn-deps lock-data))
              "mvn-deps should be a vector")
          (is (vector? (:git-deps lock-data))
              "git-deps should be a vector"))))))
