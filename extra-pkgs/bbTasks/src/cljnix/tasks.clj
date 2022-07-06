(ns cljnix.tasks
  (:require
    [clojure.spec.alpha :as s]
    [clojure.core.specs.alpha :as specs]
    [edamame.core :refer [parse-string-all]]
    [charred.api :as charred])
  (:gen-class))

(defn parse-src
  [file]
  (let [src (-> file
                slurp
                (parse-string-all {:syntax-quote true}))]
    {:ns (->> src
           (filter #(= 'ns (first %)))
           first
           second)
     :tasks (->> src
                 (filter #(= 'defn (first %)))
                 (map #(s/conform ::specs/defn-args (rest %)))
                 (map #(select-keys % [:fn-name :docstring])))}))
(defn -main
  [& args]
  (charred/write-json *out*
                      (parse-src (first args))))

(comment
  (parse-src "tasks.clj")
  (-main "tasks.clj"))
