(ns loyly.pi-harness.core.runtime.adapters
  "Process-boundary adapters. This namespace translates Clojure domain data to
  the narrow newline-delimited JSON contracts implemented by the TypeScript
  workers; it does not own workflow ordering or state."
  (:require [loyly.pi-harness.core.build.application-contract :as application-contract]
            [loyly.pi-harness.core.grading.grader-commands :as grader-commands]
            [loyly.pi-harness.core.runtime.json-process :as json-process]))

(defn- successful-result!
  [adapter-name {:keys [exit-code stdout stderr response]}]
  (when-not (and (zero? exit-code) (:ok response))
    (throw (ex-info (str adapter-name " failed: "
                         (or (:error response) stderr stdout))
                    {:adapter adapter-name :exit-code exit-code})))
  (:result response))

(defn run-cut!
  [project-root source-commit execution-backend]
  (let [script (str project-root "/.pi/harness/src/cut-worker.ts")]
    (successful-result!
     "cut adapter"
     (json-process/invoke! script {:sourceCommit source-commit
                                   :backend execution-backend}))))

(defn run-builder!
  [{:keys [project-root cuts-root builder-id builder-prompt project-adapter]}
   {:keys [request-id cut scope execution-backend prompt-text selection target-stack
           initial-workspace-root verification-evidence-root round builder-session-ref
           builder-sessions-root attempt]}]
  (let [adapter (project-adapter selection)
        output-root (str (or (System/getenv "PI_HARNESS_OUTPUT_ROOT")
                             (System/getenv "LOYLEY_OUTPUT_ROOT")
                             (str project-root "/.pi/harness/.state/outputs"))
                         "/" request-id)
        script (str project-root "/.pi/harness/src/builder-worker.ts")
        request {:backend execution-backend
                 :cutId cut
                 :scope scope
                 :prompt (application-contract/builder-instruction
                          {:base-instruction
                           (str (or prompt-text @builder-prompt)
                                (when verification-evidence-root
                                  (str "\n\nThis is repair round " round ". "
                                       "The previous candidate is already present in the workspace. "
                                       "Inspect the verifier's shared files under "
                                       "$HARNESS_VERIFICATION_EVIDENCE_ROOT, repair the candidate, "
                                       "and do not replace working behavior merely to silence a log.")))
                           :request-id request-id
                           :scope scope
                           :selection selection
                           :target-stack target-stack
                           :model-root (:modelRoot adapter)})
                 :targetStack target-stack
                 :initialWorkspaceRoot initial-workspace-root
                 :verificationEvidenceRoot verification-evidence-root
                 :builderSessionRef builder-session-ref
                 :builderSessionsRoot builder-sessions-root
                 :attempt attempt
                 :cutsRoot cuts-root
                 :outputRoot output-root}
        result (successful-result! "builder adapter"
                                   (json-process/invoke! script request
                                                        {:working-directory project-root}))]
    {:output-root (:outputRoot result)
     :builder-session-id (:builderSessionId result)
     :builder (builder-id)}))

(defn read-builder-session!
  [project-root builder-sessions-root builder-session-ref]
  (successful-result!
   "builder session adapter"
   (json-process/invoke! (str project-root "/.pi/harness/src/builder-session-worker.ts")
                         {:builderSessionsRoot builder-sessions-root
                          :builderSessionRef builder-session-ref})))

(defn run-oracle-fixture!
  [project-root request]
  (successful-result!
   "Gondolin oracle fixture adapter"
   (json-process/invoke! (str project-root "/.pi/harness/src/oracle-fixture-worker.ts")
                         request)))

(defn run-grader!
  [{:keys [project-root cuts-root]}
   {:keys [cycle-id cut output-root evidence-root evidence-namespace mode execution-backend
           project-id item-ids grader-script grader-root observability-root target-stack]}]
  (when (and (#{"independent" "baseline"} mode) (not grader-root))
    (throw (ex-info "independent grading requires read-only grader assets"
                    {:mode mode})))
  (let [stack (when target-stack
                (application-contract/stack-requirements target-stack))
        runtime-image-archive (:archive stack)
        script (str project-root "/.pi/harness/src/execution-worker.ts")
        request {:backend execution-backend
                 :cycleId cycle-id
                 :cutId cut
                 :outputRoot output-root
                 :evidenceRoot (or evidence-root (str output-root ".evidence"))
                 :evidenceNamespace (or evidence-namespace mode)
                 :cutsRoot cuts-root
                 :graderAssetsRoot grader-root
                 :observabilityAssetsRoot observability-root
                 :runtimeImagesRoot (when runtime-image-archive
                                      (str project-root "/.pi/harness/.state/runtime-images"))
                 :commands (grader-commands/commands
                            mode execution-backend
                            {:project-id project-id
                             :item-ids item-ids
                             :grader-script grader-script
                             :runtime-image-archive runtime-image-archive})}
        process-result (json-process/invoke! script request)
        response (:response process-result)]
    ;; The execution worker has a legacy response form without :ok. Preserve it
    ;; at this adapter boundary until the TypeScript protocol is consolidated.
    (if (and response (or (:ok response) (:result response)))
      (:result response)
      (throw (ex-info (str "grader adapter failed: "
                           (:stderr process-result) (:stdout process-result))
                      {:adapter "grader adapter"
                       :exit-code (:exit-code process-result)})))))
