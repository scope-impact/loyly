(ns loyly.pi-harness.cycle-measurement-test
  (:require [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [clojure.test :refer [deftest is testing]]
            [loyly.pi-harness.cycle-measurement :as measurement]))

(def selected-items
  [{:id "@item-01-001" :name "First" :rule "Availability" :source "spec.feature:6"}
   {:id "@item-01-002" :name "Second" :rule "Booking" :source "spec.feature:12"}
   {:id "@item-01-003" :name "Third" :rule "Billing" :source "spec.feature:18"}])

(deftest measurement-keeps-the-approved-selection-as-its-denominator
  (let [result (measurement/reduce-result
                {:cycle-id "cycle-1"
                 :selection-id "selection-1"
                 :cut "cut-1"
                 :backend "gondolin"
                 :selected-items selected-items
                 :verdicts [{:item-id "01-002" :verdict "pass" :evidence "observed"}]})]
    (is (= {:cycle-id "cycle-1"
            :selection-id "selection-1"
            :cut "cut-1"
            :backend "gondolin"
            :total 3
            :passed 1
            :failed 2
            :complete? false
            :items [{:item-id "@item-01-001" :verdict "fail"
                     :evidence "not graded: independent grader never reached this item"
                     :graded? false}
                    {:item-id "@item-01-002" :verdict "pass"
                     :evidence "observed"
                     :graded? true}
                    {:item-id "@item-01-003" :verdict "fail"
                     :evidence "not graded: independent grader never reached this item"
                     :graded? false}]}
           result))))

(deftest measurement-refuses-verdicts-outside-the-approved-selection
  (testing "unknown item"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown item"
                          (measurement/reduce-result
                           {:cycle-id "cycle-1" :selection-id "selection-1"
                            :cut "cut-1" :backend "gondolin"
                            :selected-items selected-items
                            :verdicts [{:item-id "01-999" :verdict "pass" :evidence "no"}]}))))
  (testing "duplicate item"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"duplicate item"
                          (measurement/reduce-result
                           {:cycle-id "cycle-1" :selection-id "selection-1"
                            :cut "cut-1" :backend "gondolin"
                            :selected-items selected-items
                            :verdicts [{:item-id "01-001" :verdict "pass" :evidence "one"}
                                       {:item-id "@item-01-001" :verdict "fail" :evidence "two"}]})))))

(deftest recorded-measurement-survives-an-event-store-restart
  (let [db (.getAbsolutePath (java.io.File/createTempFile "loyly-measurement-" ".sqlite"))
        tenant #uuid "27e205ec-80c4-3e7c-86ca-9a8cc66e51e7"
        result (measurement/reduce-result
                {:cycle-id "cycle-1" :selection-id "selection-1"
                 :cut "cut-1" :backend "gondolin"
                 :selected-items selected-items
                 :verdicts [{:item-id "01-001" :verdict "pass" :evidence "observed"}]})]
    (.delete (java.io.File. db))
    (try
      (let [events (event-store/start {:conn {:type :sqlite :database-file db}})
            context {:event-store events :tenant-id tenant}]
        (try
          (is (= result (measurement/record! context result)))
          (is (= result (measurement/record! context result)))
          (finally (event-store/stop events))))
      (let [events (event-store/start {:conn {:type :sqlite :database-file db}})]
        (try
          (is (= result
                 (measurement/find-measurement
                  {:event-store events :tenant-id tenant} "cycle-1")))
          (finally (event-store/stop events))))
      (finally (.delete (java.io.File. db))))))
