(ns loyly.pi-harness.grain.observation
  "Compatibility façade for observation model operations."
  (:require [loyly.pi-harness.grain.interface :as model]))

(def observation-schema model/observation-schema)
(def find-cycle model/find-cycle)
(def record! model/record-observation!)
