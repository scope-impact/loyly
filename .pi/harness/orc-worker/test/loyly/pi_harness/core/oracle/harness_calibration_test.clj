(ns loyly.pi-harness.core.oracle.harness-calibration-test
  (:require [clojure.edn]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.core.oracle.harness-calibration :as calibration]
            [loyly.pi-harness.core.oracle.harness-snapshot :as snapshot]))

(def snapshot-digest (str "sha256:" (apply str (repeat 64 "a"))))

(defn- temp-root []
  (.getPath (.toFile (java.nio.file.Files/createTempDirectory
                      "loyly-harness-calibration-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn- calibrate-with [root suite-exit runs]
  (with-redefs [snapshot/build! (fn [_]
                                  {:harness-revision "33e537c"
                                   :manifest-digest snapshot-digest
                                   :artifacts [{:path "harness" :digest "sha256:artifact"}]
                                   :components calibration/required-components
                                   :fixtures [{:id "carried" :role "carried"}
                                              {:id "uncarried" :role "uncarried"}]
                                   :goldens [{:labels {:fixture_id "carried"}}
                                             {:labels {:fixture_id "uncarried"}}]})
                calibration/run-suites
                (fn [_ fixtures]
                  (swap! runs inc)
                  {:fixture-results (zipmap (map :id fixtures) (repeat (zero? suite-exit)))
                   :suites {:clojure-worker {:exit-code suite-exit :output "FAIL in (flaky-test)"}
                            :node-adapters {:exit-code 0 :output "ok"}}})
                calibration/evaluate
                (fn [_ fixture-results]
                  {:passed (every? true? (vals fixture-results))
                   :mismatches [] :actual {}})]
    (calibration/calibrate! root)))

(deftest fresh-harness-calibration-builds-a-local-sealed-snapshot
  (let [root (temp-root)
        runs (atom 0)
        result (calibrate-with root 0 runs)]
    (is (true? (:passed result)))
    (is (false? (:cached result)))
    (is (= 1 @runs))
    (is (= "33e537c" (:harness-revision result)))
    (is (= snapshot-digest (:harness-snapshot-digest result)))))

(deftest a-passing-calibration-is-reused-for-the-same-snapshot-digest
  (let [root (temp-root)
        runs (atom 0)
        first-result (calibrate-with root 0 runs)
        second-result (calibrate-with root 0 runs)]
    (is (true? (:passed first-result)))
    (is (true? (:passed second-result)))
    (is (true? (:cached second-result)))
    (is (= 1 @runs) "the suites run once per harness snapshot digest")
    (is (= snapshot-digest (:harness-snapshot-digest second-result)))))

(deftest a-failed-calibration-is-recorded-and-never-reused
  (let [root (temp-root)
        runs (atom 0)
        failed (calibrate-with root 1 runs)
        again (calibrate-with root 1 runs)
        record (clojure.edn/read-string (slurp (:failure-record failed)))]
    (is (false? (:passed failed)))
    (is (= 2 @runs) "a failure is not cached; the next cycle calibrates again")
    (is (false? (:passed again)))
    (is (= 1 (get-in record [:suites :clojure-worker :exit-code])))
    (is (re-find #"FAIL in \(flaky-test\)"
                 (get-in record [:suites :clojure-worker :output-tail])))))

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
