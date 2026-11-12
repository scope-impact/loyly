(ns runner.model
  (:require [clojure.edn :as edn] [clojure.java.io :as io]
            [ai.obney.grain.event-model.interface :as em]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.event-definition :as ed]
            [ai.obney.grain.schema-util.interface :as su]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rm]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [runner.util :as u]))
(defn deep-merge [& xs] (if (every? map? xs) (apply merge-with deep-merge xs) (last xs)))
(defn load-model [context]
  (apply deep-merge (map #(edn/read-string (slurp %)) (sort (filter #(.endsWith (.getName %) ".edn") (.listFiles (io/file "../../.pi/spec" context "event-model")))))))
(def model (:cycle-runner (load-model "cycle-runner")))
(def command-handlers (atom {}))
(def advance-handler (atom (fn [_] {})))
(defn payload [e] (into {} (remove #(= "event" (namespace (key %)))) e))
(defn runs-reducer [state e]
  (let [id (:run-id e) t (:event/type e) p (payload e)]
    (case t
      :cycle-runner/run-started (assoc state id (assoc (select-keys p [:run-id :cycle-id :cut-id :commit]) :status "running" :effects 0 :attempts 0))
      :cycle-runner/effect-recorded
      (update state id
              (fn [r]
                (let [events (:events e) digest (some :candidate-digest events)
                      passed? (some #(= "regeneration-cycle/gate-attempt-passed" (:type %)) events)]
                  (cond-> (update r :effects inc)
                    (and (= "recorded" (:outcome e)) (#{"regenerate" "repair"} (:effect e))) (assoc :attempts (:attempt e))
                    digest (assoc :candidate-digest digest)
                    passed? (assoc :status "awaiting-verdicts")))))
      :cycle-runner/verdicts-handed-in (update state id assoc :status "grading" :verdicts-digest (:verdicts-digest e))
      :cycle-runner/run-concluded (update state id assoc :status (:cycle-status e) :cycle (:cycle e))
      :cycle-runner/run-halted (update state id assoc :status "halted" :halt (select-keys p [:effect :reason :detail]))
      state)))
(defn rows-reducer [fields]
  (fn [state e] (update state (:run-id e) (fnil conj []) (select-keys e fields))))
(defn todo-reducer [state e]
  (let [id (:run-id e)]
    (case (:event/type e)
      (:cycle-runner/run-concluded :cycle-runner/run-halted) (dissoc state id)
      :cycle-runner/effect-recorded (if (some #(= "regeneration-cycle/gate-attempt-passed" (:type %)) (:events e)) (dissoc state id) (assoc state id true))
      :cycle-runner/handoff-relayed state
      (assoc state id true))))
(defn project [ctx key] (rm/project ctx (keyword "cycle-runner" (name key))))
(defn ordered-runs [ctx]
  (let [runs (project ctx :runs)
        starts (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types #{:cycle-runner/run-started}})]
    (mapv #(get runs (:run-id %)) starts)))
(defn progress [ctx id]
  (when-let [r (get (project ctx :runs) id)]
    (assoc r :effect-results (get (project ctx :effect-results) id []) :relayed-handoffs (get (project ctx :relayed-handoffs) id []))))
(defn query-handler [ctx]
  (let [q (:query ctx)]
    {:query/result
     (case (:query/name q)
       :cycle-runner/run-progress (if-let [id (:run-id q)] (when-let [r (progress ctx id)] {:run r}) {:runs (mapv #(progress ctx (:run-id %)) (ordered-runs ctx))})
       :cycle-runner/runs-to-advance {:run-ids (vec (keys (project ctx :runs-to-advance)))})}))
(defn register! []
  (em/register-event-model! :cycle-runner model)
  (doseq [kind [:commands :queries :events] [k d] (get model kind)] (su/register! {k (:schema d)}))
  (doseq [[k d] (:events model)]
    (ed/register-event-definition! k (:description d) {:schema (:schema d)} {:ns "runner.model"}))
  (doseq [[k d] (:commands model)]
    (cp/register-command! k (fn [ctx] ((get @command-handlers k) ctx))
                          {:grain.event-model/reads (:reads d) :grain.event-model/produces (:produces d)}))
  (doseq [[k d] (:queries model)]
    (qp/register-query! k query-handler {:grain.event-model/reads (:reads d)}))
  (doseq [[k d] (:read-models model)]
    (rm/register-read-model! k
      (case k
        :cycle-runner/runs runs-reducer
        :cycle-runner/effect-results (rows-reducer [:run-id :position :effect :attempt :context :method :outcome :refusal :events :detail])
        :cycle-runner/relayed-handoffs (rows-reducer [:run-id :handoff-type :producer :position :consumer :outcome])
        :cycle-runner/runs-to-advance todo-reducer)
      (cond-> {:events (:consumes d) :version 1} (:schema d) (assoc :schema (:schema d)))))
  (let [[k d] (first (:todo-processors model))]
    (tp/register-processor! k (fn [ctx] (@advance-handler ctx))
                            {:topics (:subscribes d) :grain.event-model/reads (:reads d) :grain.event-model/produces (:produces d)})))
(defn event [type body] (es/->event {:type type :body body :tags #{[:run (java.util.UUID/nameUUIDFromBytes (.getBytes ^String (:run-id body) "UTF-8"))]}}))
