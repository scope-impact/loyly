(ns measurement.workflows
  "Each command runs as the ORC workflow named after the flow its command belongs
   to in the event model. A workflow is a sequence holding one code leaf, which
   processes the command through Grain's command processor on the product's own
   store, so the command's events are written inside the workflow's run."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.interface :as orc]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [measurement.model :as model]
            [measurement.read-models :as rm])
  (:import [java.time Instant]))

(def commands
  [:measurement/derive-checklist
   :measurement/file-item-verdicts
   :measurement/file-score
   :measurement/fail-score-stage
   :measurement/report-scoreboard])

(defn workflow-name [command-name]
  (let [flow (model/flow-of-command command-name)]
    (when-not flow
      (throw (ex-info (str "Command " command-name " belongs to no flow of the model") {:command command-name})))
    (str (namespace flow) "/" (name flow))))

(defn- json-ready [x]
  (cond
    (instance? Instant x) (str x)
    (inst? x) (str (.toInstant ^java.util.Date x))
    (map? x) (into {} (map (fn [[k v]] [k (json-ready v)])) x)
    (sequential? x) (mapv json-ready x)
    (set? x) (mapv json-ready x)
    :else x))

(defn event->json
  "An event as the seam shows it: its type and exactly its schema's fields."
  [event]
  (let [t (:event/type event)]
    (json-ready (into {"type" (str (namespace t) "/" (name t))}
                      (map (fn [[k v]] [(name k) v]))
                      (rm/body event)))))

(defn outcome
  "The JSON-ready outcome of a Grain command result."
  [result]
  (if (:cognitect.anomalies/category result)
    (if-let [code (:measurement/refusal result)]
      {"refusal" (cond-> {"refusal" code
                          "message" (:cognitect.anomalies/message result)}
                   (:measurement/item-ids result) (assoc "item-ids" (:measurement/item-ids result))
                   (:measurement/unstamped result) (assoc "unstamped" (:measurement/unstamped result))
                   (:measurement/stamper result) (assoc "stamper" (:measurement/stamper result)))}
      {"error" {"message" (str (:cognitect.anomalies/message result))
                "category" (str (:cognitect.anomalies/category result))
                "explain" (pr-str (:error/explain result))}})
    {"events" (mapv event->json (:command-result/events result))}))

(defn grain-context [ctx]
  (select-keys ctx [:event-store :cache :tenant-id :event-pubsub]))

(defn process-command
  "Process one command through Grain's command processor."
  [ctx command-name params]
  (cp/process-command
   (assoc (grain-context ctx) :command
          (merge params {:command/name command-name
                         :command/id (random-uuid)
                         :command/timestamp (time/now)}))))

(defn run-command
  "The code leaf of every command workflow."
  [{:keys [inputs] :as ctx}]
  (try
    (let [{:keys [command-name params]} (edn/read-string (get inputs :request))]
      {:response (json/write-str (outcome (process-command ctx command-name params)))})
    (catch Throwable t
      {:response (json/write-str {"error" {"message" (str "command failed: " (.getMessage t))}})})))

(defn workflow [wf-name]
  (orc/workflow wf-name
    (orc/blackboard
     {:request [:string {:description "The command, as EDN: its name and its params."}]
      :response [:string {:description "The command's outcome, as JSON: its events or its refusal."}]})
    (orc/sequence "run"
      (orc/code "process-command"
        :fn "measurement.workflows/run-command"
        :reads [:request]
        :writes [:response]))))

(def sheet-ids (atom {}))

(defn build-all!
  "Build every command workflow on the product's store (idempotent)."
  [ctx]
  (doseq [wf-name (distinct (map workflow-name commands))]
    (swap! sheet-ids assoc wf-name (orc/build-workflow! ctx (workflow wf-name)))))

(defn- traced? [ctx tick-id]
  (some #(= tick-id (:trace-id %))
        (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                              :types #{:sheet/execution-traced}
                                              :tags #{[:tick tick-id]}}))))

(defn await-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(defn execute-command!
  "Run `command-name` with `params` as its ORC workflow and return its outcome.
   Waits for the run's execution trace, so each run completes before the next."
  [ctx command-name params]
  (let [wf-name (workflow-name command-name)
        sheet-id (or (get @sheet-ids wf-name)
                     (throw (ex-info (str "Workflow not built: " wf-name) {})))
        tick-id (random-uuid)
        r (orc/execute ctx sheet-id {:request (pr-str {:command-name command-name :params params})}
                       :timeout-ms 30000 :tick-id tick-id)]
    (when-not (await-until #(traced? ctx tick-id) 10000)
      (binding [*out* *err*] (println "warning: no execution trace for tick" tick-id)))
    (if (= :success (:status r))
      (json/read-str (get-in r [:outputs :response]))
      {"error" {"message" (str "workflow " wf-name " " (name (:status r)) ": " (:error r))}})))
