(ns loyly.pi-harness.grain.oracle-proposal-test
  (:require [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.demos.pi-agent-loop.system :as system]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.grain.interface :as model]))

(def proposal
  {:proposal-id "proposal-1"
   :proposal-digest "sha256:proposal-1"
   :generation-id "generation-1"
   :selection-id "selection-1"
   :selection-digest "sha256:selection-1"
   :scenario-digests ["sha256:scenario-1"]
   :subject-digest "sha256:candidate-1"
   :judge-id "sha256:judge-1"
   :denominator 1
   :normalized-evidence-digest "sha256:evidence-1"
   :status "proposed"})

(deftest exact-manual-approval-precedes-immutable-oracle-publication
  (let [runtime (system/start!)
        context (:context runtime)]
    (try
      (is (= proposal (model/record-oracle-proposal! context proposal)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"digest does not match"
                            (model/approve-oracle-proposal!
                             context "proposal-1" "sha256:stale" "operator-1")))
      (is (= "approved"
             (:status (model/approve-oracle-proposal!
                       context "proposal-1" "sha256:proposal-1" "operator-1"))))
      (is (= "published"
             (:status
              (model/record-accepted-product-oracle!
               context {:proposal-id "proposal-1"
                        :proposal-digest "sha256:proposal-1"
                        :descriptor-digest "sha256:oracle-1"
                        :approved-by "operator-1"
                        :published-at "2026-11-03T22:00:00Z"}))))
      (is (= "sha256:oracle-1"
             (:descriptor-digest (model/query-oracle-proposal context "proposal-1"))))
      (finally (system/stop! runtime)))))
