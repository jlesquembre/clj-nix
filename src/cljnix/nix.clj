(ns cljnix.nix
  (:require
    [clojure.java.io :as io]
    [babashka.fs :as fs]
    [clj-commons.byte-streams :as bs]
    [cljnix.utils :refer [throw+]])
  (:import
    [java.io ByteArrayOutputStream]
    [java.nio ByteBuffer ByteOrder]
    [java.nio.file Files]
    [java.security MessageDigest]
    [java.util Base64]))


(def narVersionMagic1 "nix-archive-1")

(def ^:dynamic
  *path-filter*
 "Takes 1 argument, the path name. If true, the element will not be included in
 the NAR file"
 #{".git"
   ".clj-kondo/.cache"})


(declare serialize-entry)
(declare serialize')
(declare open!)
(declare close!)


;; NAR serialization algorithm
;; See https://edolstra.github.io/pubs/phd-thesis.pdf (Figure 5.2, page 101)

(defn int-n
  "NAR serialization algorithm
   int(n)"
  [n]
  (-> (ByteBuffer/allocate Long/BYTES)
      (.order ByteOrder/LITTLE_ENDIAN)
      (.putInt (int n))
      (.array)))

(defn pad
  "NAR serialization algorithm
   pad(s)"
  [n sink]
  (let [pad (mod (- 8 n)
               8)]
    (when-not (zero? pad)
      (bs/transfer (byte-array pad) sink))))

(defn str!
  "NAR serialization algorithm
   str(s)"
  [sink s]
  (let [n (count s)]
    (bs/transfer (int-n n) sink)
    (bs/transfer s sink)
    (pad n sink)
    sink))


(defn serialize-entry
  "NAR serialization algorithm
   serializeEntry(name,fso)"
  [sink path]
  (-> sink
      (str! "entry")
      (open!)
      (str! "name")
      (str! (fs/file-name path))
      (str! "node")
      (serialize' path)
      (close!)))


(defmulti serialize''
  "NAR serialization algorithm
   serialize''(element)"
  (fn [_ path]
    (cond
      (fs/sym-link? path) :symlink
      (fs/regular-file? path) :regular
      (fs/directory? path) :directory)))

(defmethod serialize'' :default
  [_ path]
  (throw+ "Don't know how to serialize" {:file path}))

(defmethod serialize'' :regular
  [sink path]
  (-> sink
      (str! "type")
      (str! "regular")
      (cond-> (fs/executable? path)
              (->
                (str! "executable")
                (str! "")))
      (str! "contents")
      (str! (bs/to-byte-array (fs/file path))))
  sink)

(defmethod serialize'' :symlink
  [sink path]
  (-> sink
      (str! "type")
      (str! "symlink")
      (str! "target")
      (str! (str (Files/readSymbolicLink path)))))


(defmethod serialize'' :directory
  [sink path]
  (-> sink
      (str! "type")
      (str! "directory"))
  (doseq [entry (sort-by fs/file-name
                         (remove (fn [child] (some #(fs/ends-with? child %) *path-filter*))
                                 (fs/list-dir path)))]
    (serialize-entry sink entry))
  sink)


(defn serialize'
  "NAR serialization algorithm
   serialize'(fso)"
  [sink path]
  (-> sink
      (open!)
      (serialize'' path)
      (close!)))


(defn serialize
  "NAR serialization algorithm
   serialize(fso)"
  [sink path]
  (-> sink
      (str! narVersionMagic1)
      (serialize' (fs/path path))))


;;;
;;; Helpers for the NAR algorithm
;;;

(def ^:private OPEN
  (let [a (byte-array 9 (int-n 1))]
   (aset-byte a 8 40)
   a))

(def ^:private CLOSE
  (let [a (byte-array 9 (int-n 1))]
   (aset-byte a 8 41)
   a))

(defn- open!
  [sink]
  (bs/transfer OPEN sink)
  (bs/transfer (byte-array 7) sink)
  sink)

(defn- close!
  [sink]
  (bs/transfer CLOSE sink)
  (bs/transfer (byte-array 7) sink)
  sink)


(defn- sri-hash
  [^ByteArrayOutputStream data]
  (let [digester (doto (MessageDigest/getInstance "SHA-256")
                   (.update data))]
    (str
      "sha256-"
      (.encodeToString
        (Base64/getEncoder)
        (.digest digester)))))
;;;
;;; Public API
;;;

(defn make-nar
  "Serialize directory to NAR"
  [path]
  (let [sink (ByteArrayOutputStream.)]
    (serialize sink path)
    (.toByteArray sink)))

(defn nix-hash
  "Get Nix hash for a file or directory"
  [path]
  (let [path (fs/expand-home path)]
    (sri-hash
      (cond
        (fs/directory? path) (make-nar path)
        (fs/regular-file? path) (fs/read-all-bytes path)
        :else (throw+ "Only can generate hash for directories and regular files" {:file path})))))

(comment

  (-> (ByteArrayOutputStream.)
      (open!)
      (str! "foo")
      (close!)
      (.toByteArray)
      (bs/print-bytes))

  (nix-hash "/tmp/foo")

  ; nix run github:NixOS/nixpkgs/nixpkgs-unstable#nix-prefetch-git -- https://github.com/babashka/fs.git 2bf527f797d69b3f14247940958e0d7b509f3ce2
  (nix-hash "~/.gitlibs/libs/babashka/fs/2bf527f797d69b3f14247940958e0d7b509f3ce2")

  (nix-hash "~/.m2/repository/aero/aero/1.1.3/aero-1.1.3.jar")

  (-> (make-nar "/tmp/foo")
      (bs/print-bytes))


  (bs/print-bytes
    (->> (bs/to-byte-array  (io/file "/home/jlle/projects/nix/tests/case.nar"))
         (take 200)
         (byte-array))))
