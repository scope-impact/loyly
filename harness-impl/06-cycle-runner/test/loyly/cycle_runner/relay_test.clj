(ns loyly.cycle-runner.relay-test
  "Rule: The runner stamps, pins and derives the checklist, and relays every handoff from its
   producer's store."
  (:require [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.fixtures :as f]
            [loyly.cycle-runner.standins :as standins]))

(deftest item-06-007-each-handoff-is-handed-in-unchanged-and-once-in-its-producers-order
  (let [w (f/world)]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [run (f/progress sys "R-1")
            relayed (mapv (juxt :handoff-type :producer :position :consumer) (:relayed-handoffs run))]
        (is (= "awaiting-verdicts" (:status run)))
        (is (= [["specification/scenario-stamped" "specification" 1 "measurement"]
                ["specification/scenario-stamped" "specification" 2 "measurement"]
                ["cycle-preparation/source-cut-pinned" "cycle-preparation" 1 "measurement"]
                ["cycle-preparation/source-cut-pinned" "cycle-preparation" 1 "regeneration-cycle"]]
               (take 4 relayed)))
        (is (= 1 (count (filter #(= ["regeneration-cycle/gate-attempt-passed" "regeneration-cycle" 1 "measurement"] %)
                                relayed))))
        (is (every? #(= "accepted" (:outcome %)) (:relayed-handoffs run)))
        (is (= (count relayed) (count (distinct (map #(drop 1 %) relayed)))) "each handoff once per consumer")
        (testing "handed in unchanged: the fields the producer replied"
          (let [stamped (mapcat :events (f/results-of run "stamp"))
                to-measurement (filter #(= "specification/scenario-stamped" (:method %)) (standins/handed-to "measurement"))]
            (is (= (map #(dissoc % :type) stamped) (map :params to-measurement)))))))))

(deftest item-06-008-a-checklist-measurement-refuses-halts-the-run-with-its-refusal
  (let [w (f/world :spec f/no-scenario)]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [run (f/progress sys "R-1")
            [cl] (f/results-of run "checklist")]
        (is (= "refused" (:outcome cl)))
        (is (= "empty-checklist" (:refusal cl)))
        (is (= "measurement" (:context cl)))
        (is (= "halted" (:status run)))
        (is (= {:effect "checklist" :reason "refused-before-the-cycle"} (select-keys (:halt run) [:effect :reason])))
        (is (empty? (f/results-of run "self-check")))
        (is (empty? (f/results-of run "fence")))))))

(deftest item-06-009-a-plan-whose-identities-a-fresh-stamp-does-not-give-back-halts-the-run
  (let [w (f/world :spec f/three-scenarios-one-unstamped)]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [run (f/progress sys "R-1")
            [st] (f/results-of run "stamp")]
        (is (= "recorded" (:outcome st)))
        (is (= "specification" (:context st)))
        (is (= ["01-001" "01-002" "01-003"] (mapv :item-id (:events st))))
        (is (= "halted" (:status run)))
        (is (= {:effect "stamp" :reason "refused-before-the-cycle"} (select-keys (:halt run) [:effect :reason])))
        (is (re-find #"01-003" (get-in run [:halt :detail])))
        (is (empty? (f/results-of run "pin")))
        (is (empty? (f/results-of run "checklist")))))))
