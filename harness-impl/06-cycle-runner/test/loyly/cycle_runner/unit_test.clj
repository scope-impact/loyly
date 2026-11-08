(ns loyly.cycle-runner.unit-test
  "The runner's own readings (seam.md, Digests; E1, E2; Handed in), below the seam."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.effects :as fx]
            [loyly.cycle-runner.fixtures :as f]
            [loyly.cycle-runner.gherkin :as g]
            [loyly.cycle-runner.repo :as repo]
            [loyly.cycle-runner.util :as u]))

(deftest the-stripped-copy-loses-only-item-tags
  (let [text "Feature: F\n\n  # keep me\n  @minimum @item-01-001\n  Scenario: A\n    Given a\n\n  @item-01-002\n  Scenario: B\n    Given b\n"
        out (g/strip-item-tags text)]
    (is (= "Feature: F\n\n  # keep me\n  @minimum\n  Scenario: A\n    Given a\n\n  Scenario: B\n    Given b\n" out))))

(deftest the-scenario-digest-is-the-scenarios-lines-up-to-the-next-tag-line
  (let [text "Feature: F\n  @item-01-001\n  Scenario: A\n    Given a\n    Then b\n\n\n  @item-01-002\n  Scenario: B\n    Given c\n"
        [a b] (:scenarios (g/read-feature text))]
    (is (= (u/sha256-of-string "  Scenario: A\n    Given a\n    Then b") (:digest a)))
    (is (= (u/sha256-of-string "  Scenario: B\n    Given c") (:digest b)))
    (is (= ["01-001" "01-002"] [(:item-id a) (:item-id b)]))))

(deftest a-scenario-under-a-rule-names-its-rule
  (let [[s] (:scenarios (g/read-feature f/two-scenarios))]
    (is (= "A booking holds one slot" (:rule s)))
    (is (= "A member books a free slot" (:title s)))))

(deftest the-configuration-is-read-as-key-value-lines
  (let [c (repo/parse-config "# c\nAPP_DIR=impl\nPORT=8901\nPLAN_DIR=docs/plan\nAPP_MARKER=sauna\nAPP_PROBE=harness/probe.sh\nFENCED_PATHS=\"impl harness-impl .pi\"\n  APP_DIR=indented\n")]
    (is (= {:app-dir "impl" :port 8901 :plan-dir "docs/plan" :app-marker "sauna" :app-probe "harness/probe.sh"
            :fenced-paths ["impl" "harness-impl" ".pi"]}
           (select-keys c [:app-dir :port :plan-dir :app-marker :app-probe :fenced-paths])))))

(deftest a-skip-line-names-the-rest-of-the-line
  (is (= ["bb-tools" "the oracle cannot judge this tree: a tool it grades is absent."]
         (fx/skipped-checks "ok\nSKIP bb-tools\nSKIP  the oracle cannot judge this tree: a tool it grades is absent.\n"))))

(deftest the-candidate-digest-is-one-line-per-file-by-path
  (let [d (f/temp-dir "cand")]
    (f/write! (str d "/b.txt") "b")
    (f/write! (str d "/a/x.txt") "x")
    (is (= (u/sha256-of-string (str "a/x.txt\t" (u/sha256-hex (u/utf8 "x")) "\n"
                                    "b.txt\t" (u/sha256-hex (u/utf8 "b")) "\n"))
           (fx/candidate-digest d)))))
