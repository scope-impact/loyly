(ns measurement.seam
  "The public seam (.pi/spec/measurement/seam.md): JSON-RPC 2.0, one method per
   command and query of the event model and one per consumed handoff event."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.time.interface :as time]
            [clj-uuid :as uuid]
            [clojure.data.json :as json]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [malli.util :as mu]
            [measurement.model :as model]
            [measurement.workflows :as wf])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Params
;; ---------------------------------------------------------------------------

(defn- parse-instant [x]
  (if (string? x)
    (try (Instant/parse x) (catch Exception _ x))
    x))

(def ^:private instant-transformer
  (mt/transformer {:name :iso-instants
                   :decoders {'inst? parse-instant
                              'clojure.core/inst? parse-instant}}))

(defn- keywordize
  "JSON objects with keyword keys, recursively."
  [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(keyword k) (keywordize v)])) x)
    (sequential? x) (mapv keywordize x)
    :else x))

(defn conform-params
  "Params checked against the block's schema, closed: {:ok params} or {:invalid explain}."
  [schema params]
  (let [s (mu/closed-schema (m/schema schema))
        p (m/decode s (keywordize (or params {})) instant-transformer)]
    (if (m/validate s p)
      {:ok p}
      {:invalid (me/humanize (m/explain s p))})))

;; ---------------------------------------------------------------------------
;; Methods
;; ---------------------------------------------------------------------------

(def command-methods
  (into {} (map (fn [c] [(str (namespace c) "/" (name c)) c])) wf/commands))

(def query-methods
  {"measurement/checklist" [:measurement/checklist :checklist]
   "measurement/cycle-measurement" [:measurement/cycle-measurement :measurement]
   "measurement/scoreboard" [:measurement/scoreboard :scoreboard]
   "measurement/measurements-to-report" [:measurement/measurements-to-report :cycle-ids]})

(def handoff-methods
  (into {} (map (fn [e] [(str (namespace e) "/" (name e)) e])) model/consumed-handoffs))

(defn- error
  ([code message] (error code message nil))
  ([code message data]
   {:error (cond-> {"code" code "message" message} data (assoc "data" data))}))

(defn- json-ready [x]
  (cond
    (instance? Instant x) (str x)
    (map? x) (into {} (map (fn [[k v]] [(if (keyword? k) (name k) k) (json-ready v)])) x)
    (sequential? x) (mapv json-ready x)
    (set? x) (mapv json-ready x)
    :else x))

(defn- grain-query [ctx query-name params]
  (qp/process-query (assoc (wf/grain-context ctx) :query
                           (merge params {:query/name query-name
                                          :query/id (random-uuid)
                                          :query/timestamp (time/now)}))))

(defn- measurement-filed-id [ctx cycle-id]
  (some #(when (= cycle-id (:cycle-id %)) (:event/id %))
        (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                              :types #{:measurement/measurement-filed}}))))

(defn- policy-done? [ctx event-id]
  (some #(and (= :measurement/report-scoreboard-once (:processor/name %))
              (not (uuid/< (:triggered-by %) event-id)))
        (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                              :types #{:grain/todo-processor-checkpoint}}))))

(defn- await-policy
  "The report-scoreboard-once policy runs before file-score responds."
  [ctx cycle-id]
  (when-let [event-id (measurement-filed-id ctx cycle-id)]
    (when-not (wf/await-until #(policy-done? ctx event-id) 30000)
      (binding [*out* *err*] (println "warning: report-scoreboard-once did not finish for" cycle-id)))))

(defn- outcome->response [outcome]
  (cond
    (get outcome "events") {:result {"events" (get outcome "events")}}
    (get outcome "refusal") (let [r (get outcome "refusal")]
                              (error -32000 (get r "message") (dissoc r "message")))
    :else (error -32603 (str "Internal error: " (get-in outcome ["error" "message"]))
                 (get outcome "error"))))

(defn- run-command [ctx command-name params]
  (let [{:keys [ok invalid]} (conform-params (model/schema-of :commands command-name) params)]
    (if invalid
      (error -32602 "Invalid params" {"explain" (json-ready invalid)})
      (let [outcome (wf/execute-command! ctx command-name ok)]
        (when (and (= command-name :measurement/file-score) (get outcome "events"))
          (await-policy ctx (:cycle-id ok)))
        (outcome->response outcome)))))

(defn- run-query [ctx [query-name result-key] params]
  (let [{:keys [ok invalid]} (conform-params (model/schema-of :queries query-name) params)]
    (if invalid
      (error -32602 "Invalid params" {"explain" (json-ready invalid)})
      (let [r (grain-query ctx query-name ok)]
        (if (:cognitect.anomalies/category r)
          (error -32603 (str "Internal error: " (:cognitect.anomalies/message r)))
          {:result {(name result-key) (json-ready (get (:query/result r) result-key))}})))))

(defn- hand-in [ctx event-type params]
  (let [{:keys [ok invalid]} (conform-params (model/schema-of :events event-type) params)]
    (if invalid
      (error -32602 "Invalid params" {"explain" (json-ready invalid)})
      (let [event (es/->event {:type event-type :body ok})
            r (es/append (:event-store ctx) {:tenant-id (:tenant-id ctx) :events [event]})]
        (if (:cognitect.anomalies/category r)
          (error -32603 (str "Internal error: " (:cognitect.anomalies/message r)))
          {:result {"events" [(wf/event->json event)]}})))))

(defn dispatch
  "Answer one method call: {:result ...} or {:error ...}."
  [ctx method params]
  (cond
    (and (some? params) (not (map? params)))
    (error -32602 "Invalid params: params must be an object")

    (contains? command-methods method) (run-command ctx (command-methods method) params)
    (contains? query-methods method) (run-query ctx (query-methods method) params)
    (contains? handoff-methods method) (hand-in ctx (handoff-methods method) params)
    :else (error -32601 (str "Method not found: " method))))

(defn handle-line
  "One request line in, one response line out (nil for a notification)."
  [ctx line]
  (let [req (try (json/read-str line) (catch Exception _ ::parse-error))]
    (cond
      (= ::parse-error req)
      (json/write-str {"jsonrpc" "2.0" "id" nil "error" {"code" -32700 "message" "Parse error"}})

      (not (and (map? req) (string? (get req "method"))))
      (json/write-str {"jsonrpc" "2.0"
                       "id" (when (map? req) (get req "id"))
                       "error" {"code" -32600 "message" "Invalid Request"}})

      :else
      (let [id (get req "id")
            r (try (dispatch ctx (get req "method") (get req "params"))
                   (catch Throwable t
                     (binding [*out* *err*] (println "error:" (.getMessage t)))
                     (error -32603 (str "Internal error: " (.getMessage t)))))]
        (when (contains? req "id")
          (json/write-str (if (:error r)
                            {"jsonrpc" "2.0" "id" id "error" (:error r)}
                            {"jsonrpc" "2.0" "id" id "result" (:result r)})
                          :escape-slash false))))))
