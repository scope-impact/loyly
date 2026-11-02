(ns loyly.pi-harness.core.build.prompt-evaluation-test
  (:require [ai.obney.orc.gepa.interface :as gepa]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [loyly.pi-harness.core.build.subject :as subject]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.core.worker :as worker]))

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
        result (with-redefs [worker/run-oracle (constantly {:passed true :grader-root "/oracle/grader"
                                                            :stdout "ok" :stderr ""})
                            worker/product-oracle-options (constantly {})
                            subject/digest (constantly "subject-digest")
                            worker/run-builder-adapter
                            (fn [request]
                              (swap! requests conj request)
                              {:output-root "/tmp/output"})
                            worker/run-grader-adapter
                            (fn [{:keys [mode item-ids]}]
                              (case mode
                                "compose" compose-evidence
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
                             {:trainset [valid-example]
                              :valset [valid-example]})))
      (is (false? @runtime-started?)))))

(deftest prompt-optimization-refuses-empty-datasets-before-calibration
  (let [calibrated? (atom false)]
    (with-redefs [worker/calibrate-harness! (fn [] (reset! calibrated? true))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"non-empty trainset and valset"
                            (worker/optimize-builder-prompt!
                             {:trainset [] :valset []})))
      (is (false? @calibrated?)))))

(deftest prompt-optimization-normalizes-extension-examples-for-gepa-storage
  (let [captured (atom nil)
        previous (worker/registered-workflows)]
    (try
      (worker/replace-workflows! {:prompt {:context :context :sheet-id :sheet-id}})
      (with-redefs [worker/calibrate-harness! (constantly {:passed true})
                    worker/ensure-runtime! (constantly {:started true})
                    gepa/optimize!
                    (fn [_ options]
                      (reset! captured options)
                      {:status :completed
                       :best-score 0.5
                       :optimization-id (random-uuid)
                       :best-candidate
                       {:instructions {"builder-instruction" "Improved generic instruction."}}})]
        (worker/optimize-builder-prompt!
         {:trainset [valid-example]
          :valset [(assoc valid-example :request-id "prompt-validation")]})
        (is (every? string? (keys (first (:trainset @captured)))))
        (is (every? string? (keys (first (:valset @captured)))))
        (is (= 1800000 (:timeout-ms @captured)))
        (is (= {:max-metric-calls 12} (:config @captured))))
      (finally
        (worker/replace-workflows! previous)))))

(deftest prompt-optimization-exposes-gepa-failure-reason
  (let [previous (worker/registered-workflows)]
    (try
      (worker/replace-workflows! {:prompt {:context :context :sheet-id :sheet-id}})
      (with-redefs [worker/calibrate-harness! (constantly {:passed true})
                    worker/ensure-runtime! (constantly {:started true})
                    gepa/optimize! (constantly {:status :failed
                                                :error "Instruction proposal failed: Configuration not found"
                                                :optimization-id (random-uuid)})]
        (is (= "Instruction proposal failed: Configuration not found"
               (:error (worker/optimize-builder-prompt!
                        {:trainset [valid-example]
                         :valset [valid-example]})))))
      (finally
        (worker/replace-workflows! previous)))))
