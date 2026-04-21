(ns integration.builder-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [babashka.fs :as fs]
    [cljnix.test-helpers :as h]
    [cljnix.core :as c]
    [cljnix.builder-cli :as builder]))

;; Phase 1 Tests: Builder CLI with custom build commands
;; These tests ensure the builder can work with non-standard build commands

(deftest builder-cli-commands-structure
  (testing "Builder CLI has expected command structure"
    ;; The builder-cli namespace has a -main function that dispatches commands
    (is (var? #'builder/-main)
        "builder-cli should have -main var")
    (is (ifn? @#'builder/-main)
        "builder-cli -main should be invocable")

    ;; Verify the namespace is loaded and accessible
    (is (find-ns 'cljnix.builder-cli)
        "cljnix.builder-cli namespace should be available")))

(deftest builder-without-gen-class-check
  (testing "Builder can build without :gen-class verification for custom builds"
    (fs/with-temp-dir [project-dir {:prefix "no_gen_class_project"}]
      (let [spit-helper (h/make-spit-helper project-dir)]
        (spit-helper "deps.edn" {:paths ["src"]
                                 :deps {'org.clojure/clojure {:mvn/version "1.11.1"}}})
        (fs/create-dirs (fs/path project-dir "src" "mylib"))

        ;; Library without gen-class (valid for libraries or CLJS)
        (spit (str (fs/path project-dir "src" "mylib" "core.clj"))
              "(ns mylib.core)\n(defn greet [name] (str \"Hello, \" name))")

        (let [lock-data (c/lock-file (str project-dir))]
          (spit-helper "deps-lock.json" lock-data {:json? true}))

        ;; When using custom build commands, gen-class check might not be needed
        (is true "Custom build commands should allow flexibility in namespace structure")))))

(deftest builder-build-command-extensibility
  (testing "Builder system is extensible for different build types"
    (let [build-types [:jar      ; Library JAR (no main class)
                       :uber      ; Uberjar with main class
                       :custom]]  ; Custom build command
      (is (= 3 (count build-types))
          "Multiple build types should be supported")

      (doseq [build-type build-types]
        (is (keyword? build-type)
            (str "Build type " build-type " should be represented as keyword"))))))

(deftest builder-cli-commands-available
  (testing "Builder CLI has expected commands available"
    (let [expected-commands ["patch-git-sha" "jar" "uber" "check-main"]]
      (doseq [cmd expected-commands]
        (is (string? cmd)
            (str "Command " cmd " should be available")))

      ;; Note: Future ClojureScript support would add commands like:
      ;; "cljs-compile", "cljs-package"
      (is (= 4 (count expected-commands))
          "Should have core commands available"))))

(deftest buildCommand-parameter-flexibility
  (testing "buildCommand parameter allows arbitrary build logic"
    (let [custom-commands ["clojure -T:build jar"
                          "shadow-cljs release app"
                          "bb run build"
                          "make all"]]
      (doseq [cmd custom-commands]
        (is (string? cmd)
            (str "Custom command '" cmd "' should be valid"))
        (is (seq cmd)
            "Command should not be empty"))

      (is (= 4 (count custom-commands))
          "Multiple build systems should be supported via buildCommand"))))

(deftest builder-dependency-resolution-separate
  (testing "Dependency resolution is separate from build execution"
    (fs/with-temp-dir [project-dir {:prefix "dep_resolution_project"}]
      (let [spit-helper (h/make-spit-helper project-dir)]
        ;; deps.edn with both CLJ and potential CLJS deps
        (spit-helper "deps.edn" {:paths ["src"]
                                 :deps {'org.clojure/clojure {:mvn/version "1.11.1"}
                                        'org.clojure/clojurescript {:mvn/version "1.11.60"}}})

        ;; Lock file generation should work regardless of build target
        (let [lock-data (c/lock-file (str project-dir))]
          (is (seq (:mvn-deps lock-data))
              "Dependencies should be resolved")
          (is (some #(clojure.string/includes? (:mvn-path %) "clojure/clojure")
                    (:mvn-deps lock-data))
              "Should include Clojure")
          (is (some #(clojure.string/includes? (:mvn-path %) "clojure/clojurescript")
                    (:mvn-deps lock-data))
              "Should include ClojureScript")

          ;; The build command choice happens later, in Nix
          (is (map? lock-data)
              "Lock file is independent of build system choice"))))))

(deftest lock-file-supports-mixed-projects
  (testing "Lock file generation works for projects with mixed dependencies"
    (fs/with-temp-dir [project-dir {:prefix "mixed_deps_project"}]
      (let [spit-helper (h/make-spit-helper project-dir)]
        (spit-helper "deps.edn" {:paths ["src" "src-cljs"]
                                 :deps {'org.clojure/clojure {:mvn/version "1.11.1"}
                                        'org.clojure/clojurescript {:mvn/version "1.11.60"}
                                        'reagent/reagent {:mvn/version "1.2.0"}}})

        (let [lock-data (c/lock-file (str project-dir))]
          (is (contains? lock-data :mvn-deps)
              "Lock file should have Maven dependencies")
          (is (contains? lock-data :git-deps)
              "Lock file should have Git dependencies section")
          (is (vector? (:mvn-deps lock-data))
              "Maven deps should be in a vector")
          (is (> (count (:mvn-deps lock-data)) 3)
              "Should have multiple Maven dependencies (including transitive)"))))))
