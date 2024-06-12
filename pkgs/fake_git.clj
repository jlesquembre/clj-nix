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
      (= (first rest-args) "fetch") {:exit 0}

      (= rest-args ["tag" "--sort=v:refname"])
      {:exit 0
       :out
       (->> (rev-data git-dir)
            (eduction
             (map :tag)
             (map str/trim)
             (remove str/blank?)
             (remove nil?))
            vec)}

      (= (first rest-args) "rev-parse")
      (let [commit (str/replace (second rest-args) #"\^\{commit\}" "")]
        {:exit 0
         :out
         (->> (rev-data git-dir)
              (eduction
               (filter #(or (let [tag (:tag %)]
                              (and tag (= tag commit)))
                            (str/starts-with? (:rev %) commit)))
               (map :rev)
               (take 1))
              vec)})

      (and (= (first rest-args) "merge-base")
           (= (second rest-args) "--is-ancestor"))
      (let [rev (nth rest-args 2)
            ancestor (nth rest-args 3)
            rev-f (str git-dir "/revs/" rev)
            rev-d (json/parse-string (slurp rev-f))]
        (if (get-in rev-d ["ancestor?" ancestor])
          {:exit 0}
          {:exit 1}))

      :else
      (throw (Exception. (str "fake-git: unknown git command - " args))))))

(defn main* [args]
  (result (cli/parse-args args)))

(defn main []
  (when (= *file* (System/getProperty "babashka.file"))
    (let [{:keys [exit out] :as res} (main* *command-line-args*)]
      (if (= 0 exit)
        (->> out (str/join "\n") println)
        (System/exit exit)))))
(main)
