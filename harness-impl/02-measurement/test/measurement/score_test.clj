(ns measurement.score-test
  "Rules: The score files one measurement of every checklist item; an unreached item
   fails and stays counted. A refused score fails the score stage, once."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [measurement.support :as s]))

(use-fixtures :each s/with-product)

(defn- gated-checklist! [ids]
  (s/pin! "K-3")
  (apply s/stamp! ids)
  (assert (s/result (s/derive! "K-3" ids)))
  (s/gate! "C-7" "K-3" "D-1"))

(defn- items-by-id [m] (into {} (map (juxt #(get % "item-id") identity)) (get m "items")))

(deftest item-02-012-every-checklist-item-once-in-checklist-order
  (gated-checklist! ["03-001" "03-002" "04-001"])
  (is (s/result (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "04-001" "fail")
                                                      (s/verdict "03-001" "pass")
                                                      (s/verdict "03-002" "pass")])))
  (let [r (s/file-score! "C-7" :approved ["G-1"])
        [e] (s/events r)
        digest (get (s/checklist "K-3") "checklist-digest")]
    (is (= ["measurement/measurement-filed"] (s/event-types r)))
    (is (= ["03-001" "03-002" "04-001"] (mapv #(get % "item-id") (get e "items"))))
    (is (= [3 2 0] [(get e "total") (get e "passes") (get e "unreached")]))
    (is (= digest (get e "checklist-digest")))
    (is (= ["G-1"] (get e "grader-digests")))
    (is (= {"cut-id" "K-3" "selection-id" "whole-plan" "candidate-digest" "D-1"}
           (select-keys e ["cut-id" "selection-id" "candidate-digest"])))
    (let [m (s/measurement "C-7")]
      (is (= "filed" (get m "status")))
      (is (= ["03-001" "03-002" "04-001"] (mapv #(get % "item-id") (get m "items"))))
      (is (= [3 2 0] [(get m "total") (get m "passes") (get m "unreached")]))
      (is (= "D-1" (get m "candidate-digest"))))))

(deftest item-02-013-an-item-with-no-verdict-fails-unreached-and-is-counted
  (gated-checklist! ["03-001" "03-002" "03-003"])
  (is (s/result (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "03-001" "pass")])))
  (is (s/result (s/file-score! "C-7")))
  (let [m (s/measurement "C-7")
        by-id (items-by-id m)]
    (doseq [id ["03-002" "03-003"]]
      (is (= {"verdict" "fail" "reason" "unreached"} (select-keys (by-id id) ["verdict" "reason"]))))
    (is (= [3 2] [(get m "total") (get m "unreached")]))))

(deftest item-02-014-an-outage-reads-differently-from-a-regression
  (gated-checklist! ["03-001" "03-002"])
  (is (s/result (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "03-001" "fail" "outage")
                                                      (s/verdict "03-002" "fail")])))
  (is (s/result (s/file-score! "C-7")))
  (let [by-id (items-by-id (s/measurement "C-7"))]
    (is (= "fail" (get-in by-id ["03-001" "verdict"])))
    (is (= "outage" (get-in by-id ["03-001" "reason"])))
    (is (= "fail" (get-in by-id ["03-002" "verdict"])))
    (is (not (contains? (by-id "03-002") "reason")))))

(deftest item-02-015-the-last-verdict-stands-and-earlier-ones-are-superseded
  (gated-checklist! ["03-001"])
  (is (s/result (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "03-001" "fail")])))
  (is (s/result (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "03-001" "pass")])))
  (let [[e] (s/events (s/file-score! "C-7"))]
    (is (= "pass" (get-in e ["items" 0 "verdict"])))
    (is (= [{"item-id" "03-001" "grader-digest" "G-1" "verdict" "fail"}] (get e "superseded")))
    (is (= "pass" (get-in (s/measurement "C-7") ["items" 0 "verdict"])))))

(deftest item-02-016-a-verdict-without-evidence-carries-empty-evidence
  (gated-checklist! ["03-001"])
  (let [[filed] (s/events (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "03-001" "pass")]))]
    (is (= "" (get-in filed ["verdicts" 0 "evidence"]))))
  (is (s/result (s/file-score! "C-7")))
  (is (= {"verdict" "pass" "evidence" ""}
         (select-keys (get-in (s/measurement "C-7") ["items" 0]) ["verdict" "evidence"]))))

(deftest item-02-017-a-score-with-zero-verdicts-filed
  (s/gate! "C-7" "K-3" "D-1")
  (is (= "zero-verdicts" (s/refusal (s/file-score! "C-7"))))
  (is (nil? (s/measurement "C-7")) "still awaiting its score"))

(deftest item-02-029-a-filed-measurement-is-never-filed-again
  (s/measured! {:cycle "C-7" :cut "K-3" :verdicts [["03-001" "pass"] ["03-002" "pass"] ["03-003" "fail"]]})
  (is (= "measurement-already-filed" (s/refusal (s/file-score! "C-7"))))
  (let [m (s/measurement "C-7")]
    (is (= [2 3] [(get m "passes") (get m "total")]))))

(deftest item-02-030-a-grader-digest-no-longer-approved-when-the-score-is-filed
  (gated-checklist! ["03-001"])
  (is (s/result (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "03-001" "pass")] :approved ["G-1"])))
  (is (= "grader-not-approved" (s/refusal (s/file-score! "C-7" :approved ["G-2"]))))
  (is (nil? (s/measurement "C-7")) "still awaiting its score"))

(deftest item-02-031-orc-records-the-score-stage-failed
  (gated-checklist! ["03-001"])
  (is (= "unknown-item" (s/refusal (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "09-001" "pass")]))))
  (let [r (s/fail-stage! "C-7" "unknown-item" "09-001 is not on the checklist")
        [e] (s/events r)]
    (is (= ["measurement/score-stage-failed"] (s/event-types r)))
    (is (= {"cycle-id" "C-7" "refusal" "unknown-item"} (select-keys e ["cycle-id" "refusal"]))))
  (let [m (s/measurement "C-7")]
    (is (= "score-stage-failed" (get m "status")))
    (is (= "unknown-item" (get m "refusal")))
    (is (not (contains? m "total")) "no score")
    (is (not (contains? m "passes")))))

(deftest item-02-038-a-score-for-a-cycle-never-gated
  (is (= "not-the-gated-candidate" (s/refusal (s/file-score! "C-8"))))
  (is (nil? (s/measurement "C-8"))))

(deftest item-02-039-a-failed-score-stage-for-a-cycle-never-gated
  (is (= "not-the-gated-candidate" (s/refusal (s/fail-stage! "C-8" "zero-verdicts" "nothing graded"))))
  (is (nil? (s/measurement "C-8"))))

(deftest item-02-040-a-failed-score-stage-after-the-measurement-is-filed
  (s/measured! {:cycle "C-7" :cut "K-3" :verdicts [["03-001" "pass"] ["03-002" "pass"] ["03-003" "fail"]]})
  (is (= "measurement-already-filed" (s/refusal (s/fail-stage! "C-7" "zero-verdicts" "late"))))
  (let [m (s/measurement "C-7")]
    (is (= "filed" (get m "status")))
    (is (= [2 3] [(get m "passes") (get m "total")]))))
