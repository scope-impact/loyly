(ns loyly.pi-harness.core.selection.slice-selection-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [loyly.pi-harness.core.selection.slice-selection :as selection]))

(defn- with-slice [slice spec f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-selection-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        plan (io/file root "docs" "plan" slice)]
    (try
      (.mkdirs plan)
      (spit (io/file plan "spec.feature") spec)
      (f root)
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(defn- with-cut [spec f]
  (with-slice "01-booking-core" spec f))

(deftest selection-is-deterministic-and-limited-to-the-requested-slice
  (with-cut
    "Feature: Booking\n\n  Rule: Availability\n\n    @item-01-001\n    Scenario: First\n\n    @item-01-002\n    Scenario: Second\n"
    (fn [root]
      (let [first-contract (selection/resolve-selection root "cut-1" "01-booking-core")
            second-contract (selection/resolve-selection root "cut-1" "01-booking-core")]
        (is (= first-contract second-contract))
        (is (= "booking-core" (:componentId first-contract)))
        (is (= ["@item-01-001" "@item-01-002"] (mapv :id (:items first-contract))))
        (is (every? #(re-matches #"[0-9a-f]{64}" (:scenario-digest %))
                    (:items first-contract)))
        (is (= "awaiting-approval" (:status first-contract)))
        (is (re-matches #"sha256:[0-9a-f]{64}" (:selectionDigest first-contract)))))))

(deftest scenario-digest-changes-when-the-scenario-body-changes
  (let [digest (atom nil)]
    (with-cut
      "Feature: Booking\n\n  @item-01-001\n  Scenario: First\n    Given one apartment\n"
      (fn [root]
        (reset! digest (get-in (selection/resolve-selection
                                root "cut-1" "01-booking-core")
                               [:items 0 :scenario-digest]))))
    (with-cut
      "Feature: Booking\n\n  @item-01-001\n  Scenario: First\n    Given two apartments\n"
      (fn [root]
        (is (not= @digest
                  (get-in (selection/resolve-selection
                           root "cut-1" "01-booking-core")
                          [:items 0 :scenario-digest])))))))

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

(deftest stable-item-identity-is-independent-of-slice-identity
  (with-slice
    "02-projection-integrity"
    "Feature: Projection integrity\n\n  @item-01-007\n  Scenario: Existing stable identity\n"
    (fn [root]
      (let [contract (selection/resolve-selection
                      root "cut-2" "02-projection-integrity")]
        (is (= "projection-integrity" (:componentId contract)))
        (is (= ["@item-01-007"] (mapv :id (:items contract))))))))

(deftest composition-lists-every-existing-slice-for-session-exploration
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "project-composition-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (doseq [[slice feature]
              [["01-orders" "Feature: Orders\n\n  @item-01-001\n  Scenario: Place order\n"]
               ["02-reports" "Feature: Reports\n\n  @item-02-001\n  Scenario: Read report\n"]]]
        (let [plan (io/file root "docs" "plan" slice)]
          (.mkdirs plan)
          (spit (io/file plan "spec.feature") feature)))
      (is (= [{:slice "01-orders"
               :componentId "orders"
               :items [{:id "@item-01-001" :name "Place order" :rule ""
                        :source "docs/plan/4"}]}
              {:slice "02-reports"
               :componentId "reports"
               :items [{:id "@item-02-001" :name "Read report" :rule ""
                        :source "docs/plan/4"}]}]
             (mapv (fn [slice]
                     (update slice :items
                             (fn [items]
                               (mapv (fn [item] (dissoc item :scenario-digest)) items))))
                   (:slices (selection/slice-composition root "cut-42")))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))
