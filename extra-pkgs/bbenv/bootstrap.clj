(require '[babashka.fs :as fs])
(require '[cheshire.core :as json])
(require '[clojure.java.io :as io])

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


(def ENV (get-build-env))


(defn output
  ([]
   (output :out))
  ([k]
   (get-in ENV [:outputs k])))

(def OUT (output))
