(ns cljnix.builder-cli
  (:require
    [cljnix.utils :as utils]
    [cljnix.build :as build]
    [cljnix.check :as check]))

(defn- check-main-class
  [& [_ value & more :as args]]
  (or
   (check/main-gen-class
    (interleave
     [:lib-name :version :main-ns]
     (apply vector value more)))
   (throw (ex-info "main-ns class does not specify :gen-class" {:args args}))))

; Internal CLI helpers
(defn -main
  [& [flag value & more :as args]]
  (cond
    (= flag "--patch-git-sha")
    (utils/expand-shas! value)

    (= flag "--jar")
    (build/jar
      (interleave
        [:lib-name :version]
        (apply vector value more)))

    (= flag "--uber")
    (do
      (apply check-main-class args)
      (build/uber
       (interleave
        [:lib-name :version :main-ns :java-opts]
        (apply vector value more))))

    (= flag "--check-main")
    (apply check-main-class args))

  (shutdown-agents))
