(ns loyly.pi-harness.grain.cycle-preparation-test
  (:require [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.demos.pi-agent-loop.system :as system]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.grain.interface :as model]))

(def preparation
  {:preparation-id "preparation-1"
   :preparation-digest "sha256:preparation-1"
   :status "awaiting-approval"
   :selection-id "selection-1"
   :selection-digest "sha256:selection-1"
   :scope ["impl"]
   :target-stack "typescript"
   :execution-profile "gondolin-isolated"
   :cut-digest "sha256:cut-1"
   :oracle-digest "sha256:oracle-1"
   :diagram-digest "sha256:diagram-1"
   :max-outer-cycles 1})

(deftest cycle-preparation-is-recorded-approved-and-queried-through-grain
  (let [runtime (system/start!)
        context (:context runtime)]
    (try
      (is (= preparation (model/record-cycle-preparation! context preparation)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"digest does not match"
                            (model/approve-cycle-preparation!
                             context "preparation-1" "sha256:wrong")))
      (is (= "approved"
             (:status (model/approve-cycle-preparation!
                       context "preparation-1" "sha256:preparation-1"))))
      (is (= "approved"
             (:status (model/query-cycle-preparation context "preparation-1"))))
      (finally (system/stop! runtime)))))
