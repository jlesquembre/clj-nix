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
  "Search src-dirs for the main-ns clojure file and check that its ns form
  has a (:gen-class) list. Returns :ok, :not-found (no file for main-ns in
  any of src-dirs) or :no-gen-class."
  [src-dirs main-ns]
  (let [main-clj-file (clj-file main-ns)]
    (if-let [f (->> src-dirs
                    (map #(io/file % main-clj-file))
                    (some #(when (.exists %) %)))]
      (let [zloc (z/down (z/of-string (slurp f)))]
        ;; zloc will be pointing at the first child of the first non-whitespace non-comment expression in the file
        (if (and
             (= 'ns (-> zloc z/node :value))
             (->> (iterate z/right zloc)
                  (take-while #(not (nil? %)))
                  (some (comp gen-class-node? z/node))))
          :ok
          :no-gen-class))
      :not-found)))

(defn main-gen-class
  "assumes the namespace is the first sexpr in the main clj file.
  Returns {:status (:ok, :not-found or :no-gen-class), :file, :src-dirs}"
  [{:keys [main-ns] :as opts}]
  (println "check" main-ns "for :gen-class")
  (let [{:keys [src-dirs]}
        (build/common-compile-options opts)]
    {:status (check-src-dirs src-dirs main-ns)
     :file (clj-file main-ns)
     :src-dirs src-dirs}))
