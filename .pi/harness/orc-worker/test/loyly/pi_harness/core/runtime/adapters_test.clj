(ns loyly.pi-harness.core.runtime.adapters-test
  (:require [clojure.test :refer [deftest is testing]]
            [loyly.pi-harness.core.grading.grader-commands :as grader-commands]
            [loyly.pi-harness.core.runtime.adapters :as adapters]
            [loyly.pi-harness.core.runtime.json-process :as json-process]))

(deftest cut-adapter-translates-the-process-boundary
  (let [invocation (atom nil)
        result (with-redefs [json-process/invoke!
                             (fn [script request]
                               (reset! invocation {:script script :request request})
                               {:exit-code 0
                                :stdout ""
                                :stderr ""
                                :response {:ok true :result {:cutId "cut-1"}}})]
                 (adapters/run-cut! "/repo" "abc123" "gondolin"))]
    (is (= {:script "/repo/.pi/harness/src/cut-worker.ts"
            :request {:sourceCommit "abc123" :backend "gondolin"}}
           @invocation))
    (is (= {:cutId "cut-1"} result))))

(deftest builder-adapter-preserves-the-fresh-pi-session-identity
  (let [invocation (atom nil)]
    (with-redefs [json-process/invoke!
                  (fn [script request options]
                    (reset! invocation {:script script :request request :options options})
                    {:exit-code 0
                     :stdout ""
                     :stderr ""
                     :response {:ok true
                                :result {:outputRoot "/output/cycle-1"
                                         :builderSessionId "builder-session-1"}}})]
      (is (= {:output-root "/output/cycle-1"
              :builder-session-id "builder-session-1"
              :builder "openai-codex/test"}
             (adapters/run-builder!
              {:project-root "/repo"
               :cuts-root "/cuts"
               :builder-id (constantly "openai-codex/test")
               :builder-prompt (atom "build")
               :project-adapter (constantly {:modelRoot "model"})}
              {:request-id "cycle-1"
               :cut "cut-1"
               :scope ["impl"]
               :execution-backend "gondolin"
               :selection {:items [{:id "@item-01-007"}]}
               :target-stack "typescript"})))
      (is (= {:working-directory "/repo"} (:options @invocation))))))

(deftest adapter-failures-retain-process-diagnostics
  (with-redefs [json-process/invoke!
                (constantly {:exit-code 17
                             :stdout "partial output"
                             :stderr "worker exploded"
                             :response nil})]
    (let [error (try
                  (adapters/run-cut! "/repo" "abc123" "gondolin")
                  nil
                  (catch clojure.lang.ExceptionInfo exception exception))]
      (testing "the exception identifies both the boundary and process status"
        (is (= "cut adapter" (:adapter (ex-data error))))
        (is (= 17 (:exit-code (ex-data error))))
        (is (re-find #"worker exploded" (.getMessage error)))))))

(deftest grader-adapter-carries-cycle-and-pinned-observability-roots
  (let [invocation (atom nil)]
    (with-redefs [json-process/invoke!
                  (fn [script request]
                    (reset! invocation {:script script :request request})
                    {:exit-code 0 :stdout "" :stderr ""
                     :response {:ok true :result {:compose-config {:exitCode 0}}}})]
      (adapters/run-grader!
       {:project-root "/repo" :cuts-root "/cuts"}
       {:cycle-id "cycle-42"
        :cut "cut-1"
        :output-root "/candidate"
        :evidence-root "/evidence"
        :grader-root "/oracle/grader"
        :observability-root "/oracle/runtime-observability"
        :execution-backend "gondolin"
        :mode "compose"})
      (is (= "cycle-42" (get-in @invocation [:request :cycleId])))
      (is (= "/oracle/runtime-observability" (get-in @invocation [:request :observabilityAssetsRoot])))
      (is (= "/evidence" (get-in @invocation [:request :evidenceRoot]))))))

(deftest baseline-grading-captures-runtime-evidence-around-the-public-seam
  (let [commands (grader-commands/commands
                  "baseline" "gondolin" {:runtime-image-archive "node.tar"})
        names (mapv :name commands)]
    (is (= ["baseline-runtime" "baseline-evidence" "baseline-up"
            "baseline-observe-start" "baseline-grade" "baseline-observe-stop"
            "baseline-logs" "baseline-down"]
           names))
    (is (re-find #"/grader/grade.mjs"
                 (:command (first (filter #(= "baseline-grade" (:name %)) commands)))))
    (is (re-find #"/evidence/otel/independent/baseline"
                 (:command (first (filter #(= "baseline-observe-start" (:name %))
                                          commands)))))))
