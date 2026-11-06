;; harness/v2/event_store_fixtures.clj DIR: hand-made Grain SQLite stores for the event-store
;; check's own test (event-store-check-test.sh), written with Grain's own appender, one file
;; per case. Each is named for the verdict the check must give. The model's events of a
;; passing store lie inside a completed run of their ORC workflow, as ORC writes one on the
;; candidate's store (orc-run: only the events the check reads, not every event of a run). Grain's appender validates each
;; event against the malli schema registered under its type, so every type here is registered
;; as an open map: the fixtures may hold anything, and only the check judges them. The
;; "02-" stores are judged against slice 02 (measurement), the "03-" stores against slice 03
;; (grader-lifecycle, which consumes no handoff), the others against slice 01.
(ns event-store-fixtures
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.schema-util.interface :as schema]
            [clojure.java.io :as io]))

(def tenant #uuid "0190a1b2-0000-7000-8000-000000000001")

(def stamped
  {:item-id "07-001" :slice "07" :source "/tmp/plan/07-probe/spec.feature" :line 2
   :title "the probe's scenario" :stamped-at (java.util.Date.)})
(def reported
  {:slice "07" :source "/tmp/plan/07-probe/spec.feature" :lines [9] :reported-at (java.time.Instant/now)})

(defn orc-run
  "EVENTS inside one run of the ORC workflow WORKFLOW as ORC writes it on the store: its sheet
  (one per workflow, so two runs of different workflows in one store keep their names apart),
  then the run's tree-tick-started and, unless STATUS is nil, its tree-tick-completed."
  ([events] (orc-run "specification/stamping" :success events))
  ([workflow status events]
   (let [tick (random-uuid) sheet-id (random-uuid)]
     (concat [[:sheet/sheet-created {:sheet-id sheet-id :name workflow}]
              [:sheet/tree-tick-started {:sheet-id sheet-id :tick-id tick}]]
             events
             (when status [[:sheet/tree-tick-completed {:sheet-id sheet-id :tick-id tick :root-status status}]])))))

(def pinned
  {:cut-id "K-1" :commit "0123456789abcdef0123456789abcdef01234567" :tree-digest "sha256:t1" :pinned-at (java.util.Date.)})
(def gate-passed
  {:cycle-id "C-1" :cut-id "K-1" :attempt 1 :candidate-digest "sha256:d1" :boot-budget-seconds 30
   :boot-polls 30 :answered-on-poll 2 :identity-probe-result "ok" :evidence-root "/tmp/evidence" :passed-at (java.util.Date.)})
(def derived
  {:cut-id "K-1" :selection-id "whole-plan" :checklist-digest "sha256:c1"
   :items [{:item-id "07-001" :scenario-digest "sha256:s1" :rule nil :source "plan/07-probe/spec.feature:2"
            :title "the probe's scenario"}]
   :total 1 :skipped-slices [] :derived-at (java.util.Date.)})
(def verdicts-filed
  {:cycle-id "C-1" :candidate-digest "sha256:d1" :grader-digest "sha256:g1"
   :verdicts [{:item-id "07-001" :verdict "pass" :evidence ""}] :filed-at (java.util.Date.)})

;; Slice 02 (measurement), checked against its own model plus the handoffs it consumes: the
;; handoffs are handed in through the seam outside any ORC run, as the stubbed producers' facts.
(def handed-in
  [[:specification/scenario-stamped stamped]
   [:cycle-preparation/source-cut-pinned pinned]
   [:regeneration-cycle/gate-attempt-passed gate-passed]])

(def cases-02
  {"02-pass-handoffs-and-runs" (concat handed-in
                                       (orc-run "measurement/stamping-to-checklist" :success
                                                [[:measurement/checklist-derived derived]])
                                       (orc-run "measurement/scoring" :success
                                                [[:measurement/item-verdicts-filed verdicts-filed]]))
   "02-fail-only-handoffs"     handed-in
   "02-fail-handoff-schema"    (concat [[:cycle-preparation/source-cut-pinned (assoc pinned :branch "main")]]
                                       (orc-run "measurement/stamping-to-checklist" :success
                                                [[:measurement/checklist-derived derived]]))
   "02-fail-foreign-event"     (concat handed-in
                                       [[:regeneration-cycle/gate-attempt-failed {:cycle-id "C-1"}]]
                                       (orc-run "measurement/stamping-to-checklist" :success
                                                [[:measurement/checklist-derived derived]]))
   "02-fail-outside-run"       (concat handed-in [[:measurement/checklist-derived derived]]
                                       (orc-run "measurement/scoring" :success
                                                [[:measurement/item-verdicts-filed verdicts-filed]]))
   "02-fail-wrong-workflow"    (concat handed-in
                                       (orc-run "measurement/stamping-to-checklist" :success
                                                [[:measurement/item-verdicts-filed verdicts-filed]]))})

;; Slice 03 (grader-lifecycle): both commands run as the one workflow of its one flow, and it
;; consumes no handoff, so any other context's event is refused.
(def written
  {:golden-id "G-1" :scenario "the probe's scenario" :scenario-digest "s-1" :path "harness/oracle/g1.golden"
   :digest "g-1" :written-at (java.util.Date.)})
(def moved
  {:golden-id "G-1" :from-digest "g-1" :to-digest "g-2" :reason "spec-grew" :additions-only true
   :note "the checklist grew" :scenario-digest "s-1" :moved-at (java.util.Date.)})

(def cases-03
  {"03-pass-written-then-moved" (orc-run "grader-lifecycle/golden-written-then-moved" :success
                                         [[:grader-lifecycle/instrument-golden-written written]
                                          [:grader-lifecycle/instrument-golden-moved moved]])
   "03-fail-reason-outside-three" (orc-run "grader-lifecycle/golden-written-then-moved" :success
                                           [[:grader-lifecycle/instrument-golden-written written]
                                            [:grader-lifecycle/instrument-golden-moved (assoc moved :reason "make-it-green")]])
   "03-fail-outside-run"        (concat [[:grader-lifecycle/instrument-golden-written written]]
                                        (orc-run "grader-lifecycle/golden-written-then-moved" :success
                                                 [[:grader-lifecycle/instrument-golden-moved moved]]))
   "03-fail-unconsumed-handoff" (concat [[:specification/scenario-stamped stamped]]
                                        (orc-run "grader-lifecycle/golden-written-then-moved" :success
                                                 [[:grader-lifecycle/instrument-golden-written written]]))})

(def cases-01
  {"pass-two-events"       (orc-run [[:specification/scenario-stamped stamped]
                                     [:specification/empty-title-headings-reported reported]])
   "fail-empty"            []
   "fail-unknown-type"     (orc-run [[:specification/scenario-stamped stamped]
                                     [:specification/slice-renamed {:slice "07"}]])
   "fail-extra-field"      (orc-run [[:specification/scenario-stamped (assoc stamped :digest "sha256:00")]])
   "fail-missing-field"    (orc-run [[:specification/scenario-stamped (dissoc stamped :title)]])
   "fail-ill-typed-field"  (orc-run [[:specification/scenario-stamped (assoc stamped :line 0)]])
   "fail-no-orc-run"       [[:specification/scenario-stamped stamped]]
   "fail-run-never-completed" (orc-run "specification/stamping" nil [[:specification/scenario-stamped stamped]])
   "fail-run-failed"       (orc-run "specification/stamping" :failure [[:specification/scenario-stamped stamped]])
   "fail-outside-run"      (concat (orc-run []) [[:specification/scenario-stamped stamped]])
   "fail-other-workflow"   (orc-run "specification-stamping" :success [[:specification/scenario-stamped stamped]])
   "fail-unconsumed-handoff" (concat [[:cycle-preparation/source-cut-pinned pinned]]
                                     (orc-run [[:specification/scenario-stamped stamped]]))})

(def cases (merge cases-01 cases-02 cases-03))

(schema/register! (into {} (for [[_ events] cases [t _] events] [t :map])))

(let [[dir] *command-line-args*]
  (doseq [[name events] cases
          :let [path (str (io/file dir (str name ".sqlite")))
                store (es/start {:conn {:type :sqlite :database-file path}})]]
    (try
      (when (seq events)
        (let [r (es/append store {:tenant-id tenant
                                  :events (vec (for [[t b] events] (es/->event {:type t :body b})))})]
          (when (:cognitect.anomalies/category r) (throw (ex-info (str "append failed: " r) {})))))
      (finally (es/stop store)))
    (println "fixture" path))
  (shutdown-agents)
  (System/exit 0))
