(ns measurement.main
  "Entry point: speaks the seam on stdin/stdout. Only responses go to stdout;
   everything else is redirected to stderr."
  (:require [clojure.string :as str]
            [measurement.seam :as seam]
            [measurement.system :as system])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter PrintStream]
           [java.nio.charset StandardCharsets])
  (:gen-class))

(defn -main [& _]
  (let [real-out (PrintStream. (java.io.FileOutputStream. java.io.FileDescriptor/out) true "UTF-8")
        writer (OutputStreamWriter. real-out StandardCharsets/UTF_8)
        err-writer (OutputStreamWriter. System/err StandardCharsets/UTF_8)]
    ;; Nothing but responses may reach stdout.
    (System/setOut System/err)
    (alter-var-root #'*out* (constantly err-writer))
    (binding [*out* err-writer]
      (let [store-path (not-empty (System/getenv "LOYLY_GRAIN_STORE"))
            sys (try
                  (system/start {:store-path store-path})
                  (catch Throwable t
                    (binding [*out* *err*]
                      (println "refusing to start:" (.getMessage t))
                      (when-let [d (ex-data t)] (prn (dissoc d :verdict)) (prn (get-in d [:verdict :findings]))))
                    (System/exit 1)))
            reader (BufferedReader. (InputStreamReader. System/in StandardCharsets/UTF_8))]
        (binding [*out* *err*] (println "measurement: ready" (or store-path "(in-memory store)")))
        (loop []
          (when-let [line (.readLine reader)]
            (when-not (str/blank? line)
              (when-let [response (seam/handle-line (:ctx sys) line)]
                (.write writer ^String response)
                (.write writer "\n")
                (.flush writer)))
            (recur)))
        (system/stop sys)
        (shutdown-agents)
        (System/exit 0)))))
