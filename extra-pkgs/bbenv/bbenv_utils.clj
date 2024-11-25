(ns bbenv-utils
  (:require
    ;[,pkg, :as package]
    ;[,override, :as override-package]
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [babashka.process :refer [shell]]
    [rewrite-clj.zip :as z])
  (:import [java.nio.file FileSystems]))

(def pkg ,{},)

(defn- get-build-env
  []
  (let [here       (System/getenv "NIX_BUILD_TOP")
        attrs-file (System/getenv "NIX_ATTRS_JSON_FILE")
        f (if (fs/exists? attrs-file)
            attrs-file
            (str (fs/path here ".attrs.json")))]
    (-> f
      (io/reader)
      (json/parse-stream true))))


(defn get-ns
  [path]
  (-> (z/of-file path)
      (z/find-value z/next 'ns)
      z/next
      z/sexpr))


(defn write-ns-info
  [{:keys [out pkg-path override-path]}]
  (let [ns-str (get-ns pkg-path)]
    (spit (str (fs/path out "ns.txt"))
          ns-str))
  (when-let [ns-str (get-ns override-path)]
    (spit (str (fs/path out "override-ns.txt"))
          ns-str)))


(defn write-src-info
  [{:keys [out override]}]
  (with-open [w (io/writer out)]
    (let [pkg (if override (,:override-package, pkg) pkg)]
      (json/generate-stream
        (select-keys pkg [:name :version :src])
        w))))


; See https://github.com/NixOS/nixpkgs/blob/9e447b48546c8ccf80ad039cbb56cc936fd82889/pkgs/stdenv/generic/setup.sh#L1190
(defn- unpack? [f]
  (let [matcher (.getPathMatcher
                 (FileSystems/getDefault)
                 "glob:*.{tar.xz,tar.lzma,txz,tar,tar.*,tgz,tbz2,tbz}")]
    (.matches matcher (-> f fs/file-name fs/path))))


(defn strip-hash
  [f]
  (let [f (fs/file-name f)]
    (if (re-matches #"^[a-z0-9]{32}-.*" f)
      (subs f 33)
      f)))


(defn prepare-src
  "Copy/unpack source code to the sandbox"
  [src]
  (cond
    (nil? src) nil

    (unpack? src)
    (do
      (shell (str "tar xf " src " --mode=+w --warning=no-timestamp"))
      (->> (fs/list-dir ".")
        (some #(when (fs/directory? %) %))
        str))

    (fs/regular-file? src)
    (let [dest (fs/path "src" (strip-hash src))]
      (fs/create-dir "src")
      (fs/copy src dest)
      (str dest))))


(defn mk-derivation
  [{:keys [src]}]
  (let [build-fn (:build pkg)
        env (get-build-env)]
    (build-fn
      {:env env
       :path (get env :path)
       :src-origin src
       :src (prepare-src src)
       :outputs (get env :outputs)
       :out (get-in env [:outputs :out])})))



(defn extract-deps
  [_]
  (let [{:keys [deps build-deps]
         :or {deps [] build-deps []}} pkg
        env (get-build-env)
        output (get-in env [:outputs :out])]
    (with-open [w (io/writer output)]
      (json/generate-stream
        {:deps deps
         :build-deps build-deps}
        w))))
