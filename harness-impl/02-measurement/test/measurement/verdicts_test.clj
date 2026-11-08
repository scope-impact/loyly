(ns measurement.verdicts-test
  "Rule: The approved sealed grader files the item verdicts it returned for the gated candidate only."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [measurement.support :as s]))

(use-fixtures :each s/with-product)

(defn- checklist-of! [cut-id & ids]
  (s/pin! cut-id)
  (apply s/stamp! ids)
  (assert (s/result (s/derive! cut-id (vec ids)))))

(defn- no-verdicts-filed? [cycle-id]
  ;; Read as Spec round 3, 5: file-score refused zero-verdicts and no measurement.
  (and (= "zero-verdicts" (s/refusal (s/file-score! cycle-id)))
       (nil? (s/measurement cycle-id))))

(deftest item-02-008-no-checklist-for-the-cut
  (s/gate! "C-7" "K-3" "D-1")
  (is (nil? (s/checklist "K-3")))
  (let [r (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "03-001" "pass")])]
    (is (= "no-checklist" (s/refusal r))))
  (is (no-verdicts-filed? "C-7")))

(deftest item-02-009-zero-verdicts-file-nothing
  (checklist-of! "K-3" "03-001")
  (s/gate! "C-7" "K-3" "D-1")
  (let [r (s/file-verdicts! "C-7" "D-1" "G-1" [])]
    (is (= "zero-verdicts" (s/refusal r))))
  (is (no-verdicts-filed? "C-7"))
  (is (nil? (s/measurement "C-7")) "no score shown"))

(deftest item-02-010-bad-verdict-value-before-unknown-item
  (checklist-of! "K-3" "03-001")
  (s/gate! "C-7" "K-3" "D-1")
  (let [r (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "03-001" "maybe") (s/verdict "09-001" "pass")])]
    (is (= "bad-verdict-value" (s/refusal r))))
  (is (no-verdicts-filed? "C-7")))

(deftest item-02-011-a-verdict-for-an-unknown-item
  (checklist-of! "K-3" "03-001")
  (s/gate! "C-7" "K-3" "D-1")
  (let [r (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "03-001" "pass") (s/verdict "09-001" "pass")])]
    (is (= "unknown-item" (s/refusal r)))
    (is (= ["09-001"] (get-in r ["error" "data" "item-ids"]))))
  (is (no-verdicts-filed? "C-7")))

(deftest item-02-025-the-approved-grader-files-for-the-ratified-candidate
  (checklist-of! "K-3" "03-001" "03-002")
  (s/gate! "C-7" "K-3" "D-1")
  (let [r (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "03-001" "pass") (s/verdict "03-002" "fail")])
        [e] (s/events r)]
    (is (= ["measurement/item-verdicts-filed"] (s/event-types r)))
    (is (= {"cycle-id" "C-7" "candidate-digest" "D-1" "grader-digest" "G-1"}
           (select-keys e ["cycle-id" "candidate-digest" "grader-digest"])))
    (is (= [{"item-id" "03-001" "verdict" "pass" "evidence" ""}
            {"item-id" "03-002" "verdict" "fail" "evidence" ""}]
           (get e "verdicts")))
    (is (string? (get e "filed-at")))))

(deftest item-02-026-another-candidate-than-the-gated-one
  (checklist-of! "K-3" "03-001")
  (s/gate! "C-7" "K-3" "D-2" 2)
  (let [r (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "03-001" "pass")])]
    (is (= "not-the-gated-candidate" (s/refusal r))))
  (is (no-verdicts-filed? "C-7")))

(deftest item-02-027-a-grader-digest-not-approved
  (checklist-of! "K-3" "03-001")
  (s/gate! "C-7" "K-3" "D-1")
  (let [r (s/file-verdicts! "C-7" "D-1" "G-9" [(s/verdict "03-001" "pass")] :approved ["G-1"])]
    (is (= "grader-not-approved" (s/refusal r))))
  (is (no-verdicts-filed? "C-7")))

(deftest item-02-028-a-reason-beside-a-pass-or-unreached-from-the-grader
  (checklist-of! "K-3" "03-001" "03-002")
  (s/gate! "C-7" "K-3" "D-1")
  (let [r (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "03-001" "pass" "outage")
                                                (s/verdict "03-002" "fail" "unreached")])]
    (is (= "bad-verdict-reason" (s/refusal r)))
    (is (= ["03-001" "03-002"] (get-in r ["error" "data" "item-ids"]))))
  (is (no-verdicts-filed? "C-7")))

(deftest item-02-032-item-verdicts-after-a-failed-score-stage
  (checklist-of! "K-3" "03-001")
  (s/gate! "C-7" "K-3" "D-1")
  (is (s/result (s/fail-stage! "C-7" "unknown-item" "09-001 is not on the checklist")))
  (let [r (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "03-001" "pass")])]
    (is (= "score-stage-failed" (s/refusal r)))))

(deftest item-02-036-a-cycle-the-gate-ratified-no-candidate-in
  (checklist-of! "K-3" "03-001")
  (let [r (s/file-verdicts! "C-8" "D-1" "G-1" [(s/verdict "03-001" "pass")])]
    (is (= "not-the-gated-candidate" (s/refusal r))))
  (is (nil? (s/measurement "C-8"))))

(deftest item-02-037-item-verdicts-after-the-measurement-is-filed
  (s/measured! {:cycle "C-7" :cut "K-3"
                :verdicts [["03-001" "pass"] ["03-002" "pass"] ["03-003" "fail"]]})
  (let [r (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "03-003" "pass")])]
    (is (= "measurement-already-filed" (s/refusal r))))
  (let [m (s/measurement "C-7")]
    (is (= [2 3] [(get m "passes") (get m "total")]))))
