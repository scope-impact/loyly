(ns loyly.pi-harness.core.grading.independent-grader-test
  (:require [clojure.test :refer [deftest is testing]]
            [loyly.pi-harness.core.grading.independent-grader :as grader]))

(def expected-items ["01-007"])

(deftest complete-independent-verdicts-pass
  (let [result (grader/interpret-verdicts
                expected-items
                "[{\"itemId\":\"01-007\",\"verdict\":\"pass\",\"evidence\":\"schedule, heater, and billing agree\"}]")]
    (is (= true (:passed result)))
    (is (= 1 (:total result)))
    (is (= 1 (:passed-count result)))
    (is (= expected-items (mapv :item-id (:verdicts result))))))

(deftest incomplete-or-invalid-independent-verdicts-fail-closed
  (doseq [[label output error]
          [["empty" "[]" #"graded zero items"]
           ["missing" "[]" #"graded zero items"]
           ["unknown" "[{\"itemId\":\"01-999\",\"verdict\":\"pass\",\"evidence\":\"x\"}]" #"unknown item"]
           ["duplicate" "[{\"itemId\":\"01-007\",\"verdict\":\"pass\",\"evidence\":\"x\"},{\"itemId\":\"01-007\",\"verdict\":\"pass\",\"evidence\":\"y\"}]" #"duplicate item"]
           ["bad verdict" "[{\"itemId\":\"01-007\",\"verdict\":\"maybe\",\"evidence\":\"x\"}]" #"invalid verdict"]
           ["no evidence" "[{\"itemId\":\"01-007\",\"verdict\":\"pass\",\"evidence\":\"\"}]" #"missing evidence"]]]
    (testing label
      (let [result (grader/interpret-verdicts expected-items output)]
        (is (= false (:passed result)))
        (is (re-find error (:stderr result)))))))

(deftest an-explicit-item-failure-is-a-complete-failed-grade
  (let [result (grader/interpret-verdicts
                expected-items
                "[{\"itemId\":\"01-007\",\"verdict\":\"fail\",\"evidence\":\"billing signal absent\"}]")]
    (is (= false (:passed result)))
    (is (= 1 (:total result)))
    (is (= 0 (:passed-count result)))
    (is (re-find #"01-007 failed" (:stderr result)))))
