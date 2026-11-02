(ns loyly.pi-harness.grain.cycle-measurement
  "Compatibility façade for cycle measurement model operations."
  (:require [loyly.pi-harness.grain.interface :as model]))

(def item-result-schema model/item-result-schema)
(def instrument-conformance-schema model/instrument-conformance-schema)
(def measurement-schema model/measurement-schema)
(def reduce-result model/reduce-result)
(def find-measurement model/find-measurement)
(def record! model/record-measurement!)
