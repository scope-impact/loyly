(ns loyly.pi-harness.grain.core.processors
  "Asynchronous Grain reactions owned by the Pi-harness service area."
  (:require [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]))

(defn dispatch-approved-preparation!
  "Resolve the application boundary lazily so processor registration does not
   introduce a namespace cycle through the public Grain interface."
  [context preparation-id preparation-digest]
  ((requiring-resolve
    'loyly.pi-harness.core.worker/start-approved-cycle-preparation!)
   context preparation-id preparation-digest))

(defprocessor :loyly.pi-harness start-approved-cycle
  {:topics #{:loyly.pi-harness/cycle-preparation-approved}
   :grain.event-model/reads #{:loyly.pi-harness/cycle-preparation}
   :grain.event-model/produces #{:sheet/tick-tree}}
  "Dispatch an approved preparation through ORC's command boundary."
  [{:keys [event] :as context}]
  {:result/effect
   #(dispatch-approved-preparation! context
                                    (:preparation-id event)
                                    (:preparation-digest event))
   :result/checkpoint :after})
