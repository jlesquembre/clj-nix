(ns cljnix.check-test
  (:require [clojure.test :as t]
            [cljnix.check :refer [check-src-dirs]]))

(t/deftest gen-class-check-tests
  (t/is (not (check-src-dirs ["test/resources"] "example.no-gen-class")))
  (t/is (check-src-dirs ["test/resources"] "example.has-gen-class"))
  (t/is (check-src-dirs ["test/resources"] "example.has-gen-class-with-comment"))
  (t/is (check-src-dirs ["test/resources"] "example.has-gen-class-with-odd-spacing")))
