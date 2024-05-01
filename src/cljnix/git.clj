;; Copied from https://github.com/clojure/tools.gitlibs
(ns cljnix.git
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.gitlibs.config :as config])
  (:import
    [java.lang ProcessBuilder$Redirect]
    [java.io File FilenameFilter InputStream IOException StringWriter]))

(defmacro background
  [& body]
  `(let [result# (promise)]
     (doto (Thread. (fn [] (deliver result# (do ~@body))))
       (.setDaemon true)
       (.start))
     result#))

(defn- capture
  "Reads from input-stream until EOF and returns a String (or nil if 0 length)."
  [^InputStream input-stream]
  (let [writer (StringWriter.)]
    (jio/copy input-stream writer)
    (let [s (str/trim (.toString writer))]
      (when-not (zero? (.length s))
        s))))

(defn printerrln [& msgs]
  (binding [*out* *err*]
    (apply println msgs)))

(defn- run-git
  [& args]
  (let [{:gitlibs/keys [command debug terminal]} @config/CONFIG
        command-args (cons command args)]
    (when debug
      (apply printerrln command-args))
    (let [proc-builder (ProcessBuilder. ^java.util.List command-args)
          _ (when debug (.redirectError proc-builder ProcessBuilder$Redirect/INHERIT))
          _ (when-not terminal (.put (.environment proc-builder) "GIT_TERMINAL_PROMPT" "0"))
          proc (.start proc-builder)
          out (background (capture (.getInputStream proc)))
          err (background (capture (.getErrorStream proc))) ;; if debug is true, stderr will be redirected instead
          exit (.waitFor proc)]
      {:args command-args, :exit exit, :out @out, :err @err})))

(defn ancestor?
  [git-dir x y]
  (let [{:keys [exit err] :as ret} (run-git "--git-dir" git-dir "merge-base" "--is-ancestor" x y)]
    (condp = exit
      0 true
      1 false
      (throw (ex-info (format "Unable to compare commits %s%n%s" git-dir err) ret)))))
