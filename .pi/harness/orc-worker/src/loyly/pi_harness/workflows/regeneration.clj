(ns loyly.pi-harness.workflows.regeneration
  "Deterministic ORC spine for one regeneration and grading cycle."
  (:require [ai.obney.orc.orc-service.interface :as orc]
            [clojure.string :as str]
            [loyly.pi-harness.grain.interface.schemas :as schemas]))

(defn workflow []
  (orc/workflow "pi-harness-regeneration-cycle"
    (orc/blackboard
      {:request-id :string
       :orc-tick-id :string
       :selection schemas/selection-contract-schema
       :scope [:vector :string]
       :target-stack :string
       :execution-backend :string
       :parent-candidate-id :string
       :parent-candidate-root :string
       :parent-candidate-digest :string
       :cut-result [:map
                    [:stage :string]
                    [:request-id :string]
                    [:cut :string]
                    [:scope [:vector :string]]]
       :harness-calibration-result [:map
                                    [:stage :string]
                                    [:request-id :string]
                                    [:cut :string]
                                    [:scope [:vector :string]]
                                    [:execution-backend :string]
                                    [:passed :boolean]
                                    [:instrument-oracle-digest :string]
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
       :oracle-result [:map
                       [:stage :string]
                       [:request-id :string]
                       [:cut :string]
                       [:scope [:vector :string]]
                       [:passed :boolean]
                       [:oracle-digest :string]
                       [:accepted-contract-digest :string]
                       [:instrument-oracle-digest :string]
                       [:grader-root :string]
                       [:grader-producer :string]
                       [:grader-id :string]
                       [:evidence :string]]
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
                                    [:affordance [:map
                                                  [:passed :boolean]
                                                  [:applicable :boolean]
                                                  [:total :int]
                                                  [:passed-count :int]
                                                  [:evidence :string]]]
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
       :graders-result [:map
                        [:stage :string]
                        [:request-id :string]
                        [:cut :string]
                        [:scope [:vector :string]]
                        [:passed :boolean]
                        [:observations [:vector schemas/observation-schema]]
                        [:measurement schemas/measurement-schema]
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
       :cycle-result schemas/measurement-schema
       :result-result [:map
                       [:stage :string]
                       [:operation :string]
                       [:request-id :string]
                       [:cut :string]
                       [:scope [:vector :string]]
                       [:passed :boolean]
                       [:verified? :boolean]
                       [:instrumented? :boolean]
                       [:score [:map [:passed :int] [:failed :int]]]]})
    (orc/sequence "cycle"
      (orc/code "cut"
        :fn "loyly.pi-harness.core.worker/cut-stage"
        :reads [:request-id :orc-tick-id :selection :scope :target-stack :execution-backend]
        :writes [:cut-result])
      (orc/code "harness-calibration"
        :fn "loyly.pi-harness.core.worker/harness-calibration-stage"
        :reads [:request-id :orc-tick-id :selection :scope :execution-backend :cut-result]
        :writes [:harness-calibration-result])
      (orc/code "builder"
        :fn "loyly.pi-harness.core.worker/builder-stage"
        :reads [:request-id :orc-tick-id :selection :scope :target-stack :execution-backend
                :parent-candidate-id :parent-candidate-root :parent-candidate-digest
                :harness-calibration-result]
        :writes [:builder-result])
      (orc/code "instrument"
        :fn "loyly.pi-harness.core.worker/instrument-stage"
        :reads [:request-id :orc-tick-id :selection :scope :execution-backend :builder-result
                :harness-calibration-result]
        :writes [:instrument-result])
      (orc/code "oracle"
        :fn "loyly.pi-harness.core.worker/oracle-stage"
        :reads [:request-id :orc-tick-id :selection :scope :execution-backend :instrument-result
                :harness-calibration-result]
        :writes [:oracle-result])
      (orc/code "affordance-conformance"
        :fn "loyly.pi-harness.core.worker/affordance-conformance-stage"
        :reads [:request-id :orc-tick-id :selection :scope :target-stack :execution-backend
                :builder-result :instrument-result :oracle-result]
        :writes [:affordance-conformance-result])
      (orc/code "verify"
        :fn "loyly.pi-harness.core.worker/verify-stage"
        :reads [:request-id :orc-tick-id :selection :scope :target-stack :execution-backend
                :builder-result :instrument-result :oracle-result :affordance-conformance-result]
        :writes [:verify-result])
      (orc/code "repair-verification"
        :fn "loyly.pi-harness.core.worker/repair-verification-stage"
        :reads [:request-id :orc-tick-id :selection :scope :target-stack :execution-backend
                :harness-calibration-result :builder-result :instrument-result
                :oracle-result :affordance-conformance-result :verify-result]
        :writes [:repair-verification-result])
      (orc/code "graders"
        :fn "loyly.pi-harness.core.worker/graders-stage"
        :reads [:request-id :orc-tick-id :selection :scope :target-stack :execution-backend
                :builder-result :instrument-result :oracle-result :verify-result
                :repair-verification-result]
        :writes [:graders-result])
      (orc/code "instrument-conformance"
        :fn "loyly.pi-harness.core.worker/instrument-conformance-stage"
        :reads [:request-id :orc-tick-id :selection :scope :execution-backend :builder-result
                :instrument-result :oracle-result :graders-result :repair-verification-result]
        :writes [:instrument-conformance-result])
      (orc/code "record-cycle"
        :fn "loyly.pi-harness.core.worker/record-cycle-stage"
        :reads [:request-id :orc-tick-id :selection :scope :execution-backend :builder-result
                :instrument-result :oracle-result :verify-result :graders-result
                :instrument-conformance-result :repair-verification-result]
        :writes [:cycle-result])
      (orc/code "grade-gate"
        :fn "loyly.pi-harness.core.worker/grade-gate-stage"
        :reads [:request-id :orc-tick-id :selection :scope :execution-backend :verify-result
                :graders-result :instrument-conformance-result :repair-verification-result]
        :writes [:result-result]))))

(defn- camel-case [value]
  (str/replace (name value) #"-([a-z])" #(str/upper-case (second %))))

(defn- schema-field-names [schema]
  (mapv (comp camel-case first) (rest schema)))

(defn contract
  "Transport-safe description of the executable regeneration workflow."
  []
  (let [{:keys [workflow-name blackboard-schema root-node]} (workflow)]
    {:operation "regeneration"
     :workflowName workflow-name
     :rootType (name (:node-type root-node))
     :stages (mapv :name (:children root-node))
     :builderResultFields (schema-field-names (:builder-result blackboard-schema))
     :observationFields (schema-field-names schemas/observation-schema)}))
