(ns loyly.pi-harness.grain.start-approved-cycle-test
  (:require [ai.obney.grain.todo-processor-v2.interface :as todo]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.grain.core.processors :as processors]))

(deftest approved-preparation-dispatches-once-through-the-application-boundary
  (let [calls (atom [])
        context {:tenant-id (random-uuid)
                 :event {:event/type :loyly.pi-harness/cycle-preparation-approved
                         :preparation-id "prep-37"
                         :preparation-digest "sha256:prep-37"}}
        handler (:handler-fn
                 (get @todo/processor-registry*
                      :loyly.pi-harness/start-approved-cycle))]
    (with-redefs [processors/dispatch-approved-preparation!
                  (fn [& args] (swap! calls conj args))]
      (let [result (handler context)]
        (is (= :after (:result/checkpoint result)))
        ((:result/effect result))
        (is (= [[context "prep-37" "sha256:prep-37"]] @calls))))))
