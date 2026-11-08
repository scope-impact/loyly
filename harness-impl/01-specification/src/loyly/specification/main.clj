(ns loyly.specification.main
  "start.sh's entry point: the seam over stdio. Responses alone go to stdout,
   one JSON object per line; everything else goes to stderr."
  (:require [clojure.string :as str]
            [loyly.specification.rpc :as rpc]
            [loyly.specification.system :as system])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter PrintStream]
           [java.nio.charset StandardCharsets])
  (:gen-class))

(defn -main [& _]
  (let [stdout (OutputStreamWriter. System/out StandardCharsets/UTF_8)
        stdin (BufferedReader. (InputStreamReader. System/in StandardCharsets/UTF_8))
        err (PrintStream. (java.io.FileOutputStream. java.io.FileDescriptor/err) true "UTF-8")]
    ;; Nothing but responses may reach stdout.
    (System/setOut err)
    (alter-var-root #'*out* (constantly *err*))
    (binding [*out* *err*]
      (let [store (not-empty (System/getenv "LOYLY_GRAIN_STORE"))
            sys (try
                  (system/start {:store-file store})
                  (catch Throwable t
                    (.println err (str "Refusing to start: " (ex-message t)))
                    (when-let [data (ex-data t)] (.println err (pr-str data)))
                    (System/exit 1)))]
        (.println err (str "specification: ready on " (or store "an in-memory store")))
        (let [stopped (atom false)
              stop! (fn [] (when (compare-and-set! stopped false true)
                             (try (system/stop sys) (catch Throwable _))))]
          (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop!))
        (loop []
          (when-let [line (.readLine stdin)]
            (when-not (str/blank? line)
              (when-let [response (rpc/handle-line sys line)]
                (.write stdout (str response "\n"))
                (.flush stdout)))
            (recur)))
          (stop!)
          (shutdown-agents)
          (System/exit 0))))))
