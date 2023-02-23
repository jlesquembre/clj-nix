(ns integration.flake-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [babashka.fs :as fs]
    [clojure.string :as string]
    [clojure.java.shell :refer [sh] :as shell]))

#_
(def cljnix-dir (str (fs/cwd)))

#_
(deftest nix-build-test
  (testing "nix build .#clj-builder"
    (is (= 0
           (:exit (sh "nix" "build" (str cljnix-dir "#clj-builder") "--no-link")))))

  (testing "nix build .#deps-lock"
    (is (= 0
           (:exit (sh "nix" "build" (str cljnix-dir "#deps-lock") "--no-link"))))))


#_
(def ^:dynamic *flake-project-dir* nil)

#_
(defn setup-project
  [f]
  (let [project-dir (str (fs/create-temp-dir {:prefix "demo-project_"}))]

    (sh "nix" "flake" "new" "--template" cljnix-dir project-dir)

    (shell/with-sh-dir project-dir
      (-> (str (fs/path cljnix-dir "test/integration/flake.template"))
          slurp
          (string/replace "@cljnixUrl@" cljnix-dir)
          (->> (spit (str (fs/path project-dir "flake.nix")))))
      (sh "git" "init")
      (sh "git" "add" ".")
      (sh "nix" "flake" "lock")
      (sh "git" "add" ".")
      (sh "git" "commit" "-m" "init")

      (binding [*flake-project-dir* project-dir]
        (f))
      (fs/delete-tree project-dir))))

#_
(use-fixtures :once setup-project)

#_
(deftest deps-lock-test
  (fs/delete (fs/path *flake-project-dir* "deps-lock.json"))
  (is (boolean (seq (:out (sh "git" "status" "-s")))))
  (is (= 0 (:exit (sh "nix" "run" (str cljnix-dir "#deps-lock")))))
  (is (empty? (:out (sh "git" "status" "-s")))))

#_
(deftest mkCljBin-test
  (is (= 0
         (:exit (sh "nix" "build" ".#mkCljBin-test"))))
  (is (string/includes?
        (:out (sh "./result/bin/cljdemo"))
        "Hello from CLOJURE")))

#_
(deftest customJdk-test
  (is (= 0
         (:exit (sh "nix" "build" ".#customJdk-test"))))
  (is (string/includes?
        (:out (sh "./result/bin/cljdemo"))
        "Hello from CLOJURE")))

#_
(deftest mkGraalBin-test
  (is (= 0
         (:exit (sh "nix" "build" ".#mkGraalBin-test"))))
  (is (string/includes?
        (:out (sh "./result/bin/cljdemo"))
        "Hello from CLOJURE")))

#_
(deftest jvm-container-test
  (is (= 0 (:exit (sh "nix" "build" ".#jvm-container-test"))))

  (is (= 0 (:exit (sh "docker" "load" "-i" "result"))))

  (is (string/includes?
        (:out (sh "docker" "run" "--rm" "jvm-container-test:latest"))
        "Hello from CLOJURE"))

  (is (= 0 (:exit (sh "docker" "rmi" "jvm-container-test")))))

#_
(deftest graal-container-test
  (is (= 0 (:exit (sh "nix" "build" ".#graal-container-test"))))

  (is (= 0 (:exit (sh "nix" "build" ".#graal-container-test"))))

  (is (= 0 (:exit (sh "docker" "load" "-i" "result"))))

  (is (string/includes?
        (:out (sh "docker" "run" "--rm" "graal-container-test:latest"))
        "Hello from CLOJURE"))

  (is (= 0 (:exit (sh "docker" "rmi" "graal-container-test")))))


#_
(deftest babashka-test
  (is (= 0 (:exit (sh "nix" "build" ".#babashka-test"))))
  (is (= "102" (string/trim (:out (sh "./result/bin/bb" "-e" "(inc 101)")))))
  (is (not= 0 (:exit (sh "./result/bin/bb" "-e" require-jdbc)))))

#_
(deftest babashka-with-features-test
  (is (= 0 (:exit (sh "nix" "build" ".#babashka-with-features-test"))))
  (is (= 0 (:exit (sh "./result/bin/bb" "-e" require-jdbc)))))


;; TODO Move

(comment
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
