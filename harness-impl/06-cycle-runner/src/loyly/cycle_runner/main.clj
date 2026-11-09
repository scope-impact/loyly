(ns loyly.cycle-runner.main
  "start.sh's entry: the product speaking its seam on stdin and stdout."
  (:require [clojure.string :as str]
            [loyly.cycle-runner.contexts :as contexts]
            [loyly.cycle-runner.runtime :as runtime]
            [loyly.cycle-runner.seam :as seam]
            [loyly.cycle-runner.system :as system]
            [loyly.cycle-runner.util :as u])
  (:import [java.io BufferedReader InputStreamReader PrintStream FileOutputStream FileDescriptor])
  (:gen-class))

(defn -main [& _]
  ;; stdout carries the seam alone: one JSON object per line.
  (let [real-out (PrintStream. (FileOutputStream. FileDescriptor/out) true "UTF-8")]
    (System/setOut System/err)
    (let [store (not-empty (System/getenv "LOYLY_GRAIN_STORE"))
          sys (try (system/start store)
                   (catch Throwable t
                     (u/log "refusing to start:" (.getMessage t))
                     (when-let [d (ex-data t)] (u/log (pr-str (get-in d [:verdict :findings]))))
                     (System/exit 1)))
          in (BufferedReader. (InputStreamReader. System/in "UTF-8"))]
      (u/log "ready on store" (or store "in memory"))
      (loop []
        (when-let [line (.readLine in)]
          (when-not (str/blank? line)
            (let [resp (seam/handle sys line)]
              (when resp
                (.println real-out ^String resp)
                (.flush real-out))))
          (recur)))
      (doseq [[_ rt] @runtime/runs :when (:contexts rt)]
        (try (contexts/stop-all! (:contexts rt)) (catch Exception _ nil)))
      (system/stop sys)
      (shutdown-agents)
      (System/exit 0))))
