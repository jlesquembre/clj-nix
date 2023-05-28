(ns cljnix.utils-test
  (:require
    [clojure.test :refer [deftest is use-fixtures testing]]
    [cljnix.utils :as utils]
    [cljnix.test-helpers :as h]
    [clojure.tools.deps.util.maven :as mvn]
    [babashka.fs :as fs]
    [clojure.tools.deps :as deps]))

(def my-deps '{:deps {org.clojure/clojure {:mvn/version "1.11.1"}
                      babashka/fs {:mvn/version "0.1.5"}
                      javax.activation/javax.activation-api {:mvn/version "1.2.0"}}})

(defn deps-cache-fixture [f]
  (h/prep-deps my-deps)
  (f))

(use-fixtures :once deps-cache-fixture)

(deftest mvn-repo-info-test
  (is (= {:mvn-path "org/clojure/clojure/1.11.1/clojure-1.11.1.jar"
          :mvn-repo "https://repo1.maven.org/maven2/"
          :url "https://repo1.maven.org/maven2/org/clojure/clojure/1.11.1/clojure-1.11.1.jar"}
         (utils/mvn-repo-info
           (fs/path @mvn/cached-local-repo "org/clojure/clojure/1.11.1/clojure-1.11.1.jar")
           @mvn/cached-local-repo)))

  (is (= {:mvn-path "org/clojure/pom.contrib/1.1.0/pom.contrib-1.1.0.pom"
          :mvn-repo "https://repo1.maven.org/maven2/"
          :url "https://repo1.maven.org/maven2/org/clojure/pom.contrib/1.1.0/pom.contrib-1.1.0.pom"}
         (utils/mvn-repo-info
           (fs/path @mvn/cached-local-repo "org/clojure/pom.contrib/1.1.0/pom.contrib-1.1.0.pom")
           @mvn/cached-local-repo)))

  (is (= {:mvn-path "babashka/fs/0.1.5/fs-0.1.5.jar"
          :mvn-repo "https://repo.clojars.org/"
          :url "https://repo.clojars.org/babashka/fs/0.1.5/fs-0.1.5.jar"}
         (utils/mvn-repo-info
           (fs/path @mvn/cached-local-repo "babashka/fs/0.1.5/fs-0.1.5.jar")
           @mvn/cached-local-repo))))

(deftest get-parent-test
  (is (= (str (fs/path @mvn/cached-local-repo "org/clojure/pom.contrib/1.1.0/pom.contrib-1.1.0.pom"))
         (utils/get-parent (fs/path @mvn/cached-local-repo "org/clojure/core.specs.alpha/0.2.62/core.specs.alpha-0.2.62.pom"))))
  (is (= nil
         (utils/get-parent (fs/path @mvn/cached-local-repo "org/clojure/core.specs.alpha/0.2.62/core.specs.alpha-0.2.62.jar"))))
  (testing "pom has a different encoding"
    (is (= (str (fs/path @mvn/cached-local-repo "com/sun/activation/all/1.2.0/all-1.2.0.pom"))
           (utils/get-parent (fs/path @mvn/cached-local-repo "javax/activation/javax.activation-api/1.2.0/javax.activation-api-1.2.0.pom"))))))

(def deps-data {:deps {'org.clojure/clojure {:mvn/version "1.11.1"}
                       'io.github.clojure/tools.build {:git/tag "v0.8.1" :git/sha "7d40500"}}
                :aliases {:pod-test
                          {:replace-deps {'com.cognitect/transit-clj {:mvn/version "1.0.329"}
                                          'cognitect/test-runner {:git/url "https://github.com/cognitect-labs/test-runner"
                                                                  :sha "cc75980b43011773162b485f46f939dc5fba91e4"}
                                          'babashka/babashka.pods {:git/url "https://github.com/babashka/babashka.pods"
                                                                   :git/sha "e075b13bfe3666a73f82c12817bdf5f1d6c692e3"}}
                           :main-opts ["-m" "cognitect.test-runner" "-d" "pod-test"]}
                          :build {:extra-paths ["../lib/resources"]
                                  :replace-deps {'io.github.clojure/tools.build {:tag "v0.8.1" :sha "7d40500"}}}}})
(def deps-data-full (-> deps-data
                        (assoc-in [:deps 'io.github.clojure/tools.build :git/sha] "7d40500863818c6f9a6e077b18db305d02149384")
                        (update-in [:deps 'io.github.clojure/tools.build] #(dissoc % :git/tag))
                        (assoc-in [:aliases :build :replace-deps 'io.github.clojure/tools.build :sha] "7d40500863818c6f9a6e077b18db305d02149384")
                        (update-in [:aliases :build :replace-deps 'io.github.clojure/tools.build] #(dissoc % :tag))))

(def lock-data {"lock-version" 3,
                "git-deps" [{"lib" "io.github.babashka/fs",
                             "url" "https://github.com/babashka/fs.git",
                             "rev" "dc73460e63ff10c701c353227f2689b3d7c33a43",
                             "git-dir" "https/github.com/babashka/fs",
                             "hash" "sha256-D2Xi8DQ7lVLCj6YzuH1IXkxAAaRWuqHBzA05n0otiXw="}
                            {"lib" "io.github.clojure/tools.build",
                             "url" "https://github.com/clojure/tools.build.git",
                             "rev" "7d40500863818c6f9a6e077b18db305d02149384",
                             :tag "v0.8.1"
                             "git-dir" "https/github.com/clojure/tools.build",
                             "hash" "sha256-nuPBuNQ4su6IAh7rB9kX/Iwv5LsV+FOl/uHro6VcL7c="}]
                "mvn-deps" []})


(deftest paths-to-gitdeps-test
  (is (= [[:deps 'io.github.clojure/tools.build]
          [:aliases :pod-test :replace-deps 'cognitect/test-runner]
          [:aliases :pod-test :replace-deps 'babashka/babashka.pods]
          [:aliases :build :replace-deps 'io.github.clojure/tools.build]]
         (utils/paths-to-gitdeps deps-data))))


(deftest partial-sha-test
  (is (= false (utils/full-sha? (get-in deps-data [:deps 'io.github.clojure/tools.build]))))
  (is (= true (utils/full-sha? (get-in deps-data [:aliases :pod-test :replace-deps 'cognitect/test-runner]))))
  (is (= true (utils/full-sha? (get-in deps-data [:aliases :pod-test :replace-deps 'babashka/babashka.pods]))))
  (is (= false (utils/full-sha? (get-in deps-data [:aliases :build :replace-deps 'io.github.clojure/tools.build])))))


(deftest expand-sha-tests
  (fs/with-temp-dir [project-dir {:prefix "dummy_project"}]
    (let [spit-helper (h/make-spit-helper project-dir)]
      (spit-helper "deps.edn" deps-data)
      (spit-helper "deps-lock.json" lock-data {:json? true})
      (utils/expand-shas! project-dir)
      (is (= deps-data-full
             (deps/slurp-deps (fs/file project-dir "deps.edn")))))))

(comment
  (def spit-helper (h/make-spit-helper "/tmp/foo"))
  (spit-helper "deps.edn" deps-data)
  (spit-helper "deps-lock.json" lock-data {:json? true}))
