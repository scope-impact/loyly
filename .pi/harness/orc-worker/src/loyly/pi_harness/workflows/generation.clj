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
       :instrument-result [:map
                           [:stage :string]
                           [:request-id :string]
                           [:cut :string]
                           [:scope [:vector :string]]
                           [:subject-digest :string]
                           [:evidence-root :string]
                           [:evidence-locations [:vector :string]]]
       :verify-result [:map
                       [:stage :string]
                       [:request-id :string]
                       [:cut :string]
                       [:scope [:vector :string]]
                       [:passed :boolean]
                       [:evidence :string]]
       :repair-verification-result [:map
                                    [:stage :string]
                                    [:request-id :string]
                                    [:cut :string]
                                    [:scope [:vector :string]]
                                    [:execution-backend :string]
                                    [:passed :boolean]
                                    [:rounds :int]
                                    [:builder [:map
                                               [:stage :string]
                                               [:request-id :string]
                                               [:cut :string]
                                               [:scope [:vector :string]]
                                               [:output-root :string]
                                               [:builder :string]
                                               [:builder-session-id :string]
                                               [:subject-digest :string]]]
                                    [:instrument [:map
                                                  [:stage :string]
                                                  [:request-id :string]
                                                  [:cut :string]
                                                  [:scope [:vector :string]]
                                                  [:subject-digest :string]
                                                  [:evidence-root :string]
                                                  [:evidence-locations [:vector :string]]]]
                                    [:verify [:map
                                              [:stage :string]
                                              [:request-id :string]
                                              [:cut :string]
                                              [:scope [:vector :string]]
                                              [:passed :boolean]
                                              [:evidence :string]]]
                                    [:attempts [:vector
                                                [:map
                                                 [:round :int]
                                                 [:subject-digest :string]
                                                 [:evidence-root :string]
                                                 [:passed :boolean]]]]]
       :generation-result [:map
                           [:stage :string]
                           [:operation :string]
                           [:request-id :string]
                           [:cut :string]
                           [:scope [:vector :string]]
                           [:execution-backend :string]
                           [:candidate-disposition :string]
                           [:verified? :boolean]
                           [:verification-rounds :int]
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
      (orc/code "instrument"
        :fn "loyly.pi-harness.core.worker/instrument-stage"
        :reads [:request-id :orc-tick-id :selection :scope :execution-backend :builder-result]
        :writes [:instrument-result])
      (orc/code "verify"
        :fn "loyly.pi-harness.core.worker/verify-stage"
        :reads [:request-id :orc-tick-id :selection :scope :target-stack :execution-backend
                :builder-result :instrument-result]
        :writes [:verify-result])
      (orc/code "repair-verification"
        :fn "loyly.pi-harness.core.worker/repair-verification-stage"
        :reads [:request-id :orc-tick-id :selection :scope :target-stack :execution-backend
                :builder-result :instrument-result :verify-result]
        :writes [:repair-verification-result])
      (orc/code "record-candidate"
        :fn "loyly.pi-harness.core.worker/generation-stage"
        :reads [:request-id :orc-tick-id :selection :scope :execution-backend :builder-result
                :repair-verification-result]
        :writes [:generation-result]))))

(defn contract []
  (let [{:keys [workflow-name blackboard-schema root-node]} (workflow)]
    {:operation "generation"
     :workflowName workflow-name
     :rootType (name (:node-type root-node))
     :stages (mapv :name (:children root-node))
     :candidateResultFields (mapv (comp name first) (rest (:generation-result blackboard-schema)))}))
