(ns loyly.views
  (:require [ai.obney.grain.read-model-processor-v2.interface :as rm]
            [ai.obney.grain.query-processor.interface :as qp] [ai.obney.grain.schema-util.interface :as schema]
            [ai.obney.grain.time.interface :as time] [loyly.domain :as d]))
(defn body [e] (into {} (remove (comp namespace key)) e))
(defn attempt [state e]
  (let [k [(:cycle-id e) (:attempt e)] v (select-keys e [:cycle-id :attempt :candidate-digest :evidence-root :reason])]
    (update state k merge v (case (:event/type e)
      :regeneration-cycle/candidate-regenerated {:installed? false}
      :regeneration-cycle/candidate-installed-in-gate-room {:installed? true}
      :regeneration-cycle/gate-attempt-refused {:last-refusal (:refusal e)}
      :regeneration-cycle/gate-attempt-passed {:verdict "passed" :boot-margin-polls (- (:boot-polls e) (:answered-on-poll e))}
      {:verdict "failed"}))))
(defn cycle [state e]
  (let [id (:cycle-id e) kind (:event/type e)
        stage (case kind :regeneration-cycle/clean-room-proved "regenerate" :regeneration-cycle/candidate-regenerated "gate"
                    :regeneration-cycle/gate-attempt-passed "score" :measurement/measurement-filed "scoreboard"
                    :regeneration-cycle/cycle-passed "result" :regeneration-cycle/cycle-failed (:stage e)
                    (or (get-in state [id :stage]) "fence"))
        base (merge {:gate-attempts 0 :consecutive-gate-refusals 0 :status "running"} (get state id)
                    (select-keys e [:cycle-id :cut-id :candidate-digest :budget :harness-digest :clean-room-commit]) {:stage stage})]
    (assoc state id (cond-> base
                      (not= stage (get-in state [id :stage])) (assoc :stage-entered-at (str (:event/timestamp e)))
                      (= kind :regeneration-cycle/candidate-regenerated) (assoc :gate-attempts (:attempt e))
                      (= kind :regeneration-cycle/gate-attempt-refused) (update :consecutive-gate-refusals inc)
                      (= kind :regeneration-cycle/cycle-passed) (assoc :status "passed")
                      (= kind :regeneration-cycle/cycle-failed) (assoc :status "failed" :failure (select-keys e [:stage :reason :detail]))))))
(defn run [state e]
  (let [id (:run-id e)]
    (case (:event/type e)
      :cycle-runner/run-started (assoc state id (merge (select-keys e [:run-id :cycle-id :commit :cut-id]) {:status "running" :attempts 0 :effects 0}))
      :cycle-runner/effect-recorded (cond-> (update-in state [id :effects] inc)
                                     (contains? #{"regenerate" "repair"} (:effect e))
                                     (update id merge {:attempts (:attempt e) :candidate-digest (:candidate-digest (first (:events e)))}))
      :cycle-runner/run-concluded (update state id merge {:cycle (:cycle e) :status (:cycle-status e)})
      :cycle-runner/run-halted (update state id merge {:halt (select-keys e [:effect :reason :detail]) :status "halted"}))))
(defn golden [m e]
  (let [id (:golden-id e)]
    (if (= :grader-lifecycle/instrument-golden-written (:event/type e))
      (assoc m id (assoc (select-keys e [:golden-id :path :scenario-digest :digest]) :moves []))
      (-> m (update id merge {:digest (:to-digest e) :scenario-digest (:scenario-digest e)})
          (update-in [id :moves] conj (select-keys e [:from-digest :to-digest :reason :note]))))))
(defn stamped [m e]
  (let [id (:slice e) row (get m id {:slice id :next-number 1 :items []})]
    (assoc m id (if (= :specification/scenario-stamped (:event/type e))
                  (-> row (update :next-number inc) (update :items conj (select-keys e [:item-id :title :line :source])))
                  (assoc row :empty-title-lines (:lines e))))))
(defn by [id] (fn [m e] (assoc m (id e) (body e))))
(defn pending [m e]
  (if (= :measurement/measurement-filed (:event/type e)) (assoc m (:cycle-id e) true) (dissoc m (:cycle-id e))))
(defn due [m e]
  (let [id (:cycle-id e)]
    (case (:event/type e)
      (:regeneration-cycle/cycle-passed :regeneration-cycle/cycle-failed) (dissoc m id)
      (:measurement/scoreboard-reported :measurement/score-stage-failed :regeneration-cycle/harness-self-check-failed) (assoc m id true)
      m)))
(def reducers
  {:regeneration-cycle/pinned-cuts (by :cut-id) :regeneration-cycle/instrument-goldens golden
   :regeneration-cycle/harness-self-checks (by (juxt :cycle-id :attempt)) :regeneration-cycle/gate-attempts attempt
   :regeneration-cycle/regeneration-cycles cycle :regeneration-cycle/cycles-to-conclude due
   :measurement/gated-cycles (by :cycle-id) :measurement/item-verdicts (fn [m e] (update m (:cycle-id e) (fnil conj []) (body e)))
   :measurement/cycle-measurements (fn [m e] (assoc m (:cycle-id e) (assoc (body e) :status (if (= :measurement/measurement-filed (:event/type e)) "filed" "score-stage-failed"))))
   :measurement/pinned-cuts (by :cut-id) :measurement/item-identities (by :item-id) :measurement/checklists (by :cut-id)
   :measurement/measurements-to-report pending :measurement/scoreboards
   (fn [m e] (if (= :measurement/scoreboard-reported (:event/type e)) (assoc m (:cycle-id e) (assoc (body e) :restatement nil))
                 (assoc-in m [(:cycle-id e) :restatement] (body e))))
   :cycle-runner/runs run :cycle-runner/effect-results (fn [m e] (update m (:run-id e) (fnil conj []) (dissoc (body e) :performed-at)))
   :specification/stamped-items stamped :cycle-preparation/source-cuts (by :cut-id) :grader-lifecycle/instrument-goldens golden})
(defn cycle-progress [sys {:keys [cycle-id]}]
  (let [cycles (rm/project sys :regeneration-cycle/regeneration-cycles) attempts (rm/project sys :regeneration-cycle/gate-attempts)
        enrich (fn [row] (assoc row :attempts (vec (sort-by :attempt (filter #(= (:cycle-id row) (:cycle-id %)) (vals attempts))))))]
    (if cycle-id (some->> (cycles cycle-id) enrich (hash-map :cycle))
        {:cycles (mapv enrich (vals cycles))})))
(defn query [sys k p]
  (let [project #(rm/project sys %)
        one (fn [model id key] (when-let [r (get (project model) id)] {key r}))]
    (case k
      :regeneration-cycle/cycle-progress (cycle-progress sys p)
      :regeneration-cycle/cycles-to-conclude {:cycle-ids (vec (keys (project :regeneration-cycle/cycles-to-conclude)))}
      :measurement/checklist (one :measurement/checklists (:cut-id p) :checklist)
      :measurement/scoreboard (one :measurement/scoreboards (:cycle-id p) :scoreboard)
      :measurement/cycle-measurement (one :measurement/cycle-measurements (:cycle-id p) :measurement)
      :measurement/measurements-to-report {:cycle-ids (vec (keys (project :measurement/measurements-to-report)))}
      :specification/stamped-items (let [m (project k)] {:slices (if (:slice p) (vec (keep m [(:slice p)])) (vec (vals m)))})
      :grader-lifecycle/instrument-goldens {:goldens (vec (vals (project k)))}
      :cycle-preparation/source-cuts {:cuts (vec (sort-by :pinned-at #(compare %2 %1) (vals (project k))))})))
(defn register! [models progress]
  (doseq [[_ area] models]
    (doseq [[k {:keys [consumes schema]}] (:read-models area)]
      (rm/register-read-model! k (reducers k) {:events consumes :version 1 :schema schema}))
    (doseq [[k {:keys [schema reads]}] (:queries area)]
      (schema/register! {k schema})
      (qp/register-query! k (fn [{:keys [query] :as sys}] {:value (if (= k :cycle-runner/run-progress) (progress sys query) (loyly.views/query sys k query))})
                          {:grain.event-model/reads reads}))))
(defn read-query [sys k p]
  (let [r (qp/process-query (assoc sys :query (assoc p :query/name k :query/id (random-uuid) :query/timestamp (time/now))))]
    (if (contains? r :value) (:value r) (throw (ex-info (pr-str r) {})))))
