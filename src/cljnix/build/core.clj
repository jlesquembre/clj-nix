(ns cljnix.build.core
  "Core build abstractions that are language-agnostic.

  This namespace defines protocols and common utilities for building
  Clojure projects. Language-specific implementations should be in
  separate namespaces (e.g., cljnix.build.jvm).")

;; Protocol for language-specific builders
(defprotocol Builder
  "Protocol for language-specific build implementations."

  (build-jar [this opts]
    "Build a library JAR.

    opts map should contain:
    - :lib-name - Library name (symbol or string)
    - :version - Version string
    - Additional language-specific options")

  (build-uberjar [this opts]
    "Build an uberjar (executable JAR with dependencies).

    opts map should contain:
    - :lib-name - Library name
    - :version - Version string
    - :main-ns - Main namespace (for executable)
    - Additional language-specific options")

  (get-src-paths [this project-file]
    "Extract source paths from project configuration file.
    Returns a vector of path strings."))

;; Common utilities that work across languages

(defn normalize-lib-name
  "Normalize a library name to a qualified symbol.

  Examples:
    (normalize-lib-name 'myapp) => 'myapp/myapp
    (normalize-lib-name 'myorg/myapp) => 'myorg/myapp
    (normalize-lib-name \"myapp\") => 'myapp/myapp"
  [lib-name]
  (let [lib-name (if (symbol? lib-name) lib-name (symbol lib-name))]
    (if (qualified-symbol? lib-name)
      lib-name
      (symbol (name lib-name) (name lib-name)))))

(defn output-jar-path
  "Generate output JAR path from lib-name and version.

  Returns a path like: target/myapp-1.0.0.jar"
  [lib-name version]
  (format "target/%s-%s.jar"
          (name (normalize-lib-name lib-name))
          version))

(defn validate-build-opts
  "Validate common build options.
  Returns validated opts or throws with helpful error message."
  [opts required-keys]
  (doseq [k required-keys]
    (when-not (contains? opts k)
      (throw (ex-info (str "Missing required build option: " k)
                      {:required required-keys
                       :provided (keys opts)
                       :missing k}))))
  opts)
