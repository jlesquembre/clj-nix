(ns integration.builder-cli-test
  "Characterization tests for `clj-builder` (`cljnix.builder-cli`), the CLI
  that mkCljLib (`jar`) and mkCljBin (`uber`) call.

  The CLI runs in a subprocess, because `-main` calls `shutdown-agents`."
  (:require
    [clojure.test :refer [deftest is testing]]
    [clojure.string :as str]
    [babashka.fs :as fs]
    [babashka.process :as ps]
    [cljnix.test-helpers :as h])
  (:import
    (java.util.jar JarFile)))

(defn- write-project!
  [project-dir]
  (let [spit-helper (h/make-spit-helper project-dir)]
    (fs/create-dirs (fs/path project-dir "src" "demo"))
    ;; The Clojure version on the test classpath is already in the local
    ;; Maven repository, so resolving the temp project needs no network.
    (spit-helper "deps.edn" {:paths ["src"]
                             :deps {'org.clojure/clojure {:mvn/version (clojure-version)}}})
    (spit (str (fs/path project-dir "src" "demo" "core.clj"))
          (str "(ns demo.core\n"
               "  (:gen-class))\n\n"
               "(defn -main [& _args]\n"
               "  (println \"hello\"))\n"))
    ;; Not required by demo.core, so it is AOT-compiled only when every
    ;; namespace in :paths is compiled.
    (spit (str (fs/path project-dir "src" "demo" "util.clj"))
          "(ns demo.util)\n")))

(defn- classpath
  "The test classpath, with relative entries (`src`, `test`) made absolute so
  it still works from the temp project directory."
  []
  (->> (str/split (System/getProperty "java.class.path")
                  (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))
       (map #(str (fs/absolutize %)))
       (str/join java.io.File/pathSeparator)))

(defn- clj-builder!
  "Runs `clj-builder` in `project-dir`, the way the Nix builders run it.
  Returns true when it exits with 0."
  [project-dir & args]
  (let [{:keys [exit err]}
        (apply ps/shell
               {:dir (str project-dir) :out :string :err :string :continue true}
               (str (fs/path (System/getProperty "java.home") "bin" "java"))
               "-cp" (classpath)
               "clojure.main" "-m" "cljnix.builder-cli"
               args)]
    (is (zero? exit) err)))

(defn- jar-entries
  [jar-path]
  (with-open [jar (JarFile. (str jar-path))]
    (set (map #(.getName %) (enumeration-seq (.entries jar))))))

(defn- jar-entry-text
  [jar-path entry]
  (with-open [jar (JarFile. (str jar-path))]
    (slurp (.getInputStream jar (.getEntry jar entry)))))

(defn- main-class
  [jar-path]
  (with-open [jar (JarFile. (str jar-path))]
    (.getValue (.getMainAttributes (.getManifest jar)) "Main-Class")))

(deftest jar-test
  (fs/with-temp-dir [project-dir {:prefix "builder_cli_jar"}]
    (write-project! project-dir)
    (when (clj-builder! project-dir "jar" "demo/demo" "1.0.0")
      (let [jar-path (fs/path project-dir "target" "demo-1.0.0.jar")]
        (is (fs/exists? jar-path))
        (testing "the jar holds the pom and the sources from :paths"
          (is (every? (jar-entries jar-path)
                      ["META-INF/maven/demo/demo/pom.xml"
                       "META-INF/maven/demo/demo/pom.properties"
                       "demo/core.clj"])))
        (testing "pom.properties has no timestamp comment, so the jar is reproducible"
          (let [props (jar-entry-text jar-path "META-INF/maven/demo/demo/pom.properties")]
            (is (str/includes? props "version=1.0.0"))
            (is (not-any? #(str/starts-with? % "#") (str/split-lines props)))))))))

(deftest uber-test
  (fs/with-temp-dir [project-dir {:prefix "builder_cli_uber"}]
    (write-project! project-dir)
    ;; mkCljBin passes `builtins.toJSON null` for unset compileCljOpts,
    ;; javacOpts and uberOpts.
    (when (clj-builder! project-dir "uber" "demo/demo" "1.0.0" "demo.core" "null" "null" "null")
      (let [jar-path (fs/path project-dir "target" "demo-1.0.0.jar")]
        (is (fs/exists? jar-path))
        (testing "the manifest names the main namespace"
          (is (= "demo.core" (main-class jar-path))))
        (testing "every namespace in :paths is AOT-compiled and Clojure is bundled"
          (is (every? (jar-entries jar-path)
                      ["demo/core.class"
                       "demo/core__init.class"
                       "demo/util__init.class"
                       "clojure/core__init.class"])))))))

(deftest uber-compile-clj-opts-test
  (fs/with-temp-dir [project-dir {:prefix "builder_cli_uber_opts"}]
    (write-project! project-dir)
    ;; compileCljOpts arrives as JSON, so :ns-compile holds strings that
    ;; cljnix.build has to turn into symbols for tools.build.
    (when (clj-builder! project-dir "uber" "demo/demo" "1.0.0" "demo.core"
                          "{\"ns-compile\":[\"demo.core\"]}" "null" "null")
      (let [jar-path (fs/path project-dir "target" "demo-1.0.0.jar")
            entries (jar-entries jar-path)]
        (testing ":ns-compile limits AOT compilation to the listed namespaces"
          (is (contains? entries "demo/core__init.class"))
          (is (not (contains? entries "demo/util__init.class")))
          (is (contains? entries "demo/util.clj")
              "namespaces left out of :ns-compile still ship as source"))))))
