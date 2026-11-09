(ns runner.model
  (:require [clojure.edn :as edn] [clojure.java.io :as io]
            [ai.obney.grain.event-model.interface :as em]
            [ai.obney.grain.event-model-validator.interface :as guard]
            [ai.obney.grain.schema-util.interface :as schema]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.event-definition :as evdef]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rm]
            [ai.obney.grain.todo-processor-v2.interface :as tp]))
(defn merge-deep [& ms] (apply merge-with (fn [a b] (if (and (map? a) (map? b)) (merge-deep a b) b)) ms))
(defn load-model [area]
  (apply merge-deep (for [f (sort (filter #(.endsWith (.getName %) ".edn") (file-seq (io/file (str "../../.pi/spec/" area "/event-model")))))] (edn/read-string (slurp f)))))
(def area (:cycle-runner (load-model "cycle-runner")))
(def event-fields (into {} (for [[k v] (:events area)] [k (mapv first (rest (:schema v)))])))
(defn body [e] (select-keys e (get event-fields (:event/type e))))
(defn reduce-run [state e]
  (let [id (:run-id e) b (body e)]
    (case (:event/type e)
      :cycle-runner/run-started (assoc state id (merge (select-keys b [:run-id :cycle-id :cut-id :commit]) {:status "running" :effects 0 :attempts 0}))
      :cycle-runner/effect-recorded
      (-> state (update-in [id :effects] inc)
          (cond-> (and (= "recorded" (:outcome e)) (#{"regenerate" "repair"} (:effect e)))
            (assoc-in [id :attempts] (:attempt e)))
          (cond-> (and (= "recorded" (:outcome e)) (#{"regenerate" "repair"} (:effect e)))
            (assoc-in [id :candidate-digest] (:candidate-digest (first (:events e)))))
          (cond-> (some #(= "regeneration-cycle/gate-attempt-passed" (:type %)) (:events e))
            (assoc-in [id :status] "awaiting-verdicts")))
      :cycle-runner/verdicts-handed-in (-> state (assoc-in [id :status] "grading") (assoc-in [id :verdicts-digest] (:verdicts-digest e)))
      :cycle-runner/run-concluded (-> state (assoc-in [id :status] (:cycle-status e)) (assoc-in [id :cycle] (:cycle e)))
      :cycle-runner/run-halted (-> state (assoc-in [id :status] "halted") (assoc-in [id :halt] (select-keys b [:effect :reason :detail])))
      state)))
(defn reduce-rows [state e] (update state (:run-id e) (fnil conj []) (body e)))
(defn runs [ctx] (rm/project ctx :cycle-runner/runs))
(defn progress [ctx id]
  (when-let [r (get (runs ctx) id)]
    (assoc r :effect-results (mapv #(dissoc % :performed-at) (get (rm/project ctx :cycle-runner/effect-results) id []))
             :relayed-handoffs (mapv #(dissoc % :relayed-at) (get (rm/project ctx :cycle-runner/relayed-handoffs) id [])))))
(defn started-order [ctx] (mapv :run-id (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types #{:cycle-runner/run-started}}))))
(defn emit-command [ctx command-name params]
  (let [r (cp/process-command (assoc ctx :command (merge params {:command/name command-name :command/id (random-uuid) :command/timestamp (java.time.OffsetDateTime/now)})))]
    (when-not (contains? r :command-result/events) (throw (ex-info (str "Grain command failed " command-name) {:result r})))
    (:command-result/events r)))
(defn register! [processor-fn]
  (em/register-event-model! :cycle-runner area)
  (doseq [[k b] (:events area)] (evdef/register-event-definition! k (:description b) {:schema (:schema b)} {:ns "runner.model"}))
  (doseq [[k b] (:commands area)]
    (schema/register! {k (:schema b)})
    (cp/register-command! k
      (fn [{:keys [command] :as ctx}]
        (let [event-type (first (:produces b))
              fields (get event-fields event-type)
              stamp-key (last fields)
              params (apply dissoc command [:command/name :command/id :command/timestamp])
              extra (case k
                      :cycle-runner/record-effect {:position (inc (get-in (runs ctx) [(:run-id params) :effects] 0))}
                      :cycle-runner/conclude-run {:cycle-id (get-in (runs ctx) [(:run-id params) :cycle-id])}
                      {})
              body (select-keys (merge params extra {stamp-key (java.util.Date.)}) fields)]
          {:command-result/events [(es/->event {:type event-type :body body})]}))
      {:grain.event-model/reads (:reads b) :grain.event-model/produces (:produces b)}))
  (doseq [[k b] (:read-models area)]
    (rm/register-read-model! k (if (#{:cycle-runner/runs :cycle-runner/runs-to-advance} k) reduce-run reduce-rows)
                             (cond-> {:events (:consumes b) :version 1} (:schema b) (assoc :schema (:schema b)))))
  (doseq [[k b] (:queries area)]
    (schema/register! {k (:schema b)})
    (qp/register-query! k (fn [{:keys [query] :as ctx}]
                           {:query/result
                            (if (= k :cycle-runner/run-progress)
                              (if-let [id (:run-id query)] {:run (progress ctx id)} {:runs (mapv #(progress ctx %) (started-order ctx))})
                              {:run-ids (vec (for [[id r] (rm/project ctx :cycle-runner/runs-to-advance) :when (#{"running" "grading"} (:status r))] id))})})
                        {:grain.event-model/reads (:reads b)}))
  (let [b (get-in area [:todo-processors :cycle-runner/advance-run])]
    (tp/register-processor! :cycle-runner/advance-run processor-fn {:topics (:subscribes b) :grain.event-model/reads (:reads b) :grain.event-model/produces (:produces b)}))
  (guard/verify-or-throw! {:model {:cycle-runner area}}))
