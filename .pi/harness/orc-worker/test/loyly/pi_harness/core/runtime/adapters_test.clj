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

(deftest builder-adapter-transports-the-orc-rendered-prompts-unchanged
  (let [invocation (atom nil)]
    (with-redefs [json-process/invoke!
                  (fn [script request options]
                    (reset! invocation {:script script :request request :options options})
                    {:exit-code 0
                     :stdout ""
                     :stderr ""
                     :response {:ok true
                                :result {:outputRoot "/output/cycle-1"
                                         :builderSessionId "builder-session-1"
                                         :promptDigest "sha256:rendered"
                                         :systemPromptDigest "sha256:system"}}})]
      (is (= {:output-root "/output/cycle-1"
              :builder-session-id "builder-session-1"
              :prompt-digest "sha256:rendered"
              :system-prompt-digest "sha256:system"
              :builder "openai-codex/test"}
             (adapters/run-builder!
              {:project-root "/repo"
               :cuts-root "/cuts"
               :builder-id (constantly "openai-codex/test")
               :project-adapter (constantly {:modelRoot "model"})}
              {:request-id "cycle-1"
               :cut "cut-1"
               :scope ["impl"]
               :execution-backend "gondolin"
               :system-prompt "system owned by ORC"
               :system-prompt-digest "sha256:system"
               :prompt "task owned by ORC"
               :prompt-digest "sha256:rendered"
               :selection {:items [{:id "@item-01-007"}]}
               :target-stack "typescript"})))
      (is (= "system owned by ORC" (get-in @invocation [:request :systemPrompt])))
      (is (= "sha256:system" (get-in @invocation [:request :systemPromptDigest])))
      (is (= "task owned by ORC" (get-in @invocation [:request :prompt])))
      (is (= "sha256:rendered" (get-in @invocation [:request :promptDigest])))
      (is (= {:working-directory "/repo"} (:options @invocation))))))

(deftest grader-author-adapter-exposes-the-contract-but-not-the-candidate
  (let [invocation (atom nil)]
    (with-redefs [json-process/invoke!
                  (fn [script request options]
                    (reset! invocation {:script script :request request :options options})
                    {:exit-code 0 :stdout "" :stderr ""
                     :response {:ok true
                                :result {:graderRoot "/state/graders/sealed"
                                         :graderDigest "sha256:sealed"}}})]
      (is (= {:graderRoot "/state/graders/sealed"
              :graderDigest "sha256:sealed"}
             (adapters/run-grader-author!
              "/repo"
              {:backend "gondolin"
               :cutId "cut-1"
               :cutsRoot "/cuts"
               :slice "01-booking-core"
               :itemIds ["@item-01-007"]
               :systemPrompt "system owned by ORC"
               :prompt "author the grader"
               :outputRoot "/state/graders/sealed"})))
      (is (= "/repo/.pi/harness/src/grader-author-worker.ts" (:script @invocation)))
      (is (not (contains? (:request @invocation) :candidateRoot)))
      (is (not (contains? (:request @invocation) :evidenceRoot)))
      (is (= "system owned by ORC" (get-in @invocation [:request :systemPrompt])))
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
                  "baseline" "gondolin" {:runtime-image-archive "node.tar"
                                           :item-ids ["@item-01-007" "@item-01-008"]})
        names (mapv :name commands)]
    (is (= ["baseline-runtime" "baseline-evidence" "baseline-up"
            "baseline-observe-start" "baseline-grade" "baseline-observe-stop"
            "baseline-logs" "baseline-down"]
           names))
    (let [up-command (:command (first (filter #(= "baseline-up" (:name %)) commands)))]
      (is (re-find #"--wait" up-command))
      (is (not (re-find #"/health" up-command))))
    (let [grade-command (:command (first (filter #(= "baseline-grade" (:name %)) commands)))]
      (is (re-find #"mount --bind /tmp/pi-harness-black-box /workspace" grade-command))
      (is (re-find #"/grader/grade.mjs" grade-command)))
    (is (re-find #"--item @item-01-007 --item @item-01-008"
                 (:command (first (filter #(= "baseline-grade" (:name %)) commands)))))
    (is (re-find #"/evidence/otel/independent/baseline"
                 (:command (first (filter #(= "baseline-observe-start" (:name %))
                                          commands)))))))
