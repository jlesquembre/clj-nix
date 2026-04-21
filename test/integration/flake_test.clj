(ns integration.flake-test
  "Integration tests for flake-based project setup and builds."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [babashka.fs :as fs]
    [clojure.string :as string]
    [clojure.java.shell :refer [sh] :as shell]))

(comment
  "Example tests for external demo project - kept for reference"

  (def require-jdbc "(require '[next.jdbc])")
  (def demo-url "github:jlesquembre/clj-demo-project")

  (deftest demo-mkCljBin-test
    (is (= 0
           (:exit (sh "nix" "build" (str demo-url "#clj-tuto")))))
    (is (string/includes?
          (:out (sh "./result/bin/clj-tuto"))
          "Hello from CLOJURE")))

  (deftest demo-mkCljLib-test
    (is (= 0 (:exit (sh "nix" "build" (str demo-url "#clj-lib"))))))

  (deftest demo-customJdk-test
    (is (= 0
           (:exit (sh "nix" "build" (str demo-url "#jdk-tuto")))))
    (is (string/includes?
          (:out (sh "./result/bin/clj-tuto"))
          "Hello from CLOJURE")))

  (deftest demo-mkGraalBin-test
    (is (= 0
           (:exit (sh "nix" "build" (str demo-url "#graal-tuto")))))
    (is (string/includes?
          (:out (sh "./result/bin/clj-tuto"))
          "Hello from CLOJURE")))

  (deftest demo-jvm-container-test
    (is (= 0 (:exit (sh "nix" "build" (str demo-url "#clj-container")))))
    (is (= 0 (:exit (sh "docker" "load" "-i" "result"))))
    (is (string/includes?
          (:out (sh "docker" "run" "--rm" "clj-nix:latest"))
          "Hello from CLOJURE"))
    (is (= 0 (:exit (sh "docker" "rmi" "clj-nix")))))

  (deftest demo-graal-container-test
    (is (= 0 (:exit (sh "nix" "build" (str demo-url "#graal-container")))))
    (is (= 0 (:exit (sh "docker" "load" "-i" "result"))))
    (is (string/includes?
          (:out (sh "docker" "run" "--rm" "clj-graal-nix:latest"))
          "Hello from CLOJURE"))
    (is (= 0 (:exit (sh "docker" "rmi" "clj-graal-nix")))))

  (deftest demo-babashka-test
    (is (= 0 (:exit (sh "nix" "build" (str demo-url "#babashka")))))
    (is (= "102" (string/trim (:out (sh "./result/bin/bb" "-e" "(inc 101)")))))
    (is (= 0 (:exit (sh "./result/bin/bb" "-e" require-jdbc))))))
