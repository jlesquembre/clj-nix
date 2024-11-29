(ns helpers
  (:require
    [clojure.string :as string]
    [babashka.fs :as fs]))


(defn create-exe-file
  [path]
  (fs/create-file path
                  {:posix-file-permissions (fs/str->posix "rwxr-xr-x")}))

(defn make-bin-path
  [deps]
  (->> deps
       vals
       (map #(fs/path % "bin"))
       (map str)
       (string/join ":")))
