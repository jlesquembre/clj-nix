(require '[babashka.process :refer [shell]])

(defn build
  [{:keys [out src]}]
  (shell {:dir src} (format "./configure --prefix=%s" out))
  (shell {:dir src} "make install"))


(def version  "2.12.1");

(def pkg
  {:name "foo"
   :version version
   :src {:fetcher :fetchurl
         :url (format "mirror://gnu/hello/hello-%s.tar.gz" version)
         :hash "sha256-jZkUKv2SV28wsM18tCqNxoCZmLxdYH2Idh9RLibH2yA="}
   :build build})
