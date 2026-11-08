(ns loyly.cycle-preparation.seam
  "The public seam (seam.md): JSON-RPC 2.0, one method per command and query of
   the event model, named as the block. A command method runs as its ORC
   workflow; a query method runs none."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [loyly.cycle-preparation.model :as model]))

(def ^:private command-lock (Object.))

(defn- error [id code message & [data]]
  {:jsonrpc "2.0" :id id
   :error (cond-> {:code code :message message} data (assoc :data data))})

(defn- ok [id result] {:jsonrpc "2.0" :id id :result result})

(defn- method->block
  "{:kind :command|:query :name kw :schema s} for a method of the model."
  [method]
  (when (string? method)
    (let [k (keyword method)
          am (model/area-map)]
      (cond
        (contains? (:commands am) k) {:kind :command :name k :schema (get-in am [:commands k :schema])}
        (contains? (:queries am) k) {:kind :query :name k :schema (get-in am [:queries k :schema])}))))

(defn- schema-keys [schema]
  (let [s (m/schema schema)]
    (when (= :map (m/type s))
      (mapv first (m/children s)))))

(defn- coerce-params
  "Params with keywordised field names, restricted to the schema's fields."
  [schema params]
  (let [kp (into {} (map (fn [[k v]] [(keyword k) v])) params)]
    (if-let [ks (seq (schema-keys schema))]
      (select-keys kp ks)
      {})))

(defn- json-value [v]
  (cond (instance? java.time.Instant v) (str v)
        (instance? java.util.Date v) (str (.toInstant ^java.util.Date v))
        (keyword? v) (subs (str v) 1)
        :else v))

(defn- traced?
  "Has the run's execution trace been stored?"
  [{:keys [event-store tenant-id]} tick-id]
  (boolean (some #(= tick-id (:trace-id %))
                 (into [] (es/read event-store {:tenant-id tenant-id
                                                :types #{:sheet/execution-traced}
                                                :reverse? true
                                                :limit 50})))))

(defn- await-trace!
  "Answer only once the run is wholly recorded: its trace follows its
   completion asynchronously."
  [sys tick-id]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (when-not (or (traced? sys tick-id) (> (System/currentTimeMillis) deadline))
        (Thread/sleep 10)
        (recur)))))

(defn- run-command [sys {:keys [name]} params]
  (let [sheet-id (get-in sys [:sheets name])
        tick-id (random-uuid)
        ;; Commands are serialised: one cut identity is pinned once.
        result (locking command-lock
                 (let [r (orc/execute sys sheet-id params :timeout-ms 25000 :tick-id tick-id)]
                   (await-trace! sys (or (:trace-id r) tick-id))
                   r))
        outputs (:outputs result)
        outcome (or (get outputs "outcome") (get outputs :outcome))]
    (cond
      (not= :success (:status result))
      {:error {:code -32603 :message (str "workflow run failed: " (:error result))}}

      (:refusal outcome)
      {:error {:code -32000 :message (or (:message outcome) (:refusal outcome))
               :data {:refusal (:refusal outcome)}}}

      :else
      {:result {:events (:events outcome)}})))

(defn- run-query [sys {:keys [name]} params]
  (let [res (qp/process-query (assoc sys :query (merge params
                                                       {:query/id (random-uuid)
                                                        :query/timestamp (time/now)
                                                        :query/name name})))]
    (if (:cognitect.anomalies/category res)
      {:error {:code -32603 :message (str (:cognitect.anomalies/message res))}}
      (let [rows (mapv (fn [row] (into {} (map (fn [[k v]] [k (json-value v)])) row))
                       (:query/result res))]
        {:result (case name
                   :cycle-preparation/source-cuts {:cuts rows}
                   {:rows rows})}))))

(defn handle-request
  "One parsed request map (string keys) -> one response map."
  [sys req]
  (let [id (get req "id")
        method (get req "method")
        params (get req "params" {})]
    (cond
      (not (and (map? req) (= "2.0" (get req "jsonrpc")) (string? method)))
      (error id -32600 "Invalid Request")

      :else
      (if-let [block (method->block method)]
        (if-not (map? params)
          (error id -32602 "Invalid params: params must be an object")
          (let [p (coerce-params (:schema block) params)]
            (if-let [explain (m/explain (:schema block) (into {} (map (fn [[k v]] [(keyword k) v])) params))]
              (error id -32602 "Invalid params"
                     {:errors (pr-str (me/humanize explain))})
              (let [{:keys [result] err :error}
                    (try
                      (if (= :command (:kind block))
                        (run-command sys block p)
                        (run-query sys block p))
                      (catch Throwable t
                        (binding [*out* *err*]
                          (println "cycle-preparation: internal error" (ex-message t)))
                        {:error {:code -32603 :message (str "Internal error: " (ex-message t))}}))]
                (if err
                  (error id (:code err) (:message err) (:data err))
                  (ok id result))))))
        (error id -32601 (str "Method not found: " method))))))

(defn handle-line
  "One line of input -> one JSON line of output (nil for a blank line)."
  [sys line]
  (when-not (str/blank? line)
    (let [req (try (json/read-str line) (catch Exception _ ::invalid))]
      (json/write-str
       (if (= ::invalid req)
         (error nil -32700 "Parse error")
         (handle-request sys req))))))
