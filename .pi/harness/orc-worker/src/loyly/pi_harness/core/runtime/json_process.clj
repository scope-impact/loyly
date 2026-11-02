(ns loyly.pi-harness.core.runtime.json-process
  (:require [clojure.data.json :as json]))

(defn invoke!
  "Runs a newline-delimited JSON worker and returns its decoded response and
  process diagnostics. Stdout and stderr are drained concurrently so a noisy
  child cannot block while filling either process pipe."
  [script request]
  (let [command ["node" "--experimental-strip-types" script]
        process (.start (ProcessBuilder. ^java.util.List command))]
    (with-open [writer (java.io.OutputStreamWriter. (.getOutputStream process))]
      (.write writer (json/write-str request))
      (.write writer "\n"))
    (let [stdout (future (slurp (.getInputStream process)))
          stderr (future (slurp (.getErrorStream process)))
          exit-code (.waitFor process)
          stdout-value @stdout
          stderr-value @stderr]
      {:exit-code exit-code
       :stdout stdout-value
       :stderr stderr-value
       :response (try
                   (json/read-str stdout-value :key-fn keyword)
                   (catch Exception _ nil))})))
