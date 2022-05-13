(ns cljnix.nix-test
  (:require
    [clojure.test :refer [deftest is use-fixtures testing]]
    [babashka.fs :as fs]
    [cljnix.test-helpers :as h]
    [cljnix.nix :as nix]
    [matcher-combinators.test]))

(def all-deps '{:deps {org.clojure/clojure {:mvn/version "1.11.1"}
                       io.github.babashka/fs {:git/sha "7adcefeb773bd786408cdc788582f145f79626a6"}
                       io.github.weavejester/medley {:git/sha "0044c6aacc0b23eafa3b58091f49c794f5a1f5aa"}}})

(defn deps-cache-fixture [f]
  (h/prep-deps all-deps)
  (f))

(use-fixtures :once deps-cache-fixture)

(deftest nix-hash-test
  (is (= "sha256-I4G26UI6tGUVFFWUSQPROlYkPWAGuRlK/Bv0+HEMtN4="
         (nix/nix-hash (fs/expand-home "~/.m2/repository/org/clojure/clojure/1.11.1/clojure-1.11.1.jar"))))

  (is (= "sha256-L+tsBCOxr2kJpIEPJ0A+s8/Ud2jLgfiDQIB+U3/PcG0="
         (nix/nix-hash (fs/expand-home "~/.gitlibs/libs/io.github.babashka/fs/7adcefeb773bd786408cdc788582f145f79626a6"))))

  (is (= "sha256-drh0opl3JjrpGadg74wIdOcDTaP2GT31X3O1PGXkvqk="
         (nix/nix-hash (fs/expand-home "~/.gitlibs/libs/io.github.weavejester/medley/0044c6aacc0b23eafa3b58091f49c794f5a1f5aa")))))

