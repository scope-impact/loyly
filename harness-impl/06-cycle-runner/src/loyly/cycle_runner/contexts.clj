(ns loyly.cycle-runner.contexts
  "The five contexts, each started for a run from its launch entrypoint (LOYLY_CONTEXTS) on
   a fresh store in the run's directory and reached only over its public seam: JSON-RPC 2.0
   over stdio. A context responds when it replies with a response carrying the request's id."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.cycle-runner.util :as u])
  (:import [java.io BufferedReader InputStreamReader Writer OutputStreamWriter]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]))

(def names ["specification" "cycle-preparation" "grader-lifecycle" "regeneration-cycle" "measurement"])

(def probe-query
  "A query of each context's own seam, asked to see that it responds."
  {"specification" ["specification/stamped-items" {}]
   "cycle-preparation" ["cycle-preparation/source-cuts" {}]
   "grader-lifecycle" ["grader-lifecycle/instrument-goldens" {}]
   "regeneration-cycle" ["regeneration-cycle/cycles-to-conclude" {}]
   "measurement" ["measurement/measurements-to-report" {}]})

(defn timeout-ms [env-fn k default-seconds]
  (* 1000 (or (some-> (env-fn k) parse-long) default-seconds)))

(defn start-client
  "Start one context's entrypoint with env; its stderr goes to log-file."
  [name entrypoint env log-file]
  (let [p (u/start-process [entrypoint] {:dir (.getParentFile (io/file entrypoint))
                                         :env env :err (io/file log-file)})
        pending (atom {})
        alive (atom true)
        writer (OutputStreamWriter. (.getOutputStream p) StandardCharsets/UTF_8)
        reader (BufferedReader. (InputStreamReader. (.getInputStream p) StandardCharsets/UTF_8))
        thread (Thread.
                (fn []
                  (try
                    (loop []
                      (when-let [line (.readLine reader)]
                        (when-not (str/blank? line)
                          (try
                            (let [msg (u/parse-json line)]
                              (when (map? msg)
                                (when-let [pr (get @pending (:id msg))]
                                  (deliver pr msg))))
                            (catch Exception _ nil)))
                        (recur)))
                    (catch Exception _ nil)
                    (finally
                      (reset! alive false)
                      (doseq [[_ pr] @pending] (deliver pr ::no-response))))))]
    (.setDaemon thread true)
    (.start thread)
    {:name name :process p :pending pending :alive alive :writer writer :next-id (atom 0)}))

(defn call
  "Call method with params. Returns {:result r}, {:error e}, or ::no-response when the
   context exited, closed its output or did not answer within timeout-ms."
  [{:keys [pending alive ^Writer writer next-id]} method params timeout-ms]
  (let [id (swap! next-id inc)
        pr (promise)]
    (swap! pending assoc id pr)
    (try
      (if-not @alive
        ::no-response
        (do
          (locking writer
            (.write writer (str (u/->json {:jsonrpc "2.0" :id id :method method :params params}) "\n"))
            (.flush writer))
          (let [r (deref pr timeout-ms ::no-response)]
            (if (map? r) (select-keys r [:result :error]) ::no-response))))
      (catch Exception _ ::no-response)
      (finally (swap! pending dissoc id)))))

(defn responds? [client timeout-ms]
  (let [[m params] (probe-query (:name client))]
    (map? (call client m params timeout-ms))))

(defn stop-client [{:keys [^Process process ^Writer writer]}]
  (try (.close writer) (catch Exception _ nil))
  (when process
    (when-not (.waitFor process 2 TimeUnit/SECONDS)
      (u/kill-tree! process))
    (u/kill-tree! process)))

(defn stop-all [clients]
  (doseq [c (vals clients)] (stop-client c)))
