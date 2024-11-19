(ns integration.lock-file-test
  (:require
    [clojure.test :refer [deftest is]]
    [babashka.fs :as fs]
    [cljnix.test-helpers :as h]
    [cljnix.core :as c]
    [matcher-combinators.test]
    [matcher-combinators.matchers :as m]))

(def default-mvn-url "https://repo1.maven.org/maven2/")

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
           (m/embeds [{:lib "squint/squint"
                       :url "https://github.com/squint-cljs/squint"
                       :rev "435db9c953a5c00a37f20fde5b76c0542a44abaa"
                       :git-dir "https/github.com/squint-cljs/squint"
                       :hash "sha256-c6Rfl+wMJpkSnS1DOPwHCQjrNmu+KLJoracqSm2luOk="}])
           (:git-deps (c/lock-file project-dir)))))))

(deftest maestro-build-test
  (fs/with-temp-dir [project-dir {:prefix "dummy_project"}]
    (let [spit-helper (h/make-spit-helper project-dir)]
      (spit-helper "deps.edn"
                   {:deps
                    {'protosens/maestro.plugin.build
                     {:deps/root "module/maestro.plugin.build"
                      :git/sha "b9ecc401e480060aac19c246dc160056452707d7"
                      :git/url   "https://github.com/protosens/monorepo.cljc"}}})
      (is (match?
           (m/embeds [{:lib "protosens/classpath"
                       :url "https://github.com/protosens/monorepo.cljc"
                       :rev "277a4eda9c1b9a847c89f135a39637c206914869"
                       :git-dir "https/github.com/protosens/monorepo.cljc"
                       :hash "sha256-tjBLQ5ivHo9xBriVIK54jYFQQYHGupSU1UWbqEnIEwE="
                       :ancestor? {"5d3ec3037483f09336a79e9e38eb4f64e4b30232" false}}])
           (:git-deps (c/lock-file project-dir)))))))

(deftest maestro-test
  (fs/with-temp-dir [project-dir {:prefix "dummy_project"}]
    (let [spit-helper (h/make-spit-helper project-dir)]
      (spit-helper "deps.edn"
                   {:deps
                    {'protosens/maestro
                     {:deps/root "module/maestro"
                      :git/sha "b9ecc401e480060aac19c246dc160056452707d7"
                      :git/url   "https://github.com/protosens/monorepo.cljc"}}})
      (is (match?
           (m/embeds [{:lib "protosens/maestro"
                       :url "https://github.com/protosens/monorepo.cljc"
                       :rev "b9ecc401e480060aac19c246dc160056452707d7"
                       :git-dir "https/github.com/protosens/monorepo.cljc"
                       :hash "sha256-jYYl5WIeyjmL5yv3CMPua0gtYFkccJCwEYNdnaibIuQ="}])
           (:git-deps (c/lock-file project-dir)))))))

(deftest metrics-test
  (fs/with-temp-dir [project-dir {:prefix "dummy_project"}]
    (let [spit-helper (h/make-spit-helper project-dir)]
      (spit-helper "deps.edn"
                   {:deps
                    {'metrics-clojure/metrics-clojure
                     {:git/url "https://github.com/clj-commons/metrics-clojure"
                      :deps/root "metrics-clojure-core"
                      :git/sha "cb08195d1032c1b500d8b34a355ac4c1aaa21b05"}}})
      (is (match?
           (m/embeds [{:mvn-path "io/dropwizard/metrics/metrics-core/4.0.5/metrics-core-4.0.5.jar"
                       :mvn-repo default-mvn-url
                       :hash "sha256-4x9bwvxY3KzQzzH36vpD07mBhz2sDT8P/rsUVnXxyKg="}])
           (:mvn-deps (c/lock-file project-dir)))))))

(deftest firebase-admin-test
  (fs/with-temp-dir [project-dir {:prefix "dummy_project"}]
    (let [spit-helper (h/make-spit-helper project-dir)]
      (spit-helper "deps.edn" {:deps {'com.google.firebase/firebase-admin {:mvn/version "9.2.0"}}})
      (is (match?
            (m/embeds [{:mvn-path "com/google/firebase/firebase-admin/9.2.0/firebase-admin-9.2.0.jar"
                        :mvn-repo default-mvn-url
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
                        :mvn-repo default-mvn-url}])
            (:mvn-deps (c/lock-file project-dir))))
      (fs/delete-if-exists (fs/expand-home "~/.m2/repository/org/clojure/clojure/1.11.0-alpha1/_remote.repositories"))
      (is (match?
            (m/embeds [{:mvn-path "org/clojure/clojure/1.11.0-alpha1/clojure-1.11.0-alpha1.jar"
                        :mvn-repo default-mvn-url}])
            (:mvn-deps (c/lock-file project-dir)))))))
