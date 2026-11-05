(ns loyly.pi-harness.workflows.slice-cycle
  "The one ORC slice cycle. The persisted route (fresh-generation or measured-regeneration)
  selects what the oracle, grade, repair-evaluation, and conclude stages do; every other
  stage is shared."
  (:require [ai.obney.orc.orc-service.interface :as orc]
            [clojure.string :as str]
            [loyly.pi-harness.grain.interface.schemas :as schemas]))

(def routes #{"fresh-generation" "measured-regeneration"})

(def ^:private stage-fields
  [[:stage :string]
   [:request-id :string]
   [:cut :string]
   [:scope [:vector :string]]])

(defn- stage-schema [& fields]
  (into [:map] (concat stage-fields fields)))

(def ^:private optional {:optional true})

(def ^:private builder-schema
  (stage-schema [:output-root :string]
                [:builder :string]
                [:builder-session-id :string]
                [:subject-digest :string]))

(def ^:private instrument-schema
  (stage-schema [:subject-digest :string]
                [:evidence-root :string]
                [:evidence-locations [:vector :string]]))

(def ^:private verify-schema
  (stage-schema [:passed :boolean] [:evidence :string]))

(def ^:private affordance-schema
  [:map
   [:passed :boolean]
   [:applicable :boolean]
   [:total :int]
   [:passed-count :int]
   [:evidence :string]])

(def blackboard
  {:request-id :string
   :orc-tick-id :string
   :route [:enum "fresh-generation" "measured-regeneration"]
   :selection schemas/selection-contract-schema
   :scope [:vector :string]
   :target-stack :string
   :execution-backend :string
   :parent-candidate-id :string
   :parent-candidate-root :string
   :parent-candidate-digest :string
   :cut-result (stage-schema)
   :harness-snapshot-result (stage-schema [:execution-backend :string]
                                          [:passed :boolean]
                                          [:harness-revision :string]
                                          [:harness-snapshot-digest :string]
                                          [:calibration-results :string]
                                          [:evidence :string])
   ;; fresh-generation: an authored, calibrated, sealed cycle oracle.
   ;; measured-regeneration: the pinned accepted oracle resolved by digest.
   :oracle-result (stage-schema [:execution-backend :string]
                                [:passed :boolean]
                                [:kind [:enum "cycle-oracle" "accepted-oracle"]]
                                [:grader-root :string]
                                [:cycle-oracle-digest optional :string]
                                [:binding-digest optional :string]
                                [:author-session-id optional :string]
                                [:oracle-digest optional :string]
                                [:accepted-contract-digest optional :string]
                                [:harness-snapshot-digest optional :string]
                                [:grader-producer optional :string]
                                [:grader-id optional :string]
                                [:evidence :string])
   :builder-result builder-schema
   :instrument-result instrument-schema
   :verify-result verify-schema
   :repair-verification-result (stage-schema [:execution-backend :string]
                                             [:passed :boolean]
                                             [:rounds :int]
                                             [:builder builder-schema]
                                             [:instrument instrument-schema]
                                             [:verify verify-schema]
                                             [:attempts [:vector
                                                         [:map
                                                          [:round :int]
                                                          [:subject-digest :string]
                                                          [:evidence-root :string]
                                                          [:passed :boolean]]]])
   :affordance-conformance-result (into (stage-schema [:execution-backend :string])
                                        (rest affordance-schema))
   :grade-result (stage-schema [:execution-backend optional :string]
                               [:passed :boolean]
                               [:measurement schemas/measurement-schema]
                               [:observations optional [:vector schemas/observation-schema]]
                               [:judge-id optional :string]
                               [:grader-root optional :string]
                               [:grader-producer optional :string]
                               [:normalized-evidence-digest optional :string]
                               [:evidence :string])
   :instrument-conformance-result (stage-schema [:execution-backend :string]
                                                [:passed :boolean]
                                                [:total :int]
                                                [:passed-count :int]
                                                [:items [:vector schemas/item-result-schema]]
                                                [:observations [:vector schemas/observation-schema]]
                                                [:evidence :string])
   :repair-evaluation-result (stage-schema [:execution-backend :string]
                                           [:passed :boolean]
                                           [:applicable :boolean]
                                           [:rounds :int]
                                           [:builder [:map
                                                      [:output-root :string]
                                                      [:subject-digest :string]]]
                                           [:instrument [:map
                                                         [:evidence-root :string]
                                                         [:subject-digest :string]]]
                                           [:affordance affordance-schema]
                                           [:verify [:map [:passed :boolean]]]
                                           [:grade [:map [:passed :boolean]]]
                                           [:instrument-conformance [:map [:passed :boolean]]]
                                           [:attempts [:vector
                                                       [:map
                                                        [:round :int]
                                                        [:subject-digest :string]
                                                        [:evidence-root :string]
                                                        [:verification-passed :boolean]
                                                        [:affordance-passed :boolean]
                                                        [:grade-passed :boolean]
                                                        [:instrument-conformance-passed :boolean]]]])
   ;; fresh-generation: Allure evidence and the oracle proposal (awaiting manual approval).
   ;; measured-regeneration: the recorded measurement and the grade gate.
   :conclude-result (stage-schema [:passed :boolean]
                                  [:allure optional [:map
                                                     [:result-count :int]
                                                     [:paths [:vector :string]]]]
                                  [:oracle-proposal optional schemas/oracle-proposal-schema]
                                  [:cycle optional schemas/measurement-schema]
                                  [:gate optional [:map
                                                   [:operation :string]
                                                   [:passed :boolean]
                                                   [:verified? :boolean]
                                                   [:instrumented? :boolean]
                                                   [:score [:map [:passed :int] [:failed :int]]]]])})

(def ^:private context-keys [:request-id :orc-tick-id :route :selection :scope :target-stack
                             :execution-backend])

(defn- stage [stage-name fn-name reads writes]
  (orc/code stage-name
    :fn (str "loyly.pi-harness.core.worker/" fn-name)
    :reads (into context-keys reads)
    :writes [writes]))

(def ^:private candidate-keys
  [:builder-result :instrument-result :verify-result :repair-verification-result])

(defn workflow []
  (orc/workflow "pi-harness-slice-cycle"
    (orc/blackboard blackboard)
    (orc/sequence "slice-cycle"
      (stage "cut" "cut-stage" [] :cut-result)
      (stage "harness-snapshot" "harness-snapshot-stage" [:cut-result] :harness-snapshot-result)
      (stage "oracle" "oracle-stage" [:harness-snapshot-result] :oracle-result)
      (stage "builder" "builder-stage"
             [:parent-candidate-id :parent-candidate-root :parent-candidate-digest
              :harness-snapshot-result :oracle-result]
             :builder-result)
      (stage "instrument" "instrument-stage" [:builder-result :harness-snapshot-result]
             :instrument-result)
      (stage "verify" "verify-stage" [:builder-result :instrument-result] :verify-result)
      (stage "repair-verification" "repair-verification-stage"
             [:builder-result :instrument-result :verify-result]
             :repair-verification-result)
      (stage "affordance-conformance" "affordance-conformance-stage"
             (conj candidate-keys :oracle-result)
             :affordance-conformance-result)
      (stage "grade" "grade-stage"
             (into candidate-keys [:harness-snapshot-result :oracle-result
                                   :affordance-conformance-result])
             :grade-result)
      (stage "instrument-conformance" "instrument-conformance-stage"
             (into candidate-keys [:oracle-result :grade-result])
             :instrument-conformance-result)
      (stage "repair-evaluation" "repair-evaluation-stage"
             (into candidate-keys [:harness-snapshot-result :oracle-result
                                   :affordance-conformance-result :grade-result
                                   :instrument-conformance-result])
             :repair-evaluation-result)
      (stage "conclude" "conclude-stage"
             (into candidate-keys [:parent-candidate-id :oracle-result
                                   :affordance-conformance-result :grade-result
                                   :instrument-conformance-result :repair-evaluation-result])
             :conclude-result))))

(defn- camel-case [value]
  (str/replace (name value) #"-([a-z])" #(str/upper-case (second %))))

(defn- schema-field-names [schema]
  (mapv (comp camel-case first) (rest schema)))

(defn contract
  "Transport-safe description of the executable slice cycle."
  []
  (let [{:keys [workflow-name blackboard-schema root-node]} (workflow)]
    {:workflowName workflow-name
     :routes (vec (sort routes))
     :rootType (name (:node-type root-node))
     :stages (mapv :name (:children root-node))
     :builderResultFields (schema-field-names (:builder-result blackboard-schema))
     :proposalResultFields (mapv (comp name first) (rest schemas/oracle-proposal-schema))
     :observationFields (schema-field-names schemas/observation-schema)}))
