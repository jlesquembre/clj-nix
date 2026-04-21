(ns integration.cljs-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [babashka.fs :as fs]
    [cljnix.test-helpers :as h]
    [cljnix.core :as c]
    [cljnix.build.cljs :as cljs]
    [matcher-combinators.test]))

;; Integration tests for ClojureScript support

(deftest lock-file-with-cljs-dependencies
  (testing "Lock file generation works with ClojureScript dependencies"
    (fs/with-temp-dir [project-dir {:prefix "cljs_lock_project"}]
      (let [spit-helper (h/make-spit-helper project-dir)]
        (spit-helper "deps.edn" {:paths ["src"]
                                 :deps {'org.clojure/clojure {:mvn/version "1.11.1"}
                                        'org.clojure/clojurescript {:mvn/version "1.11.60"}}})
        (let [lock-data (c/lock-file (str project-dir))]
          (is (seq (:mvn-deps lock-data))
              "Should have Maven dependencies")
          (is (some #(clojure.string/includes? (:mvn-path %) "clojure/clojure")
                    (:mvn-deps lock-data))
              "Should include Clojure")
          (is (some #(clojure.string/includes? (:mvn-path %) "clojure/clojurescript")
                    (:mvn-deps lock-data))
              "Should include ClojureScript"))))))

(deftest lock-file-with-cljs-libraries
  (testing "Lock file includes popular ClojureScript libraries"
    (fs/with-temp-dir [project-dir {:prefix "cljs_libs_project"}]
      (let [spit-helper (h/make-spit-helper project-dir)]
        (spit-helper "deps.edn" {:paths ["src"]
                                 :deps {'org.clojure/clojurescript {:mvn/version "1.11.60"}
                                        'reagent/reagent {:mvn/version "1.2.0"}}})
        (let [lock-data (c/lock-file (str project-dir))]
          (is (seq (:mvn-deps lock-data))
              "Should resolve ClojureScript library dependencies")
          (is (some #(clojure.string/includes? (:mvn-path %) "reagent/reagent")
                    (:mvn-deps lock-data))
              "Should include Reagent"))))))

(deftest mixed-clj-cljs-project-setup
  (testing "Mixed Clojure/ClojureScript project with separate source directories"
    (fs/with-temp-dir [project-dir {:prefix "mixed_project"}]
      (let [spit-helper (h/make-spit-helper project-dir)]
        (spit-helper "deps.edn" {:paths ["src" "src-cljs"]
                                 :deps {'org.clojure/clojure {:mvn/version "1.11.1"}
                                        'org.clojure/clojurescript {:mvn/version "1.11.60"}}})

        ;; Create sample source files
        (fs/create-dirs (fs/path project-dir "src" "myapp"))
        (spit (str (fs/path project-dir "src" "myapp" "core.clj"))
              "(ns myapp.core)\n(defn hello [] \"Hello from CLJ\")")

        (fs/create-dirs (fs/path project-dir "src-cljs" "myapp"))
        (spit (str (fs/path project-dir "src-cljs" "myapp" "core.cljs"))
              "(ns myapp.core)\n(defn hello [] \"Hello from CLJS\")")

        (let [lock-data (c/lock-file (str project-dir))]
          (is (contains? lock-data :mvn-deps)
              "Lock file should have Maven dependencies")
          (is (> (count (:mvn-deps lock-data)) 2)
              "Should have multiple dependencies (including transitive)"))))))

(deftest cljs-project-with-git-deps
  (testing "ClojureScript project with Git dependencies"
    (fs/with-temp-dir [project-dir {:prefix "cljs_git_deps_project"}]
      (let [spit-helper (h/make-spit-helper project-dir)]
        (spit-helper "deps.edn" {:paths ["src"]
                                 :deps {'org.clojure/clojurescript {:mvn/version "1.11.60"}
                                        'io.github.weavejester/medley {:git/sha "0044c6aacc0b23eafa3b58091f49c794f5a1f5aa"}}})
        (h/prep-deps {:deps {'io.github.weavejester/medley
                             {:git/url "https://github.com/weavejester/medley"
                              :git/sha "0044c6aacc0b23eafa3b58091f49c794f5a1f5aa"}}})

        (let [lock-data (c/lock-file (str project-dir))]
          (is (seq (:mvn-deps lock-data))
              "Should have Maven dependencies")
          (is (seq (:git-deps lock-data))
              "Should have Git dependencies")
          (is (some #(= "io.github.weavejester/medley" (:lib %))
                    (:git-deps lock-data))
              "Should include medley Git dependency"))))))

(deftest cljs-builder-protocol-integration
  (testing "ClojureScript builder integrates with build system"
    (let [builder (cljs/create-builder)]
      (is (satisfies? cljnix.build.core/Builder builder)
          "Should implement Builder protocol")

      ;; Test get-src-paths with actual file
      (fs/with-temp-dir [project-dir {:prefix "cljs_integration_project"}]
        (let [spit-helper (h/make-spit-helper project-dir)
              deps-file (str (fs/path project-dir "deps.edn"))]
          (spit-helper "deps.edn" {:paths ["src" "src-cljs"]})
          (let [paths (cljnix.build.core/get-src-paths builder deps-file)]
            (is (= ["src" "src-cljs"] paths)
                "Should extract paths via protocol")))))))

(deftest shadow-cljs-config-detection
  (testing "Shadow-cljs configuration file detection"
    (fs/with-temp-dir [project-dir {:prefix "shadow_config_project"}]
      (let [spit-helper (h/make-spit-helper project-dir)]
        ;; Create shadow-cljs.edn
        (spit-helper "shadow-cljs.edn"
                     {:source-paths ["src"]
                      :builds {:app {:target :browser
                                     :output-dir "public/js"}}})

        (let [config-path (#'cljs/find-shadow-config (str project-dir))]
          (is (some? config-path)
              "Should find shadow-cljs.edn")
          (is (clojure.string/ends-with? config-path "shadow-cljs.edn")
              "Should return correct config path"))))))

(deftest cljs-and-clj-deps-are-independent
  (testing "ClojureScript and Clojure dependency resolution are independent"
    (fs/with-temp-dir [project-dir {:prefix "independent_deps_project"}]
      (let [spit-helper (h/make-spit-helper project-dir)]
        ;; Project with only ClojureScript dependencies
        (spit-helper "deps.edn" {:paths ["src"]
                                 :deps {'org.clojure/clojurescript {:mvn/version "1.11.60"}}})

        (let [lock-data (c/lock-file (str project-dir))]
          ;; Dependency resolution should work without JVM compilation concerns
          (is (seq (:mvn-deps lock-data))
              "Should resolve dependencies without JVM")
          (is (vector? (:mvn-deps lock-data))
              "Maven deps should be a vector")
          (is (vector? (:git-deps lock-data))
              "Git deps should be a vector"))))))
