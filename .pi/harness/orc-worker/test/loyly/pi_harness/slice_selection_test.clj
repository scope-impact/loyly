(ns loyly.pi-harness.slice-selection-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [loyly.pi-harness.slice-selection :as selection]))

(defn- with-cut [spec f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-selection-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        plan (io/file root "docs" "plan" "01-booking-core")]
    (try
      (.mkdirs plan)
      (spit (io/file plan "spec.feature") spec)
      (f root)
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest selection-is-deterministic-and-limited-to-the-requested-slice
  (with-cut
    "Feature: Booking\n\n  Rule: Availability\n\n    @item-01-001\n    Scenario: First\n\n    @item-01-002\n    Scenario: Second\n"
    (fn [root]
      (let [first-contract (selection/resolve-selection root "cut-1" "01-booking-core")
            second-contract (selection/resolve-selection root "cut-1" "01-booking-core")]
        (is (= first-contract second-contract))
        (is (= "booking-core" (:componentId first-contract)))
        (is (= ["@item-01-001" "@item-01-002"] (mapv :id (:items first-contract))))
        (is (= "awaiting-approval" (:status first-contract)))
        (is (re-matches #"[0-9a-f]{64}" (:selectionDigest first-contract)))))))

(deftest selection-contract-can-pin-an-approved-item-subset
  (with-cut
    "Feature: Booking\n\n  @item-01-001\n  Scenario: First\n\n  @item-01-002\n  Scenario: Second\n"
    (fn [root]
      (let [contract (selection/resolve-selection
                      root "cut-1" "01-booking-core"
                      {:project-id "another-project"
                       :container-id "another-container"
                       :component-id "reservations"
                       :item-ids ["@item-01-002"]})]
        (is (= "another-project" (:projectId contract)))
        (is (= "another-container" (:containerId contract)))
        (is (= "reservations" (:componentId contract)))
        (is (= ["@item-01-002"] (mapv :id (:items contract))))))))

(deftest selection-rejects-an-unstamped-scenario
  (with-cut
    "Feature: Booking\n\n  Rule: Availability\n\n    Scenario: Missing identity\n"
    (fn [root]
      (testing "every scenario must carry the slice's stable item identity"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"stable @item identity"
                              (selection/resolve-selection root "cut-1" "01-booking-core")))))))
