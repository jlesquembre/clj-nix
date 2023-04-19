(ns cljnix.check
  (:require
   [cljnix.build :as build]
   [clojure.string :as string]))

(defn- clj-file [main-ns]
  (str
   (->>
    (string/split main-ns #"\.")
    (map #(string/replace % "-" "_"))
    (string/join "/")) ".clj"))

(defn main-gen-class [{:keys [main-ns] :as opts}]
  (let [{:keys [src-dirs]}
        (build/common-compile-options opts)
        main-clj-file (clj-file main-ns)   

        ]))

(main-gen-class
 {:main-ns ""
  :lib-name ""
  :version ""})
