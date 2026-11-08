(ns measurement.read-models
  "The read models of the event model, each a Grain defreadmodel of the same name."
  (:require [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [measurement.model :as model]))

(defn body
  "An event's fields without Grain's own :event/* keys."
  [event]
  (into {} (remove (fn [[k _]] (= "event" (namespace k)))) event))

(defreadmodel :measurement pinned-cuts
  {:events #{:cycle-preparation/source-cut-pinned}
   :version 1}
  "The cuts Cycle preparation pinned (NotePinnedCut: a cut already held is ignored)."
  [state event]
  (let [{:keys [cut-id]} event]
    (if (contains? state cut-id)
      state
      (assoc state cut-id (select-keys event [:cut-id :commit :tree-digest])))))

(defreadmodel :measurement item-identities
  {:events #{:specification/scenario-stamped}
   :version 1}
  "Every item identity the stamper handed out (NoteItemIdentity)."
  [state event]
  (let [{:keys [item-id]} event]
    (if (contains? state item-id)
      state
      (assoc state item-id (select-keys event [:item-id :slice :source])))))

(defreadmodel :measurement checklists
  {:events #{:measurement/checklist-derived}
   :version 1
   :schema (model/schema-of :read-models :measurement/checklists)}
  "Each cut's checklist: its items, total and digest."
  [state event]
  (let [{:keys [cut-id]} event]
    (if (contains? state cut-id)
      state
      (assoc state cut-id (select-keys event [:cut-id :selection-id :checklist-digest :total :items
                                              :skipped-slices :derived-at])))))

(defreadmodel :measurement gated-cycles
  {:events #{:regeneration-cycle/gate-attempt-passed}
   :version 1}
  "Each cycle the gate ratified: its cut and the candidate the score binds
   (OpenMeasurement: a cycle already opened is ignored)."
  [state event]
  (let [{:keys [cycle-id]} event]
    (if (contains? state cycle-id)
      state
      (assoc state cycle-id (select-keys event [:cycle-id :cut-id :candidate-digest :attempt])))))

(defreadmodel :measurement item-verdicts
  {:events #{:measurement/item-verdicts-filed}
   :version 1}
  "Each cycle's filed verdicts, in filing order, per grader."
  [state event]
  (update state (:cycle-id event) (fnil conj [])
          (select-keys event [:grader-digest :candidate-digest :verdicts :filed-at])))

(defreadmodel :measurement cycle-measurements
  {:events #{:measurement/measurement-filed :measurement/score-stage-failed}
   :version 1
   :schema (model/schema-of :read-models :measurement/cycle-measurements)}
  "Each cycle's filed measurement or its failed score stage, and the order in
   which the measurements were filed."
  [state event]
  (let [cycle-id (:cycle-id event)]
    (if (get-in state [:rows cycle-id])
      state
      (case (:event/type event)
        :measurement/measurement-filed
        (-> state
            (assoc-in [:rows cycle-id] (assoc (body event) :status "filed"))
            (update :filing-order (fnil conj []) cycle-id))
        :measurement/score-stage-failed
        (assoc-in state [:rows cycle-id] (assoc (body event) :status "score-stage-failed"))
        state))))

(defreadmodel :measurement measurements-to-report
  {:events #{:measurement/measurement-filed :measurement/scoreboard-reported}
   :version 1}
  "The report-scoreboard-once policy's to-do list: filed measurements with no
   scoreboard yet, in filing order."
  [state event]
  (let [cycle-id (:cycle-id event)]
    (case (:event/type event)
      :measurement/measurement-filed
      (if (or (some #{cycle-id} (:todo state)) (contains? (:reported state) cycle-id))
        state
        (update state :todo (fnil conj []) cycle-id))
      :measurement/scoreboard-reported
      (-> state
          (update :todo (fn [todo] (vec (remove #{cycle-id} todo))))
          (update :reported (fnil conj #{}) cycle-id))
      state)))

(defreadmodel :measurement scoreboards
  {:events #{:measurement/scoreboard-reported :measurement/moved-item-set-restated}
   :version 1}
  "Each measured cycle's scoreboard, with its restatement when the item set moved."
  [state event]
  (let [cycle-id (:cycle-id event)]
    (case (:event/type event)
      :measurement/scoreboard-reported
      (if (get-in state [cycle-id :scoreboard])
        state
        (assoc-in state [cycle-id :scoreboard] (body event)))
      :measurement/moved-item-set-restated
      (if (get-in state [cycle-id :restatement])
        state
        (assoc-in state [cycle-id :restatement] (body event)))
      state)))

(defn project [ctx rm-name]
  (rmp/project ctx rm-name))
