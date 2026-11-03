(ns loyly.pi-harness.entrypoint-test
  (:require [clojure.test :refer [deftest is]]
            [loyly.pi-harness.durable-entrypoint :as entrypoint]
            [loyly.pi-harness.interface :as harness]
            [loyly.pi-harness.grain.interface :as model]))

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
    (with-redefs [harness/runtime-context (constantly {})
                  model/approve-selection!
                  (fn [_ selection-id digest]
                    (reset! call [selection-id digest])
                    (assoc contract :status "approved"))]
      (is (= "approved"
             (:status (entrypoint/approve-selection!
                       {:selectionId "selection-1" :selectionDigest "digest-1"}))))
      (is (= ["selection-1" "digest-1"] @call)))))

(deftest approved-slice-submission-forwards-approval-without-an-operation-choice
  (let [request {:requestId "request-1"
                 :selectionId "selection-1"
                 :scope ["workspace"]
                 :targetStack "typescript"
                 :executionBackend "gondolin"}
        expected (-> request
                     (dissoc :selectionId)
                     (assoc :selectionContract (assoc contract :status "approved")))]
    (with-redefs [harness/runtime-context (constantly {})
                  model/find-selection
                  (fn [_ _] (assoc contract :status "approved"))
                  harness/submit-approved-slice! identity]
      (is (= expected (entrypoint/submit-approved! request))))))
