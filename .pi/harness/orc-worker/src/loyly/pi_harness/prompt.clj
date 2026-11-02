(ns loyly.pi-harness.prompt
  "GEPA-owned builder instruction workflow.

   GEPA optimizes the node instruction. The code node exposes that instruction
   unchanged so the active Pi/Codex session can be the proposer without a second
   provider call inside ORC. The selected instruction is later passed to the
   Codex Pi builder; TypeScript never constructs it."
  (:require [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.gepa.interface :as gepa]
            [loyly.pi-harness.selection-registry :as selection-registry]))

(def seed-instruction
  "Regenerate the requested project scope from the pinned cut. Inspect the canonical product contract and supporting source material first. Rebuild rather than copying implementation files. Write only the declared scope, run the relevant tests, and report the evidence and remaining failures.")

(defn expose-candidate-instruction [{:keys [node]}]
  {:prompt (:instruction node)})

(defn workflow []
  (orc/workflow "pi-harness-builder-prompt"
    (orc/blackboard
      {:task :string
       :request-id :string
       :selection selection-registry/contract-schema
       :scope [:vector :string]
       :prompt :string})
    (assoc
     (orc/code "builder-instruction"
       :fn "loyly.pi-harness.prompt/expose-candidate-instruction"
       :reads [:task :request-id :selection :scope]
       :writes [:prompt])
     :instruction seed-instruction)))

(defn optimize!
  "Optimize the builder instruction with GEPA.

   `metric-fn` must independently evaluate a generated candidate. For production
   use it should run the project's independent verifier and oracle against the candidate workspace."
  [context sheet-id trainset valset metric-fn]
  (gepa/optimize! context
                  {:sheet-id sheet-id
                   :trainset trainset
                   :valset valset
                   :metric-fn metric-fn
                   :inherit-from-previous true
                   :block? true
                   :config {:max-generations 3
                            :max-metric-calls 12}}))

(defn winner-instruction [optimization-result]
  (get-in optimization-result [:best-candidate :instructions "builder-instruction"]))
