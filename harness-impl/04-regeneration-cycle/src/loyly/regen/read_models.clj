(ns loyly.regen.read-models
  "The read models of the event model, one Grain read model per block, named as it.
   Their folds are loyly.regen.domain."
  (:require [ai.obney.grain.read-model-processor-v2.interface :refer [defreadmodel]]
            [loyly.regen.model :as model]
            [loyly.regen.domain :as d]))

;; ---------------------------------------------------------------------------
;; Read models
;; ---------------------------------------------------------------------------

(defreadmodel :regeneration-cycle pinned-cuts
  {:events #{:cycle-preparation/source-cut-pinned}
   :version 1}
  "The cuts Cycle preparation pinned (NotePinnedCut)."
  [state event]
  (d/fold-pinned-cuts state event))

(defreadmodel :regeneration-cycle instrument-goldens
  {:events #{:grader-lifecycle/instrument-golden-written :grader-lifecycle/instrument-golden-moved}
   :version 1}
  "The current digest of every instrument golden (NoteGoldenWritten, NoteGoldenMoved)."
  [state event]
  (d/fold-instrument-goldens state event))

(defreadmodel :regeneration-cycle harness-self-checks
  {:events #{:regeneration-cycle/harness-self-check-passed :regeneration-cycle/harness-self-check-failed}
   :version 1}
  "Each cycle's self-checks by occasion and attempt."
  [state event]
  (d/fold-harness-self-checks state event))

(defreadmodel :regeneration-cycle regeneration-cycles
  {:events #{:regeneration-cycle/harness-self-check-passed :regeneration-cycle/harness-self-check-failed
             :regeneration-cycle/clean-room-proved :regeneration-cycle/candidate-regenerated
             :regeneration-cycle/candidate-installed-in-gate-room
             :regeneration-cycle/gate-attempt-passed :regeneration-cycle/gate-attempt-failed
             :regeneration-cycle/gate-attempt-refused
             :measurement/measurement-filed :measurement/score-stage-failed :measurement/scoreboard-reported
             :regeneration-cycle/cycle-passed :regeneration-cycle/cycle-failed}
   :version 1
   :schema (model/read-model-schema :regeneration-cycle/regeneration-cycles)}
  "Each cycle (entity RegenerationCycle), folded from its stage facts and the measurement's handoffs."
  [state event]
  (d/fold-cycles-state state event))

(defreadmodel :regeneration-cycle cycles-to-conclude
  {:events #{:regeneration-cycle/harness-self-check-passed :regeneration-cycle/harness-self-check-failed
             :regeneration-cycle/gate-attempt-failed :regeneration-cycle/gate-attempt-refused
             :regeneration-cycle/gate-attempt-passed
             :measurement/measurement-filed :measurement/score-stage-failed :measurement/scoreboard-reported
             :regeneration-cycle/cycle-passed :regeneration-cycle/cycle-failed}
   :version 1}
  "The conclude-cycle policy's to-do list."
  [state event]
  (d/fold-cycles-to-conclude state event))

(defreadmodel :regeneration-cycle gate-attempts
  {:events #{:regeneration-cycle/candidate-regenerated :regeneration-cycle/candidate-installed-in-gate-room
             :regeneration-cycle/gate-attempt-passed :regeneration-cycle/gate-attempt-failed
             :regeneration-cycle/gate-attempt-refused}
   :version 1
   :schema (model/read-model-schema :regeneration-cycle/gate-attempts)}
  "Each cycle's gate attempts (entity GateAttempt)."
  [state event]
  (d/fold-gate-attempts state event))

