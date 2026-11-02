(ns loyly.pi-harness.allure-evidence-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.allure-evidence :as allure]
            [loyly.pi-harness.allure-golden :as allure-golden]
            [loyly.pi-harness.orc-worker :as worker]))

(def observation
  {:role "observed"
   :builder "openai-codex/gpt-5.6-luna"
   :evidence-kind "grader"
   :producer "booking-core-black-box"
   :grader-id "booking-core-v1"
   :project-id "loyly"
   :container-id "loyly"
   :component-id "01-booking-core"
   :item-id "@item-01-007"
   :cycle-id "cycle-02"
   :cut-digest "cut-abc"
   :subject-digest "subject-def"
   :oracle-digest "oracle-123"
   :room "gondolin"
   :scope "slice"
   :result "pass"
   :reached? true
   :evidence "booking, schedule, heater and billing agree"})

(defn- labels-by-name [result]
  (into {} (map (juxt :name :value) (:labels result))))

(deftest raw-allure-result-normalizes-to-a-deterministic-golden
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-allure-golden-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        attachment (io/file root "random-evidence.json")
        result-file (io/file root "random-result.json")]
    (try
      (spit attachment "{\"cycle-id\":\"cycle-1\",\"result\":\"fail\"}")
      (spit result-file
            (json/write-str
             {:uuid "random-uuid"
              :historyId "stable-history"
              :testCaseId "stable-case"
              :name "@item-01-008"
              :fullName "loyly.01-booking-core.@item-01-008"
              :status "failed"
              :stage "finished"
              :start 100
              :stop 200
              :labels [{:name "cycle_id" :value "cycle-1"}
                       {:name "producer" :value "booking-core-black-box"}
                       {:name "evidence_kind" :value "calibration"}
                       {:name "grader_id" :value "booking-core-v1"}]
              :parameters [{:name "scope" :value "fixture"}
                           {:name "reached" :value "true"}]
              :attachments [{:name "evidence"
                             :source "random-evidence.json"
                             :type "application/json"}]
              :statusDetails {:message "known uncarried fixture was rejected"
                              :trace "machine-specific trace"}}))
      (is (= {:schemaVersion 1
              :name "@item-01-008"
              :fullName "loyly.01-booking-core.@item-01-008"
              :status "failed"
              :stage "finished"
              :statusDetails {:message "known uncarried fixture was rejected"}
              :labels {:evidence_kind "calibration"
                       :grader_id "booking-core-v1"
                       :producer "booking-core-black-box"}
              :parameters [{:name "reached" :value "true"}
                           {:name "scope" :value "fixture"}]
              :attachments
              [{:name "evidence"
                :mediaType "application/json"
                :digest (str "sha256:"
                             "39ca3c85734717cf31f55ab2e7d04d8ad2438a3bd9f6f46fae350d12506b4699")}]}
             (allure-golden/normalize-result root result-file)))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest normalized-allure-golden-is-recorded-and-verified-explicitly
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-allure-golden-record-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        attachment (io/file root "evidence.json")
        result-file (io/file root "result.json")
        golden-file (io/file root "oracle" "known-uncarried.golden.json")]
    (try
      (spit attachment "{\"result\":\"fail\"}")
      (spit result-file
            (json/write-str
             {:uuid "first-run"
              :name "uncarried"
              :fullName "instrument.uncarried"
              :status "passed"
              :stage "finished"
              :labels [{:name "producer" :value "black-box"}]
              :parameters []
              :attachments [{:name "evidence" :source "evidence.json"
                             :type "application/json"}]}))
      (let [recorded (allure-golden/record! root result-file golden-file)]
        (is (= recorded (json/read-str (slurp golden-file) :key-fn keyword)))
        (is (= {:passed true :expected recorded :actual recorded}
               (allure-golden/verify root result-file golden-file))))
      (spit result-file
            (json/write-str
             {:uuid "second-run"
              :name "uncarried"
              :fullName "instrument.uncarried"
              :status "failed"
              :stage "finished"
              :labels [{:name "producer" :value "black-box"}]
              :parameters []
              :attachments [{:name "evidence" :source "evidence.json"
                             :type "application/json"}]}))
      (is (false? (:passed (allure-golden/verify root result-file golden-file))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest calibration-golden-projects-to-allure-without-changing-its-meaning
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-calibration-allure-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        golden {:schemaVersion 1
                :name "full-harness-known-carried"
                :fullName "full-pi-harness.full-harness-known-carried"
                :status "passed"
                :stage "finished"
                :labels {:fixture_id "full-harness-known-carried"
                         :instrument_id "full-pi-harness"}
                :parameters [{:name "role" :value "carried"}]
                :attachments []}]
    (try
      (let [result-file (allure-golden/write-allure! root golden)]
        (is (= golden (allure-golden/normalize-result root result-file))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest per-item-observation-is-written-as-allure-compatible-evidence
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-allure-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (let [first-path (allure/write-observation! root observation)
            second-path (allure/write-observation! root (assoc observation :cycle-id "cycle-03"))
            result (json/read-str (slurp first-path) :key-fn keyword)
            second-result (json/read-str (slurp second-path) :key-fn keyword)
            labels (labels-by-name result)
            attachment (first (:attachments result))
            attached (json/read-str
                      (slurp (io/file root (:source attachment)))
                      :key-fn keyword)]
        (is (= "passed" (:status result)))
        (is (= "finished" (:stage result)))
        (is (= "@item-01-007" (:name result)))
        (is (= "loyly.01-booking-core.@item-01-007" (:fullName result)))
        (is (= (:historyId result) (:historyId second-result)))
        (is (not= (:uuid result) (:uuid second-result)))
        (is (= {"role" "observed"
                "builder" "openai-codex/gpt-5.6-luna"
                "evidence_kind" "grader"
                "producer" "booking-core-black-box"
                "grader_id" "booking-core-v1"
                "container_id" "loyly"
                "component_id" "01-booking-core"
                "item_id" "@item-01-007"
                "cycle_id" "cycle-02"
                "cut_digest" "cut-abc"
                "subject_digest" "subject-def"
                "oracle_digest" "oracle-123"
                "room" "gondolin"}
               (select-keys labels
                            ["role" "builder" "evidence_kind" "producer" "grader_id"
                             "container_id" "component_id" "item_id" "cycle_id"
                             "cut_digest" "subject_digest" "oracle_digest" "room"])))
        (is (= "application/json" (:type attachment)))
        (is (= observation attached)))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest sealed-artifacts-are-projected-without-duplicating-storage
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-allure-artifacts-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        evidence-root (io/file root "evidence")
        results-root (io/file root "allure-results")
        source (io/file evidence-root "compose/compose-logs.stdout.txt")]
    (try
      (.mkdirs (.getParentFile source))
      (spit source "ready")
      (let [result-path (allure/write-observation!
                         results-root observation evidence-root
                         {:artifacts [{:path "compose/compose-logs.stdout.txt"
                                       :media-type "text/plain"}]})
            result (json/read-str (slurp result-path) :key-fn keyword)
            attachment (some #(when (= "compose/compose-logs.stdout.txt" (:name %)) %)
                             (:attachments result))
            projected (io/file results-root (:source attachment))]
        (is (= "ready" (slurp projected)))
        (is (java.nio.file.Files/isSameFile (.toPath source) (.toPath projected))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest grader-measurement-emits-one-allure-observation-per-selected-item
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-grader-allure-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (let [context {:inputs {:request-id "cycle-02"
                              :selection {:projectId "loyly"
                                          :containerId "loyly"
                                          :componentId "01-booking-core"
                                          :cut "cut-abc"}
                              :execution-backend "gondolin"
                              :scope ["impl"]
                              :builder-result {:output-root "/candidate-not-used"
                                               :builder "openai-codex/gpt-5.6-luna"
                                               :subject-digest "subject-def"}
                              :instrument-result {:subject-digest "subject-def"
                                                  :evidence-root (.getAbsolutePath root)
                                                  :evidence-locations [(.getAbsolutePath root)]}
                              :oracle-result {:grader-producer "booking-core-black-box"
                                              :grader-id "booking-core-v1"}}}
            measurement {:items [{:item-id "@item-01-007" :verdict "pass"
                                  :evidence "all views agree" :graded? true}
                                 {:item-id "@item-01-008" :verdict "fail"
                                  :evidence "not graded: independent grader never reached this item"
                                  :graded? false}]}
            paths (worker/emit-grader-observations! context measurement)
            results (mapv #(json/read-str (slurp %) :key-fn keyword) paths)
            by-item (into {} (map (fn [result]
                                    [(get (labels-by-name result) "item_id") result])
                                  results))]
        (is (= #{"@item-01-007" "@item-01-008"} (set (keys by-item))))
        (is (= "passed" (get-in by-item ["@item-01-007" :status])))
        (is (= "failed" (get-in by-item ["@item-01-008" :status])))
        (is (= "subject-def"
               (get (labels-by-name (by-item "@item-01-007")) "subject_digest")))
        (is (= #{{:name "reached" :value "true"} {:name "scope" :value "impl"}}
               (set (:parameters (by-item "@item-01-007")))))
        (is (= #{{:name "reached" :value "false"} {:name "scope" :value "impl"}}
               (set (:parameters (by-item "@item-01-008"))))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))
