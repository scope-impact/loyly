(ns loyly.cycle-runner.relay-test
  "Rule: The runner stamps, pins and derives the checklist, and relays every
   handoff from its producer's store."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.harness :as h]
            [loyly.cycle-runner.standins :as standins]))

(defn- relay-rows [run]
  (mapv (juxt :handoff-type :position :consumer) (:relayed-handoffs run)))

(deftest item-06-007-each-handoff-is-handed-in-unchanged-and-once-to-every-consumer-in-its-producers-order
  (h/with-product
    (let [w (h/world! "06-007" {})
          _ (h/start-run w)
          run (h/progress "R-1")
          rows (relay-rows run)]
      (is (= [["specification/scenario-stamped" 1 "measurement"]
              ["specification/scenario-stamped" 2 "measurement"]
              ["cycle-preparation/source-cut-pinned" 1 "measurement"]
              ["cycle-preparation/source-cut-pinned" 1 "regeneration-cycle"]]
             (take 4 rows)))
      (is (= 1 (count (filter #(= ["regeneration-cycle/gate-attempt-passed" 1 "measurement"] %) rows))))
      (is (= (count rows) (count (distinct rows))) "each handoff reaches each consumer once")
      (is (every? #(= "accepted" (:outcome %)) (:relayed-handoffs run)))
      (testing "handed in unchanged: the consumer got the producer's fields"
        (let [handed (filter #(= "specification/scenario-stamped" (first %)) (standins/requests "measurement"))
              stamped (filter #(= "specification/scenario-stamped" (:type %))
                              (:events (first (h/effect-rows run "stamp"))))]
          (is (= (map #(dissoc % :type) stamped) (map second handed))))))))

(deftest item-06-008-a-checklist-measurement-refuses-halts-the-run-with-measurements-refusal
  (h/with-product
    (let [w (h/world! "06-008" {:spec h/spec-empty})
          _ (h/start-run w)
          run (h/progress "R-1")
          checklist (first (h/effect-rows run "checklist"))]
      (is (= "refused" (:outcome checklist)))
      (is (= "empty-checklist" (:refusal checklist)))
      (is (= "measurement" (:context checklist)))
      (is (= "halted" (:status run)))
      (is (= {:effect "checklist" :reason "refused-before-the-cycle"} (select-keys (:halt run) [:effect :reason])))
      (is (empty? (h/effect-rows run "self-check")))
      (is (empty? (h/effect-rows run "fence"))))))

(deftest item-06-009-a-plan-whose-identities-a-fresh-stamp-does-not-give-back-halts-the-run-before-the-cycle
  (h/with-product
    (let [w (h/world! "06-009" {:spec h/spec-extra-unstamped})
          _ (h/start-run w)
          run (h/progress "R-1")
          stamp (first (h/effect-rows run "stamp"))]
      (is (= "recorded" (:outcome stamp)))
      (is (= "specification" (:context stamp)))
      (is (= "halted" (:status run)))
      (is (= {:effect "stamp" :reason "refused-before-the-cycle"} (select-keys (:halt run) [:effect :reason])))
      (is (str/includes? (get-in run [:halt :detail]) "01-003"))
      (is (empty? (h/effect-rows run "pin")))
      (is (empty? (h/effect-rows run "checklist"))))))

(deftest item-06-047-a-slice-that-is-a-symbolic-link-in-the-plan-is-read-at-its-target
  (h/with-product
    (let [w (h/world! "06-047" {:slices {"a" h/spec-two} :links {"01-a" "../spec/a"}})
          _ (h/start-run w)
          run (h/progress "R-1")
          stamp (first (h/effect-rows run "stamp"))
          checklist (first (h/effect-rows run "checklist"))
          items (get-in checklist [:events 0 :items])]
      (is (= "recorded" (:outcome stamp)))
      (is (not= "halted" (:status run)) (pr-str (:halt run)))
      (is (= ["01-001" "01-002"] (mapv :item-id items)))
      (is (every? #(str/starts-with? (:source %) "docs/plan/01-a/") items) (pr-str items)))))

(deftest item-06-048-the-configurations-plan-slices-select-what-is-stamped-and-checklisted
  (h/with-product
    (let [w (h/world! "06-048" {:slices {"01-booking-core" h/spec-two "02-cancel" (h/spec-of "02" 1)}
                                :plan-slices "02"})
          _ (h/start-run w)
          run (h/progress "R-1")
          stamped (standins/requests "specification")
          checklist (first (h/effect-rows run "checklist"))]
      (is (= 1 (count (h/effect-rows run "stamp"))))
      (is (= ["02"] (mapv (comp :slice second) (filter #(= "specification/stamp-slice" (first %)) stamped))))
      (is (= ["02-001"] (mapv :item-id (get-in checklist [:events 0 :items])))))))
