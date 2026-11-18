(ns cljnix.builder-cli
  (:require
   [cljnix.utils :as utils]
   [cljnix.build :as build]
   [cljnix.check :as check]
   [clojure.data.json :as json]))

(defn- check-main-class
  [args]
  (let [[_ _ main-ns] args
        _ (when (empty? main-ns)
            (throw (ex-info "main-ns is required, e.g. demo.core" {:args args})))
        {:keys [status file src-dirs]}
        (check/main-gen-class
         (interleave
          [:lib-name :version :main-ns]
          args))]
    (case status
      :ok true
      :not-found
      (throw (ex-info
              (str "main-ns " main-ns " not found: no " file " in the :paths "
                   (pr-str (vec src-dirs)) " of deps.edn. "
                   "Check the main-ns name; with flakes, is the file tracked by git?")
              {:args args :file file :src-dirs src-dirs}))
      :no-gen-class
      (throw (ex-info
              (str "main-ns " main-ns " does not specify :gen-class in its ns form")
              {:args args :file file :src-dirs src-dirs})))))

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
      (-> (zipmap [:lib-name :version :main-ns :compile-clj-opts :javac-opts :uber-opts] args)
          (update :compile-clj-opts str->json)
          (update :javac-opts str->json)
          (update :uber-opts str->json)
          (build/uber)))

    (= cmd "check-main")
    (check-main-class args))

  (shutdown-agents))
