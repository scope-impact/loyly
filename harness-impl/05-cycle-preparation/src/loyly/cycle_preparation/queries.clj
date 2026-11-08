(ns loyly.cycle-preparation.queries
  "The query cycle-preparation/source-cuts, in a namespace of its own so its
   handler var does not shadow the read model's of the same name."
  (:require [ai.obney.grain.query-processor.interface :refer [defquery]]
            [loyly.cycle-preparation.model :as model]
            [loyly.cycle-preparation.domain :as domain]))

(defquery :cycle-preparation source-cuts
  {:authorized? (constantly true)
   :grain.event-model/reads (:reads (model/query-block domain/source-cuts))}
  "The pinned cuts, newest first."
  [context]
  {:query/result (domain/pinned-cuts context)})
