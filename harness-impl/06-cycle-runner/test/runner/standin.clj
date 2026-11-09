#!/usr/bin/env bb
(require '[cheshire.core :as json] '[clojure.java.io :as io])
(let [[context port] *command-line-args*]
  (doseq [line (line-seq (io/reader *in*))]
    (with-open [socket (java.net.Socket. "127.0.0.1" (Integer/parseInt port))
                writer (io/writer socket) reader (io/reader socket)]
      (.write writer (str (json/generate-string {:context context :store (System/getenv "LOYLY_GRAIN_STORE") :request (json/parse-string line true)}) "\n"))
      (.flush writer)
      (let [response (json/parse-string (.readLine reader) true)]
        (when (:exit response) (System/exit 0))
        (when (:hang response) (Thread/sleep 70000))
        (println (json/generate-string response)) (flush)))))
