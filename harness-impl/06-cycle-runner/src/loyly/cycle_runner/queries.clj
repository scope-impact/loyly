(ns loyly.cycle-runner.queries
  "The queries of the run desk: a view, never an authority."
  (:require [ai.obney.grain.query-processor.interface :refer [defquery]]
            [loyly.cycle-runner.blocks :as blocks :refer [runs progress]]))

(defquery :cycle-runner run-progress
  {:authorized? (constantly true)
   :grain.event-model/reads #{:cycle-runner/runs :cycle-runner/effect-results :cycle-runner/relayed-handoffs}}
  "One run's status, effect results and relayed handoffs, or every run's."
  [ctx]
  (let [run-id (get-in ctx [:query :run-id])
        all (runs ctx)]
    (if run-id
      {:query/result {:run (progress ctx (get all run-id))}}
      {:query/result {:runs (->> (vals all) (sort-by :run/order) (mapv #(progress ctx %)))}})))

(defquery :cycle-runner runs-to-advance
  {:authorized? (constantly true)
   :grain.event-model/reads #{:cycle-runner/runs-to-advance}}
  "The advance-run processor's to-do list."
  [ctx]
  {:query/result {:run-ids (blocks/runs-to-advance ctx)}})

