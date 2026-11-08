(ns loyly.specification.rpc
  "The public seam (seam.md): JSON-RPC 2.0, one method per command and query of
   the event model, named as in the model."
  (:require [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.time.interface :as time]
            [clojure.data.json :as json]
            [malli.core :as m]
            [malli.error :as me]
            [loyly.specification.workflow :as workflow])
  (:import [java.time Instant]
           [java.util Date]))

(def ^:private methods
  {"specification/stamp-slice" {:kind :command :block :specification/stamp-slice}
   "specification/stamped-items" {:kind :query :block :specification/stamped-items}})

(defn- error [id code message & [data]]
  {"jsonrpc" "2.0" "id" id
   "error" (cond-> {"code" code "message" message}
             data (assoc "data" data))})

(defn- success [id result]
  {"jsonrpc" "2.0" "id" id "result" result})

(defn- write-value [_k v]
  (cond
    (instance? Date v) (str (.toInstant ^Date v))
    (instance? Instant v) (str v)
    (keyword? v) (if-let [n (namespace v)] (str n "/" (name v)) (name v))
    :else v))

(defn- event->json [event]
  (-> event
      (dissoc :event/type :event/tags :event/id :event/timestamp)
      (assoc :type (:event/type event))))

(defn- refusal-error [id reply]
  (if (:refusal reply)
    (error id -32000 (:message reply) {"refusal" (:refusal reply)})
    (error id -32603 (str (:message reply)))))

(defn- run-command [system id params]
  (let [reply (workflow/run-stamping! (:ctx system) (:sheet-id system) params)]
    (if (:anomaly reply)
      (refusal-error id reply)
      (success id {"events" (mapv event->json (:events reply))}))))

(defn- run-query [system id block params]
  (let [result (qp/process-query
                (assoc (:ctx system) :query
                       (merge params {:query/name block
                                      :query/id (random-uuid)
                                      :query/timestamp (time/now)})))]
    (if (:cognitect.anomalies/category result)
      (error id -32603 (str (:cognitect.anomalies/message result)))
      (success id {"slices" (:query/result result)}))))

(defn handle
  "One request map (JSON-decoded, string keys) to one response map, or nil
   for a notification."
  [system request]
  (let [id (get request "id")
        method (get request "method")
        raw-params (get request "params" {})]
    (cond
      (not (and (map? request) (string? method)))
      (error id -32600 "Invalid Request")

      (not (contains? methods method))
      (when (contains? request "id") (error id -32601 (str "Method not found: " method)))

      :else
      (let [{:keys [kind block]} (methods method)
            params (if (map? raw-params)
                     (update-keys raw-params keyword)
                     ::invalid)
            params (if (and (map? params) (sequential? (:unstamped params)))
                     (update params :unstamped
                             (fn [xs] (mapv #(if (map? %) (update-keys % keyword) %) xs)))
                     params)]
        (if-let [explain (cond
                           (= ::invalid params) {:params ["must be an object"]}
                           :else (some-> (m/explain block params) me/humanize))]
          (error id -32602 "Invalid params" {"explain" (pr-str explain)})
          (try
            (case kind
              :command (run-command system id params)
              :query (run-query system id block params))
            (catch Throwable t
              (error id -32603 (str "Internal error: " (ex-message t))))))))))

(defn handle-line
  "One line of JSON text to one line of JSON text, or nil when no response is due."
  [system line]
  (let [request (try (json/read-str line) (catch Throwable _ ::parse-error))
        response (if (= ::parse-error request)
                   (error nil -32700 "Parse error")
                   (handle system request))]
    (when response
      (json/write-str response :value-fn write-value :escape-slash false))))
