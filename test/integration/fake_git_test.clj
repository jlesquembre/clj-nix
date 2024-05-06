(ns integration.fake-git-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [babashka.fs :as fs]
   [cljnix.test-helpers :as h]
   [cljnix.nix :as nix]
   [matcher-combinators.test]
   [clojure.java.shell :as sh]
   [clojure.string :as str])
  )

(defn- run-fake-git [project-dir cmd]
  (sh/sh
   "bash" "-c"
   (str "nix run .#fake-git -- --git-dir " project-dir " " cmd)))

(defn- fake-git-test [revs cmd expected get-result]
  (fs/with-temp-dir [project-dir {:prefix "gitlibs"}]
    (let [spit-helper (h/make-spit-helper project-dir)]
      (fs/create-dirs (fs/file project-dir "revs"))
      (doseq [r revs]
        (spit-helper (str "revs/" (:rev r))
                     r
                     :json? true))
      (is
       (= expected
          (get-result (run-fake-git project-dir cmd)))))))

(deftest fake-git-tags-test
  (fake-git-test
   [{:rev "rev1"
     :tag "v0.0.1"}
    {:rev "rev2"
     :tag "v0.0.2"}]
    "tag --sort=v:refname"
   ["v0.0.1"
    "v0.0.2"]
   #(-> % :out str/split-lines)))

(deftest fake-git-rev-parse-tag-test
  (fake-git-test
   [{:rev "rev1"
     :tag "v0.0.1"}
    {:rev "rev2"
     :tag "v0.0.2"}]
    "rev-parse v0.0.1^{commit}"
   ["rev1"]
   #(-> % :out str/split-lines)))

(deftest fake-git-rev-parse-short-sha-test
  (fake-git-test
   [{:rev "rev1abc"
     :tag "v0.0.1"}
    {:rev "rev2abc"
     :tag "v0.0.2"}]
    "rev-parse rev2^{commit}"
   ["rev2abc"]
   #(-> % :out str/split-lines)))

(deftest fake-git-rev-parse-full-sha-test
  (fake-git-test
   [{:rev "rev1abc"
     :tag "v0.0.1"}
    {:rev "rev2abc"
     :tag "v0.0.2"}]
    "rev-parse rev2abc^{commit}"
   ["rev2abc"]
   #(-> % :out str/split-lines)))

(deftest fake-git-is-ancestor-test
  (fake-git-test
   [{:rev "rev1abc"
     :tag "v0.0.1"
     :ancestor? {"rev2abc" true}}
    {:rev "rev2abc"
     :tag "v0.0.2"}]
    "merge-base --is-ancestor rev1abc rev2abc"
   0
   #(-> % :exit)))

(deftest fake-git-is-not-ancestor-test
  (fake-git-test
   [{:rev "rev1abc"
     :tag "v0.0.1"
     :ancestor? {"rev2abc" false}}
    {:rev "rev2abc"
     :tag "v0.0.2"}]
    "merge-base --is-ancestor rev1abc rev2abc"
   1
   #(-> % :exit)))
