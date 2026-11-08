(ns measurement.policy
  "The todo processor of the event model: report-scoreboard-once."
  (:require [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [measurement.workflows :as wf]))

(defn- to-report [ctx]
  (-> (qp/process-query (assoc (wf/grain-context ctx) :query
                               {:query/name :measurement/measurements-to-report
                                :query/id (random-uuid)
                                :query/timestamp (time/now)}))
      :query/result
      :cycle-ids))

(defprocessor :measurement report-scoreboard-once
  {:topics #{:measurement/measurement-filed}
   :grain.event-model/reads #{:measurement/measurements-to-report}
   :grain.event-model/produces #{:measurement/report-scoreboard}}
  "Whenever a measurement is filed, report its scoreboard, once (rule ReportScoreboardOnce)."
  [{:keys [event] :as ctx}]
  (let [cycle-id (:cycle-id event)]
    (if (some #{cycle-id} (to-report ctx))
      {:result/effect (fn []
                        (wf/execute-command! (wf/grain-context ctx) :measurement/report-scoreboard {:cycle-id cycle-id}))
       :result/checkpoint :after}
      {})))
