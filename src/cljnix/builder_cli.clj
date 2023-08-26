(ns cljnix.builder-cli
  (:require
    [cljnix.utils :as utils]
    [cljnix.build :as build]
    [cljnix.check :as check]
    [clojure.data.json :as json]))

(defn- check-main-class
  [args]
  (or
   (check/main-gen-class
    (interleave
     [:lib-name :version :main-ns]
     args))
   (throw (ex-info "main-ns class does not specify :gen-class" {:args args}))))

(defn- str->json
  [s]
  (json/read-str s :key-fn keyword))

; Internal CLI helpers
(defn -main
  [& [cmd & args]]
  (cond
    (= cmd "patch-git-sha")
    (apply utils/expand-shas! args)

    (= cmd "jar")
    (build/jar
      (interleave
        [:lib-name :version]
        args))

    (= cmd "uber")
    (do
      (check-main-class args)
      (-> (zipmap [:lib-name :version :main-ns :compile-clj-opts :javac-opts] args)
          (update :compile-clj-opts str->json)
          (update :javac-opts str->json)
          (build/uber)))

    (= cmd "check-main")
    (check-main-class args))

  (shutdown-agents))
