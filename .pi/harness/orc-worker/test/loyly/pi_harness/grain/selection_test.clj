(ns loyly.pi-harness.grain.selection-test
  (:require [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.demos.pi-agent-loop.system :as system]
            [clojure.test :refer [deftest is testing]]
            [loyly.pi-harness.grain.interface :as model]
            [loyly.pi-harness.grain.selection :as registry]))

(def contract
  {:selectionId "selection-1" :selectionDigest "digest-1"
   :projectId "loyly" :containerId "loyly" :componentId "booking-core"
   :status "awaiting-approval" :cut "cut-1" :slice "01-booking-core"
   :items [{:id "@item-01-001" :scenario-digest "sha256:scenario-1"
            :name "First" :rule "Availability" :source "spec.feature:6"}]})

(deftest approval-survives-an-event-store-restart
  (let [db (.getAbsolutePath (java.io.File/createTempFile "loyly-registry-" ".sqlite"))
        tenant #uuid "27e205ec-80c4-3e7c-86ca-9a8cc66e51e7"]
    (.delete (java.io.File. db))
    (try
      (let [events (event-store/start {:conn {:type :sqlite :database-file db}})
            context {:event-store events :tenant-id tenant}]
        (try
          (is (= contract (registry/record! context contract)))
          (testing "approval is bound to the exact reviewed digest"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"digest does not match"
                                  (registry/approve! context "selection-1" "other"))))
          (is (= "approved" (:status (registry/approve! context "selection-1" "digest-1"))))
          (testing "an approved deterministic selection can be previewed again"
            (is (= (assoc contract :status "approved")
                   (registry/record! context contract))))
          (finally (event-store/stop events))))
      (let [events (event-store/start {:conn {:type :sqlite :database-file db}})]
        (try
          (is (= (assoc contract :status "approved")
                 (registry/find-selection {:event-store events :tenant-id tenant} "selection-1")))
          (finally (event-store/stop events))))
      (finally (.delete (java.io.File. db))))))

(deftest selection-is-readable-through-the-registered-projection
  (let [runtime (system/start!)
        context (:context runtime)
        projected-contract (assoc contract
                                  :selectionId "selection-projection"
                                  :selectionDigest "projection-digest")]
    (try
      (is (= projected-contract
             (model/record-selection! context projected-contract)))
      (is (= "approved"
             (:status (model/approve-selection!
                       context "selection-projection" "projection-digest"))))
      (is (= "approved"
             (:status (model/query-selection context "selection-projection"))))
      (finally
        (system/stop! runtime)))))
