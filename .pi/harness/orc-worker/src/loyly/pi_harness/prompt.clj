(ns loyly.pi-harness.prompt
  "Compatibility boundary for executor names stored in ORC workflow nodes.

   New implementation code belongs in `loyly.pi-harness.core.build.prompt`."
  (:require [loyly.pi-harness.core.build.prompt :as prompt]))

(def seed-instruction prompt/seed-instruction)
(def expose-candidate-instruction prompt/expose-candidate-instruction)
(def workflow prompt/workflow)
(def optimize! prompt/optimize!)
(def winner-instruction prompt/winner-instruction)
