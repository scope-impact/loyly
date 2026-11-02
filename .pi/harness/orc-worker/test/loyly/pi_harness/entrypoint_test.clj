(ns loyly.pi-harness.entrypoint-test
  (:require [clojure.test :refer [deftest is]]
            [loyly.pi-harness.durable-entrypoint :as entrypoint]
            [loyly.pi-harness.orc-worker :as worker]
            [loyly.pi-harness.selection-registry :as selection-registry]))

(def contract
  {:selectionId "selection-1"
   :selectionDigest "digest-1"
   :status "awaiting-approval"
   :projectId "loyly"
   :containerId "loyly"
   :componentId "booking-core"
   :cut "cut-1"
   :slice "01-booking-core"
   :items [{:id "@item-01-001"}]})

(deftest approval-is-forwarded-to-the-durable-selection-registry
  (let [call (atom nil)]
    (with-redefs [selection-registry/approve!
                  (fn [_ selection-id digest]
                    (reset! call [selection-id digest])
                    (assoc contract :status "approved"))]
      (is (= "approved"
             (:status (entrypoint/approve-selection!
                       {:selectionId "selection-1" :selectionDigest "digest-1"}))))
      (is (= ["selection-1" "digest-1"] @call)))))

(deftest regeneration-carries-the-approved-selection-into-the-cycle
  (with-redefs [selection-registry/find-selection
                (fn [_ _] (assoc contract :status "approved"))
                worker/submit! identity]
    (is (= {:requestId "request-1" :scope ["workspace"]
            :selectionContract (assoc contract :status "approved")}
           (entrypoint/submit-approved!
            {:requestId "request-1" :selectionId "selection-1" :scope ["workspace"]})))))
