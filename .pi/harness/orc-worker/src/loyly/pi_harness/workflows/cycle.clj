(ns loyly.pi-harness.workflows.cycle
  "The regeneration cycle in the legacy shape: fence → regenerate → gate → score → scoreboard."
  (:require [ai.obney.orc.orc-service.interface :as orc]))

(def stages ["fence" "regenerate" "gate" "score" "scoreboard"])

(def ^:private stage-result
  [:map [:stage :string] [:request-id :string] [:passed :boolean]])

(defn workflow []
  (orc/workflow "pi-harness-cycle"
    (orc/blackboard
      {:request-id :string
       :orc-tick-id :string
       :fence-result stage-result
       :regenerate-result stage-result
       :gate-result stage-result
       :score-result stage-result
       :scoreboard-result stage-result})
    (orc/sequence "cycle"
      (orc/code "fence"
        :fn "loyly.pi-harness.core.worker/cycle-fence-stage"
        :reads [:request-id :orc-tick-id]
        :writes [:fence-result])
      (orc/code "regenerate"
        :fn "loyly.pi-harness.core.worker/cycle-regenerate-stage"
        :reads [:request-id :orc-tick-id :fence-result]
        :writes [:regenerate-result])
      (orc/code "gate"
        :fn "loyly.pi-harness.core.worker/cycle-gate-stage"
        :reads [:request-id :orc-tick-id :fence-result :regenerate-result]
        :writes [:gate-result])
      (orc/code "score"
        :fn "loyly.pi-harness.core.worker/cycle-score-stage"
        :reads [:request-id :orc-tick-id :fence-result :gate-result]
        :writes [:score-result])
      (orc/code "scoreboard"
        :fn "loyly.pi-harness.core.worker/cycle-scoreboard-stage"
        :reads [:request-id :orc-tick-id :fence-result :score-result]
        :writes [:scoreboard-result]))))

(defn contract []
  (let [{:keys [workflow-name root-node]} (workflow)]
    {:workflowName workflow-name
     :rootType (name (:node-type root-node))
     :stages (mapv :name (:children root-node))}))
