(ns loyly.pi-harness.orc-worker-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ai.obney.orc.demos.pi-agent-loop.system :as system]
            [ai.obney.orc.orc-service.interface :as orc]
            [loyly.pi-harness.core.build.prompt :as prompt]
            [loyly.pi-harness.core.evidence.allure-golden :as allure-golden]
            [loyly.pi-harness.core.grading.compose :as compose]
            [loyly.pi-harness.grain.interface :as model]
            [loyly.pi-harness.core.oracle.harness-calibration :as harness-calibration]
            [loyly.pi-harness.core.worker :as worker]
            [loyly.pi-harness.core.build.stack-conformance :as stack-conformance]
            [loyly.pi-harness.core.build.subject :as subject]
            [loyly.pi-harness.workflows.slice-cycle :as slice-cycle-workflow]))

(def approved-selection
  {:selectionId "selection-1"
   :selectionDigest "digest-1"
   :status "approved"
   :projectId "loyly"
   :containerId "loyly"
   :componentId "booking-core"
   :cut "cut-1"
   :slice "01-booking-core"
   :items [{:id "@item-01-007"
            :scenario-digest "sha256:booking-scenario"
            :name "Booking" :rule "Booking" :source "spec.feature:1"}]})

(deftest persisted-prompt-executor-name-remains-resolvable
  (let [executor (requiring-resolve
                  'loyly.pi-harness.core.build.prompt/expose-candidate-instruction)]
    (is (= {:prompt "Build from the approved cut."}
           (executor {:node {:instruction "Build from the approved cut."}})))))

(def slice-cycle-stages
  ["cut" "harness-snapshot" "oracle" "builder" "instrument" "verify"
   "repair-verification" "affordance-conformance" "grade" "instrument-conformance"
   "repair-evaluation" "conclude"])

(deftest cut-preparation-refuses-an-invalid-identity-before-a-task-starts
  (let [tasks-before @@#'worker/tasks]
    (doseq [[request message] [[{:sourceCommit "abc" :executionBackend "host"} #"full lowercase 40-hex commit id"]
                               [{:sourceCommit (apply str (repeat 40 "z")) :executionBackend "host"} #"full lowercase 40-hex commit id"]
                               [{:sourceCommit (apply str (repeat 40 "0")) :executionBackend "host"} #"is not a commit in"]
                               [{:sourceCommit (apply str (repeat 40 "0")) :executionBackend "docker"} #"executionBackend must be host or gondolin"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo message (worker/prepare-cut! request))))
    (is (= tasks-before @@#'worker/tasks) "no cut task was started or recorded")))

(deftest a-host-backed-cycle-cannot-pass-as-independently-graded
  (with-redefs [worker/effective-verify-result (constantly {:passed true})
                worker/effective-builder-result (constantly {:output-root "/tmp/out"})
                worker/effective-instrument-result (constantly {:evidence-root "/tmp/evidence"})
                worker/project-adapter (constantly {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"independent comparison requires Gondolin: a host-backed cycle cannot pass"
                          (worker/grade-against-accepted-oracle
                           {:inputs {:execution-backend "host"
                                     :request-id "host-cycle"
                                     :selection {:items [{:id "@item-01-001"}] :cut "cut-1"}}})))))

(deftest the-cycle-budget-covers-a-slow-cycle-by-default
  (is (= (* 90 60 1000) worker/default-cycle-timeout-ms))
  (is (= worker/default-cycle-timeout-ms (worker/cycle-timeout-ms))))

(deftest a-replacement-worker-reconstructs-a-task-from-durable-state
  (let [tick-id #uuid "f36dc9b7-9b92-4e15-84f5-1b9c4b798416"]
    (with-redefs [worker/runtime-context (constantly {:durable true})
                  model/find-task-receipt (fn [_ task-id]
                                            (when (= 41 task-id)
                                              {:task-id 41 :tick-id (str tick-id) :cycle-id "cycle-41"}))
                  orc/get-tick (fn [_ id]
                                 (when (= id tick-id)
                                   {:root-status :failure :error "fence failed: a room must be fresh"}))
                  orc/execute (fn [& _] (throw (ex-info "a recovered task must never run again" {})))]
      (is (= {:status "failed" :tickId (str tick-id) :error "fence failed: a room must be fresh"}
             (worker/wait! {:taskId 41})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown task identity: 42"
                            (worker/wait! {:taskId 42}))))))

(deftest one-slice-cycle-exposes-the-specified-stage-order
  (let [contract (slice-cycle-workflow/contract)]
    (is (= "pi-harness-slice-cycle" (:workflowName contract)))
    (is (= ["fresh-generation" "measured-regeneration"] (:routes contract)))
    (is (= slice-cycle-stages (:stages contract)))))

(deftest the-oracle-is-settled-before-the-builder-starts
  (let [stages (:stages (slice-cycle-workflow/contract))]
    (is (< (.indexOf stages "oracle") (.indexOf stages "builder")))
    (is (< (.indexOf stages "harness-snapshot") (.indexOf stages "oracle")))))

(deftest harness-snapshot-stage-never-resolves-a-published-instrument-oracle
  (with-redefs [harness-calibration/calibrate!
                (fn [root]
                  (is (= "/repo" root))
                  {:passed true :harness-revision "33e537c"
                   :harness-snapshot-digest "sha256:snapshot"
                   :mismatches [] :actual {} :suites {}})
                worker/project-root (constantly "/repo")]
    (let [result (worker/harness-snapshot-stage
                  {:inputs {:request-id "fresh-1" :selection approved-selection
                            :scope ["impl"] :execution-backend "gondolin"}})]
      (is (true? (:passed result)))
      (is (= "harness-snapshot" (:stage result)))
      (is (= "sha256:snapshot" (:harness-snapshot-digest result))))))

(deftest failed-harness-calibration-names-the-failing-suite-and-its-record
  ;; ORC persists only the exception message, so it must identify what failed.
  (with-redefs [harness-calibration/calibrate!
                (constantly {:passed false :mismatches ["full-harness-known-carried"]
                             :harness-snapshot-digest "sha256:snapshot"
                             :failure-record "/state/harness-calibrations/snapshot.failed.edn"
                             :suites {:clojure-worker {:exit-code 1}
                                      :node-adapters {:exit-code 0}}})
                worker/project-root (constantly "/repo")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"full harness calibration failed: clojure-worker exit 1; details in /state/harness-calibrations/snapshot\.failed\.edn"
         (worker/calibrate-harness!)))))

(deftest affordance-conformance-preserves-a-flexible-independent-browser-verdict
  (let [request (atom nil)]
    (with-redefs [worker/baseline-grader-root (constantly "/judge")
                  worker/run-grader-adapter
                  (fn [value]
                    (reset! request value)
                    {:affordance-runtime {:exitCode 0}
                     :affordance-evidence {:exitCode 0}
                     :affordance-up {:exitCode 0}
                     :affordance-grade
                     {:exitCode 0
                      :stdout (json/write-str
                               {:passed false
                                :checks [{:id "screen:Home"
                                          :passed false
                                          :evidence "GET / returned JSON, not an operable page"
                                          :validator-extension "allowed"}]
                                :additional-validator-data {:layout "unconstrained"}})}
                     :affordance-down {:exitCode 0}})]
      (let [result (worker/affordance-conformance-stage
                    {:inputs {:request-id "generation-api-only"
                              :selection approved-selection
                              :scope ["impl"]
                              :target-stack "typescript"
                              :execution-backend "gondolin"
                              :builder-result {:output-root "/candidate"}
                              :instrument-result {:evidence-root "/evidence"}}})]
        (is (= "affordance" (:mode @request)))
        (is (= "loyly" (:project-id @request)))
        (is (false? (:passed result)))
        (is (= 1 (:total result)))
        (is (= 0 (:passed-count result)))))))

(def approved-oracle
  {:passed true :kind "accepted-oracle" :oracle-digest "digest-1"
   :grader-root "/oracle/grader"
   :grader-producer "loyly-black-box" :grader-id "booking-core-v1"})

(deftest runtime-start-asks-orc-to-resume-durable-frontiers
  (let [context {:durable true}
        resume-calls (atom [])]
    (with-redefs [orc/build-workflow! (fn [_ _] (random-uuid))
                  orc/resume-in-progress! (fn [actual-context]
                                            (swap! resume-calls conj actual-context)
                                            [])]
      (try
        (worker/start-runtime! (constantly {:context context}))
        (is (= [context] @resume-calls))
        (finally
          (worker/stop-runtime! (constantly nil)))))))

(deftest execution-status-comes-directly-from-the-orc-read-model
  (let [tick-id #uuid "f36dc9b7-9b92-4e15-84f5-1b9c4b798416"
        sheet-id #uuid "952b704e-c4e6-44aa-859c-b54594f1f06d"
        correlation-id #uuid "46ec162b-2539-4964-aea0-9d06552e7e07"]
    (with-redefs [worker/runtime-context (constantly {:durable true})
                  orc/get-tick (fn [_ requested-tick-id]
                                 (is (= tick-id requested-tick-id))
                                 {:tick-id tick-id
                                  :sheet-id sheet-id
                                  :correlation-id correlation-id
                                  :status :completed
                                  :root-status :success})]
      (is (= {:tickId (str tick-id)
              :sheetId (str sheet-id)
              :correlationId (str correlation-id)
              :status "completed"
              :rootStatus "success"}
             (worker/execution-status! {:tickId (str tick-id)}))))))

(deftest execution-status-reconciles-durable-stage-and-builder-session-progress
  (let [tick-id #uuid "f36dc9b7-9b92-4e15-84f5-1b9c4b798416"
        sheet-id #uuid "952b704e-c4e6-44aa-859c-b54594f1f06d"
        context {:durable true :cache ::possibly-stale-projection-cache}]
    (with-redefs [worker/runtime-context (constantly context)
                  orc/get-tick (constantly {:id tick-id
                                            :sheet-id sheet-id
                                            :status :running})
                  model/find-cycle-progress
                  (fn [actual-context actual-tick-id]
                    (is (= (dissoc context :cache) actual-context))
                    (is (= (str tick-id) actual-tick-id))
                    {:stage "builder"
                     :attempt 2
                     :builder-session-ref "builder-session-02"
                     :status "running"})]
      (is (= {:tickId (str tick-id)
              :sheetId (str sheet-id)
              :status "running"
              :stage "builder"
              :attempt 2
              :builderSessionRef "builder-session-02"}
             (worker/execution-status! {:tickId (str tick-id)}))))))

(deftest builder-session-rpc-is-a-read-only-adapter
  (let [view {:builderSessionRef "builder-session-38"
              :status "completed"
              :entries [{:type "message"}]
              :toolCalls 2
              :workspaceGeneration ["src"]
              :progress {:stage "builder" :attempt 1}}]
    (with-redefs [worker/run-builder-session-adapter
                  (fn [builder-session-ref]
                    (is (= "builder-session-38" builder-session-ref))
                    view)]
      (is (= view
             (worker/builder-session!
              {:builderSessionRef "builder-session-38"}))))))

(deftest builder-publishes-its-session-reference-before-execution
  (let [tick-id (random-uuid)
        request (atom nil)
        context {:tick-id tick-id
                 :inputs {:request-id "cycle-37"
                          :selection approved-selection
                          :scope ["impl"]
                          :target-stack "typescript"
                          :execution-backend "gondolin"}}]
    (with-redefs [worker/builder-sessions-root (constantly "/state/builder-sessions")
                  worker/run-builder-adapter
                  (fn [value]
                    (reset! request value)
                    {:output-root "/state/output"
                     :builder-session-id (:builder-session-ref value)})
                  subject/digest (constantly "subject-digest")]
      (let [result (worker/builder-stage context)]
        (is (= "/state/builder-sessions" (:builder-sessions-root @request)))
        (is (= (:builder-session-ref @request) (:builder-session-id result)))))))

(deftest approved-slice-routes-to-initial-generation-without-prior-lineage
  (let [executed (promise)]
    (worker/start-runtime! system/start!)
    (try
      (with-redefs [model/find-latest-candidate (fn [_ & _] nil)
                    orc/execute (fn [_ sheet-id inputs & _]
                                  (deliver executed {:sheet-id sheet-id :inputs inputs})
                                  {:status :success})]
        (let [request {:requestId "route-generation-1"
                       :selectionContract approved-selection
                       :scope ["impl"] :targetStack "typescript"
                       :executionBackend "gondolin"}
              receipt (worker/submit-approved-slice! request)
              repeated (worker/submit-approved-slice! request)]
          (is (= receipt repeated))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"different approved-slice payload"
               (worker/submit-approved-slice! (assoc request :scope ["different"]))))
          (is (= "fresh-generation" (:operation receipt)))
          (is (= "fresh-generation"
                 (:operation (model/find-approved-slice-route
                              (worker/runtime-context) "route-generation-1"))))
          (is (instance? java.util.UUID (:sheet-id (deref executed 10000 nil))))
          (is (= "fresh-generation" (get-in (deref executed 10000 nil) [:inputs :route])))
          (is (not (contains? (:inputs (deref executed 10000 nil)) :parent-candidate-root)))))
      (finally (worker/stop-runtime! system/stop!)))))

(deftest approved-slice-routes-to-regeneration-from-latest-candidate
  (let [executed (promise)
        prior {:candidate-id "candidate-previous"
               :output-root "/state/candidates/previous"
               :subject-digest "sha256:parent"}]
    (worker/start-runtime! system/start!)
    (try
      (with-redefs [model/find-latest-candidate (fn [_ & _] prior)
                    orc/execute (fn [_ sheet-id inputs & _]
                                  (deliver executed {:sheet-id sheet-id :inputs inputs})
                                  {:status :success})]
        (let [receipt (worker/submit-approved-slice!
                       {:requestId "route-regeneration-1"
                        :selectionContract approved-selection
                        :scope ["impl"] :targetStack "typescript"
                        :executionBackend "gondolin"})]
          (is (= "measured-regeneration" (:operation receipt)))
          (is (= "candidate-previous"
                 (:parent-candidate-id
                  (model/find-approved-slice-route
                   (worker/runtime-context) "route-regeneration-1"))))
          (is (instance? java.util.UUID (:sheet-id (deref executed 10000 nil))))
          (is (= "measured-regeneration" (get-in (deref executed 10000 nil) [:inputs :route])))
          (is (= "candidate-previous"
                 (get-in (deref executed 10000 nil) [:inputs :parent-candidate-id])))
          (is (= "/state/candidates/previous"
                 (get-in (deref executed 10000 nil) [:inputs :parent-candidate-root])))
          (is (= "sha256:parent"
                 (get-in (deref executed 10000 nil) [:inputs :parent-candidate-digest])))))
      (finally (worker/stop-runtime! system/stop!)))))

(deftest fresh-generation-rejects-a-parent-candidate
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"fresh generation must not carry a parent candidate"
       (#'worker/submit-slice-cycle!
        {:requestId "generation-1"
         :selectionContract approved-selection
         :scope ["impl"]
         :targetStack "typescript"
         :executionBackend "gondolin"
         :parentCandidateRoot "/state/candidate-before"
         :parentCandidateDigest "sha256:before"}
        "fresh-generation"))))

(deftest measured-regeneration-requires-a-parent-candidate
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"measured regeneration requires a persisted parent candidate"
       (#'worker/submit-slice-cycle!
        {:requestId "regeneration-1"
         :selectionContract approved-selection
         :scope ["impl"]
         :targetStack "typescript"
         :executionBackend "gondolin"}
        "measured-regeneration"))))

(deftest fresh-slice-cycle-seals-its-oracle-then-proposes-it
  (let [runtime (system/start!)]
    (try
      (let [context (:context runtime)
            sheet-id (orc/build-workflow! context (worker/workflow))
            result (with-redefs [worker/baseline-grader-root (constantly "/judge")
                                 worker/ensure-cycle-oracle!
                                 (constantly {:grader-root "/judge"
                                              :binding-digest "sha256:binding"
                                              :calibration {:cases []}})
                                 worker/harness-snapshot-stage
                                 (fn [{:keys [inputs]}]
                                   {:stage "harness-snapshot"
                                    :request-id (:request-id inputs)
                                    :cut (get-in inputs [:selection :cut])
                                    :scope (:scope inputs)
                                    :execution-backend (:execution-backend inputs)
                                    :passed true
                                    :harness-revision "33e537c"
                                    :harness-snapshot-digest "sha256:snapshot"
                                    :calibration-results "{}"
                                    :evidence "{}"})
                                 worker/author-cycle-oracle
                                 (fn [{:keys [inputs]}]
                                   {:stage "cycle-oracle"
                                    :request-id (:request-id inputs)
                                    :cut (get-in inputs [:selection :cut])
                                    :scope (:scope inputs)
                                    :execution-backend (:execution-backend inputs)
                                    :passed true :grader-root "/judge"
                                    :cycle-oracle-digest "sha256:cycle-oracle"
                                    :binding-digest "sha256:binding"
                                    :author-session-id "grader-session"
                                    :evidence "{}"})
                                 worker/run-builder-adapter
                                 (fn [_] {:output-root "/tmp/generated-candidate"
                                          :builder-session-id "generation-session"})
                                 subject/digest (constantly "generation-subject")
                                 worker/run-grader-adapter
                                 (fn [{:keys [mode]}]
                                   (case mode
                                     "affordance"
                                     {:affordance-runtime {:exitCode 0}
                                      :affordance-evidence {:exitCode 0}
                                      :affordance-up {:exitCode 0}
                                      :affordance-grade {:exitCode 0 :stdout "{\"passed\":true,\"checks\":[{\"id\":\"screen:Home\",\"passed\":true}]}"}
                                      :affordance-down {:exitCode 0}}

                                     "compose"
                                     {:compose-config {:exitCode 0 :stdout "{\"services\":{\"app\":{\"healthcheck\":{}},\"test\":{}}}" :stderr ""}
                                      :compose-services {:exitCode 0 :stdout "app\ntest\n" :stderr ""}
                                      :compose-test {:exitCode 0 :stdout "tests passed" :stderr ""}
                                      :compose-up {:exitCode 0 :stdout "healthy" :stderr ""}
                                      :compose-logs {:exitCode 0 :stdout "ready" :stderr ""}
                                      :compose-down {:exitCode 0 :stdout "removed" :stderr ""}}))
                                 stack-conformance/check
                                 (fn [& _] {:passed true :exit-code 0 :stdout "stack ok" :stderr ""})
                                 worker/grade-with-cycle-oracle
                                 (fn [{:keys [inputs]}]
                                   {:stage "baseline-grade"
                                    :request-id (:request-id inputs)
                                    :cut (get-in inputs [:selection :cut])
                                    :scope (:scope inputs)
                                    :execution-backend (:execution-backend inputs)
                                    :passed true
                                    :judge-id "sha256:judge"
                                    :grader-root "/judge"
                                    :grader-producer "independent-baseline-judge"
                                    :measurement {:cycle-id (:request-id inputs)
                                                  :selection-id "selection-1"
                                                  :cut "cut-1" :backend "gondolin"
                                                  :selected 1 :total 1 :passed 1 :failed 0
                                                  :complete? true
                                                  :items [{:item-id "@item-01-007"
                                                           :verdict "pass"
                                                           :evidence "observed"
                                                           :graded? true}]}
                                    :normalized-evidence-digest "sha256:evidence"
                                    :evidence "{}"})
                                 worker/instrument-conformance-stage
                                 (fn [{:keys [inputs]}]
                                   {:stage "instrument-conformance"
                                    :request-id (:request-id inputs)
                                    :cut (get-in inputs [:selection :cut])
                                    :scope (:scope inputs)
                                    :execution-backend (:execution-backend inputs)
                                    :passed true :total 1 :passed-count 1
                                    :items [{:item-id "@item-01-007" :verdict "pass"
                                             :evidence "observed" :graded? true}]
                                    :observations [] :evidence "{}"})]
                     (orc/execute context sheet-id
                                  {:request-id "generation-1"
                                   :route "fresh-generation"
                                   :selection approved-selection
                                   :scope ["impl"]
                                   :target-stack "typescript"
                                   :execution-backend "gondolin"}))
            outputs (:outputs result)]
        (is (= :success (:status result)) (pr-str result))
        (is (= "cycle-oracle" (get-in outputs [:oracle-result :kind])))
        (is (= "oracle" (get-in outputs [:oracle-result :stage])))
        (is (true? (get-in outputs [:repair-evaluation-result :applicable])))
        (is (true? (get-in outputs [:conclude-result :passed])))
        (is (not (contains? (:conclude-result outputs) :gate))))
      (finally
        (system/stop! runtime)))))

(deftest baseline-grade-authors-and-calibrates-a-fenced-grader-without-candidate-access
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-grader-author-test"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        fixture (java.io.File. root "cut-1/model/loyly/fixtures/oracle-fixture-server.mjs")
        author-request (atom nil)
        fixture-request (atom nil)
        inputs {:execution-backend "gondolin"
                :selection (assoc approved-selection :selectionDigest "sha256:digest-1")
                :harness-snapshot-result {:harness-snapshot-digest "sha256:instrument"}}]
    (.mkdirs (.getParentFile fixture))
    (spit fixture "fixture")
    (with-redefs [worker/project-root (constantly "/repo")
                  worker/cuts-root (constantly (.getPath root))
                  worker/builder-sessions-root (constantly "/sessions")
                  worker/run-grader-author-adapter
                  (fn [request]
                    (reset! author-request request)
                    {:graderRoot "/sealed/grader" :sessionId "grader-session" :reused false})
                  worker/run-oracle-fixture-adapter
                  (fn [request]
                    (reset! fixture-request request)
                    {:cases [{:itemId "@item-01-007" :role "carried" :exitCode 0
                              :stdout "[{\"itemId\":\"01-007\",\"verdict\":\"pass\",\"evidence\":\"carried\"}]"}
                             {:itemId "@item-01-007" :role "uncarried" :exitCode 1
                              :stdout "[{\"itemId\":\"01-007\",\"verdict\":\"fail\",\"evidence\":\"uncarried\"}]"}]})]
      (let [result (worker/ensure-cycle-oracle! inputs)]
        (is (= "/sealed/grader" (:grader-root result)))
        (is (not (contains? @author-request :candidateRoot)))
        (is (not (contains? @author-request :evidenceRoot)))
        (is (= ["@item-01-007"] (:itemIds @author-request)))
        (is (= "sha256:instrument" (:harnessSnapshotDigest @author-request)))
        (is (= "sha256:digest-1" (:selectionDigest @author-request)))
        (is (re-find #"carried.*must pass" (:prompt @author-request)))
        (is (re-find #"context.close.*no dispose" (:prompt @author-request)))
        (is (= ["carried" "uncarried"] (mapv :role (:cases @fixture-request))))))))

(defn- cycle-oracle-calibration-cases [carried-verdict]
  [{:itemId "@item-01-007" :role "carried" :exitCode (if (= "pass" carried-verdict) 0 1)
    :stdout (str "[{\"itemId\":\"01-007\",\"verdict\":\"" carried-verdict "\",\"evidence\":\"carried\"}]")}
   {:itemId "@item-01-007" :role "uncarried" :exitCode 1
    :stdout "[{\"itemId\":\"01-007\",\"verdict\":\"fail\",\"evidence\":\"uncarried\"}]"}])

(defn- with-cycle-oracle-authoring [carried-verdicts f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-grader-reauthor-test"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        fixture (java.io.File. root "cut-1/model/loyly/fixtures/oracle-fixture-server.mjs")
        author-requests (atom [])
        verdicts (atom carried-verdicts)
        inputs {:execution-backend "gondolin"
                :selection (assoc approved-selection :selectionDigest "sha256:digest-1")
                :harness-snapshot-result {:harness-snapshot-digest "sha256:instrument"}}]
    (.mkdirs (.getParentFile fixture))
    (spit fixture "fixture")
    (with-redefs [worker/project-root (constantly (.getPath root))
                  worker/cuts-root (constantly (.getPath root))
                  worker/builder-sessions-root (constantly "/sessions")
                  worker/run-grader-author-adapter
                  (fn [request]
                    (swap! author-requests conj request)
                    (.mkdirs (io/file (:outputRoot request)))
                    (spit (io/file (:outputRoot request) "grade.mjs") "grader")
                    {:graderRoot (:outputRoot request) :sessionId "grader-session" :reused false})
                  worker/run-oracle-fixture-adapter
                  (fn [_]
                    (let [verdict (first @verdicts)]
                      (swap! verdicts rest)
                      {:cases (cycle-oracle-calibration-cases verdict)}))]
      (f inputs author-requests))))

(deftest uncalibrated-cycle-oracle-is-discarded-and-reauthored
  (with-cycle-oracle-authoring
    ["fail" "pass"]
    (fn [inputs author-requests]
      (let [result (worker/ensure-cycle-oracle! inputs)
            [first-request second-request] @author-requests]
        (is (= 2 (:authoring-attempts result)))
        (is (= 2 (count @author-requests)))
        (is (not (.exists (io/file (:outputRoot first-request)))))
        (is (.exists (io/file (:outputRoot second-request))))
        (is (= (:outputRoot second-request) (:grader-root result)))
        (is (not= (:bindingDigest first-request) (:bindingDigest second-request)))
        (is (not (re-find #"failed public calibration" (:prompt first-request))))
        (is (re-find #"failed public calibration" (:prompt second-request)))
        (is (re-find #"@item-01-007.*carried.*\"expected\":\"pass\"" (:prompt second-request)))))))

(deftest cycle-fails-after-three-uncalibrated-cycle-oracles
  (with-cycle-oracle-authoring
    ["fail" "fail" "fail" "pass"]
    (fn [inputs author-requests]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"failed carried/uncarried calibration after 3 attempts: @item-01-007 carried"
           (worker/ensure-cycle-oracle! inputs)))
      (is (= 3 (count @author-requests)))
      (is (every? #(not (.exists (io/file (:outputRoot %)))) @author-requests)))))

(deftest baseline-grade-normalizes-the-complete-selected-denominator
  (let [evidence-root (.toFile (java.nio.file.Files/createTempDirectory
                                "loyly-playwright-trace-test"
                                (make-array java.nio.file.attribute.FileAttribute 0)))
        trace (java.io.File. evidence-root
                             "cycles/generation-graded/playwright/baseline/trace.zip")]
    (.mkdirs (.getParentFile trace))
    (spit trace "genuine trace fixture")
    (with-redefs [worker/baseline-grader-root (constantly "/judge")
                worker/ensure-cycle-oracle!
                (constantly {:grader-root "/judge"
                             :binding-digest "sha256:binding"
                             :calibration {:cases []}})
                subject/digest (constantly "judge-digest")
                worker/run-grader-adapter
                (fn [request]
                  (is (= "baseline" (:mode request)))
                  {:baseline-runtime {:exitCode 0}
                   :baseline-evidence {:exitCode 0}
                   :baseline-up {:exitCode 0}
                   :baseline-observe-start {:exitCode 0}
                   :baseline-grade
                   {:exitCode 0
                    :stdout "[{\"itemId\":\"01-007\",\"verdict\":\"pass\",\"evidence\":\"public response observed\"}]"}
                   :baseline-observe-stop {:exitCode 0}
                   :baseline-down {:exitCode 0}})]
    (let [result (worker/grade-with-cycle-oracle
                  {:inputs {:request-id "generation-graded"
                            :oracle-result {:grader-root "/judge"
                                            :binding-digest "sha256:binding"}
                            :selection approved-selection
                            :scope ["impl"]
                            :target-stack "typescript"
                            :execution-backend "gondolin"
                            :repair-verification-result
                            {:verify {:passed true}
                             :builder {:output-root "/candidate"
                                       :subject-digest "candidate-digest"}
                             :instrument {:evidence-root (.getPath evidence-root)
                                          :subject-digest "candidate-digest"}}}})]
      (is (true? (:passed result)))
      (is (= "playwright/baseline/trace.zip"
             (get-in result [:playwright-trace :path])))
      (is (= "c02f235c5bcb7aa16738c113eb7fff23b2d062a2e7faba80d0fa798b503b9352"
             (get-in result [:playwright-trace :sha256])))
      (is (= "sha256:judge-digest" (:judge-id result)))
      (is (= {:total 1 :passed 1 :failed 0 :complete? true}
             (select-keys (:measurement result)
                          [:total :passed :failed :complete?])))
      (is (string? (:normalized-evidence-digest result)))))))

(deftest baseline-grade-cannot-pass-without-a-playwright-trace
  (with-redefs [worker/baseline-grader-root (constantly "/judge")
                worker/ensure-cycle-oracle!
                (constantly {:grader-root "/judge"
                             :binding-digest "sha256:binding"
                             :calibration {:cases []}})
                subject/digest (constantly "judge-digest")
                worker/run-grader-adapter
                (constantly
                 {:baseline-runtime {:exitCode 0}
                  :baseline-evidence {:exitCode 0}
                  :baseline-up {:exitCode 0}
                  :baseline-observe-start {:exitCode 0}
                  :baseline-grade
                  {:exitCode 0
                   :stdout "[{\"itemId\":\"01-007\",\"verdict\":\"pass\",\"evidence\":\"observed\"}]"}
                  :baseline-observe-stop {:exitCode 0}
                  :baseline-down {:exitCode 0}})]
    (let [result (worker/grade-with-cycle-oracle
                  {:inputs {:request-id "generation-without-trace"
                            :oracle-result {:grader-root "/judge"
                                            :binding-digest "sha256:binding"}
                            :selection approved-selection
                            :scope ["impl"]
                            :target-stack "typescript"
                            :execution-backend "gondolin"
                            :repair-verification-result
                            {:verify {:passed true}
                             :builder {:output-root "/candidate"
                                       :subject-digest "candidate-digest"}
                             :instrument {:evidence-root "/missing-evidence"
                                          :subject-digest "candidate-digest"}}}})]
      (is (false? (:passed result)))
      (is (nil? (:playwright-trace result))))))

(deftest measured-slice-cycle-resolves-the-accepted-oracle-and-records-the-gated-cycle
  (let [runtime (system/start!)]
    (try
      (let [context (:context runtime)
            sheet-id (orc/build-workflow! context (worker/workflow))
            result (with-redefs [worker/project-root (constantly "/tmp")
                                  worker/instrument-oracle-ref (constantly "instrument-oracle-test")
                                  harness-calibration/calibrate!
                                  (fn [& _] {:passed true
                                             :harness-revision "33e537c"
                                             :harness-snapshot-digest "harness-snapshot-test"
                                             :mismatches [] :suites {}})
                                  worker/run-oracle (constantly {:passed true :oracle-digest "oracle-test"
                                                                 :grader-root "/oracle/grader"
                                                                 :artifacts [] :stdout "ok" :stderr ""})
                                  worker/project-adapter (constantly {:productOracleRef "ghcr.io/scope-impact/loyly/product-oracle@sha256:e328f56447a5eb888e958da29fb90a4e2500adf86463249f2d69738df4a021a6"
                                                                      :graderProducer "loyly-black-box"
                                                                      :graderId "booking-core-v1"
                                                                      :runtimeEvidenceContract "model/loyly/runtime-evidence-contract.json"
                                                                      :itemIdPrefix "@item-"
                                                                      :itemGraderScript "booking-core-{itemSuffix}.sh"})
                                  worker/run-builder-adapter (fn [_] {:output-root "/tmp/output"
                                                                      :builder-session-id "builder-session-test"})
                                  subject/digest (constantly "subject-test")
                                  stack-conformance/check (fn [& _] {:passed true :exit-code 0
                                                                     :stdout "confirmed" :stderr ""})
                                  worker/emit-cycle-observation! (fn [& _] nil)
                                  model/record-generated-candidate! (fn [_ candidate] candidate)
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

                                                                "affordance"
                                                                {:affordance-runtime {:exitCode 0}
                                                                 :affordance-evidence {:exitCode 0}
                                                                 :affordance-up {:exitCode 0}
                                                                 :affordance-grade {:exitCode 0 :stdout "{\"passed\":true,\"checks\":[{\"id\":\"screen:Home\",\"passed\":true}]}"}
                                                                 :affordance-down {:exitCode 0}}

                                                                "compose"
                                                                {:compose-config {:exitCode 0 :stdout "{\"services\":{\"app\":{\"healthcheck\":{\"test\":[\"CMD\",\"true\"]}},\"test\":{}}}" :stderr ""}
                                                                 :compose-services {:exitCode 0 :stdout "app\ntest\n" :stderr ""}
                                                                 :compose-test {:exitCode 0 :stdout "tests passed" :stderr ""}
                                                                 :compose-up {:exitCode 0 :stdout "healthy" :stderr ""}
                                                                 :compose-logs {:exitCode 0 :stdout "ready" :stderr ""}
                                                                 :compose-down {:exitCode 0 :stdout "removed" :stderr ""}}))
                                  model/record-observation! (fn [_ value] value)
                                  model/record-measurement! (fn [_ measurement] measurement)
                                  worker/author-cycle-oracle
                                  (fn [& _] (throw (ex-info "measured regeneration must never author an oracle" {})))
                                  worker/run-grader-author-adapter
                                  (fn [& _] (throw (ex-info "measured regeneration must never author an oracle" {})))]
                     (orc/execute context sheet-id
                                  {:request-id "request-1"
                                   :route "measured-regeneration"
                                   :parent-candidate-id "candidate-previous"
                                   :parent-candidate-root "/state/candidates/previous"
                                   :parent-candidate-digest "subject-test"
                                   :selection approved-selection
                                   :scope ["impl"]
                                   :target-stack "typescript"
                                   :execution-backend "gondolin"}))]
        (is (= :success (:status result)) (pr-str result))
        (is (= ["cut" "harness-snapshot" "oracle" "builder" "instrument" "verify"
                "repair-verification" "affordance-conformance" "grade"
                "instrument-conformance" "repair-evaluation" "conclude"]
               (mapv #(get-in (:outputs result) [% :stage])
                     [:cut-result :harness-snapshot-result :oracle-result :builder-result
                      :instrument-result :verify-result :repair-verification-result
                      :affordance-conformance-result :grade-result
                      :instrument-conformance-result :repair-evaluation-result
                      :conclude-result])))
        (is (= "accepted-oracle" (get-in result [:outputs :oracle-result :kind])))
        (is (= "harness-snapshot-test"
               (get-in result [:outputs :harness-snapshot-result :harness-snapshot-digest])))
        (is (false? (get-in result [:outputs :repair-evaluation-result :applicable])))
        (is (= 1 (get-in result [:outputs :repair-evaluation-result :rounds])))
        (is (true? (get-in result [:outputs :conclude-result :gate :instrumented?])))
        (is (= 1 (get-in result [:outputs :conclude-result :cycle :total])))
        (is (not (contains? (get-in result [:outputs :conclude-result]) :oracle-proposal))))
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
                          :affordance-conformance-result {:passed true}}}
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
    (is (nil? (:grader-root @adapter-request)))
    (is (nil? (:observability-root @adapter-request)))
    (is (= true (:passed result)))
    (is (re-find #"tests passed" (:evidence result)))
    (is (re-find #"app ready" (:evidence result)))))

(deftest verification-is-limited-to-the-fenced-build-contract
  (let [context {:inputs {:request-id "request-api-only"
                          :selection approved-selection
                          :scope ["impl"]
                          :target-stack "typescript"
                          :execution-backend "gondolin"
                          :builder-result {:output-root "/tmp/api-only"}
                          :affordance-conformance-result
                          {:passed false :total 1 :passed-count 0}}}
        compose-evidence {:compose-config {:exitCode 0 :stdout "{\"services\":{\"app\":{\"healthcheck\":{}},\"test\":{}}}"}
                          :compose-services {:exitCode 0 :stdout "app\ntest\n"}
                          :compose-test {:exitCode 0 :stdout "tests passed"}
                          :compose-up {:exitCode 0 :stdout "healthy"}
                          :compose-down {:exitCode 0 :stdout "removed"}}
        result (with-redefs [worker/run-grader-adapter (constantly compose-evidence)
                             stack-conformance/check (constantly {:passed true})]
                 (worker/verify-stage context))]
    (is (true? (:passed result)))
    (is (not (re-find #"affordance" (:evidence result))))))

(deftest affordance-conformance-passes-when-the-contract-does-not-apply-to-the-slice
  (let [request (atom nil)
        result (with-redefs [worker/baseline-grader-root (constantly "/judge")
                             worker/run-grader-adapter
                             (fn [value]
                               (reset! request value)
                               {:affordance-runtime {:exitCode 0}
                                :affordance-evidence {:exitCode 0}
                                :affordance-up {:exitCode 0}
                                :affordance-grade
                                {:exitCode 0
                                 :stdout "{\"passed\":true,\"applicable\":false,\"checks\":[]}"}
                                :affordance-down {:exitCode 0}})]
                 (worker/affordance-conformance-stage
                  {:inputs {:request-id "request-no-ui"
                            :selection (assoc approved-selection :slice "02-ledger")
                            :scope ["impl"]
                            :target-stack "typescript"
                            :execution-backend "gondolin"
                            :builder-result {:output-root "/candidate"}
                            :instrument-result {:evidence-root "/evidence"}}}))]
    (is (= "02-ledger" (:slice @request)))
    (is (true? (:passed result)))
    (is (false? (:applicable result)))
    (is (zero? (:total result)))))

(deftest failed-verification-is-repaired-from-the-shared-evidence-directory
  (let [builder-call (atom nil)
        context {:inputs {:request-id "request-repair"
                          :selection approved-selection
                          :scope ["impl"]
                          :target-stack "rust"
                          :execution-backend "gondolin"
                          :harness-snapshot-result {:passed true}
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
                             worker/affordance-conformance-stage
                             (fn [_] {:stage "affordance-conformance"
                                      :passed true :total 1 :passed-count 1
                                      :evidence "green"})
                             worker/verify-stage
                             (fn [_] {:stage "verify" :passed true :evidence "green"})]
                 (worker/repair-verification-stage context))]
    (is (true? (:passed result)))
    (is (= 2 (:rounds result)))
    (is (= "/state/request-repair" (:initial-workspace-root @builder-call)))
    (is (= "/state/request-repair.evidence" (:verification-evidence-root @builder-call)))
    (is (= 2 (:round @builder-call)))
    (is (= "/state/request-repair-repair-02" (get-in result [:builder :output-root])))
    (is (nil? (:affordance result)))
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
           (worker/grade-against-accepted-oracle context))))
    (is (false? @called?))))

(deftest fresh-evaluation-sample-gets-its-own-bounded-verification-repair
  (let [builder-calls (atom [])
        verify-calls (atom 0)
        grade-builder (atom nil)
        context {:inputs {:request-id "generation-conformance-repair"
                          :selection approved-selection
                          :scope ["impl"]
                          :target-stack "typescript"
                          :execution-backend "gondolin"
                          :builder-result {:output-root "/state/candidate"
                                           :subject-digest "subject-one"}
                          :instrument-result {:evidence-root "/state/candidate.evidence"
                                              :subject-digest "subject-one"}
                          :affordance-conformance-result {:passed true}
                          :verify-result {:passed true}
                          :grade-result {:passed true}
                          :instrument-conformance-result {:passed false}
                          :route "fresh-generation"}}
        result (with-redefs [worker/run-builder-adapter
                             (fn [request]
                               (let [call (swap! builder-calls conj request)
                                     repair? (= 2 (count call))]
                                 {:output-root (if repair?
                                                 "/state/fresh-sample-repair-02"
                                                 "/state/fresh-sample")
                                  :builder "openai-codex/test"}))
                             subject/digest (constantly "subject-two")
                             worker/instrument-stage
                             (fn [{:keys [inputs]}]
                               (let [output-root (get-in inputs [:builder-result :output-root])]
                                 {:evidence-root (str output-root ".evidence")
                                  :subject-digest "subject-two"}))
                             worker/affordance-conformance-stage
                             (fn [_] {:passed true :applicable true :total 1
                                      :passed-count 1 :evidence "green"})
                             worker/verify-stage
                             (fn [_]
                               {:passed (> (swap! verify-calls inc) 1)})
                             worker/grade-with-cycle-oracle
                             (fn [{:keys [inputs]}]
                               (reset! grade-builder
                                       (get-in inputs [:repair-verification-result
                                                       :builder :output-root]))
                               {:passed true})
                             worker/instrument-conformance-stage (fn [_] {:passed true})]
                 (worker/repair-evaluation-stage context))]
    (is (true? (:passed result)))
    (is (= 2 (:rounds result)))
    (is (= 2 (count @builder-calls)))
    (is (nil? (:initial-workspace-root (first @builder-calls))))
    (is (nil? (:verification-evidence-root (first @builder-calls))))
    (is (= "/state/fresh-sample"
           (:initial-workspace-root (second @builder-calls))))
    (is (= "/state/fresh-sample.evidence"
           (:verification-evidence-root (second @builder-calls))))
    (is (= 2 (:round (first @builder-calls))))
    (is (= 2 (:round (second @builder-calls))))
    (is (= "/state/fresh-sample-repair-02" @grade-builder))
    (is (= "subject-two" (get-in result [:builder :subject-digest])))
    (is (true? (get-in result [:instrument-conformance :passed])))))

(deftest oracle-stage-requires-the-accepted-project-oci-pack
  (let [call (atom nil)
        result (with-redefs [worker/project-root (constantly "/repo")
                             worker/project-adapter
                             (constantly {:productOracleRef (str "ghcr.io/scope-impact/loyly/product-oracle@"
                                                                 "sha256:e328f56447a5eb888e958da29fb90a4e2500adf86463249f2d69738df4a021a6")
                                          :graderProducer "loyly-black-box"
                                          :graderId "booking-core-v1"})
                             worker/run-oracle
                             (fn [cut selection options]
                               (reset! call {:cut cut :selection selection :options options})
                               {:passed true :oracle-digest "product-contract"
                                :grader-root "/oracle/grader"
                                :artifacts [] :stdout "ok" :stderr ""})]
                 (worker/resolve-accepted-oracle
                  {:inputs {:request-id "request-oracle"
                            :selection approved-selection
                            :scope ["impl"]
                            :execution-backend "gondolin"
                            :harness-snapshot-result
                            {:instrument-oracle-digest "instrument-oracle"}}}))]
    (is (= "cut-1" (:cut @call)))
    (is (= (str "ghcr.io/scope-impact/loyly/product-oracle@"
                "sha256:e328f56447a5eb888e958da29fb90a4e2500adf86463249f2d69738df4a021a6")
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
        result (compose/interpret-verification evidence)]
    (is (= false (:passed result)))
    (is (re-find #"app service must define a healthcheck" (:stderr result)))))

(deftest rendered-generation-prompt-carries-strategy-and-runtime-contract
  (let [rendered (prompt/render-builder-prompt
                  {:purpose :fresh-generation
                   :instruction (prompt/seed-instruction :fresh-generation)
                   :request-id "request-compose"
                   :scope ["impl"]
                   :target-stack "typescript"
                   :model-root "model/loyly"
                   :selection approved-selection})
        instruction (:prompt rendered)]
    (is (re-find #"model/loyly" instruction))
    (is (re-find #"Target stack: typescript" instruction))
    (is (re-find #"Base image: node:22-alpine" instruction))
    (is (re-find #"Node JavaScript or TypeScript source files" instruction))
    (is (re-find #"impl/compose.yaml" instruction))
    (is (re-find #"impl/start.sh" instruction))
    (is (re-find #"internal port 8080" instruction))
    (is (re-find #"http://app:8080" instruction))
    (is (re-find #"docker compose run --rm test" instruction))
    (is (re-find #"no Docker daemon" instruction))
    (is (re-find #"prove its contents natively" instruction))
    (is (re-find #"must not run the candidate test suite" instruction))
    (is (re-find #"never fetch packages" instruction))
    (is (re-find #"runtime evidence" instruction))
    (is (re-find #"human affordance" instruction))
    (is (re-find #"camelCase aliases" instruction))
    (is (re-find #"Percent-decode" instruction))
    (is (re-find #"authoritative structured state" instruction))
    (is (re-find #"mutations are atomic" instruction))
    (is (re-find #"overlap concurrent requests" instruction))
    (is (re-find #"test discovered by its runner" instruction))))

(deftest rendered-rust-context-cannot-fall-back-to-node
  (let [instruction (:prompt
                     (prompt/render-builder-prompt
                      {:purpose :fresh-generation
                       :instruction (prompt/seed-instruction :fresh-generation)
                       :request-id "request-rust"
                       :scope ["impl"]
                       :target-stack "rust"
                       :model-root "model/loyly"
                       :selection approved-selection}))]
    (is (re-find #"Base image: pi-harness/rust:1.91-web" instruction))
    (is (re-find #"Rust \.rs source files" instruction))
    (is (not (re-find #"Base image: node:22-alpine" instruction)))))

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
                              :harness-snapshot-result
                              {:calibration-results (json/write-str [calibration-golden])}
                              :builder-result {:output-root (.getAbsolutePath root)
                                               :subject-digest digest}}})]
        (is (= digest (:subject-digest result)))
        (is (= digest (subject/digest (.getAbsolutePath root))))
        (is (= 1 (count (:evidence-locations result))))
        (is (not (.startsWith (first (:evidence-locations result))
                              (str (.getAbsolutePath root) "/"))))
        (let [results-root (java.io.File. (first (:evidence-locations result)))
              result-file (first (filter #(str/ends-with? (.getName %) "-result.json")
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
                 (worker/grade-against-accepted-oracle context))]
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
                          :grade-result {:passed false}}}
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

(deftest instrument-conformance-records-an-empty-applicable-denominator
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-instrument-not-applicable-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        contract (io/file root "cut-1/model/loyly/runtime-evidence-contract.json")
        adapter-called (atom false)]
    (.mkdirs (.getParentFile contract))
    (spit contract (json/write-str {:contracts [{:itemId "@item-01-099"}]}))
    (let [result (with-redefs [worker/cuts-root (constantly (.getPath root))
                               worker/run-grader-adapter
                               (fn [_] (reset! adapter-called true) {})]
                   (worker/instrument-conformance-stage
                    {:inputs {:request-id "request-no-runtime-contract"
                              :selection approved-selection
                              :scope ["impl"]
                              :execution-backend "gondolin"
                              :oracle-result {:grader-root "/judge"}
                              :builder-result {:output-root "/tmp/out" :subject-digest "s"}
                              :instrument-result {:evidence-root "/tmp/out.evidence"
                                                  :subject-digest "s"}}}))]
      (is (false? @adapter-called))
      (is (true? (:passed result)))
      (is (false? (:applicable result)))
      (is (= 0 (:total result)))
      (is (= [] (:items result))))))

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
        projected (atom [])
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
                :builder-result {:builder "openai-codex/test"
                                 :output-root "/tmp/candidate"}
                :instrument-result {:subject-digest "subject-test"
                                    :evidence-root (.getAbsolutePath evidence-root)}
                :oracle-result approved-oracle
                :grade-result {:measurement measurement
                                 :observations [grader-observation]}
                :instrument-conformance-result
                {:passed true :total 1 :passed-count 1
                 :items [{:item-id "@item-01-007" :verdict "pass"
                          :evidence "correlated runtime facts" :graded? true}]
                 :observations [instrument-observation]}}]
    (try
      (let [result (with-redefs [model/record-measurement!
                                 (fn [_ value]
                                   (swap! order conj :measurement-persisted)
                                   (reset! recorded value)
                                   value)
                                 model/record-generated-candidate!
                                 (fn [_ value] value)
                                 model/record-observation!
                                 (fn [_ value]
                                   (swap! order conj :observation-persisted)
                                   (swap! observations conj value)
                                   value)
                                 worker/emit-cycle-observation!
                                 (fn [_ value]
                                   (swap! order conj :evidence-projected)
                                   (swap! projected conj value))]
                     (worker/record-cycle! {:inputs inputs}))
            manifest (:evidence-manifest result)
            artifacts (:artifacts manifest)]
        (is (= result @recorded))
        (is (= 2 (count @observations)))
        (is (every? #(= (set (map :sha256 artifacts))
                         (set (map :digest (:artifacts %))))
                    @observations))
        (is (every? #(and (re-matches #"evaluation-[0-9a-f]{64}" (:evaluation-id %))
                          (= (:digest manifest) (:evidence-digest %)))
                    @observations))
        (is (= @observations @projected))
        (is (= [:observation-persisted :observation-persisted
                :measurement-persisted :evidence-projected :evidence-projected]
               @order))
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
          :operation "measured-regeneration"
          :request-id "request-failed-grade"
          :cut "cut-1"
          :scope ["impl"]
          :execution-backend "gondolin"
          :passed false
          :verified? true
          :instrumented? false
          :score {:passed 0 :failed 1}}
         (worker/grade-gate
          {:inputs {:request-id "request-failed-grade"
                    :selection approved-selection
                    :scope ["impl"]
                    :execution-backend "gondolin"
                    :verify-result {:passed true}
                    :instrument-conformance-result {:passed false}
                    :grade-result {:passed false
                                     :measurement {:passed 0 :failed 1}}}}))))

(deftest final-gate-requires-behavior-and-instrument-conformance
  (let [base {:request-id "request-gates"
              :selection approved-selection
              :scope ["impl"]
              :execution-backend "gondolin"
              :verify-result {:passed true}
              :grade-result {:passed true :measurement {:passed 1 :failed 0}}}]
    (is (false? (:passed (worker/grade-gate
                          {:inputs (assoc base :instrument-conformance-result {:passed false})}))))
    (is (true? (:passed (worker/grade-gate
                         {:inputs (assoc base :instrument-conformance-result {:passed true})}))))))
