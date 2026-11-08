(ns loyly.cycle-preparation.main
  "start.sh's entry point: the JSON-RPC seam over stdio. Only responses go to
   stdout; everything else (logs, diagnostics) goes to stderr."
  (:import [java.io BufferedReader InputStreamReader PrintStream OutputStreamWriter BufferedWriter]
           [java.nio.charset StandardCharsets])
  (:gen-class))

(defn -main [& _]
  (let [real-out (BufferedWriter. (OutputStreamWriter. System/out StandardCharsets/UTF_8))
        err-stream (PrintStream. (java.io.FileOutputStream. java.io.FileDescriptor/err) true "UTF-8")]
    ;; Nothing but responses reaches stdout.
    (System/setOut err-stream)
    (alter-var-root #'*out* (constantly (java.io.OutputStreamWriter. err-stream)))
    (let [start (requiring-resolve 'loyly.cycle-preparation.system/start)
          stop (requiring-resolve 'loyly.cycle-preparation.system/stop)
          handle-line (requiring-resolve 'loyly.cycle-preparation.seam/handle-line)
          store (System/getenv "LOYLY_GRAIN_STORE")
          sys (try
                (start {:store-path (when (seq store) store)})
                (catch Throwable t
                  (binding [*out* *err*]
                    (println "cycle-preparation: refusing to start:" (ex-message t))
                    (when-let [d (ex-data t)] (prn d)))
                  (System/exit 1)))
          in (BufferedReader. (InputStreamReader. System/in StandardCharsets/UTF_8))]
      (binding [*out* *err*] (println "cycle-preparation: ready"))
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (try (stop sys) (catch Throwable _)))))
      (loop []
        (when-let [line (.readLine in)]
          (when-let [resp (handle-line sys line)]
            (locking real-out
              (.write real-out ^String resp)
              (.write real-out "\n")
              (.flush real-out)))
          (recur)))
      (System/exit 0))))
