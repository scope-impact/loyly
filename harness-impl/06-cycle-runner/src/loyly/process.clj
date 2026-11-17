(ns loyly.process (:require [clojure.java.io :as io] [clojure.string :as str]))
(defn stop! [^Process p]
  (doseq [h (reverse (vec (.toList (.descendants (.toHandle p)))))] (.destroyForcibly h))
  (.destroyForcibly p))
(def live-processes (atom #{}))
(defonce shutdown-hook (do (.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (doseq [p @live-processes] (when (.isAlive p) (stop! p)))))) true))
(defn run! [dir args seconds env]
  (let [pb (ProcessBuilder. ^java.util.List (mapv str args))
        _ (.directory pb (io/file dir)) _ (.putAll (.environment pb) env)
        p (.start pb) _ (swap! live-processes conj p) out (future (slurp (.getInputStream p))) err (future (slurp (.getErrorStream p)))
        ended (.waitFor p (long (max 1 (* 1000 seconds))) java.util.concurrent.TimeUnit/MILLISECONDS)]
    (when-not ended (stop! p))
    (swap! live-processes disj p)
    (let [r {:exit (if ended (.exitValue p) -1) :timeout (not ended) :out (deref out 2000 "") :err (deref err 2000 "")}]
      (when (#{126 127} (:exit r)) (throw (ex-info (str "Program could not run: " (:err r)) r))) r)))
(defn shell! [dir cmd args seconds env]
  (when (str/blank? cmd) (throw (ex-info "Required command is not configured" {})))
  (run! dir (into ["sh" "-c" (str "exec " cmd " \"$@\"") "loyly"] args) seconds env))
(defn copy! [source target]
  (.mkdirs (io/file target))
  (let [r (run! "." ["cp" "-R" (str source "/.") (str target "/")] 60 {})]
    (when-not (zero? (:exit r)) (throw (ex-info (:err r) r)))))
(defn remove! [path]
  (doseq [f (reverse (file-seq (io/file path)))] (io/delete-file f true)))
(defn clean-builder [dir args env]
  (let [pb (ProcessBuilder. ^java.util.List (mapv str args)) e (.environment pb)]
    (.directory pb (io/file dir))
    (doseq [k (vec (.keySet e)) :when (or (.startsWith k "LOYLY_") (= k "GATE_STORE"))] (.remove e k))
    (.putAll e env) pb))
(defn clean-run! [dir args seconds env]
  (let [p (.start (clean-builder dir args env)) out (future (slurp (.getInputStream p))) err (future (slurp (.getErrorStream p)))
        ended (.waitFor p (long (* 1000 seconds)) java.util.concurrent.TimeUnit/MILLISECONDS)]
    (when-not ended (stop! p))
    (swap! live-processes disj p)
    (let [r {:exit (if ended (.exitValue p) -1) :timeout (not ended) :out (deref out 2000 "") :err (deref err 2000 "")}]
      (when (#{126 127} (:exit r)) (throw (ex-info (str "Program could not run: " (:err r)) r))) r)))
