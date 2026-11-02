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

(deftest regeneration-carries-the-approved-selection-into-the-cycle
  (with-redefs [harness/runtime-context (constantly {})
                model/find-selection
                (fn [_ _] (assoc contract :status "approved"))
                harness/submit! identity]
    (is (= {:requestId "request-1" :scope ["workspace"]
            :selectionContract (assoc contract :status "approved")}
           (entrypoint/submit-approved!
            {:requestId "request-1" :selectionId "selection-1" :scope ["workspace"]})))))

(deftest durable-cycle-start-carries-the-approved-selection-to-orc
  (let [request {:cycleId "cycle-1"
                 :tickId "f36dc9b7-9b92-4e15-84f5-1b9c4b798416"
                 :correlationId "46ec162b-2539-4964-aea0-9d06552e7e07"
                 :selectionId "selection-1"
                 :scope ["workspace"]
                 :targetStack "typescript"
                 :executionBackend "gondolin"}]
    (with-redefs [harness/runtime-context (constantly {})
                  model/find-selection
                  (fn [_ _] (assoc contract :status "approved"))
                  harness/start-cycle! identity]
      (is (= (-> request
                 (dissoc :selectionId)
                 (assoc :selectionContract (assoc contract :status "approved")))
             (entrypoint/start-approved-cycle! request))))))
