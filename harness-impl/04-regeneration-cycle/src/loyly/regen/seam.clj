(ns loyly.regen.seam
  "The public seam (seam.md): JSON-RPC 2.0 over stdio, one JSON object per line,
   one response per request. Nothing but responses goes to stdout."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [ai.obney.grain.anomalies.interface :refer [anomaly?]]
            [loyly.regen.model :as model]
            [loyly.regen.system :as system])
  (:import [java.time Instant OffsetDateTime ZonedDateTime]
           [java.io BufferedReader PrintStream OutputStreamWriter BufferedWriter]))

;; ---------------------------------------------------------------------------
;; Methods
;; ---------------------------------------------------------------------------

(def command-methods (set (keys system/command-workflow)))
(def query-methods #{:regeneration-cycle/cycle-progress :regeneration-cycle/cycles-to-conclude})
(def handoff-methods (set (keys model/consumed-handoffs)))

;; ---------------------------------------------------------------------------
;; JSON <-> params, guided by the model's schemas
;; ---------------------------------------------------------------------------

(defn- parse-instant [s]
  (or (try (Instant/parse s) (catch Exception _ nil))
      (try (.toInstant (OffsetDateTime/parse s)) (catch Exception _ nil))
      (try (.toInstant (ZonedDateTime/parse s)) (catch Exception _ nil))))

(defn- props+children [schema]
  (let [[_ maybe-props & more] schema]
    (if (map? maybe-props) [maybe-props more] [nil (rest schema)])))

(defn coerce
  "Turns parsed JSON (keyword keys) into the schema's values: instants from text."
  [schema v]
  (cond
    (= 'inst? schema) (if (string? v) (or (parse-instant v) v) v)
    (vector? schema)
    (let [[t] schema
          [_ children] (props+children schema)]
      (case t
        :map (if (map? v)
               (reduce (fn [acc entry]
                         (let [[k & r] entry
                               s (last r)]
                           (if (contains? acc k) (update acc k #(coerce s %)) acc)))
                       v children)
               v)
        :vector (if (sequential? v) (mapv #(coerce (first children) %) v) v)
        :maybe (if (nil? v) v (coerce (first children) v))
        v))
    :else v))

(defn- closed [schema]
  (if (and (vector? schema) (= :map (first schema)))
    (let [[props children] (props+children schema)]
      (into [:map (assoc (or props {}) :closed true)]
            (map (fn [entry] (let [[k & r] entry]
                               (if (map? (first r))
                                 [k (first r) (closed (last r))]
                                 [k (closed (last r))])))
                 children)))
    (if (and (vector? schema) (#{:vector :maybe} (first schema)))
      (let [[props children] (props+children schema)]
        (into (if props [(first schema) props] [(first schema)]) (map closed children)))
      schema)))

(defn- keywordize [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(keyword k) (keywordize v)])) x)
    (sequential? x) (mapv keywordize x)
    :else x))

(defn- ->json-value [x]
  (cond
    (instance? Instant x) (str x)
    (instance? OffsetDateTime x) (str (.toInstant ^OffsetDateTime x))
    (instance? java.util.Date x) (str (.toInstant ^java.util.Date x))
    (keyword? x) (if (namespace x) (str (namespace x) "/" (name x)) (name x))
    (map? x) (into {} (map (fn [[k v]] [(->json-value k) (->json-value v)])) x)
    (set? x) (mapv ->json-value x)
    (sequential? x) (mapv ->json-value x)
    (uuid? x) (str x)
    :else x))

(defn event->json [e]
  (let [t (:event/type e)]
    (->json-value (assoc (into {} (remove (fn [[k _]] (= "event" (namespace k)))) e)
                         :type (str (namespace t) "/" (name t))))))

;; ---------------------------------------------------------------------------
;; Responses
;; ---------------------------------------------------------------------------

(defn- ok [id result] {"jsonrpc" "2.0" "id" id "result" result})
(defn- err [id code message & [data]]
  {"jsonrpc" "2.0" "id" id
   "error" (cond-> {"code" code "message" message} data (assoc "data" data))})

(defn- invalid-params
  ([id explain] (invalid-params id explain nil))
  ([id explain refusal-code]
   (err id -32602 (str "Invalid params" (when refusal-code (str ": " refusal-code)))
        (cond-> {"errors" (->json-value (me/humanize explain))}
          refusal-code (assoc "refusal" refusal-code)))))

(defn- budget-out-of-bounds?
  "RecordHarnessSelfCheck requires 7 restates the schema's budget bounds: when the
   budget's limits are the only mismatch, the -32602 also names that refusal."
  [mk schema params]
  (when (= mk :regeneration-cycle/record-harness-self-check)
    (let [p (coerce schema (keywordize params))
          b (:budget p)
          ok-budget {:gate-attempt-limit 3 :max-outer-cycles 1 :max-continuations 0
                     :stage-timeouts-seconds {:fence 1 :regenerate 1 :gate 1 :score 1}}]
      (and (map? b)
           (int? (:gate-attempt-limit b)) (int? (:max-continuations b))
           (not (and (<= 1 (:gate-attempt-limit b) 3) (<= 0 (:max-continuations b) 3)))
           (m/validate schema (assoc p :budget (assoc b :gate-attempt-limit 3 :max-continuations 0)))
           (m/validate schema (assoc p :budget ok-budget))))))

(defn- refusal [id result]
  (if-let [code (:refusal/code result)]
    (err id -32000 (str (:cognitect.anomalies/message result))
         (cond-> {"refusal" code}
           (:refusal/paths result) (assoc "paths" (:refusal/paths result))
           (:refusal/goldens result) (assoc "goldens" (:refusal/goldens result))))
    (err id -32603 (str "Internal error: " (:cognitect.anomalies/message result)))))

(defn- validated [schema params]
  (let [v (coerce schema (keywordize params))]
    (if-let [ex (m/explain schema v)]
      [nil ex]
      [v nil])))

(defn dispatch
  "Handles one parsed request map; returns the response map, or nil for a notification."
  [req]
  (let [id (get req "id")
        method (get req "method")
        params (get req "params" {})]
    (cond
      (not (and (map? req) (string? method)))
      (err id -32600 "Invalid Request")

      (not (map? params))
      (err id -32602 "Invalid params: params must be an object")

      :else
      (let [mk (keyword method)]
        (cond
          (contains? command-methods mk)
          (let [schema (model/command-schema mk)
                [p ex] (validated schema params)]
            (if ex
              (invalid-params id ex (when (budget-out-of-bounds? mk schema params) "budget-out-of-bounds"))
              (let [r (system/command! mk p)]
                (if (anomaly? r)
                  (refusal id r)
                  (ok id {"events" (mapv event->json (:command-result/events r))})))))

          (contains? handoff-methods mk)
          (let [schema (closed (model/event-schema mk))
                [p ex] (validated schema params)]
            (if ex
              (invalid-params id ex)
              (let [r (system/handoff! mk p)]
                (if (anomaly? r)
                  (err id -32602 (str "Invalid params: " (:cognitect.anomalies/message r)))
                  (ok id {"events" (mapv event->json (:events r))})))))

          (contains? query-methods mk)
          (let [schema (model/query-schema mk)
                [p ex] (validated schema params)]
            (if ex
              (invalid-params id ex)
              (let [r (system/query mk p)]
                (if (anomaly? r)
                  (err id -32603 (str "Internal error: " (:cognitect.anomalies/message r)))
                  (ok id (->json-value (:query/result r)))))))

          :else (err id -32601 (str "Method not found: " method)))))))

(defn handle-line
  "One request line in, one response line out (nil when the line is blank)."
  [^String line]
  (when-not (str/blank? line)
    (let [parsed (try {:ok (json/read-str line)} (catch Exception _ {:bad true}))]
      (json/write-str
       (if (:bad parsed)
         (err nil -32700 "Parse error")
         (try
           (dispatch (:ok parsed))
           (catch Throwable t
             (binding [*out* *err*] (println "request failed:" t))
             (err (when (map? (:ok parsed)) (get (:ok parsed) "id")) -32603
                  (str "Internal error: " (ex-message t))))))
       :escape-slash false))))

(defn -main [& _]
  (let [stdout System/out
        out (BufferedWriter. (OutputStreamWriter. stdout "UTF-8"))]
    ;; Nothing but responses on stdout: everything else goes to stderr.
    (System/setOut System/err)
    (alter-var-root #'*out* (constantly (java.io.PrintWriter. System/err true)))
    (try
      (system/start!)
      (catch Throwable t
        (binding [*out* *err*]
          (println "refusing to start:" (ex-message t))
          (when-let [d (ex-data t)] (prn (select-keys d [:type]))
                                    (doseq [f (get-in d [:verdict :findings])
                                            :when (= :error (:severity f))]
                                      (prn f))))
        (System/exit 1)))
    (binding [*out* *err*] (println "loyly regeneration-cycle: ready"))
    (let [in (BufferedReader. (java.io.InputStreamReader. System/in "UTF-8"))]
      (loop []
        (when-let [line (.readLine in)]
          (when-let [resp (handle-line line)]
            (.write out ^String resp)
            (.write out "\n")
            (.flush out))
          (recur))))
    (system/stop!)
    (shutdown-agents)
    (System/exit 0)))
