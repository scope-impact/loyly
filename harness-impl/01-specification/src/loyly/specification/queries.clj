(ns loyly.specification.queries
  "The query :specification/stamped-items, answered from its read model."
  (:require [ai.obney.grain.query-processor.interface :refer [defquery]]
            [loyly.specification.domain :as domain]))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defquery :specification stamped-items
  {:authorized? (constantly true)
   :grain.event-model/reads #{:specification/stamped-items}}
  "The stamped items of one slice, or of every slice."
  [context]
  (let [wanted (get-in context [:query :slice])
        state (domain/stamped-items context)]
    {:query/result
     (->> (if (some? wanted)
            (keep #(get state %) [wanted])
            (vals state))
          (sort-by :slice)
          vec)}))
