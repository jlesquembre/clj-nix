(ns cljnix.tasks
  (:require
    [clojure.spec.alpha :as s]
    [clojure.core.specs.alpha :as specs]
    [edamame.core :refer [parse-string-all]])
  (:gen-class))

(defn -main
  [& args]
  (->> (first args)
       (slurp)
       (parse-string-all)
       (filter #(= 'defn (first %)))
       (mapv #(s/conform ::specs/defn-args (rest %)))
       prn))

(comment
  (-main "/home/jlle/projects/clj-nix/tasks.clj"))
