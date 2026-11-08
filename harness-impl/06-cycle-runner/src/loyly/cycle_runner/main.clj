(ns loyly.cycle-runner.main
  "start.sh: boot (register the model as given, refuse to start when the running blocks and
   the model are apart), then read JSON-RPC requests on stdin, one per line, and write one
   response per request on stdout. Nothing else reaches stdout."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.cycle-runner.advance :as advance]
            [loyly.cycle-runner.rpc :as rpc]
            [loyly.cycle-runner.system :as system]
            [loyly.cycle-runner.util :as u])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter PrintStream]
           [java.nio.charset StandardCharsets])
  (:gen-class))

(defn boot!
  "Register and verify; start the system on store-path."
  [store-path env-fn]
  (system/register-all! advance/processor-handler)
  (system/verify!)
  (system/start! {:store-path store-path :env-fn env-fn}))

(defn shutdown! [sys]
  (doseq [[_ s] @(:sessions sys)] (try (advance/close-session! s) (catch Throwable _)))
  (system/stop! sys))

(defn -main [& _]
  ;; Keep stdout for responses only: everything else written to System.out goes to stderr.
  (let [real-out (PrintStream. (java.io.FileOutputStream. java.io.FileDescriptor/out) true "UTF-8")
        _ (System/setOut System/err)
        out (OutputStreamWriter. real-out StandardCharsets/UTF_8)
        env-fn (fn [k] (System/getenv k))
        caller (or (System/getenv "LOYLY_CALLER_DIR") (System/getProperty "user.dir"))
        store (let [s (System/getenv "LOYLY_GRAIN_STORE")]
                (cond
                  (str/blank? s) (str (u/abs-path (io/file (System/getProperty "java.io.tmpdir")
                                                           (str "loyly-cycle-runner-" (System/currentTimeMillis) ".sqlite"))))
                  (.isAbsolute (io/file s)) s
                  :else (u/abs-path (io/file caller s))))
        sys (binding [*out* *err*]
              (try (boot! store env-fn)
                   (catch Throwable t
                     (u/log "refusing to start: " (.getMessage t))
                     (when-let [v (:verdict (ex-data t))]
                       (u/log (pr-str (:findings v))))
                     (System/exit 2))))]
    (u/log "ready on store " store)
    (binding [*out* *err*]
      (let [in (BufferedReader. (InputStreamReader. System/in StandardCharsets/UTF_8))]
        (loop []
          (when-let [line (.readLine in)]
            (when-not (str/blank? line)
              (when-let [resp (rpc/handle-line sys line)]
                (.write out (str resp "\n"))
                (.flush out)))
            (recur)))))
    (shutdown! sys)
    (shutdown-agents)
    (System/exit 0)))
