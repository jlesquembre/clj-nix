(ns hello.package
  (:require [babashka.process :refer [shell]]))


(defn build
  [{:keys [out src]}]
  (shell {:dir src} (format "./configure CFLAGS='-O2' --prefix=%s" out))
  (shell {:dir src} "make install"))


(def version "2.12.1");

(defn version-url
  [v]
  (format "mirror://gnu/hello/hello-%s.tar.gz" v))


(def pkg
  {:name "hello"
   :version version
   :deps []
   :build-deps [:gcc]
   :src {:fetcher :fetchurl
         :url (version-url version)
         :hash "sha256-jZkUKv2SV28wsM18tCqNxoCZmLxdYH2Idh9RLibH2yA="}
   :build build})
