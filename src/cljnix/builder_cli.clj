(ns cljnix.builder-cli
  (:require
    [cljnix.utils :as utils]
    [cljnix.build :as build]
    [cljnix.check :as check]))

(defn- check-main-class
  [args]
  (or
   (check/main-gen-class
    (interleave
     [:lib-name :version :main-ns]
     args))
   (throw (ex-info "main-ns class does not specify :gen-class" {:args args}))))

; Internal CLI helpers
(defn -main
  [& [flag & args]]
  (cond
    (= flag "--patch-git-sha")
    (apply utils/expand-shas! args)

    (= flag "--jar")
    (build/jar
      (interleave
        [:lib-name :version]
        args))

    (= flag "--uber")
    (do
      (check-main-class args)
      (build/uber
       (interleave
        [:lib-name :version :main-ns :java-opts]
        args)))

    (= flag "--check-main")
    (check-main-class args))

  (shutdown-agents))
