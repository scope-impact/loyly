(ns loyly.grader-lifecycle.seam
  "The public seam (.pi/spec/grader-lifecycle/seam.md): JSON-RPC 2.0 over stdio,
   one method per command and query of the event model, named as the block.
   A command method runs as its ORC workflow; a query method runs none."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.interface :as orc]
            [clojure.data.json :as json]
            [cognitect.anomalies :as anom]
            [loyly.grader-lifecycle.model :as model]
            [loyly.grader-lifecycle.system :as system]
            [clojure.string]
            [malli.core :as m]
            [malli.error :as me])
  (:gen-class))

(def command-methods
  #{:grader-lifecycle/write-instrument-golden
    :grader-lifecycle/move-instrument-golden})

(def query-methods
  #{:grader-lifecycle/instrument-goldens})

(defn- method-key [method]
  (when (string? method) (keyword method)))

(defn- ok [id result] {"jsonrpc" "2.0" "id" id "result" result})

(defn- err
  ([id code message] (err id code message nil))
  ([id code message data]
   {"jsonrpc" "2.0" "id" id
    "error" (cond-> {"code" code "message" message}
              data (assoc "data" data))}))

(defn- params-schema
  "The command's schema, with the note's {:min 1} left to the rule's own
   refusal (empty-note), which the Allium orders."
  [command-name]
  (let [schema (model/command-schema command-name)]
    (if (= command-name :grader-lifecycle/move-instrument-golden)
      (into [:map] (map (fn [[k & more :as entry]]
                          (if (= k :note) [:note :string] entry))
                        (rest schema)))
      schema)))

(defn- keywordize [params]
  (into {} (map (fn [[k v]] [(keyword k) v])) params))

(defn- traced?
  "Whether the run's execution trace is in the store."
  [{:keys [event-store tenant-id]} tick-id]
  (some #(= tick-id (:trace-id %))
        (into [] (es/read event-store {:tenant-id tenant-id
                              :types #{:sheet/execution-traced}
                              :reverse? true
                              :limit 50}))))

(defn- await-trace!
  "Answers only once the run is complete in the store, its trace included, so
   no response leaves a run behind half recorded."
  [ctx tick-id]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (when-not (or (traced? ctx tick-id) (> (System/currentTimeMillis) deadline))
        (Thread/sleep 10)
        (recur)))))

(defn- run-command [{:keys [ctx sheet-id]} id command-name params]
  (let [fields (set (map first (rest (model/command-schema command-name))))
        params (select-keys params fields)
        tick-id (random-uuid)
        result (orc/execute ctx sheet-id
                            {:request (json/write-str {"command" (subs (str command-name) 1)
                                                       "params" params})}
                            :timeout-ms 20000
                            :tick-id tick-id)
        _ (await-trace! ctx tick-id)
        outcome (some-> (get-in result [:outputs :outcome]) json/read-str)]
    (cond
      (not= :success (:status result))
      (err id -32603 (str "workflow run did not succeed: " (or (:error result) (:status result))))

      (get outcome "refusal")
      (err id -32000 (get outcome "message") {"refusal" (get outcome "refusal")})

      (get outcome "error")
      (err id -32603 (get outcome "error"))

      :else
      (ok id {"events" (get outcome "events")}))))

(defn- run-query [{:keys [ctx]} id query-name params]
  (let [result (qp/process-query
                (assoc ctx :query (merge params
                                         {:query/name query-name
                                          :query/id (random-uuid)
                                          :query/timestamp (time/now)})))]
    (if (::anom/category result)
      (err id -32603 (str (::anom/message result)))
      (ok id (json/read-str (json/write-str (:query/result result)))))))

(defn handle-request
  "Answers one decoded JSON-RPC request map."
  [sys req]
  (let [id (get req "id")
        method (get req "method")
        params (get req "params" {})
        k (method-key method)]
    (cond
      (not (and (map? req) (= "2.0" (get req "jsonrpc")) (string? method)))
      (err id -32600 "Invalid Request")

      (not (or (contains? command-methods k) (contains? query-methods k)))
      (err id -32601 (str "Method not found: " method))

      (not (map? params))
      (err id -32602 "params must be an object")

      (contains? command-methods k)
      (let [p (keywordize params)
            schema (params-schema k)]
        (if-let [explain (m/explain schema p)]
          (err id -32602 "Invalid params" {"errors" (pr-str (me/humanize explain))})
          (run-command sys id k p)))

      :else
      (let [p (keywordize params)]
        (if-let [explain (m/explain (model/query-schema k) p)]
          (err id -32602 "Invalid params" {"errors" (pr-str (me/humanize explain))})
          (run-query sys id k p))))))

(defn handle-line
  "Answers one line of input with one response map."
  [sys line]
  (let [req (try (json/read-str line) (catch Exception _ ::parse-error))]
    (cond
      (= ::parse-error req) (err nil -32700 "Parse error")
      (not (map? req)) (err nil -32600 "Invalid Request")
      :else (try
              (handle-request sys req)
              (catch Exception e
                (binding [*out* *err*]
                  (println "internal error:" (.getMessage e)))
                (err (get req "id") -32603 (str "Internal error: " (.getMessage e))))))))

(defn serve
  "Reads requests from `in`, one per line, and writes one response per line
   to `out`."
  [sys ^java.io.BufferedReader in ^java.io.Writer out]
  (loop []
    (when-let [line (.readLine in)]
      (when-not (clojure.string/blank? line)
        (let [resp (handle-line sys line)]
          (.write out (json/write-str resp :escape-slash false))
          (.write out "\n")
          (.flush out)))
      (recur))))

(defn -main [& _]
  ;; stdout is the seam's alone: everything else goes to stderr.
  (let [stdout (java.io.OutputStreamWriter. System/out "UTF-8")]
    (System/setOut (java.io.PrintStream. (java.io.FileOutputStream. java.io.FileDescriptor/err) true "UTF-8"))
    (alter-var-root #'*out* (constantly (java.io.OutputStreamWriter. System/err "UTF-8")))
    (let [sys (system/start {:store-path (System/getenv "LOYLY_GRAIN_STORE")
                             :digests-path (System/getenv "LOYLY_SCENARIO_DIGESTS")})]
      (binding [*out* *err*] (println "grader-lifecycle candidate ready"))
      (serve sys (java.io.BufferedReader. (java.io.InputStreamReader. System/in "UTF-8")) stdout)
      (system/stop sys)
      (shutdown-agents)
      (System/exit 0))))
