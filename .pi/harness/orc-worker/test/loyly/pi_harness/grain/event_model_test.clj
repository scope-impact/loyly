(ns loyly.pi-harness.grain.event-model-test
  (:require [ai.obney.orc.demos.pi-agent-loop.system]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.grain.interface]
            [loyly.pi-harness.grain.runtime-event-model :as runtime-event-model]))

(deftest committed-event-model-strictly-covers-the-live-harness
  (let [verdict (runtime-event-model/load-and-verify!)]
    (is (:valid? verdict))
    (is (zero? (get-in verdict [:summary :fatal])))
    (is (true? (get-in verdict [:summary :runtime/registries-present?])))))
