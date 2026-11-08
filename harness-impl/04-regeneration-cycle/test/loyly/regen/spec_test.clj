(ns loyly.regen.spec-test
  "One test per stamped scenario of .pi/spec/regeneration-cycle/spec.feature, each
   named after its @item id and driven through the public seam."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loyly.regen.support :as s :refer [rpc result error refusal events event-types
                                               recorded! progress to-conclude]]
            [loyly.regen.system :as system]))

(use-fixtures :each (fn [f] (s/reset-goldens!) (s/with-fresh-product {:scoring-tools []} f)))

(defn- verdicts [p] (mapv #(get % "verdict") (get p "attempts")))
(defn- failed-attempts [p] (count (filter #{"failed"} (verdicts p))))
(defn- of-type [t] (filterv #(= t (:event/type %)) (s/model-events)))

(defmacro with-scoring-tools
  "Restarts the product handed these scoring tools (LOYLY_SCORING_TOOL_PATHS)."
  [tools & body]
  `(do (system/stop!)
       (s/with-fresh-product {:scoring-tools ~tools} (fn [] ~@body))))

;; ---------------------------------------------------------------------------
;; Rule: the harness self-check opens the cycle before the fence
;; ---------------------------------------------------------------------------

(deftest item-04-001-passing-self-check-opens-the-cycle
  (testing "@item-04-001"
    (s/pin-cut! "K-3")
    (s/write-golden! "checklist" "g-1")
    (s/write-golden! "scoreboard-01" "g-2")
    (let [g1 (s/goldens-digest)
          r (recorded! (s/self-check {"goldens-digest" g1 "grader-digests" ["D-1"]
                                      "budget" (s/budget 3)}))
          [e] (events r)]
      (is (= ["regeneration-cycle/harness-self-check-passed"] (event-types r)))
      (is (= #{"type" "cycle-id" "cut-id" "occasion" "attempt" "harness-digest" "goldens-digest"
               "identity-probe-digest" "skipped-checks" "grader-digests" "budget" "checked-at"}
             (set (keys e))))
      (is (= ["C-7" "before-fence" 0] [(e "cycle-id") (e "occasion") (e "attempt")]))
      (is (= ["H-1" g1 ["D-1"] "P-1" 3]
             [(e "harness-digest") (e "goldens-digest") (e "grader-digests")
              (e "identity-probe-digest") (get-in e ["budget" "gate-attempt-limit"])]))
      (let [p (progress "C-7")]
        (is (= ["C-7" "running" "fence" "K-3" 0]
               [(p "cycle-id") (p "status") (p "stage") (p "cut-id") (p "gate-attempts")]))
        (is (= (s/budget 3) (p "budget")))
        (is (not (contains? p "candidate-digest")))
        (is (= [] (p "attempts")))))))

(deftest item-04-002-self-check-at-goldens-no-longer-current-is-refused
  (testing "@item-04-002"
    (s/pin-cut! "K-3")
    (s/write-golden! "checklist" "g-1")
    (let [g1 (s/goldens-digest)]
      (s/move-golden! "checklist" "g-1" "g-2" "spec-grew")
      (is (not= g1 (s/goldens-digest)))
      (let [r (s/self-check {"goldens-digest" g1})]
        (is (= "goldens-not-current" (refusal r)))
        (is (= -32000 (get-in r ["error" "code"]))))
      (is (nil? (progress "C-7")))
      (is (empty? (of-type :regeneration-cycle/harness-self-check-passed)))
      (testing "a new self-check needs the new digest"
        (recorded! (s/self-check {}))))))

(deftest item-04-003-golden-not-reproduced-fails-the-self-check-by-name
  (testing "@item-04-003"
    (s/pin-cut! "K-3")
    (s/write-golden! "score-refusal" "g-refusal")
    (let [r (recorded! (s/self-check {"outcome" "failed"
                                      "failing-check" "instrument golden score-refusal"
                                      "detail" "expected refusal reason \"unknown item\", got \"unknown id\""}))
          [e] (events r)]
      (is (= ["regeneration-cycle/harness-self-check-failed"] (event-types r)))
      (is (= "C-7" (e "cycle-id")))
      (is (re-find #"score-refusal" (e "failing-check"))))))

(deftest item-04-004-self-check-before-gate-without-candidate-is-refused
  (testing "@item-04-004"
    (s/regenerated!)
    (let [before (concat (of-type :regeneration-cycle/harness-self-check-passed)
                         (of-type :regeneration-cycle/harness-self-check-failed))
          r (s/self-check {"occasion" "before-gate" "attempt" 2})]
      (is (= "no-candidate-for-attempt" (refusal r)))
      (is (= before (concat (of-type :regeneration-cycle/harness-self-check-passed)
                            (of-type :regeneration-cycle/harness-self-check-failed)))))))

(deftest item-04-005-missing-scoreboard-golden-cannot-pass
  (testing "@item-04-005"
    (s/pin-cut! "K-3")
    (let [r (s/self-check {"missing-goldens" ["scoreboard-01"]})]
      (is (= "missing-golden" (refusal r)))
      (is (= ["scoreboard-01"] (get-in r ["error" "data" "goldens"]))))
    (is (nil? (progress "C-7")))))

(deftest item-04-006-golden-without-its-cycle-does-not-fail
  (testing "@item-04-006"
    (s/pin-cut! "K-3")
    (s/write-golden! "scoreboard-01" "g-sb")
    (let [r (recorded! (s/self-check {"missing-goldens" []}))]
      (is (= ["regeneration-cycle/harness-self-check-passed"] (event-types r)))
      (is (not (contains? (first (events r)) "missing-goldens"))))))

(deftest item-04-007-fenced-out-scoring-tool-is-a-listed-skip
  (testing "@item-04-007"
    (s/regenerated!)
    (let [r (recorded! (s/self-check {"occasion" "before-gate" "attempt" 1 "skipped-checks" ["score"]}))
          [e] (events r)]
      (is (= ["regeneration-cycle/harness-self-check-passed"] (event-types r)))
      (is (= ["before-gate" 1] [(e "occasion") (e "attempt")]))
      (is (= ["score"] (e "skipped-checks"))))))

;; ---------------------------------------------------------------------------
;; Rule: the fence proves the clean room
;; ---------------------------------------------------------------------------

(deftest item-04-008-clean-room-proved-moves-cycle-to-regenerate
  (testing "@item-04-008"
    (s/opened!)
    (let [r (recorded! (s/prove-clean-room {"fenced-paths" ["harness/score.clj" "harness/cycles"]}))
          [e] (events r)]
      (is (= ["regeneration-cycle/clean-room-proved"] (event-types r)))
      (is (= ["C-7" "K-3" "R-1" ["harness/score.clj" "harness/cycles"]]
             [(e "cycle-id") (e "cut-id") (e "clean-room-commit") (e "fenced-paths")]))
      (is (string? (e "proved-at")))
      (let [p (progress "C-7")]
        (is (= ["running" "regenerate" "R-1"] [(p "status") (p "stage") (p "clean-room-commit")]))))))

(defn- no-clean-room []
  (is (empty? (of-type :regeneration-cycle/clean-room-proved)))
  (is (not (contains? (or (progress "C-7") {}) "clean-room-commit"))))

(deftest item-04-009-no-clean-room-after-failed-self-check
  (testing "@item-04-009"
    (s/pin-cut! "K-3")
    (recorded! (s/self-check {"outcome" "failed" "failing-check" "golden checklist differs"}))
    (is (= "self-check-not-passed" (refusal (s/prove-clean-room {}))))
    (no-clean-room)))

(deftest item-04-010-fenced-path-matching-nothing-stops-the-fence
  (testing "@item-04-010"
    (s/opened!)
    (let [r (s/prove-clean-room {"fenced-paths" ["harness/scroe.clj"] "unmatched-paths" ["harness/scroe.clj"]})]
      (is (= "fenced-path-matched-nothing" (refusal r)))
      (is (= ["harness/scroe.clj"] (get-in r ["error" "data" "paths"])))
      (is (re-find #"harness/scroe\.clj" (get-in r ["error" "message"]))))
    (no-clean-room)))

(deftest item-04-011-two-root-commits-refused
  (testing "@item-04-011"
    (s/opened!)
    (is (= "root-commits-not-one" (refusal (s/prove-clean-room {"root-commits" 2}))))
    (no-clean-room)))

(deftest item-04-012-more-than-one-commit-refused
  (testing "@item-04-012"
    (s/opened!)
    (is (= "commits-not-one" (refusal (s/prove-clean-room {"root-commits" 1 "commits" 3}))))
    (no-clean-room)))

(deftest item-04-013-fenced-path-left-in-tree-refused
  (testing "@item-04-013"
    (s/opened!)
    (let [r (s/prove-clean-room {"paths-left-in-tree" ["harness/cycles"]})]
      (is (= "fenced-path-left-in-tree" (refusal r)))
      (is (= ["harness/cycles"] (get-in r ["error" "data" "paths"]))))
    (no-clean-room)))

(deftest item-04-014-prior-candidate-reachable-in-object-graph-refused
  (testing "@item-04-014"
    (s/opened!)
    (let [r (s/prove-clean-room {"fenced-paths" ["harness-impl" "harness/score.clj"]
                                 "paths-left-in-tree" []
                                 "paths-reachable-in-object-graph" ["harness-impl"]})]
      (is (= "fenced-path-reachable-in-object-graph" (refusal r)))
      (is (= ["harness-impl"] (get-in r ["error" "data" "paths"]))))
    (no-clean-room)))

(deftest item-04-015-clean-room-keeping-a-scoring-tool-refused
  (testing "@item-04-015"
    (with-scoring-tools ["harness/score.clj" "harness/scoreboard.clj"]
      (s/opened!)
      (let [r (s/prove-clean-room {"fenced-paths" ["harness/score.clj"]})]
        (is (= "scoring-tool-not-fenced" (refusal r)))
        (is (= ["harness/scoreboard.clj"] (get-in r ["error" "data" "paths"]))))
      (no-clean-room)
      (testing "fencing both proves it"
        (recorded! (s/prove-clean-room {"fenced-paths" ["harness/score.clj" "harness/scoreboard.clj"]}))))))

(deftest item-04-016-fence-with-no-fenced-paths-proves-nothing
  (testing "@item-04-016"
    (s/opened!)
    (let [r (s/prove-clean-room {"fenced-paths" []})]
      (is (= -32602 (get-in r ["error" "code"]))))
    (no-clean-room)))

;; ---------------------------------------------------------------------------
;; Rule: one verdict per attempt, with the boot budget and its margin
;; ---------------------------------------------------------------------------

(defn- awaiting? [p attempt]
  (let [row (first (filter #(= attempt (% "attempt")) (p "attempts")))]
    (and row (not (contains? row "verdict")))))

(deftest item-04-017-pass-inside-boot-budget-says-the-margin
  (testing "@item-04-017"
    (s/installed!)
    (let [r (recorded! (s/gate-attempt {"boot-budget-seconds" 10 "boot-polls" 20 "answered-on-poll" 2
                                        "identity-probe-result" "kalibra"}))
          [e] (events r)]
      (is (= ["regeneration-cycle/gate-attempt-passed"] (event-types r)))
      (is (= ["C-7" "K-3" 1 "D-1" 10 20 2 "kalibra" "E-1"]
             [(e "cycle-id") (e "cut-id") (e "attempt") (e "candidate-digest") (e "boot-budget-seconds")
              (e "boot-polls") (e "answered-on-poll") (e "identity-probe-result") (e "evidence-root")]))
      (let [p (progress "C-7")
            [row] (p "attempts")]
        (is (= ["score" "D-1"] [(p "stage") (p "candidate-digest")]))
        (is (= ["passed" 18] [(row "verdict") (row "boot-margin-polls")]))))))

(deftest item-04-018-boot-budget-exhausted-fails-attempt-back-to-regenerate
  (testing "@item-04-018"
    (s/installed! 3)
    (let [r (recorded! (s/failed-gate-attempt {"reason" "boot-budget-exhausted" "evidence-root" "E-1"}))
          [e] (events r)]
      (is (= ["regeneration-cycle/gate-attempt-failed"] (event-types r)))
      (is (= ["boot-budget-exhausted" 10 20 "E-1"]
             [(e "reason") (e "boot-budget-seconds") (e "boot-polls") (e "evidence-root")]))
      (let [p (progress "C-7")]
        (is (= ["running" "regenerate" 1 1] [(p "status") (p "stage") (p "gate-attempts") (failed-attempts p)])))
      (testing "E-1 is the gate evidence a repair room for attempt 2 may mount"
        (recorded! (s/regenerate {"attempt" 2 "purpose" "verification-repair" "candidate-digest" "D-2"
                                  "parent-candidate-digest" "D-1" "mounted-evidence" ["E-1"]}))))))

(deftest item-04-019-boot-clock-covers-the-launch-entrypoint
  (testing "@item-04-019"
    (s/installed!)
    (let [r (recorded! (s/failed-gate-attempt {"reason" "boot-budget-exhausted" "boot-budget-seconds" 10
                                               "boot-polls" 20 "detail" "no response within 20 polls: the launch entrypoint was still building"}))
          [e] (events r)]
      (is (= "boot-budget-exhausted" (e "reason")))
      (is (not (contains? e "answered-on-poll"))))
    (testing "a candidate that answered only after the budget's last poll cannot pass"
      (is (= "verdict-already-recorded" (refusal (s/gate-attempt {"answered-on-poll" 21}))))
      (is (= ["failed"] (verdicts (progress "C-7")))))))

(deftest item-04-020-no-grade-over-an-unchecked-harness
  (testing "@item-04-020"
    (s/installed!)
    (is (= "no-passing-self-check" (refusal (s/gate-attempt {"harness-digest" "H-2"}))))
    (let [p (progress "C-7")]
      (is (awaiting? p 1))
      (is (= ["gate" 0] [(p "stage") (failed-attempts p)])))))

(deftest item-04-021-no-grade-without-identity-probe
  (testing "@item-04-021"
    (s/installed!)
    (is (= "identity-probe-missing" (refusal (s/gate-attempt {"identity-probe-present" false}))))
    (let [p (progress "C-7")]
      (is (awaiting? p 1))
      (is (= 0 (failed-attempts p))))))

(deftest item-04-022-identity-probe-must-be-the-instruments
  (testing "@item-04-022"
    (s/installed!)
    (is (= "identity-probe-not-the-instruments" (refusal (s/gate-attempt {"identity-probe-digest" "P-9"}))))
    (let [p (progress "C-7")]
      (is (awaiting? p 1))
      (is (= 0 (failed-attempts p))))))

;; ---------------------------------------------------------------------------
;; Rule: a failed harness self-check fails the cycle; no score
;; ---------------------------------------------------------------------------

(deftest item-04-023-failed-self-check-before-fence-fails-cycle-with-no-score
  (testing "@item-04-023"
    (s/pin-cut! "K-3")
    (let [r (recorded! (s/self-check {"outcome" "failed"
                                      "failing-check" "instrument golden scoreboard-arithmetic"}))]
      (is (= ["regeneration-cycle/harness-self-check-failed"] (event-types r))))
    (let [p (progress "C-7")]
      (is (= ["failed" "fence"] [(p "status") (p "stage")]))
      (is (= {"stage" "fence" "reason" "harness-self-check-failed"
              "detail" "instrument golden scoreboard-arithmetic"}
             (p "failure")))
      (is (not (contains? p "clean-room-commit")))
      (is (= #{"cycle-id" "cut-id" "status" "stage" "stage-entered-at" "consecutive-gate-refusals"
               "budget" "harness-digest" "gate-attempts" "failure" "attempts"}
             (set (keys p)))
          "no score: no item verdict, no pass rate, no measurement"))
    (is (empty? (of-type :regeneration-cycle/clean-room-proved)))
    (is (= [] (to-conclude)))))

(deftest item-04-024-failed-self-check-before-gate-fails-at-gate-spends-no-attempt
  (testing "@item-04-024"
    (s/regenerated!)
    (s/attempt-failed! 1)
    (recorded! (s/regenerate {"attempt" 2 "purpose" "verification-repair" "candidate-digest" "D-2"
                              "parent-candidate-digest" "D-1" "mounted-evidence" ["E-1"]}))
    (is (= "gate" (get (progress "C-7") "stage")))
    (recorded! (s/self-check {"occasion" "before-gate" "attempt" 2 "outcome" "failed"
                              "failing-check" "identity probe changed"}))
    (let [p (progress "C-7")]
      (is (= ["failed" "gate"] [(p "status") (p "stage")]))
      (is (= "harness-self-check-failed" (get-in p ["failure" "reason"])))
      (is (= 1 (failed-attempts p))))
    (is (= "cycle-concluded" (refusal (s/install {"attempt" 2 "candidate-digest" "D-2"}))))
    (is (empty? (filter #(= 2 (:attempt %)) (of-type :regeneration-cycle/candidate-installed-in-gate-room))))))

;; ---------------------------------------------------------------------------
;; Rule: a candidate is regenerated for the next attempt, within the budget
;; ---------------------------------------------------------------------------

(deftest item-04-025-no-candidate-before-clean-room
  (testing "@item-04-025"
    (s/opened!)
    (is (= "no-clean-room" (refusal (s/regenerate {}))))
    (let [p (progress "C-7")]
      (is (= ["fence" 0] [(p "stage") (p "gate-attempts")])))))

;; ---------------------------------------------------------------------------
;; Rule: a cycle concludes once
;; ---------------------------------------------------------------------------

(deftest item-04-026-last-allowed-attempt-fails-cycle-without-score
  (testing "@item-04-026"
    (s/regenerated! 3)
    (s/attempt-failed! 1)
    (s/attempt-failed! 2)
    (recorded! (s/regenerate {"attempt" 3 "purpose" "verification-repair" "candidate-digest" "D-3"
                              "parent-candidate-digest" "D-2" "mounted-evidence" ["E-2"]}))
    (recorded! (s/install {"attempt" 3 "candidate-digest" "D-3" "purpose" "verification-repair" "gate-room" "G-3"}))
    (recorded! (s/self-check {"occasion" "before-gate" "attempt" 3}))
    (is (= "running" (get (progress "C-7") "status")))
    (recorded! (s/failed-gate-attempt {"attempt" 3 "candidate-digest" "D-3" "reason" "boot-budget-exhausted"
                                       "evidence-root" "E-3"}))
    (let [p (progress "C-7")]
      (is (= ["failed" "gate"] [(p "status") (p "stage")]))
      (is (= {"stage" "gate" "reason" "gate-attempts-exhausted"} (select-keys (p "failure") ["stage" "reason"])))
      (is (= 3 (failed-attempts p))))
    (is (= [] (to-conclude)))))

(deftest item-04-027-refused-fence-fails-cycle-at-fence
  (testing "@item-04-027"
    (s/opened!)
    (is (= "fenced-path-matched-nothing"
           (refusal (s/prove-clean-room {"fenced-paths" ["harness-impl/"] "unmatched-paths" ["harness-impl/"]}))))
    (let [detail "fence refused: fenced path harness-impl/ matched nothing"
          r (recorded! (s/fail-cycle {"stage" "fence" "reason" "fence-refused" "detail" detail}))
          [e] (events r)]
      (is (= ["regeneration-cycle/cycle-failed"] (event-types r)))
      (is (= ["fence" "fence-refused" detail] [(e "stage") (e "reason") (e "detail")]))
      (let [p (progress "C-7")]
        (is (= "failed" (p "status")))
        (is (= {"stage" "fence" "reason" "fence-refused" "detail" detail} (p "failure")))))))

(deftest item-04-028-reworded-golden-fails-the-self-check
  (testing "@item-04-028"
    (s/pin-cut! "K-3")
    (s/write-golden! "checklist" "g-1" "S-1")
    (let [r (s/self-check {"reworded-goldens" ["checklist"]})]
      (is (= "reworded-golden" (refusal r)))
      (is (= ["checklist"] (get-in r ["error" "data" "goldens"]))))
    (is (nil? (progress "C-7")))))

;; ---------------------------------------------------------------------------
;; Rule: the gate installs the regenerated candidate
;; ---------------------------------------------------------------------------

(deftest item-04-029-candidate-installed-in-fresh-gate-room
  (testing "@item-04-029"
    (s/regenerated!)
    (let [r (recorded! (s/install {"candidate-digest" "D-1" "gate-room" "G-1" "attempt" 1}))
          [e] (events r)]
      (is (= ["regeneration-cycle/candidate-installed-in-gate-room"] (event-types r)))
      (is (= ["C-7" 1 "D-1" "G-1" "fresh-generation"]
             [(e "cycle-id") (e "attempt") (e "candidate-digest") (e "gate-room") (e "purpose")]))
      (let [[row] ((progress "C-7") "attempts")]
        (is (= [1 "D-1" true] [(row "attempt") (row "candidate-digest") (row "installed?")]))
        (is (not (contains? row "verdict")))))))

(deftest item-04-030-never-a-fourth-gate-attempt
  (testing "@item-04-030"
    (s/regenerated! 3)
    (s/attempt-failed! 1)
    (s/attempt-failed! 2)
    (s/attempt-failed! 3)
    (let [r (s/install {"attempt" 4 "candidate-digest" "D-4" "gate-room" "G-4" "purpose" "verification-repair"})]
      (is (some? (error r))))
    (let [p (progress "C-7")]
      (is (= 3 (count (p "attempts"))))
      (is (= 3 (p "gate-attempts"))))
    (is (= 3 (count (of-type :regeneration-cycle/candidate-installed-in-gate-room))))))

(deftest item-04-031-replayed-verdict-records-nothing
  (testing "@item-04-031"
    (s/regenerated! 3)
    (s/attempt-failed! 1)
    (let [n (s/model-event-count)]
      (is (= "verdict-already-recorded" (refusal (s/failed-gate-attempt {"reason" "suite-red"}))))
      (is (= n (s/model-event-count))))
    (let [p (progress "C-7")]
      (is (= ["failed"] (verdicts p)))
      (is (= 1 (failed-attempts p))))))

(deftest item-04-032-seam-answering-before-boot-is-not-graded
  (testing "@item-04-032"
    (s/installed!)
    (is (= "seam-answered-before-boot" (refusal (s/gate-attempt {"seam-answered-before-boot" true}))))
    (let [p (progress "C-7")]
      (is (awaiting? p 1))
      (is (= ["running" "gate" 0] [(p "status") (p "stage") (failed-attempts p)])))))

(deftest item-04-033-first-candidate-is-a-claim-not-done
  (testing "@item-04-033"
    (s/proved!)
    (let [r (recorded! (s/regenerate {"attempt" 1 "purpose" "fresh-generation" "candidate-digest" "D-1"
                                      "mounted-evidence" []}))
          [e] (events r)]
      (is (= ["regeneration-cycle/candidate-regenerated"] (event-types r)))
      (is (= [1 "D-1" "fresh-generation"] [(e "attempt") (e "candidate-digest") (e "purpose")])))
    (let [p (progress "C-7")]
      (is (= ["running" "gate" 1 "D-1"] [(p "status") (p "stage") (p "gate-attempts") (p "candidate-digest")])))
    (is (= [] (to-conclude)))
    (is (= "scoreboard-not-reported" (refusal (s/pass-cycle "C-7"))))
    (is (empty? (of-type :regeneration-cycle/cycle-passed)))))

(deftest item-04-034-no-candidate-beyond-the-budget
  (testing "@item-04-034"
    (s/regenerated! 2)
    (s/attempt-failed! 1)
    (s/attempt-failed! 2)
    (is (= "over-gate-attempt-limit"
           (refusal (s/regenerate {"attempt" 3 "purpose" "verification-repair" "candidate-digest" "D-3"
                                   "parent-candidate-digest" "D-2" "mounted-evidence" []}))))
    (is (= 2 (get (progress "C-7") "gate-attempts")))))

(deftest item-04-035-only-a-repair-room-mounts-only-this-cycles-gate-evidence
  (testing "@item-04-035 fresh generation mounting gate evidence root E-0"
    (s/proved!)
    (is (= "evidence-not-mountable"
           (refusal (s/regenerate {"mounted-evidence" ["E-0"]}))))
    (is (= 0 (get (progress "C-7") "gate-attempts"))))
  (testing "@item-04-035 repair mounting the score evidence of cycle C-6"
    (s/regenerate {})
    (s/attempt-failed! 1)
    (is (= "evidence-not-mountable"
           (refusal (s/regenerate {"attempt" 2 "purpose" "verification-repair" "candidate-digest" "D-2"
                                   "parent-candidate-digest" "D-1" "mounted-evidence" ["score-evidence/C-6"]}))))
    (is (= 1 (get (progress "C-7") "gate-attempts"))))
  (testing "@item-04-035 repair mounting gate evidence root E-5 of cycle C-6"
    (is (= "evidence-not-mountable"
           (refusal (s/regenerate {"attempt" 2 "purpose" "verification-repair" "candidate-digest" "D-2"
                                   "parent-candidate-digest" "D-1" "mounted-evidence" ["E-5"]}))))
    (is (= 1 (get (progress "C-7") "gate-attempts")))))

;; ---------------------------------------------------------------------------
;; Rule: a cycle concludes once (score and scoreboard)
;; ---------------------------------------------------------------------------

(deftest item-04-036-failed-score-stage-fails-cycle-at-score
  (testing "@item-04-036"
    (s/gate-passed!)
    (recorded! (s/fail-score-stage! "C-7" "zero-verdicts"))
    (let [p (progress "C-7")]
      (is (= ["failed" "score"] [(p "status") (p "stage")]))
      (is (= {"stage" "score" "reason" "score-stage-failed"} (select-keys (p "failure") ["stage" "reason"]))))
    (testing "no score is retaken: a measurement filed afterwards changes nothing"
      (recorded! (s/file-measurement! "C-7" "D-1"))
      (is (= ["failed" "score"] (let [p (progress "C-7")] [(p "status") (p "stage")])))
      (is (empty? (of-type :regeneration-cycle/cycle-passed))))))

(deftest item-04-037-cycle-passes-once-its-scoreboard-is-reported
  (testing "@item-04-037"
    (s/gate-passed!)
    (recorded! (s/file-measurement! "C-7" "D-1"))
    (let [r (recorded! (s/report-scoreboard! "C-7"))]
      (is (= ["measurement/scoreboard-reported"] (event-types r))))
    (let [[e] (of-type :regeneration-cycle/cycle-passed)]
      (is (= ["C-7" "D-1"] [(:cycle-id e) (:candidate-digest e)])))
    (let [p (progress "C-7")]
      (is (= ["passed" "result"] [(p "status") (p "stage")])))
    (is (= [] (to-conclude)))))

(deftest item-04-038-filed-measurement-moves-cycle-to-scoreboard-only
  (testing "@item-04-038"
    (s/gate-passed!)
    (let [n-candidates (count (of-type :regeneration-cycle/candidate-regenerated))
          n-verdicts (count (concat (of-type :regeneration-cycle/gate-attempt-passed)
                                    (of-type :regeneration-cycle/gate-attempt-failed)))
          r (recorded! (s/file-measurement! "C-7" "D-1"))]
      (is (= ["measurement/measurement-filed"] (event-types r)))
      (let [p (progress "C-7")]
        (is (= ["running" "scoreboard" 1] [(p "status") (p "stage") (p "gate-attempts")])))
      (is (= n-candidates (count (of-type :regeneration-cycle/candidate-regenerated))))
      (is (= n-verdicts (count (concat (of-type :regeneration-cycle/gate-attempt-passed)
                                       (of-type :regeneration-cycle/gate-attempt-failed))))))))

(deftest item-04-039-scoreboard-without-measurement-does-not-pass
  (testing "@item-04-039"
    (s/gate-passed!)
    (recorded! (s/report-scoreboard! "C-7"))
    (let [p (progress "C-7")]
      (is (= ["running" "score"] [(p "status") (p "stage")])))
    (is (not (some #{"C-7"} (to-conclude))))
    (is (empty? (of-type :regeneration-cycle/cycle-passed)))))

(deftest item-04-040-measurement-after-failure-leaves-cycle-where-it-failed
  (testing "@item-04-040"
    (s/gate-passed!)
    (recorded! (s/fail-cycle {"stage" "score" "reason" "budget" "detail" "score stage timed out"}))
    (recorded! (s/file-measurement! "C-7" "D-1"))
    (let [p (progress "C-7")]
      (is (= ["failed" "score" "budget"] [(p "status") (p "stage") (get-in p ["failure" "reason"])])))))

;; ---------------------------------------------------------------------------
;; Balance: the harness self-check
;; ---------------------------------------------------------------------------

(deftest item-04-041-self-check-on-unpinned-cut-refused
  (testing "@item-04-041"
    (s/pin-cut! "K-3")
    (is (= "cut-not-pinned" (refusal (s/self-check {"cut-id" "K-9"}))))
    (is (nil? (progress "C-7")))))

(deftest item-04-042-failed-self-check-naming-no-check-refused
  (testing "@item-04-042"
    (s/pin-cut! "K-3")
    (is (= "no-failing-check" (refusal (s/self-check {"outcome" "failed"}))))
    (is (nil? (progress "C-7")))))

(deftest item-04-043-second-self-check-before-fence-refused
  (testing "@item-04-043"
    (s/proved!)
    (is (= "not-the-cycles-first-fact" (refusal (s/self-check {"attempt" 1}))))
    (let [cycles (get (progress) "cycles")]
      (is (= ["C-7"] (mapv #(% "cycle-id") cycles)))
      (is (= ["regenerate" "R-1"] [((first cycles) "stage") ((first cycles) "clean-room-commit")])))))

(deftest item-04-044-one-self-check-per-cycle-occasion-and-attempt
  (testing "@item-04-044"
    (s/regenerated!)
    (recorded! (s/self-check {"occasion" "before-gate" "attempt" 1}))
    (let [before (progress "C-7")]
      (is (= "self-check-already-recorded" (refusal (s/self-check {"occasion" "before-gate" "attempt" 1}))))
      (is (= before (progress "C-7"))))))

(deftest item-04-045-budget-beyond-three-gate-attempts-refused
  (testing "@item-04-045"
    (s/pin-cut! "K-3")
    (let [r (s/self-check {"budget" (s/budget 4)})]
      (is (some? (error r)))
      (is (= -32602 (get-in r ["error" "code"])))
      (is (= "budget-out-of-bounds" (refusal r))))
    (is (nil? (progress "C-7")))))

(deftest item-04-046-reworded-golden-that-is-not-current-refused
  (testing "@item-04-046"
    (s/pin-cut! "K-3")
    (let [r (s/self-check {"outcome" "failed" "failing-check" "reworded golden"
                           "reworded-goldens" ["scoreboard-09"]})]
      (is (= "reworded-golden-not-current" (refusal r)))
      (is (= ["scoreboard-09"] (get-in r ["error" "data" "goldens"]))))
    (is (nil? (progress "C-7")))))

;; ---------------------------------------------------------------------------
;; Balance: the fence
;; ---------------------------------------------------------------------------

(deftest item-04-047-a-cycle-has-one-clean-room
  (testing "@item-04-047"
    (s/proved!)
    (let [n (s/model-event-count)]
      (is (= "clean-room-already-proved" (refusal (s/prove-clean-room {"clean-room-commit" "R-2"}))))
      (is (= n (s/model-event-count))))
    (is (= "R-1" (get (progress "C-7") "clean-room-commit")))))

(deftest item-04-048-clean-room-from-another-cut-refused
  (testing "@item-04-048"
    (s/opened!)
    (s/pin-cut! "K-4")
    (is (= "not-the-self-checked-cut" (refusal (s/prove-clean-room {"cut-id" "K-4"}))))
    (no-clean-room)))

(deftest item-04-049-no-clean-room-for-a-concluded-cycle
  (testing "@item-04-049"
    (s/opened!)
    (recorded! (s/fail-cycle {"stage" "fence" "reason" "budget" "detail" "stage timeout"}))
    (is (= "cycle-concluded" (refusal (s/prove-clean-room {}))))
    (no-clean-room)))

;; ---------------------------------------------------------------------------
;; Balance: installation
;; ---------------------------------------------------------------------------

(deftest item-04-050-no-install-for-an-attempt-never-regenerated
  (testing "@item-04-050"
    (s/proved!)
    (is (= "no-candidate-for-attempt" (refusal (s/install {}))))
    (is (= [] (get (progress "C-7") "attempts")))))

(deftest item-04-051-another-candidate-is-not-installed
  (testing "@item-04-051"
    (s/regenerated!)
    (is (= "not-the-regenerated-candidate" (refusal (s/install {"candidate-digest" "D-9"}))))
    (is (= [false] (mapv #(% "installed?") (get (progress "C-7") "attempts"))))))

(deftest item-04-052-no-install-for-a-concluded-cycle
  (testing "@item-04-052"
    (s/regenerated!)
    (recorded! (s/fail-cycle {"stage" "gate" "reason" "budget" "detail" "stage timeout"}))
    (is (= "cycle-concluded" (refusal (s/install {}))))
    (is (= [false] (mapv #(% "installed?") (get (progress "C-7") "attempts"))))))

;; ---------------------------------------------------------------------------
;; Balance: verdicts
;; ---------------------------------------------------------------------------

(deftest item-04-053-no-verdict-for-an-uninstalled-candidate
  (testing "@item-04-053"
    (s/regenerated!)
    (is (= "not-installed" (refusal (s/gate-attempt {}))))
    (is (= [nil] (verdicts (progress "C-7"))))))

(deftest item-04-054-failed-verdict-without-reason-refused
  (testing "@item-04-054"
    (s/installed!)
    (is (= "reason-does-not-fit-outcome"
           (refusal (s/failed-gate-attempt {"reason" nil}))))
    (is (awaiting? (progress "C-7") 1))))

(deftest item-04-055-pass-without-poll-and-probe-result-refused
  (testing "@item-04-055"
    (s/installed!)
    (is (= "pass-without-poll-or-probe-result"
           (refusal (s/gate-attempt {"answered-on-poll" nil "identity-probe-result" nil}))))
    (is (awaiting? (progress "C-7") 1))))

(deftest item-04-056-response-after-last-poll-does-not-pass
  (testing "@item-04-056"
    (s/installed!)
    (is (= "answered-after-last-poll" (refusal (s/gate-attempt {"answered-on-poll" 21 "boot-polls" 20}))))
    (is (awaiting? (progress "C-7") 1))))

(deftest item-04-057-verdict-on-another-candidate-refused
  (testing "@item-04-057"
    (s/installed!)
    (is (= "not-the-installed-candidate" (refusal (s/gate-attempt {"candidate-digest" "D-9"}))))
    (let [p (progress "C-7")]
      (is (awaiting? p 1))
      (is (= "D-1" (get-in p ["attempts" 0 "candidate-digest"]))))))

(deftest item-04-058-no-verdict-for-a-concluded-cycle
  (testing "@item-04-058"
    (s/installed!)
    (recorded! (s/fail-cycle {"stage" "gate" "reason" "budget" "detail" "stage timeout"}))
    (is (= "cycle-concluded" (refusal (s/gate-attempt {}))))
    (is (awaiting? (progress "C-7") 1))))

(deftest item-04-059-self-check-at-other-grader-digests-does-not-stand
  (testing "@item-04-059"
    (s/installed!)
    (is (= "other-grader-digests" (refusal (s/gate-attempt {"grader-digests" ["R-2"]}))))
    (let [p (progress "C-7")]
      (is (awaiting? p 1))
      (is (= 0 (failed-attempts p))))))

;; ---------------------------------------------------------------------------
;; Balance: gate refusals
;; ---------------------------------------------------------------------------

(deftest item-04-060-refusal-to-grade-is-recorded-spends-no-attempt
  (testing "@item-04-060"
    (s/installed!)
    (let [r (recorded! (s/gate-refusal {"refusal" "seam-answered-before-boot"}))
          [e] (events r)]
      (is (= ["regeneration-cycle/gate-attempt-refused"] (event-types r)))
      (is (= ["C-7" 1 "seam-answered-before-boot"] [(e "cycle-id") (e "attempt") (e "refusal")])))
    (let [p (progress "C-7")]
      (is (awaiting? p 1))
      (is (= "seam-answered-before-boot" (get-in p ["attempts" 0 "last-refusal"])))
      (is (= [0 1 "running"] [(failed-attempts p) (p "consecutive-gate-refusals") (p "status")])))))

(deftest item-04-061-no-refusal-for-an-uninstalled-candidate
  (testing "@item-04-061"
    (s/regenerated!)
    (is (= "not-installed" (refusal (s/gate-refusal {}))))
    (is (empty? (of-type :regeneration-cycle/gate-attempt-refused)))))

(deftest item-04-062-no-refusal-over-a-verdict
  (testing "@item-04-062"
    (s/gate-passed!)
    (is (= "verdict-already-recorded" (refusal (s/gate-refusal {"refusal" "identity-probe-missing"}))))
    (is (= ["passed"] (verdicts (progress "C-7"))))
    (is (empty? (of-type :regeneration-cycle/gate-attempt-refused)))))

(deftest item-04-063-no-refusal-for-a-concluded-cycle
  (testing "@item-04-063"
    (s/installed!)
    (recorded! (s/fail-cycle {"stage" "gate" "reason" "budget" "detail" "stage timeout"}))
    (is (= "cycle-concluded" (refusal (s/gate-refusal {}))))
    (is (empty? (of-type :regeneration-cycle/gate-attempt-refused)))))

(deftest item-04-064-three-consecutive-refusals-fail-for-infrastructure
  (testing "@item-04-064"
    (s/installed!)
    (recorded! (s/gate-refusal {}))
    (recorded! (s/gate-refusal {}))
    (is (= "running" (get (progress "C-7") "status")))
    (recorded! (s/gate-refusal {}))
    (let [p (progress "C-7")]
      (is (= ["failed" "gate"] [(p "status") (p "stage")]))
      (is (= "infrastructure" (get-in p ["failure" "reason"])))
      (is (= [1 0] [(p "gate-attempts") (failed-attempts p)])))))

(deftest item-04-065-a-verdict-between-refusals-resets-their-count
  (testing "@item-04-065"
    (s/installed!)
    (recorded! (s/gate-refusal {}))
    (recorded! (s/gate-refusal {}))
    (recorded! (s/failed-gate-attempt {"reason" "suite-red"}))
    (recorded! (s/regenerate {"attempt" 2 "purpose" "verification-repair" "candidate-digest" "D-2"
                              "parent-candidate-digest" "D-1" "mounted-evidence" ["E-1"]}))
    (recorded! (s/install {"attempt" 2 "candidate-digest" "D-2" "purpose" "verification-repair" "gate-room" "G-2"}))
    (recorded! (s/gate-refusal {"attempt" 2 "refusal" "identity-probe-missing"}))
    (let [p (progress "C-7")]
      (is (= ["running" "gate"] [(p "status") (p "stage")]))
      (is (= [2 1 1] [(p "gate-attempts") (failed-attempts p) (p "consecutive-gate-refusals")])))))

;; ---------------------------------------------------------------------------
;; Balance: regeneration
;; ---------------------------------------------------------------------------

(deftest item-04-066-candidate-for-a-cycle-that-never-opened
  (testing "@item-04-066"
    (is (= "no-cycle" (refusal (s/regenerate {"cycle-id" "C-9"}))))
    (is (nil? (progress "C-9")))))

(deftest item-04-067-candidate-for-an-attempt-not-the-next
  (testing "@item-04-067"
    (s/proved!)
    (is (= "not-the-next-attempt"
           (refusal (s/regenerate {"attempt" 2 "purpose" "verification-repair" "parent-candidate-digest" "D-1"}))))
    (let [p (progress "C-7")]
      (is (= ["regenerate" 0] [(p "stage") (p "gate-attempts")])))))

(deftest item-04-068-attempt-one-fresh-later-attempts-repair
  (testing "@item-04-068 attempt 1 by verification repair"
    (s/proved!)
    (is (= "wrong-purpose" (refusal (s/regenerate {"purpose" "verification-repair"}))))
    (is (= 0 (get (progress "C-7") "gate-attempts"))))
  (testing "@item-04-068 attempt 2 by fresh generation"
    (s/regenerate {})
    (s/attempt-failed! 1)
    (is (= "wrong-purpose" (refusal (s/regenerate {"attempt" 2 "purpose" "fresh-generation" "candidate-digest" "D-2"}))))
    (is (= 1 (get (progress "C-7") "gate-attempts")))))

(deftest item-04-069-no-repair-after-a-passed-attempt
  (testing "@item-04-069"
    (s/gate-passed!)
    (is (= "not-after-a-failed-attempt"
           (refusal (s/regenerate {"attempt" 2 "purpose" "verification-repair" "candidate-digest" "D-2"
                                   "parent-candidate-digest" "D-1"}))))
    (let [p (progress "C-7")]
      (is (= ["score" 1] [(p "stage") (p "gate-attempts")])))))

(deftest item-04-070-repairs-parent-is-the-previous-candidate
  (testing "@item-04-070"
    (s/regenerated!)
    (s/attempt-failed! 1)
    (is (= "not-the-previous-candidate"
           (refusal (s/regenerate {"attempt" 2 "purpose" "verification-repair" "candidate-digest" "D-2"
                                   "parent-candidate-digest" "D-0"}))))
    (let [p (progress "C-7")]
      (is (= ["regenerate" "D-1"] [(p "stage") (p "candidate-digest")])))))

;; ---------------------------------------------------------------------------
;; Balance: conclusions
;; ---------------------------------------------------------------------------

(deftest item-04-071-cycle-without-reported-scoreboard-cannot-pass
  (testing "@item-04-071"
    (s/gate-passed!)
    (recorded! (s/file-measurement! "C-7" "D-1"))
    (is (= "scoreboard-not-reported" (refusal (s/pass-cycle "C-7"))))
    (let [p (progress "C-7")]
      (is (= ["running" "scoreboard"] [(p "status") (p "stage")])))))

(deftest item-04-072-concluded-cycle-never-concluded-again
  (testing "@item-04-072"
    (s/opened!)
    (recorded! (s/fail-cycle {"stage" "fence" "reason" "fence-refused" "detail" "fence refused"}))
    (let [before (progress "C-7")]
      (is (= "cycle-concluded" (refusal (s/fail-cycle {"stage" "fence" "reason" "budget" "detail" "late"}))))
      (is (= before (progress "C-7")))
      (is (= "fence-refused" (get-in before ["failure" "reason"]))))
    (is (= 1 (count (of-type :regeneration-cycle/cycle-failed))))))

(deftest item-04-073-cycle-fails-only-at-the-stage-it-reached
  (testing "@item-04-073"
    (s/proved!)
    (is (= "not-the-stage-reached" (refusal (s/fail-cycle {"stage" "fence" "reason" "fence-refused"}))))
    (let [p (progress "C-7")]
      (is (= ["running" "regenerate"] [(p "status") (p "stage")])))))

(deftest item-04-074-reason-must-belong-to-the-stage
  (testing "@item-04-074"
    (s/regenerated!)
    (is (= "reason-not-of-the-stage" (refusal (s/fail-cycle {"stage" "gate" "reason" "fence-refused"}))))
    (let [p (progress "C-7")]
      (is (= ["running" "gate"] [(p "status") (p "stage")])))))

(deftest item-04-075-infrastructure-failure-belongs-to-the-gate
  (testing "@item-04-075"
    (s/proved!)
    (is (= "infrastructure-not-at-the-gate"
           (refusal (s/fail-cycle {"stage" "regenerate" "reason" "infrastructure"}))))
    (let [p (progress "C-7")]
      (is (= ["running" "regenerate"] [(p "status") (p "stage")])))))

;; ---------------------------------------------------------------------------
;; Balance: observing changes nothing
;; ---------------------------------------------------------------------------

(deftest item-04-076-reading-the-progress-view-records-nothing
  (testing "@item-04-076"
    (s/regenerated!)
    (Thread/sleep 200)
    (let [n (count (s/store-events))
          a (progress "C-7")
          b (progress "C-7")]
      (is (= ["gate" 1] [(a "stage") (a "gate-attempts")]))
      (is (= a b))
      (Thread/sleep 100)
      (is (= n (count (s/store-events))) "no event of any kind, no ORC run"))))
