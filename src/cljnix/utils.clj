(ns cljnix.utils
  (:require
    [clojure.java.io :as io])
  (:import
    [java.util Base64]
    [java.nio.file Files]
    [java.security MessageDigest]))


(defn sri-hash
  [path]
  (let [path (.toPath (io/file path))
        data (Files/readAllBytes path)
        digester (doto (MessageDigest/getInstance "SHA-256")
                   (.update data))]
    (str
      "sha256-"
      (.encodeToString
        (Base64/getEncoder)
        (.digest digester)))))

(defn throw+
  [msg data]
  (throw (ex-info (prn-str msg data) data)))
