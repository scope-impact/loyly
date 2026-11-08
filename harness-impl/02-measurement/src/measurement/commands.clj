(ns measurement.commands
  "The commands of the event model, each a Grain defcommand of the same name.
   Each refusal is a `requires` of the Allium rule, checked in its order; the
   first that fails is the refusal, and a refusal records nothing."
  (:require [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [clojure.string]
            [cognitect.anomalies :as anom]
            [measurement.domain :as domain]
            [measurement.read-models :as rm])
  (:import [java.time Instant]))

(defn refusal
  ([code message] (refusal code message {}))
  ([code message data]
   (merge {::anom/category ::anom/conflict
           ::anom/message message
           :measurement/refusal code}
          data)))

(defn- event [type body]
  (es/->event {:type type :body body}))

(defn- now [] (Instant/now))

(defn- result [events]
  {:command-result/events (vec events)})

;; ---------------------------------------------------------------------------
;; Checklist
;; ---------------------------------------------------------------------------

(defcommand :measurement derive-checklist
  {:grain.event-model/reads #{:measurement/pinned-cuts :measurement/item-identities :measurement/checklists}
   :grain.event-model/produces #{:measurement/checklist-derived}}
  "Derive the checklist of a pinned cut from the reading it is offered (rule DeriveChecklist)."
  [{:keys [command] :as ctx}]
  (let [{:keys [cut-id selection-id items unstamped skipped-slices]} command
        pinned (rm/project ctx :measurement/pinned-cuts)
        identities (rm/project ctx :measurement/item-identities)
        checklists (rm/project ctx :measurement/checklists)
        ids (mapv :item-id items)]
    (cond
      (not (contains? pinned cut-id))
      (refusal "cut-not-pinned" (str "Source cut " cut-id " was never pinned."))

      (seq unstamped)
      (refusal "unstamped-scenarios"
               (str (count unstamped) " unstamped scenario(s) found; stamp them first with "
                    "the stamper, specification/stamp-slice.")
               {:measurement/unstamped (mapv #(select-keys % [:source :title]) unstamped)
                :measurement/stamper "specification/stamp-slice"})

      (empty? items)
      (refusal "empty-checklist" (str "The checklist of " cut-id " would hold no stamped item."))

      (some #(not (contains? identities %)) ids)
      (let [missing (vec (distinct (remove #(contains? identities %) ids)))]
        (refusal "item-never-stamped"
                 (str "Item identities the stamper never handed out: " (clojure.string/join ", " missing))
                 {:measurement/item-ids missing}))

      (seq (domain/duplicates ids))
      (let [twice (domain/duplicates ids)]
        (refusal "item-twice"
                 (str "Item identities carried by more than one scenario: " (clojure.string/join ", " twice))
                 {:measurement/item-ids twice}))

      (contains? checklists cut-id)
      (refusal "checklist-already-derived" (str "The checklist of " cut-id " was already derived."))

      (some #(= "" (:title %)) items)
      (let [empty-ids (vec (distinct (map :item-id (filter #(= "" (:title %)) items))))]
        (refusal "empty-title" (str "Stamped items with an empty title: " (clojure.string/join ", " empty-ids))
                 {:measurement/item-ids empty-ids}))

      :else
      (let [items (mapv #(select-keys % [:item-id :scenario-digest :rule :source :title]) items)]
        (result
         [(event :measurement/checklist-derived
                 {:cut-id cut-id
                  :selection-id selection-id
                  :checklist-digest (domain/checklist-digest items)
                  :items items
                  :total (count items)
                  :skipped-slices (mapv #(select-keys % [:slice :status]) skipped-slices)
                  :derived-at (now)})])))))

;; ---------------------------------------------------------------------------
;; Cycle measurement
;; ---------------------------------------------------------------------------

(defn- status-of [measurements cycle-id]
  (get-in measurements [:rows cycle-id :status] "awaiting-score"))

(defcommand :measurement file-item-verdicts
  {:grain.event-model/reads #{:measurement/gated-cycles :measurement/checklists :measurement/cycle-measurements}
   :grain.event-model/produces #{:measurement/item-verdicts-filed}}
  "File the verdicts one approved sealed grader returned for the gated candidate (rule FileItemVerdicts)."
  [{:keys [command] :as ctx}]
  (let [{:keys [cycle-id candidate-digest grader-digest approved-grader-digests verdicts]} command
        gated (get (rm/project ctx :measurement/gated-cycles) cycle-id)
        checklist (when gated (get (rm/project ctx :measurement/checklists) (:cut-id gated)))
        checklist-ids (set (map :item-id (:items checklist)))
        measurements (rm/project ctx :measurement/cycle-measurements)
        status (status-of measurements cycle-id)
        bad-value (filterv #(not (#{"pass" "fail"} (:verdict %))) verdicts)
        unknown (filterv #(not (contains? checklist-ids (:item-id %))) verdicts)
        bad-reason (filterv #(and (some? (:reason %))
                                  (not (and (= "fail" (:verdict %))
                                            (#{"inconclusive" "outage"} (:reason %)))))
                            verdicts)
        ids (fn [vs] (vec (distinct (map :item-id vs))))]
    (cond
      (nil? gated)
      (refusal "not-the-gated-candidate" (str "No gate attempt of cycle " cycle-id " passed."))

      (not= candidate-digest (:candidate-digest gated))
      (refusal "not-the-gated-candidate"
               (str "Candidate " candidate-digest " is not the candidate the gate ratified for " cycle-id "."))

      (nil? checklist)
      (refusal "no-checklist" (str "No checklist was derived for source cut " (:cut-id gated) "."))

      (empty? verdicts)
      (refusal "zero-verdicts" "Zero item verdicts: the grading did not happen, nothing is filed.")

      (seq bad-value)
      (refusal "bad-verdict-value" "A verdict is neither pass nor fail."
               {:measurement/item-ids (ids bad-value)})

      (seq unknown)
      (refusal "unknown-item" (str "Verdicts for items not on the checklist: " (clojure.string/join ", " (ids unknown)))
               {:measurement/item-ids (ids unknown)})

      (= "filed" status)
      (refusal "measurement-already-filed" (str "The measurement of " cycle-id " is already filed."))

      (= "score-stage-failed" status)
      (refusal "score-stage-failed" (str "The score stage of " cycle-id " failed; it is never retaken."))

      (not (some #{grader-digest} approved-grader-digests))
      (refusal "grader-not-approved" (str "Grader digest " grader-digest " is not approved."))

      (seq bad-reason)
      (refusal "bad-verdict-reason"
               "A reason sits only beside a fail, and is inconclusive or outage."
               {:measurement/item-ids (ids bad-reason)})

      :else
      (result
       [(event :measurement/item-verdicts-filed
               {:cycle-id cycle-id
                :candidate-digest candidate-digest
                :grader-digest grader-digest
                :verdicts (mapv (fn [{:keys [item-id verdict reason evidence]}]
                                  (cond-> {:item-id item-id :verdict verdict :evidence (or evidence "")}
                                    reason (assoc :reason reason)))
                                verdicts)
                :filed-at (now)})]))))

(defcommand :measurement file-score
  {:grain.event-model/reads #{:measurement/gated-cycles :measurement/checklists :measurement/item-verdicts
                              :measurement/cycle-measurements}
   :grain.event-model/produces #{:measurement/measurement-filed}}
  "File the cycle's one measurement from the verdicts filed (rule FileScore)."
  [{:keys [command] :as ctx}]
  (let [{:keys [cycle-id approved-grader-digests]} command
        gated (get (rm/project ctx :measurement/gated-cycles) cycle-id)
        filings (get (rm/project ctx :measurement/item-verdicts) cycle-id)
        measurements (rm/project ctx :measurement/cycle-measurements)
        status (status-of measurements cycle-id)
        approved (set approved-grader-digests)]
    (cond
      (nil? gated)
      (refusal "not-the-gated-candidate" (str "No gate attempt of cycle " cycle-id " passed."))

      (empty? filings)
      (refusal "zero-verdicts" (str "No item verdicts were filed for " cycle-id "."))

      (= "filed" status)
      (refusal "measurement-already-filed" (str "The measurement of " cycle-id " is already filed."))

      (= "score-stage-failed" status)
      (refusal "score-stage-failed" (str "The score stage of " cycle-id " failed; it is never retaken."))

      (some #(not (contains? approved (:grader-digest %))) filings)
      (refusal "grader-not-approved"
               (str "Grader digest(s) no longer approved: "
                    (clojure.string/join ", " (distinct (remove approved (map :grader-digest filings))))))

      (some #(not= (:candidate-digest gated) (:candidate-digest %)) filings)
      (refusal "not-the-gated-candidate" (str "A filing of " cycle-id " is for another candidate."))

      :else
      (let [checklist (get (rm/project ctx :measurement/checklists) (:cut-id gated))
            items (domain/scored-items (:items checklist) filings)]
        (result
         [(event :measurement/measurement-filed
                 {:cycle-id cycle-id
                  :cut-id (:cut-id gated)
                  :selection-id (:selection-id checklist)
                  :candidate-digest (:candidate-digest gated)
                  :checklist-digest (:checklist-digest checklist)
                  :grader-digests (vec (distinct (map :grader-digest filings)))
                  :total (:total checklist)
                  :passes (domain/passes-of items)
                  :unreached (domain/unreached-of items)
                  :items items
                  :superseded (domain/superseded filings)
                  :filed-at (now)})])))))

(defcommand :measurement fail-score-stage
  {:grain.event-model/reads #{:measurement/gated-cycles :measurement/cycle-measurements}
   :grain.event-model/produces #{:measurement/score-stage-failed}}
  "Record that the cycle's score stage failed because its score was refused (rule FailScoreStage)."
  [{:keys [command] :as ctx}]
  (let [{:keys [cycle-id refusal detail]} command
        gated (get (rm/project ctx :measurement/gated-cycles) cycle-id)
        status (status-of (rm/project ctx :measurement/cycle-measurements) cycle-id)]
    (cond
      (nil? gated)
      (measurement.commands/refusal "not-the-gated-candidate" (str "No gate attempt of cycle " cycle-id " passed."))

      (= "filed" status)
      (measurement.commands/refusal "measurement-already-filed" (str "The measurement of " cycle-id " is already filed."))

      (= "score-stage-failed" status)
      (measurement.commands/refusal "score-stage-failed" (str "The score stage of " cycle-id " already failed."))

      :else
      (result
       [(event :measurement/score-stage-failed
               {:cycle-id cycle-id :refusal refusal :detail detail :failed-at (now)})]))))

;; ---------------------------------------------------------------------------
;; Scoreboard
;; ---------------------------------------------------------------------------

(defn previous-measurement
  "The last cycle whose measurement was filed before this one's, with the same
   selection (contract Comparison, LastMeasuredCycleOfTheSameSelection)."
  [measurements cycle-id]
  (let [{:keys [rows filing-order]} measurements
        selection (get-in rows [cycle-id :selection-id])
        before (take-while #(not= % cycle-id) filing-order)]
    (->> (reverse before)
         (map rows)
         (filter #(= selection (:selection-id %)))
         first)))

(defcommand :measurement report-scoreboard
  {:grain.event-model/reads #{:measurement/cycle-measurements :measurement/scoreboards}
   :grain.event-model/produces #{:measurement/scoreboard-reported :measurement/moved-item-set-restated}}
  "Report the cycle's scoreboard against the last measured cycle of the same selection (rule ReportScoreboard)."
  [{:keys [command] :as ctx}]
  (let [{:keys [cycle-id]} command
        measurements (rm/project ctx :measurement/cycle-measurements)
        measurement (get-in measurements [:rows cycle-id])
        scoreboards (rm/project ctx :measurement/scoreboards)]
    (cond
      (not= "filed" (:status measurement))
      (refusal "no-measurement-filed" (str "No measurement is filed for " cycle-id "."))

      (get-in scoreboards [cycle-id :scoreboard])
      (refusal "scoreboard-already-reported" (str "The scoreboard of " cycle-id " was already reported."))

      :else
      (let [previous (previous-measurement measurements cycle-id)
            {:keys [shared new-items dropped-items] :as cmp} (domain/comparison measurement previous)
            reported-at (now)]
        (result
         (cond-> [(event :measurement/scoreboard-reported
                         (cond-> {:cycle-id cycle-id
                                  :selection-id (:selection-id measurement)
                                  :passes (:passes measurement)
                                  :total (:total measurement)
                                  :rate (domain/rate (:passes measurement) (:total measurement))
                                  :previous-cycle-id (:cycle-id previous)
                                  :previous-selection-id (:selection-id previous)
                                  :new-items new-items
                                  :dropped-items dropped-items
                                  :reported-at reported-at}
                           shared (assoc :shared shared)))]
           (and previous (domain/item-set-moved? cmp))
           (conj (event :measurement/moved-item-set-restated
                        {:cycle-id cycle-id
                         :previous-cycle-id (:cycle-id previous)
                         :previous-total (:total previous)
                         :total (:total measurement)
                         :added (count new-items)
                         :dropped (count dropped-items)
                         :restated-at reported-at}))))))))
