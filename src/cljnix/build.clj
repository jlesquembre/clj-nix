(ns cljnix.build
  "Backward compatibility shim for JVM build functions.

  This namespace maintains backward compatibility with existing code.
  New code should use cljnix.build.jvm or cljnix.build.core directly.

  All functions delegate to cljnix.build.jvm."
  (:require
    [cljnix.build.jvm :as jvm]))

;; Re-export JVM build functions for backward compatibility

(def class-dir
  "Target directory for compiled classes."
  jvm/class-dir)

(defn remove-timestamp!
  "Remove timestamp from Maven pom.properties for reproducible builds.
  DEPRECATED: Use cljnix.build.jvm/remove-timestamp! directly."
  [root-dir lib-name]
  (jvm/remove-timestamp! root-dir lib-name))

(defn common-compile-options
  "Build common options for compilation.
  DEPRECATED: Use cljnix.build.jvm/common-compile-options directly."
  [opts]
  (jvm/common-compile-options opts))

(defn uber
  "Build an uberjar.
  DEPRECATED: Use cljnix.build.jvm/uber directly."
  [opts]
  (jvm/uber opts))

(defn jar
  "Build a library JAR.
  DEPRECATED: Use cljnix.build.jvm/jar directly."
  [opts]
  (jvm/jar opts))
