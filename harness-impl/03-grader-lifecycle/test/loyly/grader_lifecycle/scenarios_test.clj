(ns loyly.grader-lifecycle.scenarios-test
  "One test per stamped scenario of .pi/plan/03-grader-lifecycle/spec.feature,
   each driven through the public seam and named by its @item id."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loyly.grader-lifecycle.support :as s]))

(use-fixtures :each s/with-system*)

(def scenario "a refused score names the unknown item")

(defn- written-golden!
  "Given instrument golden G was written with the digest d from a scenario
   whose scenario digest is sd."
  ([golden-id digest sd] (written-golden! golden-id digest sd scenario))
  ([golden-id digest sd scen]
   (s/set-digests! {scen sd})
   (let [resp (s/write! {"golden-id" golden-id "scenario" scen "scenario-digest" sd
                         "path" (str "goldens/" golden-id ".out") "digest" digest})]
     (is (contains? resp "result") (str "given write failed: " resp))
     resp)))

(deftest item-03-001-moves-a-golden-for-a-recorded-reason
  (testing "@item-03-001 The instrument maintainer moves a golden for a recorded reason"
    (written-golden! "G-score-unknown" "g-40" "s-12")
    (let [resp (s/move! {"golden-id" "G-score-unknown" "digest" "g-41"
                         "reason" "instrument-behavior-changed" "additions-only" false
                         "note" "the refusal now names the grader digest"
                         "scenario-digest" "s-12"})
          [event] (get-in resp ["result" "events"])]
      (is (= 1 (count (get-in resp ["result" "events"]))))
      (is (= {"type" "grader-lifecycle/instrument-golden-moved"
              "golden-id" "G-score-unknown" "from-digest" "g-40" "to-digest" "g-41"
              "reason" "instrument-behavior-changed" "additions-only" false
              "note" "the refusal now names the grader digest" "scenario-digest" "s-12"}
             (dissoc event "moved-at")))
      (is (string? (get event "moved-at")))
      (let [g (s/golden "G-score-unknown")]
        (is (= "g-41" (get g "digest")))
        (is (= "s-12" (get g "scenario-digest")))
        (is (= [{"from-digest" "g-40" "to-digest" "g-41"
                 "reason" "instrument-behavior-changed"
                 "note" "the refusal now names the grader digest"}]
               (get g "moves")))))))

(deftest item-03-002-spec-grew-not-additions-only-is-refused
  (testing "@item-03-002 \"Spec grew\" over a diff that is not additions only is refused"
    (written-golden! "G-checklist" "g-50" "s-20")
    (let [before (count (s/model-events))
          resp (s/move! {"golden-id" "G-checklist" "digest" "g-51" "reason" "spec-grew"
                         "additions-only" false "note" "a title changed"
                         "scenario-digest" "s-20"})]
      (is (= -32000 (s/error-code resp)))
      (is (= "spec-grew-not-additions-only" (s/refusal resp)))
      (is (= before (count (s/model-events))))
      (is (= "g-50" (get (s/golden "G-checklist") "digest")))
      (is (= [] (get (s/golden "G-checklist") "moves"))))))

(deftest item-03-003-a-move-without-a-note-is-refused
  (testing "@item-03-003 A move without a note is refused"
    (written-golden! "G-score-unknown" "g-40" "s-12")
    (let [before (count (s/model-events))
          resp (s/move! {"golden-id" "G-score-unknown" "digest" "g-41"
                         "reason" "instrument-behavior-changed" "additions-only" false
                         "note" "" "scenario-digest" "s-12"})]
      (is (= -32000 (s/error-code resp)))
      (is (= "empty-note" (s/refusal resp)))
      (is (= before (count (s/model-events))))
      (is (= "g-40" (get (s/golden "G-score-unknown") "digest"))))))

(deftest item-03-004-never-moved-for-a-reason-outside-the-three
  (testing "@item-03-004 A golden is never moved for a reason outside the three"
    (written-golden! "G-score-unknown" "g-40" "s-12")
    (let [before (count (s/model-events))
          resp (s/move! {"golden-id" "G-score-unknown" "digest" "g-41"
                         "reason" "make-the-self-check-green" "additions-only" false
                         "note" "it was red" "scenario-digest" "s-12"})]
      (is (= -32602 (s/error-code resp)))
      (is (= before (count (s/model-events))))
      (is (= "g-40" (get (s/golden "G-score-unknown") "digest")))
      (is (= [] (get (s/golden "G-score-unknown") "moves"))))
    (testing "the quoted reason with spaces is none of the three either"
      (is (= -32602 (s/error-code
                     (s/move! {"golden-id" "G-score-unknown" "digest" "g-41"
                               "reason" "make the self-check green" "additions-only" false
                               "note" "it was red" "scenario-digest" "s-12"})))))))

(deftest item-03-005-a-golden-whose-scenario-digest-is-not-its-scenarios-is-refused
  (testing "@item-03-005 A golden whose scenario digest is not its scenario's is refused"
    (s/set-digests! {scenario "s-12"})
    (let [resp (s/write! {"golden-id" "G-score-unknown" "scenario" scenario
                          "scenario-digest" "s-11" "path" "goldens/g.out" "digest" "g-40"})]
      (is (= -32000 (s/error-code resp)))
      (is (= "scenario-digest-mismatch" (s/refusal resp)))
      (is (nil? (s/golden "G-score-unknown")))
      (is (empty? (s/model-events))))))

(deftest item-03-006-a-move-to-the-same-digest-is-refused
  (testing "@item-03-006 A move to the same digest is refused"
    (written-golden! "G-score-unknown" "g-40" "s-12")
    (let [resp (s/move! {"golden-id" "G-score-unknown" "digest" "g-40"
                         "reason" "wording-replaced" "additions-only" false
                         "note" "no change" "scenario-digest" "s-12"})]
      (is (= -32000 (s/error-code resp)))
      (is (= "same-digest" (s/refusal resp)))
      (is (= [] (get (s/golden "G-score-unknown") "moves")))
      (is (= "g-40" (get (s/golden "G-score-unknown") "digest"))))))

(deftest item-03-007-a-move-that-names-a-stale-scenario-digest-is-refused
  (testing "@item-03-007 A move that names a stale scenario digest is refused"
    (written-golden! "G-score-unknown" "g-40" "s-12")
    ;; the scenario was reworded: its digest is now s-13
    (s/set-digests! {scenario "s-13"})
    (let [resp (s/move! {"golden-id" "G-score-unknown" "digest" "g-41"
                         "reason" "wording-replaced" "additions-only" false
                         "note" "reworded" "scenario-digest" "s-12"})]
      (is (= -32000 (s/error-code resp)))
      (is (= "stale-scenario-digest" (s/refusal resp)))
      (let [g (s/golden "G-score-unknown")]
        (is (= "g-40" (get g "digest")))
        (is (= "s-12" (get g "scenario-digest")))
        (is (= [] (get g "moves")))))))

(deftest item-03-008-writes-a-golden-from-its-scenario
  (testing "@item-03-008 The instrument maintainer writes a golden from its scenario"
    (s/set-digests! {scenario "s-12"})
    (let [resp (s/write! {"golden-id" "G-score-unknown" "scenario" scenario
                          "scenario-digest" "s-12" "path" "goldens/score-unknown.out"
                          "digest" "g-40"})
          [event] (get-in resp ["result" "events"])]
      (is (= 1 (count (get-in resp ["result" "events"]))))
      (is (= {"type" "grader-lifecycle/instrument-golden-written"
              "golden-id" "G-score-unknown" "scenario" scenario "scenario-digest" "s-12"
              "path" "goldens/score-unknown.out" "digest" "g-40"}
             (dissoc event "written-at")))
      (is (re-matches #"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z" (get event "written-at")))
      (is (= [{"golden-id" "G-score-unknown" "path" "goldens/score-unknown.out"
               "digest" "g-40" "scenario-digest" "s-12" "moves" []}]
             (s/goldens))))))

(deftest item-03-009-a-golden-is-written-once
  (testing "@item-03-009 A golden is written once"
    (written-golden! "G-score-unknown" "g-40" "s-12")
    (let [resp (s/write! {"golden-id" "G-score-unknown" "scenario" scenario
                          "scenario-digest" "s-12" "path" "goldens/other.out" "digest" "g-41"})]
      (is (= -32000 (s/error-code resp)))
      (is (= "golden-exists" (s/refusal resp)))
      (let [gs (filter #(= "G-score-unknown" (get % "golden-id")) (s/goldens))]
        (is (= 1 (count gs)))
        (is (= "g-40" (get (first gs) "digest")))))))

(deftest item-03-010-moving-a-golden-never-written-is-refused
  (testing "@item-03-010 Moving a golden that was never written is refused"
    (let [resp (s/move! {"golden-id" "G-unknown" "digest" "g-41" "reason" "spec-grew"
                         "additions-only" true "note" "grew" "scenario-digest" "s-12"})]
      (is (= -32000 (s/error-code resp)))
      (is (= "no-such-golden" (s/refusal resp)))
      (is (nil? (s/golden "G-unknown")))
      (is (empty? (s/model-events))))))

(deftest item-03-011-rewording-changes-the-scenario-digest-and-keeps-the-identity
  (testing "@item-03-011 Rewording a scenario changes its scenario digest and keeps its item identity"
    (written-golden! "G-score-unknown" "g-40" "s-12" "@item-03-004")
    (s/set-digests! {"@item-03-004" "s-13"})
    (let [resp (s/move! {"golden-id" "G-score-unknown" "digest" "g-41"
                         "reason" "wording-replaced" "additions-only" false
                         "note" "reworded" "scenario-digest" "s-13"})]
      (is (contains? resp "result") (str resp))
      (let [g (s/golden "G-score-unknown")]
        (is (= "g-41" (get g "digest")))
        (is (= "s-13" (get g "scenario-digest")))))))
