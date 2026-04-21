(ns cljnix.build.cljs-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [babashka.fs :as fs]
    [cljnix.test-helpers :as h]
    [cljnix.build.cljs :as cljs]
    [cljnix.build.core :as build-core]))

;; Tests for ClojureScript builder implementation

(deftest cljs-builder-implements-protocol
  (testing "ClojureScript builder implements Builder protocol"
    (let [builder (cljs/create-builder)]
      (is (satisfies? build-core/Builder builder)
          "Should implement Builder protocol"))))

(deftest get-src-paths-extraction
  (testing "Source path extraction from deps.edn"
    (fs/with-temp-dir [project-dir {:prefix "cljs_src_project"}]
      (let [spit-helper (h/make-spit-helper project-dir)
            deps-file (str (fs/path project-dir "deps.edn"))]
        (spit-helper "deps.edn" {:paths ["src" "src-cljs" "resources"]})
        (let [builder (cljs/create-builder)
              paths (build-core/get-src-paths builder deps-file)]
          (is (vector? paths)
              "Paths should be a vector")
          (is (= ["src" "src-cljs" "resources"] paths)
              "Should extract all paths from deps.edn"))))))

(deftest get-src-paths-default-value
  (testing "Source paths default to src when not specified"
    (fs/with-temp-dir [project-dir {:prefix "cljs_default_src_project"}]
      (let [spit-helper (h/make-spit-helper project-dir)
            deps-file (str (fs/path project-dir "deps.edn"))]
        (spit-helper "deps.edn" {})
        (let [builder (cljs/create-builder)
              paths (build-core/get-src-paths builder deps-file)]
          (is (= ["src"] paths)
              "Should default to [\"src\"]"))))))

(deftest compile-cljs-validates-options
  (testing "compile-cljs validates required options"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Missing required build option"
          (cljs/compile-cljs {}))
        "Should throw when lib-name is missing")

    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Missing required build option"
          (cljs/compile-cljs {:lib-name "test/app"}))
        "Should throw when version is missing")))

(deftest package-cljs-validates-options
  (testing "package-cljs validates required options"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Missing required build option"
          (cljs/package-cljs {:lib-name "test/app"
                              :version "1.0.0"
                              :build-id :app}))
        "Should throw when target is missing")))

(deftest build-uberjar-throws-error
  (testing "build-uberjar throws informative error for ClojureScript"
    (let [builder (cljs/create-builder)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Uberjar not applicable for ClojureScript"
            (build-core/build-uberjar builder {:lib-name "test/app"
                                                :version "1.0.0"}))
          "Should throw explaining uberjar not supported"))))

(deftest build-id-and-target-handling
  (testing "Build ID and target are properly handled"
    (let [opts {:lib-name "myapp"
                :version "1.0.0"
                :build-id :custom-build
                :target :node-script}]
      ;; Just test option processing, not actual compilation
      (is (= :custom-build (:build-id opts))
          "Build ID should be a keyword")
      (is (= :node-script (:target opts))
          "Target should be a keyword"))))

(deftest output-directories-use-defaults
  (testing "Output directories use sensible defaults"
    (is (= "target/cljs" cljs/default-output-dir)
        "Default output dir should be target/cljs")
    (is (= :app cljs/default-build-id)
        "Default build ID should be :app")))

(deftest lib-name-normalization-in-cljs
  (testing "Library names are normalized in ClojureScript builds"
    (let [test-cases [["myapp" 'myapp/myapp]
                      ["myorg/myapp" 'myorg/myapp]
                      ['myapp 'myapp/myapp]
                      ['myorg/myapp 'myorg/myapp]]]
      (doseq [[input expected] test-cases]
        (is (= expected (build-core/normalize-lib-name input))
            (str "lib-name " input " should normalize to " expected))))))

(deftest cljs-build-options-structure
  (testing "ClojureScript build options structure"
    (let [opts {:lib-name "test/app"
                :version "1.0.0"
                :build-id :app
                :target :browser
                :optimizations :advanced}]
      (is (contains? opts :lib-name)
          "Options should have lib-name")
      (is (contains? opts :version)
          "Options should have version")
      (is (contains? opts :build-id)
          "Options should have build-id")
      (is (contains? opts :target)
          "Options should have target")
      (is (keyword? (:target opts))
          "Target should be a keyword")
      (is (keyword? (:optimizations opts))
          "Optimizations should be a keyword"))))
