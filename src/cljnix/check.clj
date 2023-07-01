(ns cljnix.check
  (:require
   [cljnix.build :as build]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [rewrite-clj.zip :as z]))

(defn- clj-file [main-ns]
  (str
   (->>
    (string/split main-ns #"\.")
    (map #(string/replace % "-" "_"))
    (string/join "/")) ".clj"))

(defn- gen-class-node? [node]
  (and
   (= :list (:tag node))
   (some #(= :gen-class (:k %)) (:children node))))

(defn check-src-dirs
  "search for a main-ns clojure file with a (:gen-class) list in the ns form"
  [src-dirs main-ns]
  (let [main-clj-file (clj-file main-ns)]
    (when-let [f (->> src-dirs
                      (map #(io/file % main-clj-file))
                      (some #(when (.exists %) %)))]
      (let [zloc (z/down (z/of-string (slurp f)))]
        ;; zloc will be pointing at the first child of the first non-whitespace non-comment expression in the file
        (and
         (= 'ns (-> zloc z/node :value))
         (->> (iterate z/right zloc)
              (take-while #(not (nil? %)))
              (some (comp gen-class-node? z/node))))))))

(defn main-gen-class
  "assumes the namespace is the first sexpr in the main clj file"
  [{:keys [main-ns] :as opts}]
  (println "check" main-ns "for :gen-class")
  (let [{:keys [src-dirs]}
        (build/common-compile-options opts)]
    (check-src-dirs src-dirs main-ns)))
