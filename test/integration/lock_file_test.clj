(ns integration.lock-file-test
  (:require
    [clojure.test :refer [deftest is]]
    [babashka.fs :as fs]
    [cljnix.test-helpers :as h]
    [cljnix.core :as c]
    [matcher-combinators.test]
    [matcher-combinators.matchers :as m]))

(deftest manual-manifest
  (fs/with-temp-dir [project-dir {:prefix "dummy_project"}]
    (let [spit-helper (h/make-spit-helper project-dir)]
      (spit-helper "deps.edn"
                   {:deps
                    {'squint/squint
                     {:deps/manifest :deps
                      :git/sha "435db9c953a5c00a37f20fde5b76c0542a44abaa"
                      :git/url "https://github.com/squint-cljs/squint"}}})
      (is (match?
           (m/embeds [{:mvn-path "borkdude/edamame/1.0.16/edamame-1.0.16.jar"
                       :mvn-repo "https://repo.clojars.org/"
                       :hash "sha256-Y4GkQ+c5+EwR3wcqUoo11jVRO8O2rP3Um5CgqlN018A=" }])
           (:mvn-deps (c/lock-file project-dir)))))))

(deftest metrics-test
  (fs/with-temp-dir [project-dir {:prefix "dummy_project"}]
    (let [spit-helper (h/make-spit-helper project-dir)]
      (spit-helper "deps.edn"
                   {:deps
                    {'metrics-clojure/metrics-clojure
                     {:git/url "https://github.com/clj-commons/metrics-clojure"
                      :deps/root "metrics-clojure-core"
                      :git/sha "532fc437e734b049a54cb9a198f201dc5f47328e"}}})
      (is (match?
           (m/embeds [{:mvn-path "io/dropwizard/metrics/metrics-core/4.0.5/metrics-core-4.0.5.jar"
                       :mvn-repo "https://repo.maven.apache.org/maven2/"
                       :hash "sha256-4x9bwvxY3KzQzzH36vpD07mBhz2sDT8P/rsUVnXxyKg="}])
           (:mvn-deps (c/lock-file project-dir)))))))

(deftest firebase-admin-test
  (fs/with-temp-dir [project-dir {:prefix "dummy_project"}]
    (let [spit-helper (h/make-spit-helper project-dir)]
      (spit-helper "deps.edn" {:deps {'com.google.firebase/firebase-admin {:mvn/version "9.2.0"}}})
      (is (match?
            (m/embeds [{:mvn-path "com/google/firebase/firebase-admin/9.2.0/firebase-admin-9.2.0.jar"
                        :mvn-repo "https://repo.maven.apache.org/maven2/"
                        :hash "sha256-pPTZGop6SjnQZqJ4tigwrUxpF3ESo6ALgns6CpoRgEA="}])
            (:mvn-deps (c/lock-file project-dir)))))))

(deftest reitit-test
  (fs/with-temp-dir [project-dir {:prefix "dummy_project"}]
    (let [spit-helper (h/make-spit-helper project-dir)]
      (spit-helper "deps.edn" {:deps {'metosin/reitit {:mvn/version "0.5.15"}}})
      (is (match?
            (m/embeds [{:mvn-path "metosin/reitit-core/0.5.15/reitit-core-0.5.15.jar"
                        :mvn-repo "https://repo.clojars.org/"
                        :hash "sha256-vzZFqtQ6YeO6BQ2F11el0+VT6kLxza4EpHmhtHeBa5g="}])
            (:mvn-deps (c/lock-file project-dir)))))))

(deftest jitpack-extra-mvn-repos-test
  (fs/with-temp-dir [project-dir {:prefix "dummy_project"}]
    (let [spit-helper (h/make-spit-helper project-dir)]
      (spit-helper "deps.edn" {:mvn/repos {"jitpack" {:url "https://jitpack.io"}}
                               :deps {'com.github.kenglxn.qrgen/javase {:mvn/version "3.0.1"}}})
      (is (match?
            (m/embeds [{:mvn-path "com/github/kenglxn/qrgen/javase/3.0.1/javase-3.0.1.jar"
                        :mvn-repo "https://jitpack.io/"
                        :hash "sha256-GTOA6l5NLkhBXTv3mxsacna/xDfap9hnr7m/uN4iuvk="}])
            (:mvn-deps (c/lock-file project-dir)))))))

(deftest invalid-pom-test
  (fs/with-temp-dir [project-dir {:prefix "dummy_project"}]
    (let [spit-helper (h/make-spit-helper project-dir)]
      (spit-helper "deps.edn" {:deps {'url-normalizer/url-normalizer {:mvn/version "0.5.3-1"}}})
      (is (match?
            (m/embeds [{:mvn-path "url-normalizer/url-normalizer/0.5.3-1/url-normalizer-0.5.3-1.jar"
                        :hash "sha256-i+tOYpIMVhSCZKAKyycaBmKKFsmoxd1G9FlOlVW0RdA="}
                       {:mvn-path "org/apache/geronimo/specs/specs/1.1/specs-1.1.pom"
                        :hash "sha256-C7dXlXPQwHQ8O4HAN297KRrPb/OwP0DQvULK2gOjYlA="}])
            (:mvn-deps (c/lock-file project-dir)))))))

(deftest invalid-_remote.repositories
  (fs/with-temp-dir [project-dir {:prefix "dummy_project"}]
    (let [spit-helper (h/make-spit-helper project-dir)]
      (spit-helper "deps.edn" {:deps {'org.clojure/clojure {:mvn/version "1.11.0-alpha1"}}})
      (is (match?
            (m/embeds [{:mvn-path "org/clojure/clojure/1.11.0-alpha1/clojure-1.11.0-alpha1.jar"
                        :mvn-repo "https://repo.maven.apache.org/maven2/"}])
            (:mvn-deps (c/lock-file project-dir))))
      (fs/delete-if-exists (fs/expand-home "~/.m2/repository/org/clojure/clojure/1.11.0-alpha1/_remote.repositories"))
      (is (match?
            (m/embeds [{:mvn-path "org/clojure/clojure/1.11.0-alpha1/clojure-1.11.0-alpha1.jar"
                        :mvn-repo "https://repo.maven.apache.org/maven2/"}])
            (:mvn-deps (c/lock-file project-dir)))))))
