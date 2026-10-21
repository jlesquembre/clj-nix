(ns cljnix.check-test
  (:require [clojure.test :as t]
            [cljnix.check :refer [check-src-dirs]]))

(t/deftest gen-class-check-tests
  (t/is (= :no-gen-class (check-src-dirs ["test/resources"] "example.no-gen-class")))
  (t/is (= :ok (check-src-dirs ["test/resources"] "example.has-gen-class")))
  (t/is (= :ok (check-src-dirs ["test/resources"] "example.has-gen-class-with-comment")))
  (t/is (= :ok (check-src-dirs ["test/resources"] "example.has-gen-class-with-odd-spacing"))))

(t/deftest missing-main-ns-file-test
  (t/testing "a main-ns with no source file is reported as not found, not as missing :gen-class"
    (t/is (= :not-found (check-src-dirs ["test/resources"] "example.does-not-exist")))
    (t/is (= :not-found (check-src-dirs [] "example.has-gen-class")))))
