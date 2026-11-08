(ns loyly.grader-lifecycle.queries
  "The query instrument-goldens, answered from its read model."
  (:require [ai.obney.grain.query-processor.interface :refer [defquery]]
            [loyly.grader-lifecycle.domain :refer [goldens-state find-golden row]]))

;; ---------------------------------------------------------------------------
;; The query
;; ---------------------------------------------------------------------------

(defquery :grader-lifecycle instrument-goldens
  {:authorized? (constantly true)
   :grain.event-model/reads #{:grader-lifecycle/instrument-goldens}}
  "Every instrument golden with its move history, in the order written."
  [context]
  (let [state (goldens-state context)]
    {:query/result {:goldens (mapv #(row (find-golden state %)) (:order state))}}))
