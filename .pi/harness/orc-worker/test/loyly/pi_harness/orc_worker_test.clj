(ns loyly.pi-harness.orc-worker-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [ai.obney.orc.demos.pi-agent-loop.system :as system]
            [ai.obney.orc.orc-service.interface :as orc]
            [loyly.pi-harness.application-contract :as application-contract]
            [loyly.pi-harness.allure-golden :as allure-golden]
            [loyly.pi-harness.cycle-measurement :as cycle-measurement]
            [loyly.pi-harness.harness-calibration :as harness-calibration]
            [loyly.pi-harness.observation :as observation]
            [loyly.pi-harness.orc-worker :as worker]
            [loyly.pi-harness.stack-conformance :as stack-conformance]
            [loyly.pi-harness.subject :as subject]))

(def approved-selection
  {:selectionId "selection-1"
   :selectionDigest "digest-1"
   :status "approved"
   :projectId "loyly"
   :containerId "loyly"
   :componentId "booking-core"
   :cut "cut-1"
   :slice "01-booking-core"
   :items [{:id "@item-01-007" :name "Booking" :rule "Booking" :source "spec.feature:1"}]})

(def approved-oracle
  {:passed true :oracle-digest "digest-1"
   :grader-producer "loyly-black-box" :grader-id "booking-core-v1"})

(deftest regeneration-workflow-retains-every-stage-result
  (let [runtime (system/start!)]
    (try
      (let [context (:context runtime)
            sheet-id (orc/build-workflow! context (worker/workflow))
            result (with-redefs [worker/project-root (constantly "/tmp")
                                  harness-calibration/calibrate!
                                  (fn [& _] {:passed true
                                             :oracle-digest "instrument-oracle-test"
                                             :mismatches [] :suites {}})
                                  worker/run-oracle (constantly {:passed true :oracle-digest "oracle-test"
                                                                 :grader-root "/oracle/grader"
                                                                 :artifacts [] :stdout "ok" :stderr ""})
                                  worker/project-adapter (constantly {:productOracleRef ".pi/harness/config/oracle-packs/loyly:accepted"
                                                                      :graderProducer "loyly-black-box"
                                                                      :graderId "booking-core-v1"
                                                                      :runtimeEvidenceContract "model/loyly/runtime-evidence-contract.json"
                                                                      :itemIdPrefix "@item-"
                                                                      :itemGraderScript "booking-core-{itemSuffix}.sh"})
                                  worker/run-builder-adapter (fn [_] {:output-root "/tmp/output"})
                                  subject/digest (constantly "subject-test")
                                  stack-conformance/check (fn [& _] {:passed true :exit-code 0
                                                                     :stdout "confirmed" :stderr ""})
                                  worker/emit-cycle-observation! (fn [& _] nil)
                                  worker/run-grader-adapter (fn [{:keys [mode]}]
                                                              (case mode
                                                                "independent"
                                                                {:independent-runtime {:exitCode 0 :stdout "" :stderr ""}
                                                                 :independent-evidence {:exitCode 0 :stdout "" :stderr ""}
                                                                 :independent-up {:exitCode 0 :stdout "healthy" :stderr ""}
                                                                 :independent-grade {:exitCode 0 :stdout "[{\"itemId\":\"01-007\",\"verdict\":\"pass\",\"evidence\":\"observed\"}]" :stderr ""}
                                                                 :independent-logs {:exitCode 0 :stdout "ready" :stderr ""}
                                                                 :independent-down {:exitCode 0 :stdout "removed" :stderr ""}}

                                                                "instrument"
                                                                {:instrument-conformance
                                                                 {:exitCode 0
                                                                  :stdout "[{\"itemId\":\"01-007\",\"verdict\":\"pass\",\"evidence\":\"one event and three correlated snapshots\"}]"
                                                                  :stderr ""}}

                                                                "compose"
                                                                {:compose-config {:exitCode 0 :stdout "{\"services\":{\"app\":{\"healthcheck\":{\"test\":[\"CMD\",\"true\"]}},\"test\":{}}}" :stderr ""}
                                                                 :compose-services {:exitCode 0 :stdout "app\ntest\n" :stderr ""}
                                                                 :compose-test {:exitCode 0 :stdout "tests passed" :stderr ""}
                                                                 :compose-up {:exitCode 0 :stdout "healthy" :stderr ""}
                                                                 :compose-logs {:exitCode 0 :stdout "ready" :stderr ""}
                                                                 :compose-down {:exitCode 0 :stdout "removed" :stderr ""}}))
                                  observation/record! (fn [_ value] value)
                                  cycle-measurement/record! (fn [_ measurement] measurement)]
                     (orc/execute context sheet-id
                                  {:request-id "request-1"
                                   :selection approved-selection
                                   :scope ["impl"]
                                   :target-stack "typescript"
                                   :execution-backend "gondolin"}))]
        (is (= :success (:status result)) (pr-str result))
        (is (= ["cut" "harness-calibration" "builder" "instrument" "repair-verification" "graders" "instrument-conformance" "result"]
               (mapv #(get-in (:outputs result) [% :stage])
                     [:cut-result :harness-calibration-result :builder-result :instrument-result
                      :repair-verification-result :graders-result
                      :instrument-conformance-result :result-result])))
        (is (= "instrument-oracle-test"
               (get-in result [:outputs :harness-calibration-result :instrument-oracle-digest])))
        (is (true? (get-in result [:outputs :instrument-conformance-result :passed])))
        (is (true? (get-in result [:outputs :result-result :instrumented?])))
        (is (= 1 (get-in result [:outputs :cycle-result :total]))))
      (finally
        (system/stop! runtime)))))

(deftest verification-uses-the-compose-application-contract
  (let [adapter-request (atom nil)
        context {:inputs {:request-id "request-compose"
                          :selection approved-selection
                          :scope ["impl"]
                          :target-stack "typescript"
                          :execution-backend "host"
                          :builder-result {:output-root "/tmp/loyly-compose-output"
                                           :subject-digest "subject-test"}
                          :oracle-result approved-oracle}}
        evidence {:compose-config {:exitCode 0 :stdout "{\"services\":{\"app\":{\"healthcheck\":{\"test\":[\"CMD\",\"true\"]}},\"test\":{}}}" :stderr ""}
                  :compose-services {:exitCode 0 :stdout "app\ntest\n" :stderr ""}
                  :compose-test {:exitCode 0 :stdout "tests passed" :stderr ""}
                  :compose-up {:exitCode 0 :stdout "healthy" :stderr ""}
                  :compose-logs {:exitCode 0 :stdout "app ready" :stderr ""}
                  :compose-down {:exitCode 0 :stdout "removed" :stderr ""}}
        result (with-redefs [worker/run-grader-adapter
                             (fn [request]
                               (reset! adapter-request request)
                               evidence)
                             stack-conformance/check (fn [& _] {:passed true :exit-code 0
                                                                 :stdout "confirmed" :stderr ""})
                             worker/emit-cycle-observation! (fn [& _] nil)]
                 (worker/verify-stage context))]
    (is (= "compose" (:mode @adapter-request)))
    (is (= "host" (:execution-backend @adapter-request)))
    (is (= true (:passed result)))
    (is (re-find #"tests passed" (:evidence result)))
    (is (re-find #"app ready" (:evidence result)))))

(deftest failed-verification-is-repaired-from-the-shared-evidence-directory
  (let [builder-call (atom nil)
        context {:inputs {:request-id "request-repair"
                          :selection approved-selection
                          :scope ["impl"]
                          :target-stack "rust"
                          :execution-backend "gondolin"
                          :harness-calibration-result {:passed true}
                          :oracle-result approved-oracle
                          :builder-result {:output-root "/state/request-repair"
                                           :subject-digest "subject-one"}
                          :instrument-result {:evidence-root "/state/request-repair.evidence"
                                              :subject-digest "subject-one"}
                          :verify-result {:passed false :evidence "round one failed"}}}
        result (with-redefs [worker/run-builder-adapter
                             (fn [request]
                               (reset! builder-call request)
                               {:output-root "/state/request-repair-repair-02"
                                :builder "openai-codex/test"})
                             subject/digest (constantly "subject-two")
                             worker/instrument-stage
                             (fn [{:keys [inputs]}]
                               {:stage "instrument" :request-id (:request-id inputs)
                                :subject-digest "subject-two"
                                :evidence-root "/state/request-repair-repair-02.evidence"
                                :evidence-locations []})
                             worker/verify-stage
                             (fn [_] {:stage "verify" :passed true :evidence "green"})]
                 (worker/repair-verification-stage context))]
    (is (true? (:passed result)))
    (is (= 2 (:rounds result)))
    (is (= "/state/request-repair" (:initial-workspace-root @builder-call)))
    (is (= "/state/request-repair.evidence" (:verification-evidence-root @builder-call)))
    (is (= 2 (:round @builder-call)))
    (is (= "/state/request-repair-repair-02" (get-in result [:builder :output-root])))
    (is (true? (get-in result [:verify :passed])))))

(deftest independent-grading-never-runs-for-an-unverified-candidate
  (let [called? (atom false)
        context {:inputs {:request-id "request-unverified"
                          :selection approved-selection
                          :scope ["impl"]
                          :execution-backend "gondolin"
                          :builder-result {:output-root "/tmp/candidate"
                                           :subject-digest "subject-test"}
                          :oracle-result approved-oracle
                          :verify-result {:passed false}}}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"verification did not pass"
         (with-redefs [worker/run-grader-adapter
                       (fn [_] (reset! called? true) {})]
           (worker/graders-stage context))))
    (is (false? @called?))))

(deftest oracle-stage-requires-the-accepted-project-oci-pack
  (let [call (atom nil)
        result (with-redefs [worker/project-root (constantly "/repo")
                             worker/project-adapter
                             (constantly {:productOracleRef ".pi/harness/config/oracle-packs/loyly:accepted"
                                          :graderProducer "loyly-black-box"
                                          :graderId "booking-core-v1"})
                             worker/run-oracle
                             (fn [cut selection options]
                               (reset! call {:cut cut :selection selection :options options})
                               {:passed true :oracle-digest "product-contract"
                                :grader-root "/oracle/grader"
                                :artifacts [] :stdout "ok" :stderr ""})]
                 (worker/oracle-stage
                  {:inputs {:request-id "request-oracle"
                            :selection approved-selection
                            :scope ["impl"]
                            :execution-backend "gondolin"
                            :harness-calibration-result
                            {:instrument-oracle-digest "instrument-oracle"}}}))]
    (is (= "cut-1" (:cut @call)))
    (is (= "/repo/.pi/harness/config/oracle-packs/loyly:accepted"
           (get-in @call [:options :oracle-pack-ref])))
    (is (= "/repo/.pi/harness/.state/oracles"
           (get-in @call [:options :oracle-cache-root])))
    (is (= "/oracle/grader" (:grader-root result)))
    (is (= "product-contract" (:accepted-contract-digest result)))
    (is (re-matches #"[0-9a-f]{64}" (:oracle-digest result)))))

(deftest verification-rejects-an-app-without-a-healthcheck
  (let [evidence {:compose-config {:exitCode 0 :stdout "{\"services\":{\"app\":{},\"test\":{}}}" :stderr ""}
                  :compose-services {:exitCode 0 :stdout "app\ntest\n" :stderr ""}
                  :compose-test {:exitCode 0 :stdout "tests passed" :stderr ""}
                  :compose-up {:exitCode 0 :stdout "started" :stderr ""}
                  :compose-logs {:exitCode 0 :stdout "app ready" :stderr ""}
                  :compose-down {:exitCode 0 :stdout "removed" :stderr ""}}
        result (loyly.pi-harness.compose/interpret-verification evidence)]
    (is (= false (:passed result)))
    (is (re-find #"app service must define a healthcheck" (:stderr result)))))

(deftest builder-instruction-requires-the-compose-application-contract
  (let [instruction (application-contract/builder-instruction
                     {:base-instruction "Build from the model."
                      :request-id "request-compose"
                      :scope ["impl"]
                      :target-stack "typescript"
                      :model-root "model/loyly"
                      :selection approved-selection})]
    (is (re-find #"model/loyly" instruction))
    (is (re-find #"Target implementation stack: typescript" instruction))
    (is (re-find #"impl/compose.yaml" instruction))
    (is (re-find #"impl/start.sh" instruction))
    (is (re-find #"Independent grading starts the Compose app" instruction))
    (is (re-find #"private container network" instruction))
    (is (re-find #"app" instruction))
    (is (re-find #"test" instruction))
    (is (re-find #"internal port 8080" instruction))
    (is (re-find #"http://app:8080" instruction))
    (is (re-find #"docker compose run --rm test" instruction))
    (is (re-find #"exact selected compiler or runtime" instruction))
    (is (re-find #"deliberately has no Docker daemon" instruction))
    (is (re-find #"impl/Dockerfile" instruction))
    (is (re-find #"node:22-alpine" instruction))
    (is (re-find #"no registry or package-index access" instruction))
    (is (re-find #"runtime guaranteed to exist" instruction))
    (is (re-find #"docker compose up.*--wait" instruction))
    (is (re-find #"runtime-evidence contract" instruction))
    (is (re-find #"every approved item" instruction))
    (is (re-find #"record counts" instruction))
    (is (re-find #"health-only suite is insufficient" instruction))
    (is (re-find #"exact external interfaces" instruction))
    (is (re-find #"wire field names exactly" instruction))
    (is (re-find #"percent-decode dynamic path parameters" instruction))
    (is (re-find #"percent-encode dynamic path-segment values" instruction))
    (is (re-find #"every scenario Then step" instruction))
    (is (re-find #"identity fields unchanged in every view" instruction))
    (is (re-find #"isolated initial state" instruction))
    (is (re-find #"overlap before either response" instruction))
    (is (re-find #"constructing the follow-up request from that response alone" instruction))
    (is (re-find #"camelCase aliases" instruction))
    (is (re-find #"syntax or compile check" instruction))
    (is (re-find #"zero discovered tests" instruction))))

(deftest rust-builder-instruction-cannot-fall-back-to-node-or-python
  (let [instruction (application-contract/builder-instruction
                     {:base-instruction "Build from the model."
                      :request-id "request-rust"
                      :scope ["impl"]
                      :target-stack "rust"
                      :model-root "model/loyly"
                      :selection approved-selection})]
    (is (re-find #"rust:1.91-alpine" instruction))
    (is (re-find #"\.rs" instruction))
    (is (not (re-find #"prefer node:22-alpine or python:3.12-alpine" instruction)))))

(deftest builder-result-identifies-the-generated-subject-by-content
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-subject-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        context {:inputs {:request-id "request-subject"
                          :cut "cut-1"
                          :scope ["impl"]
                          :execution-backend "gondolin"}}]
    (try
      (spit (java.io.File. root "app.txt") "first")
      (let [first-result (with-redefs [worker/run-builder-adapter
                                       (fn [_] {:output-root (.getAbsolutePath root)})]
                           (worker/builder-stage context))
            _ (spit (java.io.File. root "app.txt") "second")
            second-result (with-redefs [worker/run-builder-adapter
                                        (fn [_] {:output-root (.getAbsolutePath root)})]
                            (worker/builder-stage context))]
        (is (re-matches #"[0-9a-f]{64}" (:subject-digest first-result)))
        (is (not= (:subject-digest first-result) (:subject-digest second-result))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest instrumentation-binds-evidence-without-changing-the-candidate
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-instrument-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (spit (java.io.File. root "app.txt") "candidate")
      (let [digest (subject/digest (.getAbsolutePath root))
            calibration-golden {:schemaVersion 1
                                :name "full-harness-known-carried"
                                :fullName "full-pi-harness.full-harness-known-carried"
                                :status "passed" :stage "finished"
                                :labels {:fixture_id "full-harness-known-carried"
                                         :instrument_id "full-pi-harness"}
                                :parameters [{:name "role" :value "carried"}]
                                :attachments []}
            result (worker/instrument-stage
                    {:inputs {:request-id "request-instrument"
                              :selection approved-selection
                              :scope ["impl"]
                              :execution-backend "gondolin"
                              :harness-calibration-result
                              {:calibration-results (json/write-str [calibration-golden])}
                              :builder-result {:output-root (.getAbsolutePath root)
                                               :subject-digest digest}}})]
        (is (= digest (:subject-digest result)))
        (is (= digest (subject/digest (.getAbsolutePath root))))
        (is (= 1 (count (:evidence-locations result))))
        (is (not (.startsWith (first (:evidence-locations result))
                              (str (.getAbsolutePath root) "/"))))
        (let [results-root (java.io.File. (first (:evidence-locations result)))
              result-file (first (filter #(clojure.string/ends-with? (.getName %) "-result.json")
                                         (file-seq results-root)))]
          (is (= calibration-golden
                 (allure-golden/normalize-result results-root result-file)))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest instrumentation-refuses-a-changed-candidate
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-instrument-mismatch-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (spit (java.io.File. root "app.txt") "changed")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"subject digest changed"
           (worker/instrument-stage
            {:inputs {:request-id "request-instrument-mismatch"
                      :selection approved-selection
                      :scope ["impl"]
                      :execution-backend "gondolin"
                      :builder-result {:output-root (.getAbsolutePath root)
                                       :subject-digest "stale-digest"}}})))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest gondolin-graders-run-the-independent-behavior-probe
  (let [adapter-request (atom nil)
        context {:inputs {:request-id "request-independent"
                          :selection approved-selection
                          :scope ["impl"]
                          :execution-backend "gondolin"
                          :builder-result {:output-root "/tmp/loyly-independent-output"
                                           :subject-digest "subject-test"}
                          :oracle-result approved-oracle
                          :verify-result {:passed true}}}
        evidence {:independent-runtime {:exitCode 0 :stdout "" :stderr ""}
                  :independent-evidence {:exitCode 0 :stdout "" :stderr ""}
                  :independent-up {:exitCode 0 :stdout "healthy" :stderr ""}
                  :independent-grade {:exitCode 0
                                      :stdout "[{\"itemId\":\"01-007\",\"verdict\":\"pass\",\"evidence\":\"all views agree\"}]"
                                      :stderr ""}
                  :independent-logs {:exitCode 0 :stdout "ready" :stderr ""}
                  :independent-down {:exitCode 0 :stdout "removed" :stderr ""}}
        result (with-redefs [worker/run-grader-adapter
                             (fn [request] (reset! adapter-request request) evidence)
                             worker/emit-cycle-observation! (fn [& _] nil)]
                 (worker/graders-stage context))]
    (is (= "independent" (:mode @adapter-request)))
    (is (= "gondolin" (:execution-backend @adapter-request)))
    (is (= true (:passed result)))
    (is (= "loyly-black-box" (get-in result [:observations 0 :producer])))
    (is (= "grader" (get-in result [:observations 0 :evidence-kind])))
    (is (= 1 (get-in result [:measurement :total])))
    (is (= "@item-01-007" (get-in result [:measurement :items 0 :item-id])))
    (is (re-find #"01-007" (:evidence result)))))

(deftest instrument-conformance-is-independent-of-the-behavioral-grade
  (let [adapter-request (atom nil)
        context {:inputs {:request-id "request-instrument-conformance"
                          :selection approved-selection
                          :scope ["impl"]
                          :execution-backend "gondolin"
                          :builder-result {:output-root "/tmp/loyly-independent-output"
                                           :subject-digest "subject-test"}
                          :instrument-result {:evidence-root "/tmp/loyly-independent-output.evidence"
                                              :subject-digest "subject-test"}
                          :oracle-result approved-oracle
                          :graders-result {:passed false}}}
        result (with-redefs [worker/run-grader-adapter
                             (fn [request]
                               (reset! adapter-request request)
                               {:instrument-conformance
                                {:exitCode 0
                                 :stdout "[{\"itemId\":\"01-007\",\"verdict\":\"pass\",\"evidence\":\"correlated runtime facts\"}]"
                                 :stderr ""}})]
                 (worker/instrument-conformance-stage context))]
    (is (true? (:passed result)))
    (is (= "instrument-conformance" (:stage result)))
    (is (= "loyly" (:project-id @adapter-request)))
    (is (= ["@item-01-007"] (:item-ids @adapter-request)))
    (is (= "runtime-instrument-conformance" (get-in result [:observations 0 :producer])))
    (is (= "pass" (get-in result [:items 0 :verdict])))))

(deftest cycle-measurement-is-persisted-before-the-grade-gate
  (let [evidence-root (.toFile (java.nio.file.Files/createTempDirectory
                                "loyly-cycle-evidence-"
                                (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (doto (java.io.File. evidence-root "compose") .mkdirs)
        _ (doto (java.io.File. evidence-root "playwright/@item-01-007") .mkdirs)
        _ (doto (java.io.File. evidence-root "otel") .mkdirs)
        _ (spit (java.io.File. evidence-root "compose/compose-logs.stdout.txt") "ready")
        _ (spit (java.io.File. evidence-root "playwright/@item-01-007/trace.zip") "trace")
        _ (spit (java.io.File. evidence-root "otel/traces.json") "[]")
        recorded (atom nil)
        observations (atom [])
        order (atom [])
        grader-observation {:producer "loyly-black-box" :evidence-kind "grader"}
        instrument-observation {:producer "runtime-instrument-conformance"
                                :evidence-kind "grader"}
        measurement {:cycle-id "cycle-1" :selection-id "selection-1" :cut "cut-1"
                     :backend "gondolin" :total 1 :passed 0 :failed 1 :complete? false
                     :items [{:item-id "@item-01-007" :verdict "fail"
                              :evidence "not graded" :graded? false}]}
        inputs {:request-id "cycle-1"
                :selection approved-selection
                :scope ["impl"]
                :execution-backend "gondolin"
                :builder-result {:builder "openai-codex/test"}
                :instrument-result {:subject-digest "subject-test"
                                    :evidence-root (.getAbsolutePath evidence-root)}
                :oracle-result approved-oracle
                :graders-result {:measurement measurement
                                 :observations [grader-observation]}
                :instrument-conformance-result
                {:passed true :total 1 :passed-count 1
                 :items [{:item-id "@item-01-007" :verdict "pass"
                          :evidence "correlated runtime facts" :graded? true}]
                 :observations [instrument-observation]}}]
    (try
      (let [result (with-redefs [cycle-measurement/record!
                                 (fn [_ value]
                                   (swap! order conj :measurement-persisted)
                                   (reset! recorded value)
                                   value)
                                 observation/record!
                                 (fn [_ value]
                                   (swap! observations conj value)
                                   value)
                                 worker/emit-cycle-observation!
                                 (fn [_ _]
                                   (swap! order conj :allure-projected))]
                     (worker/record-cycle-stage {:inputs inputs}))
            manifest (:evidence-manifest result)
            artifacts (:artifacts manifest)]
        (is (= result @recorded))
        (is (= [grader-observation instrument-observation] @observations))
        (is (= [:measurement-persisted :allure-projected :allure-projected] @order))
        (is (true? (get-in result [:instrument-conformance :passed])))
        (is (= "subject-test" (:subject-digest manifest)))
        (is (= "digest-1" (:oracle-digest manifest)))
        (is (= #{"compose/compose-logs.stdout.txt"
                 "playwright/@item-01-007/trace.zip"
                 "otel/traces.json"}
               (set (map :path artifacts))))
        (is (every? #(re-matches #"[0-9a-f]{64}" (:sha256 %)) artifacts)))
      (finally
        (doseq [file (reverse (file-seq evidence-root))] (.delete file))))))

(deftest valid-poor-score-completes-the-cycle
  (is (= {:stage "result"
          :request-id "request-failed-grade"
          :cut "cut-1"
          :scope ["impl"]
          :execution-backend "gondolin"
          :passed false
          :verified? true
          :instrumented? false
          :score {:passed 0 :failed 1}}
         (worker/grade-gate-stage
          {:inputs {:request-id "request-failed-grade"
                    :selection approved-selection
                    :scope ["impl"]
                    :execution-backend "gondolin"
                    :verify-result {:passed true}
                    :instrument-conformance-result {:passed false}
                    :graders-result {:passed false
                                     :measurement {:passed 0 :failed 1}}}}))))

(deftest final-gate-requires-behavior-and-instrument-conformance
  (let [base {:request-id "request-gates"
              :selection approved-selection
              :scope ["impl"]
              :execution-backend "gondolin"
              :verify-result {:passed true}
              :graders-result {:passed true :measurement {:passed 1 :failed 0}}}]
    (is (false? (:passed (worker/grade-gate-stage
                          {:inputs (assoc base :instrument-conformance-result {:passed false})}))))
    (is (true? (:passed (worker/grade-gate-stage
                         {:inputs (assoc base :instrument-conformance-result {:passed true})}))))))
