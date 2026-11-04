(ns loyly.pi-harness.workflows.generation
  "ORC workflow for independently graded first generation and oracle proposal."
  (:require [ai.obney.orc.orc-service.interface :as orc]
            [loyly.pi-harness.grain.interface.schemas :as schemas]))

(defn workflow []
  (orc/workflow "pi-harness-fresh-generation"
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
       :harness-snapshot-result [:map
                                    [:stage :string]
                                    [:request-id :string]
                                    [:cut :string]
                                    [:scope [:vector :string]]
                                    [:execution-backend :string]
                                    [:passed :boolean]
                                    [:harness-revision :string]
                                    [:harness-snapshot-digest :string]
                                    [:calibration-results :string]
                                    [:evidence :string]]
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
       :affordance-conformance-result [:map
                                       [:stage :string]
                                       [:request-id :string]
                                       [:cut :string]
                                       [:scope [:vector :string]]
                                       [:execution-backend :string]
                                       [:passed :boolean]
                                       [:applicable :boolean]
                                       [:total :int]
                                       [:passed-count :int]
                                       [:evidence :string]]
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
       :cycle-oracle-result [:map
                             [:stage :string]
                             [:request-id :string]
                             [:cut :string]
                             [:scope [:vector :string]]
                             [:execution-backend :string]
                             [:passed :boolean]
                             [:grader-root :string]
                             [:cycle-oracle-digest :string]
                             [:binding-digest :string]
                             [:author-session-id :string]
                             [:evidence :string]]
       :baseline-grade-result [:map
                               [:stage :string]
                               [:request-id :string]
                               [:cut :string]
                               [:scope [:vector :string]]
                               [:execution-backend :string]
                               [:passed :boolean]
                               [:judge-id :string]
                               [:grader-root :string]
                               [:grader-producer :string]
                               [:measurement schemas/measurement-schema]
                               [:normalized-evidence-digest :string]
                               [:evidence :string]]
       :instrument-conformance-result [:map
                                       [:stage :string]
                                       [:request-id :string]
                                       [:cut :string]
                                       [:scope [:vector :string]]
                                       [:execution-backend :string]
                                       [:passed :boolean]
                                       [:total :int]
                                       [:passed-count :int]
                                       [:items [:vector schemas/item-result-schema]]
                                       [:observations [:vector schemas/observation-schema]]
                                       [:evidence :string]]
       :repair-evaluation-result [:map
                                  [:stage :string]
                                  [:request-id :string]
                                  [:cut :string]
                                  [:scope [:vector :string]]
                                  [:execution-backend :string]
                                  [:passed :boolean]
                                  [:rounds :int]
                                  [:builder [:map
                                             [:output-root :string]
                                             [:subject-digest :string]]]
                                  [:instrument [:map
                                                [:evidence-root :string]
                                                [:subject-digest :string]]]
                                  [:affordance [:map
                                                [:passed :boolean]
                                                [:applicable :boolean]
                                                [:total :int]
                                                [:passed-count :int]
                                                [:evidence :string]]]
                                  [:verify [:map [:passed :boolean]]]
                                  [:baseline-grade [:map [:passed :boolean]]]
                                  [:instrument-conformance [:map [:passed :boolean]]]
                                  [:attempts [:vector
                                              [:map
                                               [:round :int]
                                               [:subject-digest :string]
                                               [:evidence-root :string]
                                               [:verification-passed :boolean]
                                               [:affordance-passed :boolean]
                                               [:grade-passed :boolean]
                                               [:instrument-conformance-passed :boolean]]]]]
       :allure-result [:map
                       [:stage :string]
                       [:request-id :string]
                       [:cut :string]
                       [:scope [:vector :string]]
                       [:execution-backend :string]
                       [:result-count :int]
                       [:paths [:vector :string]]]
       :oracle-proposal-result schemas/oracle-proposal-schema})
    (orc/sequence "generation"
      (orc/code "cut"
        :fn "loyly.pi-harness.core.worker/cut-stage"
        :reads [:request-id :orc-tick-id :selection :scope :target-stack :execution-backend]
        :writes [:cut-result])
      (orc/code "harness-snapshot"
        :fn "loyly.pi-harness.core.worker/harness-snapshot-stage"
        :reads [:request-id :orc-tick-id :selection :scope :execution-backend :cut-result]
        :writes [:harness-snapshot-result])
      (orc/sequence "fenced-build"
        (orc/code "builder"
          :fn "loyly.pi-harness.core.worker/builder-stage"
          :reads [:request-id :orc-tick-id :selection :scope :target-stack :execution-backend]
          :writes [:builder-result])
        (orc/code "instrument"
          :fn "loyly.pi-harness.core.worker/instrument-stage"
          :reads [:request-id :orc-tick-id :selection :scope :execution-backend :builder-result]
          :writes [:instrument-result]))
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
      (orc/sequence "grade"
        (orc/code "cycle-oracle"
          :fn "loyly.pi-harness.core.worker/cycle-oracle-stage"
          :reads [:request-id :orc-tick-id :selection :scope :execution-backend
                  :harness-snapshot-result]
          :writes [:cycle-oracle-result])
        (orc/code "affordance-conformance"
          :fn "loyly.pi-harness.core.worker/affordance-conformance-stage"
          :reads [:request-id :orc-tick-id :selection :scope :target-stack :execution-backend
                  :builder-result :instrument-result :repair-verification-result :cycle-oracle-result]
          :writes [:affordance-conformance-result])
        (orc/code "baseline-grade"
          :fn "loyly.pi-harness.core.worker/baseline-grade-stage"
          :reads [:request-id :orc-tick-id :selection :scope :target-stack :execution-backend
                  :harness-snapshot-result :cycle-oracle-result :builder-result :instrument-result
                  :verify-result :repair-verification-result]
          :writes [:baseline-grade-result])
        (orc/code "instrument-conformance"
          :fn "loyly.pi-harness.core.worker/instrument-conformance-stage"
          :reads [:request-id :orc-tick-id :selection :scope :execution-backend :builder-result
                  :instrument-result :baseline-grade-result :repair-verification-result
                  :cycle-oracle-result]
          :writes [:instrument-conformance-result])
        (orc/code "repair-evaluation"
          :fn "loyly.pi-harness.core.worker/repair-evaluation-stage"
          :reads [:request-id :orc-tick-id :selection :scope :target-stack :execution-backend
                  :harness-snapshot-result :cycle-oracle-result :builder-result :instrument-result
                  :verify-result :repair-verification-result :affordance-conformance-result
                  :baseline-grade-result :instrument-conformance-result]
          :writes [:repair-evaluation-result])
        (orc/code "materialize-allure"
          :fn "loyly.pi-harness.core.worker/initial-allure-stage"
          :reads [:request-id :orc-tick-id :selection :scope :execution-backend
                  :builder-result :instrument-result :repair-verification-result
                  :baseline-grade-result :instrument-conformance-result
                  :affordance-conformance-result :repair-evaluation-result]
          :writes [:allure-result])
        (orc/code "propose-oracle"
          :fn "loyly.pi-harness.core.worker/propose-oracle-stage"
          :reads [:request-id :orc-tick-id :selection :scope :execution-backend :builder-result
                  :repair-verification-result :baseline-grade-result
                  :instrument-conformance-result :repair-evaluation-result :allure-result]
          :writes [:oracle-proposal-result])))))

(defn contract []
  (let [{:keys [workflow-name blackboard-schema root-node]} (workflow)]
    {:operation "fresh-generation"
     :workflowName workflow-name
     :rootType (name (:node-type root-node))
     :stages (mapv :name (:children root-node))
     :proposalResultFields (mapv (comp name first)
                                 (rest (:oracle-proposal-result blackboard-schema)))}))
