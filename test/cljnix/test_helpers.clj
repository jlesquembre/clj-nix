(ns cljnix.test-helpers
  (:require
    [clojure.pprint :as pp]
    [clojure.data.json :as json]
    [clojure.tools.deps.cli.api :as tools]
    [clojure.tools.deps :as deps]
    [babashka.fs :as fs]))

;; Constants
(def default-mvn-url "https://repo1.maven.org/maven2/")

;; Private helpers
(defn- deps-file
  [content]
  (let [f (str (fs/create-temp-file {:prefix "deps_" :suffix ".edn"}))]
    (spit f content)
    f))

;; Deps management
(defn prep-deps
  "Prep dependencies from a deps map (downloads them to local cache)."
  [deps]
  (let [f (deps-file deps)]
    (tools/prep {:user nil :project f})
    (fs/delete f)))

(defn basis
  "Create a basis from a deps map."
  [deps]
  (let [f (deps-file deps)
        basis (deps/create-basis {:user nil :project f})]
    (fs/delete f)
    basis))

;; Project setup helpers
(defn make-spit-helper
  "Create a helper function for spitting files into a project directory.
   Supports both EDN and JSON output."
  [project-dir]
  (fn spit-helper
    [path content & {:keys [json?]}]
    (binding [*print-namespace-maps* false]
      (spit (str (fs/path project-dir path))
            (if json?
              (json/write-str content
                :escape-slash false
                :escape-unicode false
                :escape-js-separators false)
              (with-out-str (pp/pprint content)))))))

;; Test fixtures
(defn deps-cache-fixture
  "Fixture that preps dependencies before running tests.
   Usage: (use-fixtures :once (deps-cache-fixture all-deps))"
  [deps-map]
  (fn [f]
    (prep-deps deps-map)
    (f)))

(defn with-temp-project
  "Execute test-fn in the context of a temporary project directory.
   The test-fn receives [project-dir spit-helper] as arguments."
  [test-fn]
  (fs/with-temp-dir [project-dir {:prefix "test_project_"}]
    (let [spit-helper (make-spit-helper project-dir)]
      (test-fn project-dir spit-helper))))

;; Macro for defining lock-file tests
(defmacro deftest-lock-file
  "Define a test that creates a temp project, writes deps.edn, and asserts lock-file results.

   Usage:
   (deftest-lock-file my-test
     {:deps {'my/lib {:mvn/version \"1.0.0\"}}}
     (is (match? (m/embeds [{...}]) (:mvn-deps (c/lock-file project-dir)))))"
  [test-name deps-map & body]
  `(clojure.test/deftest ~test-name
     (with-temp-project
       (fn [~'project-dir ~'spit-helper]
         (~'spit-helper "deps.edn" ~deps-map)
         ~@body))))

;; Helper functions for snapshot assertions
(defn normalize-git-url
  "Normalize git URL by removing .git suffix if present.
   Git URLs with and without .git are equivalent, but tools.deps may
   normalize them differently across environments (Linux vs macOS, different versions)."
  [url]
  (when url
    (clojure.string/replace url #"\.git$" "")))

(defn normalize-git-dep-urls
  "Normalize :url field in git dependency maps for comparison.
   Useful for test assertions where URL format may vary between environments."
  [deps]
  (mapv #(update % :url normalize-git-url) deps))

(defn normalize-lock-file-git-urls
  "Normalize :url fields in a lock file's :git-deps for comparison."
  [lock-file]
  (update lock-file :git-deps normalize-git-dep-urls))

(defn find-dep
  "Find a dependency by lib name in a collection of deps."
  [lib deps]
  (first (filter #(= lib (:lib %)) deps)))

(defn find-pom
  "Find a POM file by lib name prefix in a collection of deps."
  [lib-prefix deps]
  (first (filter (fn [dep]
                   (and (clojure.string/starts-with? (:mvn-path dep "") (str lib-prefix))
                        (clojure.string/ends-with? (:mvn-path dep "") ".pom")))
                 deps)))
