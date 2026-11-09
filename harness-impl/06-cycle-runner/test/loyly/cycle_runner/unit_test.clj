(ns loyly.cycle-runner.unit-test
  "The runner's own readings: the seam's error codes, the stripped copy, the
   scenario and candidate digests, the configuration."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.harness :as h]
            [loyly.cycle-runner.repo :as repo]
            [loyly.cycle-runner.util :as u]))

(deftest the-seam-answers-errors-as-json-rpc
  (h/with-product
    (is (= -32601 (get-in (h/rpc "cycle-runner/halt-run" {}) [:error :code])))
    (is (= -32601 (get-in (h/rpc "cycle-runner/relay-handoff" {}) [:error :code])))
    (is (= -32602 (get-in (h/rpc "cycle-runner/start-run" {:run-id "R-1"}) [:error :code])))
    (is (= -32700 (get-in (u/<-json (loyly.cycle-runner.seam/handle (:sys @h/product) "not json")) [:error :code])))
    (is (= {:run nil} (:result (h/rpc "cycle-runner/run-progress" {:run-id "nope"}))))))

(deftest the-stamp-copy-loses-only-its-item-tags
  (let [copy (repo/strip-item-tags h/spec-two)]
    (is (not (str/includes? copy "@item-")))
    (is (str/includes? copy "    @minimum\n    Scenario: A guest books a free slot"))
    (is (str/includes? copy "@balance\n    Scenario: A guest cannot book"))
    (is (str/includes? copy "# a comment above the scenario"))
    (is (= 2 (count (:scenarios (repo/read-spec copy)))))
    (is (every? nil? (map :item-id (:scenarios (repo/read-spec copy)))))))

(deftest a-scenario-digest-is-its-lines-to-the-next-heading-or-tag-line
  (let [s (first (:scenarios (repo/read-spec h/spec-two)))]
    (is (= ["01-001" "A guest books a free slot" "A guest books a slot"] [(:item-id s) (:title s) (:rule s)]))
    (is (= (u/sha256-of-text (str "    Scenario: A guest books a free slot\n"
                                  "      Given a free slot at six\n"
                                  "      When the guest books it\n"
                                  "      Then the slot is booked"))
           (:digest s)))))

(deftest the-configuration-is-read-as-legacy-config-sh
  (is (= {:app-dir "impl" :port 8901 :plan-dir "docs/plan" :app-marker "sauna" :app-probe "harness/probe.sh"
          :fenced-paths ["impl" ".pi" "archive/legacy-harness/cycles"] :app-seam "http" :plan-slices []}
         (repo/config-of "APP_DIR=impl\nPORT=8901\nPLAN_DIR=docs/plan\nAPP_MARKER=sauna\nAPP_PROBE=harness/probe.sh\nFENCED_PATHS=\"impl .pi archive/legacy-harness/cycles\"\n"))))

(deftest a-candidate-digest-is-one-line-per-file-by-path
  (let [d (str (h/tmp-root) "/digest")]
    (u/delete-tree! d)
    (h/write! (str d "/b.txt") "b")
    (h/write! (str d "/a/x.txt") "x")
    (is (= (u/sha256-of-text (str "a/x.txt\t" (u/sha256-hex (u/utf8 "x")) "\n"
                                  "b.txt\t" (u/sha256-hex (u/utf8 "b")) "\n"))
           (u/tree-digest d)))))
