(ns hello-override.override
  (:require [hello.package :as hello]))

(def version "2.10")

(defn override
  [pkg]
  (-> pkg
      (assoc :version version)
      (assoc-in [:src :url] (hello/version-url version))
      (assoc-in [:src :hash] "sha256-MeBmE3qWJnbon2nRtlOC3pWn732RS4y5VvQepy4PUWs=")))
