;; harness/v2/event_store_fixtures.clj DIR: hand-made Grain SQLite stores for the event-store
;; check's own test (event-store-check-test.sh), written with Grain's own appender, one file
;; per case. Each is named for the verdict the check must give. The model's events of a
;; passing store lie inside a completed run of their ORC workflow, as ORC writes one on the
;; candidate's store (orc-run: only the events the check reads, not every event of a run). Grain's appender validates each
;; event against the malli schema registered under its type, so every type here is registered
;; as an open map: the fixtures may hold anything, and only the check judges them. The
;; "02-" stores are judged against slice 02 (measurement), the "03-" stores against slice 03
;; (grader-lifecycle), the "04-" stores against slice 04 (regeneration-cycle), the "05-" stores
;; against slice 05 (cycle-preparation), the "06-" stores against slice 06 (cycle-runner), the
;; others against slice 01. Since spec round 18 the one event model is every context's, so
;; another context's event is allowed, held to its schema and owed an ORC run of its own flow or
;; of the slice's own (the cycle runner's leaves issue the five contexts' commands). Every completed run
;; carries Grain's processor checkpoints, except where a case takes them out (unchecked) or
;; doubles a processor's output (doubled).
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

(defn processed
  "What ORC's processors append when a run's tick completes with STATUS, as Grain's todo
  processor writes it when started with a :processor-name: the tree-tick-completed, the
  execution-traced, each with Grain's checkpoint."
  [sheet-id tick status]
  [[:sheet/tree-tick-completed {:sheet-id sheet-id :tick-id tick :root-status status}]
   [:grain/todo-processor-checkpoint {:processor/name :sheet/tick-completer :triggered-by (random-uuid)}]
   [:sheet/execution-traced {:sheet-id sheet-id :trace-id tick :root-trace-id tick :status status}]
   [:grain/todo-processor-checkpoint {:processor/name :sheet/tracer :triggered-by (random-uuid)}]])

(defn orc-run
  "EVENTS inside one run of the ORC workflow WORKFLOW as ORC writes it on the store: its sheet
  (one per workflow, so two runs of different workflows in one store keep their names apart),
  then the run's tree-tick-started and, unless STATUS is nil, what its processors append
  when it completes (processed)."
  ([events] (orc-run "specification/stamping" :success events))
  ([workflow status events]
   (let [tick (random-uuid) sheet-id (random-uuid)]
     (concat [[:sheet/sheet-created {:sheet-id sheet-id :name workflow}]
              [:sheet/tree-tick-started {:sheet-id sheet-id :tick-id tick}]]
             events
             (when status (processed sheet-id tick status))))))

(defn orc-tree-run
  "LEAVES, each [name events], inside one run of the ORC workflow WORKFLOW, each leaf as ORC
  writes it on the store: its name (node-name-set), then per leaf its node-execution-started,
  the leaf's events and its node-execution-completed with success; then what the run's
  processors append when it completes (processed). Slice 06 declares its leaves
  (cycle-runner/event-model/area.edn, :leaf), so its events are held to them. A leaf
  [:delegate CHILD-WORKFLOW CHILD-LEAVES] is a delegate node: its execution holds a child tick
  of CHILD-WORKFLOW (spec round 16, the cycle runner's gate attempt), whose tree-tick-started
  names this run's tick as its :parent-tick-id. PARENT, when given, is that parent tick for this
  run itself (nil: a root tick; a tick id no run started: an orphan child)."
  ([workflow leaves] (orc-tree-run workflow leaves nil))
  ([workflow leaves parent]
   (let [tick (random-uuid) sheet-id (random-uuid)
         ids (into {} (for [[n _] leaves] [n (random-uuid)]))]
     (concat [[:sheet/sheet-created {:sheet-id sheet-id :name workflow}]]
             (for [[n id] ids :when (string? n)] [:sheet/node-name-set {:sheet-id sheet-id :node-id id :name n}])
             [[:sheet/tree-tick-started (cond-> {:sheet-id sheet-id :tick-id tick}
                                          parent (assoc :parent-tick-id parent))]]
             (mapcat (fn [[n x y]]
                       (if (= :delegate n)
                         (orc-tree-run x y tick)
                         (concat [[:sheet/node-execution-started {:sheet-id sheet-id :tick-id tick :node-id (ids n) :inputs {}}]]
                                 x
                                 [[:sheet/node-execution-completed {:sheet-id sheet-id :tick-id tick :node-id (ids n) :status :success}]])))
                     leaves)
             (processed sheet-id tick :success)))))

(defn re-ticked-run
  "EVENTS in a run ORC re-ticks: the tick completes :running, ORC starts it again, and the
  second tick completes with success. One terminal completion: rule 1 holds."
  [workflow events]
  (let [tick (random-uuid) sheet-id (random-uuid)]
    (concat [[:sheet/sheet-created {:sheet-id sheet-id :name workflow}]
             [:sheet/tree-tick-started {:sheet-id sheet-id :tick-id tick}]
             [:sheet/tree-tick-completed {:sheet-id sheet-id :tick-id tick :root-status :running}]
             [:grain/todo-processor-checkpoint {:processor/name :sheet/tick-completer :triggered-by (random-uuid)}]
             [:sheet/tree-tick-started {:sheet-id sheet-id :tick-id tick}]]
            events
            (processed sheet-id tick :success))))

(defn doubled
  "EVENTS with each event of type T appended twice, byte-identical, as an unchecked processor
  redelivered."
  [t events]
  (mapcat #(if (= t (first %)) [% %] [%]) events))

(defn unchecked
  "EVENTS without Grain's checkpoints, as processors started with no :processor-name write them."
  [events]
  (remove #(= :grain/todo-processor-checkpoint (first %)) events))

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

;; Slice 02 (measurement), on the one store: the other contexts' events it folds lie inside
;; runs of their producers' own workflows (spec round 18: no hand-in, no handoff contract).
(def others
  (concat (orc-run "specification/stamping" :success [[:specification/scenario-stamped stamped]])
          (orc-run "cycle-preparation/cut-to-fence" :success [[:cycle-preparation/source-cut-pinned pinned]])
          (orc-run "regeneration-cycle/fence-to-scoreboard" :success [[:regeneration-cycle/gate-attempt-passed gate-passed]])))

(def cases-02
  {"02-pass-others-and-runs"   (concat others
                                       (orc-run "measurement/stamping-to-checklist" :success
                                                [[:measurement/checklist-derived derived]])
                                       (orc-run "measurement/scoring" :success
                                                [[:measurement/item-verdicts-filed verdicts-filed]]))
   "02-fail-only-others"       others
   "02-fail-other-schema"      (concat [[:cycle-preparation/source-cut-pinned (assoc pinned :branch "main")]]
                                       (orc-run "measurement/stamping-to-checklist" :success
                                                [[:measurement/checklist-derived derived]]))
   "02-fail-other-outside-run" (concat others
                                       [[:regeneration-cycle/gate-attempt-passed gate-passed]]
                                       (orc-run "measurement/stamping-to-checklist" :success
                                                [[:measurement/checklist-derived derived]]))
   "02-fail-outside-run"       (concat others [[:measurement/checklist-derived derived]]
                                       (orc-run "measurement/scoring" :success
                                                [[:measurement/item-verdicts-filed verdicts-filed]]))
   "02-fail-wrong-workflow"    (concat others
                                       (orc-run "measurement/stamping-to-checklist" :success
                                                [[:measurement/item-verdicts-filed verdicts-filed]]))})

;; Slice 03 (grader-lifecycle): both commands run as the one workflow of its one flow; another
;; context's event outside any ORC run is refused.
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
   "03-fail-other-outside-run"  (concat [[:specification/scenario-stamped stamped]]
                                        (orc-run "grader-lifecycle/golden-written-then-moved" :success
                                                 [[:grader-lifecycle/instrument-golden-written written]]))})

;; Slice 04 (regeneration-cycle): its commands run as the workflows of the flows that hold
;; them; the other contexts' events it folds lie inside runs of their producers' workflows, and
;; another context's event outside any ORC run is refused.
(def budget
  {:gate-attempt-limit 3 :max-outer-cycles 1 :max-continuations 0
   :stage-timeouts-seconds {:fence 3600 :regenerate 3600 :gate 3600 :score 3600}})
(def self-check-passed
  {:cycle-id "C-1" :cut-id "K-1" :occasion "before-fence" :attempt 0 :harness-digest "h-1"
   :goldens-digest "sha256:g" :identity-probe-digest "p-1" :skipped-checks [] :grader-digests ["r-1"]
   :budget budget :checked-at (java.util.Date.)})
(def clean-room-proved
  {:cycle-id "C-1" :cut-id "K-1" :clean-room-commit "c-1" :fenced-paths ["harness-impl"] :proved-at (java.util.Date.)})
(def cycle-failed
  {:cycle-id "C-1" :stage "fence" :reason "fence-refused" :detail "harness/scroe.clj matched nothing"
   :concluded-at (java.util.Date.)})
(def gate-refused
  {:cycle-id "C-1" :attempt 1 :refusal "identity-probe-missing" :detail "no probe" :refused-at (java.util.Date.)})
(def score-failed
  {:cycle-id "C-1" :refusal "zero-verdicts" :detail "no verdict filed" :failed-at (java.util.Date.)})
(def others-04
  (concat (orc-run "cycle-preparation/cut-to-fence" :success [[:cycle-preparation/source-cut-pinned pinned]])
          (orc-run "grader-lifecycle/golden-written-then-moved" :success [[:grader-lifecycle/instrument-golden-written written]])
          (orc-run "measurement/scoring" :success [[:measurement/score-stage-failed score-failed]])))

(def cases-04
  {"04-pass-others-and-runs"   (concat others-04
                                       (orc-run "regeneration-cycle/fence-to-scoreboard" :success
                                                [[:regeneration-cycle/harness-self-check-passed self-check-passed]
                                                 [:regeneration-cycle/clean-room-proved clean-room-proved]])
                                       (orc-run "regeneration-cycle/fence-refused-fails-cycle" :success
                                                [[:regeneration-cycle/cycle-failed cycle-failed]]))
   "04-fail-other-schema"      (concat [[:measurement/score-stage-failed (assoc score-failed :refusal "make-it-green")]]
                                       (orc-run "regeneration-cycle/fence-to-scoreboard" :success
                                                [[:regeneration-cycle/harness-self-check-passed self-check-passed]]))
   "04-fail-other-outside-run" (concat others-04 [[:measurement/checklist-derived derived]]
                                       (orc-run "regeneration-cycle/fence-to-scoreboard" :success
                                                [[:regeneration-cycle/harness-self-check-passed self-check-passed]]))
   "04-fail-outside-run"       (concat others-04
                                       (orc-run "regeneration-cycle/fence-to-scoreboard" :success
                                                [[:regeneration-cycle/harness-self-check-passed self-check-passed]])
                                       [[:regeneration-cycle/clean-room-proved clean-room-proved]])
   "04-fail-wrong-workflow"    (concat others-04
                                       (orc-run "regeneration-cycle/fence-to-scoreboard" :success
                                                [[:regeneration-cycle/harness-self-check-passed self-check-passed]
                                                 [:regeneration-cycle/gate-attempt-refused gate-refused]]))})

;; Slice 05 (cycle-preparation): its one command runs as the workflow of its one flow; another
;; context's event outside any ORC run is refused.
(def cases-05
  {"05-pass-pinned"             (orc-run "cycle-preparation/cut-to-fence" :success
                                         [[:cycle-preparation/source-cut-pinned pinned]])
   ;; slice 05 declares no leaf, so a one-node wrapper still passes its gate
   "05-pass-one-node-wrapper"   (orc-tree-run "cycle-preparation/cut-to-fence"
                                              [["perform-command" [[:cycle-preparation/source-cut-pinned pinned]]]])
   "05-fail-short-commit"       (orc-run "cycle-preparation/cut-to-fence" :success
                                         [[:cycle-preparation/source-cut-pinned (assoc pinned :commit "4f2a9c1")]])
   "05-fail-outside-run"        (concat [[:cycle-preparation/source-cut-pinned pinned]]
                                        (orc-run "cycle-preparation/cut-to-fence" :success []))
   "05-fail-other-outside-run"  (concat [[:specification/scenario-stamped stamped]]
                                        (orc-run "cycle-preparation/cut-to-fence" :success
                                                 [[:cycle-preparation/source-cut-pinned pinned]]))})

;; Slice 06 (cycle-runner): the run is one tick of the one tree cycle-runner/cycle.v1 (spec
;; round 18), a gate attempt a child tick of cycle-runner/gate-attempt.v1 it delegated. Its steps
;; declare leaves, so its passing stores write each event in the leaf named for it
;; (orc-tree-run), the five contexts' events their leaves issue among them; a one-node wrapper,
;; every command in one "perform-command" leaf, fails.
(def run-started
  {:run-id "R-probe" :cycle-id "C-probe" :cut-id "K-probe" :commit "0123456789abcdef0123456789abcdef01234567"
   :selection-id "whole-plan"
   :budget {:gate-attempt-limit 1 :max-outer-cycles 1 :max-continuations 0
            :stage-timeouts-seconds {:fence 60 :regenerate 60 :gate 60 :score 60}}
   :grader-digests ["r-probe"] :started-at (java.util.Date.)})
(def run-halted
  {:run-id "R-probe" :effect "grader" :reason "refused-before-the-cycle" :detail "sha256:unapproved"
   :halted-at (java.util.Date.)})

(def effect-recorded
  {:run-id "R-probe" :position 1 :effect "fence" :attempt 0 :context "regeneration-cycle"
   :method "regeneration-cycle/prove-clean-room" :outcome "refused" :refusal "fenced-path-matched-nothing"
   :events [{:type "regeneration-cycle/cycle-failed" :cycle-id "C-probe"}] :detail "secret matched nothing"
   :performed-at (java.util.Date.)})
(def run-concluded
  {:run-id "R-probe" :cycle-id "C-probe" :cycle-status "failed"
   :cycle {:stage "fence" :status "failed" :attempts 0 :failure {:reason "fence-refused"}}
   :concluded-at (java.util.Date.)})

(def self-check-recorded
  (assoc effect-recorded :position 1 :effect "self-check" :method "regeneration-cycle/record-harness-self-check"
         :outcome "recorded" :events [] :detail "passed"))

(def regenerate-recorded
  (assoc (dissoc effect-recorded :refusal) :effect "regenerate" :attempt 1 :method "regeneration-cycle/record-regenerated-candidate"
         :outcome "recorded" :events [] :detail "candidate left"))
(def install-recorded
  (assoc regenerate-recorded :effect "install" :method "regeneration-cycle/install-candidate-in-gate-room"))
(def gate-recorded
  (assoc regenerate-recorded :effect "gate" :method "regeneration-cycle/record-gate-attempt"
         :events [{:type "regeneration-cycle/gate-attempt-passed" :cycle-id "C-probe"}]))

(def cases-06
  {"06-pass-halted"          (orc-tree-run "cycle-runner/cycle.v1"
                                           [["start" [[:cycle-runner/run-started run-started]]]
                                            ["halt" [[:cycle-runner/run-halted run-halted]]]])
   "06-pass-concluded"       (orc-tree-run "cycle-runner/cycle.v1"
                                           [["start" [[:cycle-runner/run-started run-started]]]
                                            ["self-check:0" [[:cycle-runner/effect-recorded self-check-recorded]]]
                                            ["fence" [[:regeneration-cycle/cycle-failed cycle-failed]
                                                      [:cycle-runner/effect-recorded (assoc effect-recorded :position 2)]]]
                                            ["conclude" [[:cycle-runner/run-concluded run-concluded]]]])
   "06-fail-one-node-wrapper" (concat (orc-tree-run "cycle-runner/cycle.v1"
                                                    [["perform-command" [[:cycle-runner/run-started run-started]]]])
                                      (orc-tree-run "cycle-runner/cycle.v1"
                                                    [["perform-command" [[:cycle-runner/effect-recorded effect-recorded]]]])
                                      (orc-tree-run "cycle-runner/cycle.v1"
                                                    [["perform-command" [[:cycle-runner/run-concluded run-concluded]]]]))
   ;; spec round 16: a gate attempt runs as a child tick of cycle-runner/gate-attempt.v1,
   ;; delegated from the run's cycle.v1 tick; its effects are held to their leaves there, and
   ;; the Regeneration cycle's event the gate leaf issues lies in it
   "06-pass-delegated-attempt" (orc-tree-run "cycle-runner/cycle.v1"
                                             [["start" [[:cycle-runner/run-started run-started]]]
                                              ["regenerate" [[:cycle-runner/effect-recorded (assoc regenerate-recorded :position 1)]]]
                                              [:delegate "cycle-runner/gate-attempt.v1"
                                               [["install" [[:cycle-runner/effect-recorded (assoc install-recorded :position 2)]]]
                                                ["self-check" [[:cycle-runner/effect-recorded (assoc self-check-recorded :position 3 :attempt 1)]]]
                                                ["gate:1" [[:regeneration-cycle/gate-attempt-passed gate-passed]
                                                           [:cycle-runner/effect-recorded (assoc gate-recorded :position 4)]]]]]])
   "06-fail-delegated-wrong-leaf" (orc-tree-run "cycle-runner/cycle.v1"
                                                [["start" [[:cycle-runner/run-started run-started]]]
                                                 [:delegate "cycle-runner/gate-attempt.v1"
                                                  [["install" [[:cycle-runner/effect-recorded (assoc gate-recorded :position 1)]]]]]])
   "06-fail-orphan-attempt"  (concat (orc-tree-run "cycle-runner/cycle.v1"
                                                   [["start" [[:cycle-runner/run-started run-started]]]])
                                     (orc-tree-run "cycle-runner/gate-attempt.v1"
                                                   [["gate:1" [[:cycle-runner/effect-recorded (assoc gate-recorded :position 1)]]]]
                                                   (random-uuid)))
   "06-fail-wrong-leaf"      (orc-tree-run "cycle-runner/cycle.v1"
                                           [["start" [[:cycle-runner/run-started run-started]]]
                                            ["pin" [[:cycle-runner/effect-recorded effect-recorded]]]])
   "06-fail-halt-reason"     (orc-run "cycle-runner/cycle.v1" :success
                                      [[:cycle-runner/run-halted (assoc run-halted :reason "context-did-not-respond")]])
   "06-fail-outside-run"     (concat [[:cycle-runner/run-started run-started]]
                                     (orc-run "cycle-runner/cycle.v1" :success []))
   "06-fail-other-outside-run" (concat [[:specification/scenario-stamped stamped]]
                                       (orc-tree-run "cycle-runner/cycle.v1"
                                                     [["start" [[:cycle-runner/run-started run-started]]]]))})

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
   "fail-other-outside-run" (concat [[:cycle-preparation/source-cut-pinned pinned]]
                                     (orc-run [[:specification/scenario-stamped stamped]]))
   "pass-re-ticked-run"    (re-ticked-run "specification/stamping" [[:specification/scenario-stamped stamped]])
   "fail-doubled-completion" (doubled :sheet/tree-tick-completed (orc-run [[:specification/scenario-stamped stamped]]))
   "fail-doubled-trace"    (doubled :sheet/execution-traced (orc-run [[:specification/scenario-stamped stamped]]))
   "fail-no-checkpoint"    (unchecked (orc-run [[:specification/scenario-stamped stamped]]))})

(def cases (merge cases-01 cases-02 cases-03 cases-04 cases-05 cases-06))

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
