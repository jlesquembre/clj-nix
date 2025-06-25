(ns leiningen-example-project.core-test
  (:require [clojure.test :refer :all]
            [leiningen-example-project.core :refer :all]))

(deftest a-test
  (testing "I test something very important."
    (is (= 3 3))))
