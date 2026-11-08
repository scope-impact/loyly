(ns measurement.scoreboard-test
  "Rules: The scoreboard is reported once a cycle, to a person, never into the loop.
   A cycle is compared with the last measured cycle of the same selection.
   A rate has one decimal, rounded half to even, and \"n/a\" over zero."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [measurement.support :as s]))

(use-fixtures :each s/with-product)

(defn- verdicts [passes total slice]
  (map-indexed (fn [i id] [id (if (< i passes) "pass" "fail")]) (s/ids-of total slice)))

(deftest item-02-018-a-baseline-scoreboard
  (let [r (s/measured! {:cycle "C-7" :cut "K-3" :verdicts (verdicts 2 3 "03")})]
    (testing "the policy's events are not in file-score's result"
      (is (= ["measurement/measurement-filed"] (s/event-types r)))))
  (testing "the report-scoreboard-once policy reported it before file-score responded"
    (is (= [] (s/to-report))))
  (let [b (s/scoreboard "C-7")]
    (is (= {"cycle-id" "C-7" "selection-id" "whole-plan" "passes" 2 "total" 3 "rate" "66.7%"}
           (select-keys b ["cycle-id" "selection-id" "passes" "total" "rate"])))
    (is (contains? b "previous-cycle-id"))
    (is (nil? (get b "previous-cycle-id")))
    (is (nil? (get b "previous-selection-id")))
    (is (nil? (get b "restatement")))))

(deftest item-02-019-a-scoreboard-for-a-cycle-with-no-measurement
  (s/gate! "C-8" "K-3" "D-1")
  (is (s/result (s/fail-stage! "C-8" "zero-verdicts" "the grader graded nothing")))
  (let [r (s/rpc "measurement/report-scoreboard" {"cycle-id" "C-8"})]
    (is (= "no-measurement-filed" (s/refusal r))))
  (is (nil? (s/scoreboard "C-8"))))

(deftest item-02-020-compared-with-the-last-measured-cycle
  (s/measured! {:cycle "C-5" :cut "K-1" :verdicts [["03-001" "pass"]]})
  ;; C-6 failed at the gate: no gate-attempt-passed is handed in for it.
  (is (= "not-the-gated-candidate" (s/refusal (s/file-score! "C-6"))))
  (s/measured! {:cycle "C-7" :cut "K-3" :verdicts [["03-001" "fail"]]})
  (let [b (s/scoreboard "C-7")]
    (is (= "C-5" (get b "previous-cycle-id")))
    (is (not= "C-6" (get b "previous-cycle-id")))
    (is (not= "C-7" (get b "previous-cycle-id")))))

(deftest item-02-021-both-directions-apart-never-a-net
  (s/measured! {:cycle "C-6" :cut "K-2" :verdicts [["03-001" "pass"] ["03-002" "fail"]]})
  (s/measured! {:cycle "C-7" :cut "K-3" :verdicts [["03-001" "fail"] ["03-002" "pass"]]})
  (let [b (s/scoreboard "C-7")
        shared (get b "shared")]
    (is (= ["03-002"] (get shared "newly-passing")))
    (is (= ["03-001"] (get shared "newly-failing")))
    (is (= {"items" 2 "previous-passes" 1 "passes" 1 "previous-rate" "50.0%" "rate" "50.0%"}
           (select-keys shared ["items" "previous-passes" "passes" "previous-rate" "rate"])))
    (testing "no net is computed"
      (is (empty? (filter #(re-find #"net" %) (concat (keys b) (keys shared))))))))

(deftest item-02-022-a-moved-item-set-is-restated
  (s/measured! {:cycle "C-6" :cut "K-2" :verdicts (verdicts 2 3 "03")})
  (let [r (s/measured! {:cycle "C-7" :cut "K-3" :verdicts (verdicts 2 4 "03")})]
    (is (= ["measurement/measurement-filed"] (s/event-types r))))
  (is (= {"cycle-id" "C-7" "previous-cycle-id" "C-6" "previous-total" 3 "total" 4 "added" 1 "dropped" 0}
         (select-keys (get (s/scoreboard "C-7") "restatement")
                      ["cycle-id" "previous-cycle-id" "previous-total" "total" "added" "dropped"])))
  (testing "a cycle whose item set did not move is reported with no restatement"
    (s/measured! {:cycle "C-8" :cut "K-4" :verdicts (verdicts 3 4 "03")})
    (let [b (s/scoreboard "C-8")]
      (is (= "C-7" (get b "previous-cycle-id")))
      (is (nil? (get b "restatement"))))))

(deftest item-02-023-a-rate-on-a-half-rounds-to-even
  (s/measured! {:cycle "C-7" :cut "K-3" :verdicts (verdicts 1 16 "03")})
  (is (= "6.2%" (get (s/scoreboard "C-7") "rate")))
  (s/measured! {:cycle "C-9" :cut "K-9" :selection "slices 05" :verdicts (verdicts 5 16 "05")})
  (is (= "31.2%" (get (s/scoreboard "C-9") "rate"))))

(deftest item-02-024-no-shared-item-rates-n-a
  (s/measured! {:cycle "C-6" :cut "K-2" :verdicts [["03-001" "pass"]]})
  (s/measured! {:cycle "C-7" :cut "K-3" :verdicts [["04-001" "pass"]]})
  (let [shared (get (s/scoreboard "C-7") "shared")]
    (is (= 0 (get shared "items")))
    (is (= "n/a" (get shared "rate")))
    (is (= "n/a" (get shared "previous-rate")))))

(deftest item-02-041-a-cycle-s-scoreboard-is-reported-once
  (s/measured! {:cycle "C-7" :cut "K-3" :verdicts (verdicts 1 2 "03")})
  (let [before (s/scoreboard "C-7")]
    (is (some? before))
    (is (= "scoreboard-already-reported"
           (s/refusal (s/rpc "measurement/report-scoreboard" {"cycle-id" "C-7"}))))
    (is (= before (s/scoreboard "C-7")))))

(deftest item-02-042-compared-within-the-selection-across-cuts
  (s/measured! {:cycle "C-4" :cut "K-2" :verdicts [["03-001" "pass"] ["04-001" "pass"]]})
  (s/measured! {:cycle "C-5" :cut "K-4" :selection "03,04" :verdicts [["03-001" "pass"] ["04-001" "fail"]]})
  (s/measured! {:cycle "C-6" :cut "K-3" :verdicts [["03-001" "fail"] ["04-001" "pass"]]})
  (let [b (s/scoreboard "C-6")]
    (is (= "C-4" (get b "previous-cycle-id")))
    (is (= "whole-plan" (get b "previous-selection-id")))))

(deftest item-02-043-new-items-are-the-holdout-and-dropped-items-are-counted
  (s/measured! {:cycle "C-6" :cut "K-2" :verdicts [["03-001" "pass"] ["03-002" "pass"]]})
  (s/measured! {:cycle "C-7" :cut "K-3" :verdicts [["03-001" "pass"] ["04-001" "fail"]]})
  (let [b (s/scoreboard "C-7")]
    (is (= ["04-001"] (get b "new-items")))
    (is (= ["03-002"] (get b "dropped-items")))
    (is (= 1 (get-in b ["shared" "items"])))))
