#!/usr/bin/env bb

(ns newer-clojure-versions
  (:require [clojure.string :as str]
            [babashka.http-client :as http]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [edamame.core :as edamame]
            [rewrite-clj.zip :as z]))

(defn parse-version
  "Parse version string like '1.12.0' into a vector of integers [1 12 0]"
  [version-str]
  (mapv parse-long (str/split version-str #"\.")))

(defn version>
  "Returns true if v1 > v2 (both are version vectors)"
  [v1 v2]
  (pos? (compare v1 v2)))

(defn rc-version?
  "Returns true if tag name contains 'rc' or 'RC' (release candidate)"
  [tag-name]
  (re-find #"(?i)rc" tag-name))

(defn extract-version
  "Extract version from tag name like 'clojure-1.12.0' -> '1.12.0'"
  [tag-name]
  (second (re-find #"clojure-(\d+\.\d+\.\d+)$" tag-name)))

(defn fetch-tags
  "Fetch all tags from clojure/clojure repo. Handles pagination."
  []
  (loop [url "https://api.github.com/repos/clojure/clojure/tags?per_page=100"
         all-tags []]
    (let [response (http/get url {:headers {"Accept" "application/vnd.github+json"}})
          tags (json/parse-string (:body response) true)
          link-header (get-in response [:headers "link"])
          next-url (when link-header
                     (second (re-find #"<([^>]+)>;\s*rel=\"next\"" link-header)))]
      (if next-url
        (recur next-url (into all-tags tags))
        (into all-tags tags)))))

(defn core-file-path
  "Returns the path to src/cljnix/core.clj relative to the script location"
  []
  (let [script-dir (fs/parent (fs/file (System/getProperty "babashka.file")))]
    (fs/file script-dir ".." "src" "cljnix" "core.clj")))

(defn extract-clojure-versions
  "Extract clojure-versions from src/cljnix/core.clj"
  []
  (let [content (slurp (core-file-path))
        forms (edamame/parse-string-all content {:all true})]
    (->> forms
         (filter #(and (seq? %) (= 'def (first %)) (= 'clojure-versions (second %))))
         first
         (drop 2)
         first)))

(defn max-version
  "Returns the largest version from a seq of version strings"
  [versions]
  (->> versions
       (sort-by parse-version)
       last))

(defn newer-clojure-versions
  "Returns all Clojure versions newer than the given version.
   Skips RC (release candidate) versions.

   Example: (newer-clojure-versions \"1.10.0\")
   => [\"1.12.0\" \"1.11.4\" \"1.11.3\" ...]"
  [version]
  (let [base-version (parse-version version)
        tags (fetch-tags)]
    (->> tags
         (map :name)
         (remove rc-version?)
         (keep extract-version)
         (filter #(version> (parse-version %) base-version))
         (sort-by parse-version)
         reverse
         vec)))

(defn update-clojure-versions!
  "Update clojure-versions in src/cljnix/core.clj with new versions appended"
  [current-versions new-versions]
  (let [core-file (core-file-path)
        content (slurp core-file)
        updated-versions (into (vec current-versions)
                               (sort-by parse-version new-versions))
        zloc (-> (z/of-string content)
                 (z/find-value z/next 'clojure-versions)
                 z/right
                 (z/replace updated-versions))
        new-content (z/root-string zloc)]
    (spit core-file new-content)))

(when (= *file* (System/getProperty "babashka.file"))
  (let [versions (extract-clojure-versions)]
    (if (seq versions)
      (let [current-max (max-version versions)
            newer (newer-clojure-versions current-max)]
        (if (seq newer)
          (do
            (println "Found newer versions:" (str/join ", " newer))
            (update-clojure-versions! versions newer)
            (println "Updated src/cljnix/core.clj"))
          (println "No newer Clojure versions found, latest is" current-max)))
      (do
        (println "Error: Could not extract clojure-versions from src/cljnix/core.clj")
        (System/exit 1)))))
