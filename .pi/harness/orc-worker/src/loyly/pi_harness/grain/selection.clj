(ns loyly.pi-harness.grain.selection
  "Compatibility façade for selection model operations."
  (:require [loyly.pi-harness.grain.interface :as model]))

(def item-schema model/item-schema)
(def contract-schema model/selection-contract-schema)
(def find-selection model/find-selection)
(def record! model/record-selection!)
(def approve! model/approve-selection!)
