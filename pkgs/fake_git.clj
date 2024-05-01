(ns fake-git
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.cli :as cli]))

(defn rev-data [git-dir]
  (->> (fs/glob (str git-dir "/revs") "*")
       (mapv #(json/parse-string
               (slurp (str %))
               true))))

(defn result [args]
  (let [git-dir (-> args :opts :git-dir)
        rest-args (:args args)]
    (cond
      (= (first rest-args) "fetch") nil

      (= rest-args ["tag" "--sort=v:refname"])
      (->> (rev-data git-dir)
           (map :tag)
           (remove nil?)
           vec)

      (= (first rest-args) "rev-parse")
      (let [commit (str/replace (second rest-args) #"\^\{commit\}" "")]
        (->> (rev-data git-dir)
             (filter #(or (let [tag (:tag %)]
                            (and tag (= tag commit)))
                          (str/starts-with? (:rev %) commit)))
             (map :rev)
             (take 1)
             vec))

      (and (= (first rest-args) "merge-base")
           (= (second rest-args) "--is-ancestor"))
      (let [rev (nth rest-args 2)
            ancestor (nth rest-args 3)
            rev-f (str git-dir "/revs/" rev)
            rev-d (json/parse-string (slurp rev-f))]
        (if (get-in rev-d ["ancestor?" ancestor])
          [0]
          [1]))

      :else
      (throw (Exception. (str "fake-git: unknown git command - " args))))))

(defn -main []
  (let [res (result (cli/parse-args *command-line-args*))]
    (doseq [line res]
      (cond (= line 0) (System/exit 0)
            (= line 1) (System/exit 1)
            :else (println line)))))

(-main)
