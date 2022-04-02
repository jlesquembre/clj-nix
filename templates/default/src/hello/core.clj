(ns hello.core
  (:require
    [clojure.string :as string])
  (:gen-class))

(defn -main
  [& args]
  (println (str "Hello from " (string/upper-case "clojure!!!"))))
