(ns cljnix.utils
  (:require
    [clojure.java.io :as io])
  (:import
    [java.util Base64]
    [java.nio.file Files]
    [java.security MessageDigest]))


(defn throw+
  [msg data]
  (throw (ex-info (prn-str msg data) data)))
