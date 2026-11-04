(ns loyly.pi-harness.core.build.prompt-evaluation-test
  (:require [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.orc-service.interface :as orc]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [loyly.pi-harness.core.build.prompt :as prompt]
            [loyly.pi-harness.core.build.subject :as subject]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.core.worker :as worker]))

(declare selection)

(deftest purpose-specific-prompts-have-one-orc-owned-rendering-seam
  (let [runtime {:request-id "cycle-1"
                 :scope ["impl"]
                 :selection selection
                 :target-stack "typescript"
                 :model-root "model/loyly"}
        generation (prompt/render-builder-prompt
                    (assoc runtime
                           :purpose :fresh-generation
                           :instruction (prompt/seed-instruction :fresh-generation)))
        repair (prompt/render-builder-prompt
                (assoc runtime
                       :purpose :verification-repair
                       :instruction (prompt/seed-instruction :verification-repair)
                       :verification-evidence-root "/evidence"))]
    (is (str/includes? (:prompt generation) "cycle-1"))
    (is (str/includes? (:prompt generation) "@item-01-001"))
    (is (str/includes? (:prompt repair) "/evidence"))
    (is (not= (:instruction-digest generation) (:instruction-digest repair)))
    (is (= (prompt/digest (:prompt generation)) (:prompt-digest generation)))
    (is (= (prompt/system-instruction :fresh-generation) (:system-prompt generation)))))

(deftest generation-and-repair-workflows-have-independent-instruction-components
  (is (str/includes? (pr-str (prompt/workflow :fresh-generation))
                     "fresh-generation-instruction"))
  (is (str/includes? (pr-str (prompt/workflow :verification-repair))
                     "verification-repair-instruction")))

(def compose-evidence
  {:compose-config {:exitCode 0
                    :stdout "{\"services\":{\"app\":{\"healthcheck\":{\"test\":[\"CMD\",\"true\"]}},\"test\":{}}}"
                    :stderr ""}
   :compose-services {:exitCode 0 :stdout "app\ntest\n" :stderr ""}
   :compose-test {:exitCode 0 :stdout "tests passed" :stderr ""}
   :compose-up {:exitCode 0 :stdout "healthy" :stderr ""}
   :compose-logs {:exitCode 0 :stdout "ready" :stderr ""}
   :compose-down {:exitCode 0 :stdout "removed" :stderr ""}})

(def selection
  {:status "approved"
   :selectionId "selection-prompt-metric"
   :selectionDigest "selection-digest"
   :cut "cut-1"
   :projectId "loyly"
   :containerId "loyly"
   :componentId "booking-core"
   :slice "booking-core"
   :items [{:id "@item-01-001" :name "first" :rule "first rule" :source "spec.feature"}
           {:id "@item-01-002" :name "second" :rule "second rule" :source "spec.feature"}]})

(def valid-example
  {:task "Produce a builder instruction for this approved regeneration selection."
   :request-id "prompt-example"
   :selection selection
   :scope ["impl"]
   :execution-backend "gondolin"})

(defn optimization-request [trainset valset]
  {:purpose "fresh-generation"
   :reflectionProvider "bedrock"
   :reflectionModel "eu.amazon.nova-2-lite-v1:0"
   :trainset trainset
   :valset valset})

(defn independent-evidence [item-id verdict]
  {:independent-runtime {:exitCode 0 :stdout "" :stderr ""}
   :independent-evidence {:exitCode 0 :stdout "" :stderr ""}
   :independent-up {:exitCode 0 :stdout "" :stderr ""}
   :independent-grade
   {:exitCode (if (= "pass" verdict) 0 1)
    :stdout (json/write-str [{:itemId (str/replace item-id #"^@item-" "")
                              :verdict verdict
                              :evidence (str item-id " independently " verdict)}])
    :stderr ""}
   :independent-logs {:exitCode 0 :stdout "" :stderr ""}
   :independent-down {:exitCode 0 :stdout "" :stderr ""}})

(deftest prompt-optimization-scores-independent-item-results
  (let [requests (atom [])
        result (with-redefs [worker/ensure-cycle-oracle!
                            (constantly {:grader-root "/cycle-oracle"})
                            worker/baseline-grader-root (constantly "/validator")
                            worker/baseline-grade-stage
                            (constantly
                             {:measurement
                              {:complete? true :total 2 :passed 1 :failed 1
                               :items [{:item-id "@item-01-001" :verdict "pass"
                                        :evidence "@item-01-001 independently pass" :graded? true}
                                       {:item-id "@item-01-002" :verdict "fail"
                                        :evidence "@item-01-002 independently fail" :graded? true}]}})
                            subject/digest (constantly "subject-digest")
                            worker/run-builder-adapter
                            (fn [request]
                              (swap! requests conj request)
                              {:output-root "/tmp/output"})
                            worker/run-grader-adapter
                            (fn [{:keys [mode item-ids]}]
                              (case mode
                                "compose" compose-evidence
                                "affordance"
                                {:affordance-runtime {:exitCode 0}
                                 :affordance-evidence {:exitCode 0}
                                 :affordance-up {:exitCode 0}
                                 :affordance-grade
                                 {:exitCode 0
                                  :stdout "{\"passed\":true,\"checks\":[{\"id\":\"screen:Home\",\"passed\":true}]}"}
                                 :affordance-down {:exitCode 0}}
                                "independent" (independent-evidence
                                               (first item-ids)
                                               (if (= ["@item-01-002"] item-ids) "fail" "pass"))
                                {}))]
                (worker/evaluate-prompt
                 {:request-id "request-independent"
                  :task "Produce a builder instruction for this approved regeneration selection."
                  :selection selection
                  :scope ["impl"]
                  :execution-backend "gondolin"}
                 {:prompt "Build it."}))]
    (is (= 0.5 (:score result)))
    (is (= {:selected 2
            :passed 1
            :failed 1
            :failed-items [{:item-id "@item-01-002"
                            :evidence "@item-01-002 independently fail"
                            :graded? true}]}
           (json/read-str (:feedback result) :key-fn keyword)))
    (is (= selection (:selection (first @requests))))
    (is (str/starts-with? (:request-id (first @requests))
                          "gepa-request-independent-"))))

(deftest prompt-optimization-gives-api-only-candidates-no-score
  (let [result (with-redefs [worker/ensure-cycle-oracle!
                             (constantly {:grader-root "/cycle-oracle"})
                             worker/baseline-grader-root (constantly "/validator")
                             subject/digest (constantly "subject-digest")
                             worker/run-builder-adapter
                             (constantly {:output-root "/tmp/api-only-output"})
                             worker/run-grader-adapter
                             (fn [{:keys [mode item-ids]}]
                               (case mode
                                 "compose" compose-evidence
                                 "affordance"
                                 {:affordance-runtime {:exitCode 0}
                                  :affordance-evidence {:exitCode 0}
                                  :affordance-up {:exitCode 0}
                                  :affordance-grade
                                  {:exitCode 1
                                   :stdout "{\"passed\":false,\"checks\":[{\"id\":\"screen:Home\",\"passed\":false}]}"}
                                  :affordance-down {:exitCode 0}}
                                 "independent" (independent-evidence (first item-ids) "pass")))]
                 (worker/evaluate-prompt
                  valid-example
                  {:prompt "Build an API without pages."}))]
    (is (= 0.0 (:score result)))
    (is (= false (:affordance-passed
                  (json/read-str (:feedback result) :key-fn keyword))))))

(deftest prompt-optimization-refuses-an-example-without-an-approved-selection
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"approved selection"
       (worker/evaluate-prompt
        {:request-id "request-unselected" :scope ["impl"]}
        {:prompt "Build it."}))))

(deftest prompt-optimization-cannot-open-a-builder-before-calibration
  (let [runtime-started? (atom false)]
    (with-redefs [worker/calibrate-harness!
                  (fn [] (throw (ex-info "full harness calibration failed" {})))
                  worker/ensure-runtime! (fn [] (reset! runtime-started? true))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"full harness calibration failed"
                            (worker/optimize-builder-prompt!
                             (optimization-request [valid-example] [valid-example]))))
      (is (false? @runtime-started?)))))

(deftest prompt-optimization-refuses-empty-datasets-before-calibration
  (let [calibrated? (atom false)]
    (with-redefs [worker/calibrate-harness! (fn [] (reset! calibrated? true))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"non-empty trainset and valset"
                            (worker/optimize-builder-prompt!
                             (optimization-request [] []))))
      (is (false? @calibrated?)))))

(deftest prompt-optimization-normalizes-extension-examples-for-gepa-storage
  (let [captured (atom nil)
        previous (worker/registered-workflows)]
    (try
      (worker/replace-workflows! {:generation-prompt {:context :context :sheet-id :sheet-id}})
      (with-redefs [worker/calibrate-harness! (constantly {:passed true})
                    worker/ensure-runtime! (constantly {:started true})
                    orc/get-latest-version
                    (constantly {:version-number 1})
                    gepa/apply-winner! (constantly {:target-version 2})
                    gepa/optimize!
                    (fn [_ options]
                      (reset! captured options)
                      {:status :completed
                       :best-score 0.5
                       :optimization-id (random-uuid)
                       :best-candidate
                       {:instructions {"fresh-generation-instruction" "Improved generic instruction."}}})]
        (worker/optimize-builder-prompt!
         (optimization-request
          [valid-example]
          [(assoc valid-example :request-id "prompt-validation")]))
        (is (every? string? (keys (first (:trainset @captured)))))
        (is (every? string? (keys (first (:valset @captured)))))
        (is (= 1800000 (:timeout-ms @captured)))
        (is (= {:max-metric-calls 12
                :reflection-lm "eu.amazon.nova-2-lite-v1:0"}
               (:config @captured))))
      (finally
        (worker/replace-workflows! previous)))))

(deftest prompt-optimization-exposes-gepa-failure-reason
  (let [previous (worker/registered-workflows)]
    (try
      (worker/replace-workflows! {:generation-prompt {:context :context :sheet-id :sheet-id}})
      (with-redefs [worker/calibrate-harness! (constantly {:passed true})
                    worker/ensure-runtime! (constantly {:started true})
                    orc/get-latest-version
                    (constantly {:version-number 1})
                    gepa/optimize! (constantly {:status :failed
                                                :error "Instruction proposal failed: Configuration not found"
                                                :optimization-id (random-uuid)})]
        (is (= "Instruction proposal failed: Configuration not found"
               (:error (worker/optimize-builder-prompt!
                        (optimization-request [valid-example] [valid-example]))))))
      (finally
        (worker/replace-workflows! previous)))))
