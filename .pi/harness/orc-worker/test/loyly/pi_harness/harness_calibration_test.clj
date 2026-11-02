(ns loyly.pi-harness.harness-calibration-test
  (:require [clojure.test :refer [deftest is]]
            [loyly.pi-harness.harness-calibration :as calibration]))

(deftest full-harness-calibration-rejects-an-uncovered-component
  (let [pack {:instruments [{:id "full-pi-harness"
                             :components ["allure-golden-normalizer"]}]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"missing calibrated harness components.*cycle-measurement"
                          (calibration/validate-component-coverage! pack)))))

(defn golden [fixture-id role]
  {:schemaVersion 1
   :name fixture-id
   :fullName (str "full-pi-harness." fixture-id)
   :status "passed"
   :stage "finished"
   :labels {:instrument_id "full-pi-harness" :fixture_id fixture-id}
   :parameters [{:name "role" :value role}]
   :attachments []})

(deftest full-harness-calibration-compares-both-fixture-roles-with-oci-goldens
  (let [components (vec (sort calibration/required-components))
        pack {:policy {:version 1 :minimumFixturesPerInstrument 2}
              :instruments [{:id "full-pi-harness"
                             :digest (str "sha256:" (apply str (repeat 64 "a")))
                             :components components}]
              :fixtures [{:id "known-carried" :instrumentId "full-pi-harness"
                          :role "carried"}
                         {:id "known-uncarried" :instrumentId "full-pi-harness"
                          :role "uncarried"}]
              :goldens [(golden "known-carried" "carried")
                        (golden "known-uncarried" "uncarried")]}
        passed (calibration/evaluate pack {"known-carried" true
                                           "known-uncarried" true})
        failed (calibration/evaluate pack {"known-carried" true
                                           "known-uncarried" false})]
    (is (true? (:passed passed)))
    (is (empty? (:mismatches passed)))
    (is (false? (:passed failed)))
    (is (= ["known-uncarried"] (:mismatches failed)))))
