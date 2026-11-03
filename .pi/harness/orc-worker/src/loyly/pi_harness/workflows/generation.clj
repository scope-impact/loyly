(ns loyly.pi-harness.workflows.generation
  "ORC workflow for first-time, explicitly ungraded candidate generation."
  (:require [ai.obney.orc.orc-service.interface :as orc]
            [loyly.pi-harness.grain.interface.schemas :as schemas]))

(defn workflow []
  (orc/workflow "pi-harness-initial-generation"
    (orc/blackboard
      {:request-id :string
       :orc-tick-id :string
       :selection schemas/selection-contract-schema
       :scope [:vector :string]
       :target-stack :string
       :execution-backend :string
       :cut-result [:map
                    [:stage :string]
                    [:request-id :string]
                    [:cut :string]
                    [:scope [:vector :string]]]
       :builder-result [:map
                        [:stage :string]
                        [:request-id :string]
                        [:cut :string]
                        [:scope [:vector :string]]
                        [:output-root :string]
                        [:builder :string]
                        [:builder-session-id :string]
                        [:subject-digest :string]]
       :generation-result [:map
                           [:stage :string]
                           [:operation :string]
                           [:request-id :string]
                           [:cut :string]
                           [:scope [:vector :string]]
                           [:execution-backend :string]
                           [:candidate-disposition :string]
                           [:output-root :string]
                           [:builder :string]
                           [:builder-session-id :string]
                           [:subject-digest :string]]})
    (orc/sequence "generation"
      (orc/code "cut"
        :fn "loyly.pi-harness.core.worker/cut-stage"
        :reads [:request-id :orc-tick-id :selection :scope :target-stack :execution-backend]
        :writes [:cut-result])
      (orc/code "builder"
        :fn "loyly.pi-harness.core.worker/builder-stage"
        :reads [:request-id :orc-tick-id :selection :scope :target-stack :execution-backend]
        :writes [:builder-result])
      (orc/code "record-candidate"
        :fn "loyly.pi-harness.core.worker/generation-stage"
        :reads [:request-id :orc-tick-id :selection :scope :execution-backend :builder-result]
        :writes [:generation-result]))))

(defn contract []
  (let [{:keys [workflow-name blackboard-schema root-node]} (workflow)]
    {:operation "generation"
     :workflowName workflow-name
     :rootType (name (:node-type root-node))
     :stages (mapv :name (:children root-node))
     :candidateResultFields (mapv (comp name first) (rest (:generation-result blackboard-schema)))}))
