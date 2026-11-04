(ns loyly.pi-harness.core.worker
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.interface.schemas]
            [clojure.string :as str]
            [loyly.pi-harness.core.evidence.allure-evidence :as allure-evidence]
            [loyly.pi-harness.core.evidence.allure-golden :as allure-golden]
            [loyly.pi-harness.core.evidence.evidence-projection :as evidence-projection]
            [loyly.pi-harness.core.runtime.adapters :as adapters]
            [loyly.pi-harness.core.grading.compose :as compose]
            [loyly.pi-harness.grain.interface :as model]
            [loyly.pi-harness.core.evidence.evidence-manifest :as evidence-manifest]
            [loyly.pi-harness.core.oracle.harness-calibration :as harness-calibration]
            [loyly.pi-harness.core.grading.independent-evidence :as independent-evidence]
            [loyly.pi-harness.core.grading.independent-grader :as independent-grader]
            [loyly.pi-harness.core.grading.prompt :as grading-prompt]
            [loyly.pi-harness.core.build.prompt :as prompt]
            [loyly.pi-harness.core.build.project-profile :as project-profile]
            [loyly.pi-harness.core.build.stack-conformance :as stack-conformance]
            [loyly.pi-harness.core.build.subject :as subject]
            [loyly.pi-harness.workflows.cut :as cut-workflow]
            [loyly.pi-harness.workflows.slice-cycle :as slice-cycle-workflow]))

(defonce ^:private runtime (atom nil))
(defonce ^:private workflows (atom {}))
(defonce ^:private tasks (atom {}))
(defonce ^:private next-task (atom 0))

(defn registered-workflows [] @workflows)

(defn replace-workflows!
  "Replace workflow registrations. Intended for lifecycle recovery and tests."
  [registrations]
  (reset! workflows registrations))

(defn project-root []
  (or (System/getenv "PI_HARNESS_PROJECT_ROOT")
      (System/getenv "LOYLEY_PROJECT_ROOT")
      (let [cwd (.getCanonicalFile (java.io.File. (System/getProperty "user.dir")))
            candidates (take-while some? (iterate #(.getParentFile %) cwd))]
        (some (fn [candidate]
                (when (.isFile (java.io.File. candidate ".pi/harness/config/oracles.json"))
                  (.getPath candidate)))
              candidates))
      (throw (ex-info "PI_HARNESS_PROJECT_ROOT is not configured" {}))))

(defn cuts-root []
  (or (System/getenv "PI_HARNESS_CUTS_ROOT")
      (System/getenv "LOYLEY_CUTS_ROOT")
      (str (System/getProperty "user.home") "/.pi/harness/cuts")))

(defn builder-sessions-root []
  (or (System/getenv "PI_HARNESS_BUILDER_SESSIONS_ROOT")
      (some-> (System/getenv "PI_HARNESS_STATE_ROOT")
              (str "/builder-sessions"))
      (str (project-root) "/.pi/harness/.state/builder-sessions")))

(defn project-adapter [selection]
  (project-profile/resolved (project-root) selection))

(defn builder-id []
  (str "openai-codex/" (or (System/getenv "PI_BUILDER_MODEL") "gpt-5.6-luna")))

(defn stage-result [stage {:keys [inputs]}]
  {:stage stage
   :request-id (get inputs :request-id)
   :cut (get-in inputs [:selection :cut])
   :scope (get inputs :scope)
   :execution-backend (get inputs :execution-backend)})

(defn record-stage-progress!
  ([context stage] (record-stage-progress! context stage 1 nil "running"))
  ([_context _stage _attempt _builder-session-ref _status]
   ;; ORC's execution facts are the sole progress history. The Pi harness
   ;; projects those facts; it never mirrors them into harness-owned events.
   nil))

(defn effective-builder-result [inputs]
  (or (get-in inputs [:repair-evaluation-result :builder])
      (get-in inputs [:repair-verification-result :builder])
      (:builder-result inputs)))

(defn effective-instrument-result [inputs]
  (or (get-in inputs [:repair-evaluation-result :instrument])
      (get-in inputs [:repair-verification-result :instrument])
      (:instrument-result inputs)))

(defn effective-verify-result [inputs]
  (or (get-in inputs [:repair-evaluation-result :verify])
      (get-in inputs [:repair-verification-result :verify])
      (:verify-result inputs)))

(defn effective-grade-result [inputs]
  (or (get-in inputs [:repair-evaluation-result :grade])
      (:grade-result inputs)))

(defn effective-instrument-conformance-result [inputs]
  (or (get-in inputs [:repair-evaluation-result :instrument-conformance])
      (:instrument-conformance-result inputs)))

(defn effective-affordance-conformance-result [inputs]
  (or (get-in inputs [:repair-evaluation-result :affordance])
      (get-in inputs [:repair-verification-result :affordance])
      (:affordance-conformance-result inputs)))

(defn effective-grading-context [inputs]
  (or (:oracle-result inputs)
      (:grade-result inputs)))

(defn run-cut-adapter [source-commit execution-backend]
  (adapters/run-cut! (project-root) source-commit execution-backend))

(defn prepare-cut-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "prepare-cut")
  (merge {:stage "cut" :source-commit (:source-commit inputs) :execution-backend (:execution-backend inputs)}
         (run-cut-adapter (:source-commit inputs) (:execution-backend inputs))))

(defn cut-stage [context]
  (record-stage-progress! context "cut")
  (stage-result "cut" context))

(defn instrument-oracle-ref
  "Legacy configuration reader retained for measured-regeneration compatibility.
  Fresh generation never calls it."
  []
  (or (System/getenv "PI_HARNESS_INSTRUMENT_ORACLE_REF")
      (System/getenv "LOYLEY_INSTRUMENT_ORACLE_REF")
      (:instrumentOracleRef
       (json/read-str
        (slurp (str (project-root) "/.pi/harness/config/oracles.json"))
        :key-fn keyword))))

(defn calibrate-harness! []
  (let [root (project-root)
        result (harness-calibration/calibrate! root)
        suite-summary (into {}
                            (map (fn [[suite value]]
                                   [suite (select-keys value [:exit-code :timed-out?])]))
                            (:suites result))]
    (when-not (:passed result)
      ;; ORC persists only the message, so it names the failing suites and the record
      ;; that holds their output.
      (throw (ex-info (str "full harness calibration failed: "
                           (str/join ", " (for [[suite value] (sort-by key (:suites result))
                                                :when (not (zero? (or (:exit-code value) 1)))]
                                            (str (name suite) " exit " (:exit-code value)
                                                 (when (:timed-out? value) " (timed out)"))))
                           (when-let [record (:failure-record result)]
                             (str "; details in " record)))
                      {:harness-snapshot-digest (:harness-snapshot-digest result)
                       :mismatches (:mismatches result)
                       :suites suite-summary})))
    result))

(defn harness-snapshot-stage [context]
  (record-stage-progress! context "harness-snapshot")
  (let [result (calibrate-harness!)
        suite-summary (into {}
                            (map (fn [[suite value]]
                                   [suite (select-keys value [:exit-code :timed-out?])]))
                            (:suites result))]
    (merge (stage-result "harness-snapshot" context)
           {:passed true
            :harness-revision (:harness-revision result)
            :harness-snapshot-digest (:harness-snapshot-digest result)
            :calibration-results
            (json/write-str (->> (:actual result)
                                 (sort-by key)
                                 (mapv val)))
            :evidence (json/write-str {:mismatches (:mismatches result)
                                       :suites suite-summary})})))

(defn- prompt-registration-key [purpose]
  (case purpose
    :fresh-generation :generation-prompt
    :verification-repair :repair-prompt
    (throw (ex-info "unknown builder instruction purpose" {:purpose purpose}))))

(defn- find-workflow-node [node node-name]
  (when node
    (or (when (= node-name (:name node)) node)
        (some #(find-workflow-node % node-name) (:children node)))))

(defn- active-instruction [purpose]
  (let [{:keys [context sheet-id]} (get @workflows (prompt-registration-key purpose))
        version (when (and context sheet-id) (orc/get-latest-version context sheet-id))]
    (or (:instruction (find-workflow-node (get-in version [:snapshot :nodes])
                                          (prompt/component-name purpose)))
        (prompt/seed-instruction purpose))))

(defn- render-builder-request [request]
  (let [purpose (or (:purpose request)
                    (if (:verification-evidence-root request)
                      :verification-repair
                      :fresh-generation))
        adapter (project-adapter (:selection request))]
    (merge request
           (prompt/render-builder-prompt
            {:purpose purpose
             :instruction (or (:instruction request) (active-instruction purpose))
             :request-id (:request-id request)
             :scope (:scope request)
             :selection (:selection request)
             :target-stack (:target-stack request)
             :model-root (:modelRoot adapter)
             :verification-evidence-root (:verification-evidence-root request)}))))

(defn run-builder-adapter [request]
  (adapters/run-builder! {:project-root (project-root)
                          :cuts-root (cuts-root)
                          :builder-id builder-id
                          :project-adapter project-adapter}
                         (render-builder-request request)))

(defn run-builder-session-adapter [builder-session-ref]
  (adapters/read-builder-session! (project-root)
                                  (builder-sessions-root)
                                  builder-session-ref))

(defn builder-session! [{:keys [builderSessionRef]}]
  (when-not (and (string? builderSessionRef) (not (str/blank? builderSessionRef)))
    (throw (ex-info "builder-session requires builderSessionRef"
                    {:builderSessionRef builderSessionRef})))
  (run-builder-session-adapter builderSessionRef))

(defn builder-stage [{:keys [inputs] :as context}]
  (let [builder-session-ref (str (random-uuid))
        parent-root (:parent-candidate-root inputs)
        parent-digest (:parent-candidate-digest inputs)
        _ (when (not= (boolean parent-root) (boolean parent-digest))
            (throw (ex-info "parent candidate root and digest must be supplied together" {})))
        _ (when parent-root
            (let [actual (subject/digest parent-root)]
              (when-not (= parent-digest actual)
                (throw (ex-info "parent candidate changed before regeneration"
                                {:expected parent-digest :actual actual})))))
        _ (record-stage-progress! context "builder" 1 builder-session-ref "running")
        builder-result (run-builder-adapter {:request-id (:request-id inputs)
                                             :cut (get-in inputs [:selection :cut])
                                             :scope (:scope inputs)
                                             :selection (:selection inputs)
                                             :target-stack (:target-stack inputs)
                                             :execution-backend (:execution-backend inputs)
                                             :initial-workspace-root parent-root
                                             :builder-session-ref builder-session-ref
                                             :builder-sessions-root (builder-sessions-root)
                                             :attempt 1})]
    (merge (stage-result "builder" context)
           builder-result
           {:builder (or (:builder builder-result) (builder-id))
            :subject-digest (subject/digest (:output-root builder-result))})))

(defn instrument-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "instrument")
  (let [output-root (get-in inputs [:builder-result :output-root])
        expected-digest (get-in inputs [:builder-result :subject-digest])
        observed-digest (subject/digest output-root)
        evidence-root (str output-root ".evidence")
        calibration-results (some-> (get-in inputs [:harness-snapshot-result
                                                     :calibration-results])
                                    (json/read-str :key-fn keyword))]
    (when-not (= expected-digest observed-digest)
      (throw (ex-info "subject digest changed before instrumentation"
                      {:expected expected-digest :observed observed-digest})))
    (let [final-digest (subject/digest output-root)]
      (when-not (= expected-digest final-digest)
        (throw (ex-info "subject digest changed during instrumentation"
                        {:expected expected-digest :observed final-digest})))
      (doseq [golden calibration-results]
        (allure-golden/write-allure! (str evidence-root "/allure-results") golden))
      (merge (stage-result "instrument" context)
             {:subject-digest final-digest
              :evidence-root evidence-root
              :evidence-locations [(str evidence-root "/allure-results")]}))))

(declare ensure-runtime! run-grader-adapter sha256 baseline-grader-root
         ensure-cycle-oracle!
         affordance-conformance-stage grade-with-cycle-oracle)

(defn run-oracle-fixture-adapter [request]
  (adapters/run-oracle-fixture! (project-root) request))

(defn run-grader-author-adapter [request]
  (adapters/run-grader-author! (project-root) request))

(defn run-oracle
  ([cut] (run-oracle cut {:allItems true}))
  ([cut selection]
   (run-oracle cut selection {}))
  ([cut selection options]
  (let [cuts-root (cuts-root)
        evaluate-cut (requiring-resolve 'loyly.pi-harness.core.oracle.oracle/evaluate-cut)]
    (evaluate-cut (str cuts-root "/" cut) selection options))))

(defn- rooted [root path]
  (let [registry-ref? (boolean
                       (re-matches #"(?i)(?:localhost(?::[0-9]+)?|[a-z0-9][a-z0-9.-]*\.[a-z0-9.-]+(?::[0-9]+)?)/.+"
                                   path))]
    (if (or registry-ref? (.isAbsolute (java.io.File. path)))
      path
      (str root "/" path))))

(defn product-oracle-options [selection]
  (let [root (project-root)
        adapter (project-adapter selection)
        oracle-ref (or (System/getenv "PI_HARNESS_PRODUCT_ORACLE_REF")
                       (System/getenv "LOYLEY_PRODUCT_ORACLE_REF")
                       (when-let [configured (:productOracleRef adapter)]
                         (rooted root configured)))]
    (when-not (and (string? oracle-ref) (not (str/blank? oracle-ref)))
      (throw (ex-info "project has no pinned product oracle; an oracle-backed measured cycle cannot run"
                      {:project-id (:projectId selection)})))
    {:oracle-pack-ref oracle-ref
     :oracle-cache-root (str root "/.pi/harness/.state/oracles")
     :fixture-runner run-oracle-fixture-adapter}))

(defn- keywordize [value]
  (json/read-str (json/write-str value) :key-fn keyword))

(defn- stringify-example-keys [example]
  (into {} (map (fn [[key value]] [(name key) value])) example))

(defn- prompt-evaluation-input [input]
  (let [value (keywordize input)
        selection (:selection value)
        task (:task value)
        request-id (:request-id value)
        scope (:scope value)
        execution-backend (or (:execution-backend value) "gondolin")]
    (when-not (and (string? task)
                   (not (str/blank? task))
                   (string? request-id)
                   (map? selection)
                   (= "approved" (:status selection))
                   (string? (:selectionId selection))
                   (string? (:selectionDigest selection))
                   (string? (:cut selection))
                   (string? (:projectId selection))
                   (string? (:containerId selection))
                   (string? (:componentId selection))
                   (string? (:slice selection))
                   (vector? (:items selection))
                   (seq (:items selection))
                   (every? (fn [item]
                             (every? #(string? (get item %))
                                     [:id :name :rule :source]))
                           (:items selection))
                   (vector? scope)
                   (seq scope)
                   (= "gondolin" execution-backend))
      (throw (ex-info
              "prompt metric requires a task, request-id, complete approved selection, non-empty scope, and Gondolin backend"
              {:input value})))
    {:task task
     :request-id request-id
     :selection selection
     :cut (:cut selection)
     :scope scope
     :target-stack (or (:target-stack value) "unspecified")
     :harness-snapshot-digest (:harness-snapshot-digest value)
     :initial-workspace-root (:initial-workspace-root value)
     :verification-evidence-root (:verification-evidence-root value)
     :execution-backend execution-backend}))

(defn evaluate-prompt
  ([input output] (evaluate-prompt :fresh-generation input output))
  ([purpose input output]
  (let [candidate (or (:prompt output) (get output "prompt"))
        _ (when-not (and (string? candidate) (not (str/blank? candidate)))
            (throw (ex-info "prompt metric requires a generated instruction" {:output output})))
        {:keys [request-id selection cut scope target-stack execution-backend
                harness-snapshot-digest initial-workspace-root
                verification-evidence-root]}
        (prompt-evaluation-input input)
        _ (when (and (= :verification-repair purpose)
                     (not (and (string? initial-workspace-root)
                               (string? verification-evidence-root))))
            (throw (ex-info "repair prompt metric requires fixed candidate and verifier evidence roots"
                            {:input input})))
        evaluation-id (str "gepa-" request-id "-" (subs (sha256 candidate) 0 12)
                           "-" (random-uuid))
        evidence (run-builder-adapter {:request-id evaluation-id
                                       :cut cut
                                       :scope scope
                                       :selection selection
                                       :target-stack target-stack
                                       :execution-backend execution-backend
                                       :purpose purpose
                                       :instruction candidate
                                       :initial-workspace-root (when (= :verification-repair purpose)
                                                                 initial-workspace-root)
                                       :verification-evidence-root (when (= :verification-repair purpose)
                                                                     verification-evidence-root)})
        subject-digest (subject/digest (:output-root evidence))
        evidence-root (str (:output-root evidence) ".evidence")
        grader-request {:cut cut :output-root (:output-root evidence)
                        :cycle-id evaluation-id
                        :evidence-root evidence-root
                        :execution-backend execution-backend
                        :target-stack target-stack}
        base-inputs {:request-id evaluation-id
                     :selection selection
                     :scope scope
                     :target-stack target-stack
                     :execution-backend execution-backend
                     :builder-result (assoc evidence :subject-digest subject-digest)
                     :instrument-result {:subject-digest subject-digest
                                         :evidence-root evidence-root}
                     :harness-snapshot-result
                     {:harness-snapshot-digest
                      (or harness-snapshot-digest
                          (str "sha256:" (sha256 "direct-prompt-evaluation")))}}
        affordance (affordance-conformance-stage {:inputs base-inputs})
        compose-verification (compose/interpret-verification
                              (run-grader-adapter (assoc grader-request :mode "compose")))
        verification (assoc compose-verification
                            :passed (and (:passed compose-verification)
                                         (:passed affordance))
                            :affordance affordance)
        verified-inputs (assoc base-inputs
                               :affordance-conformance-result affordance
                               :verify-result verification)
        cycle-oracle (when (:passed verification)
                       (ensure-cycle-oracle! verified-inputs))
        context {:inputs (assoc verified-inputs :oracle-result cycle-oracle)}
        grading (when (:passed verification)
                  (grade-with-cycle-oracle context))
        measurement (:measurement grading)
        valid-measurement? (and (:passed verification)
                                (:complete? measurement)
                                (pos? (:total measurement)))
        score (if valid-measurement?
                (double (/ (:passed measurement) (:total measurement)))
                0.0)
        feedback (if valid-measurement?
                   {:selected (:total measurement)
                    :passed (:passed measurement)
                    :failed (:failed measurement)
                    :failed-items (->> (:items measurement)
                                       (filter #(= "fail" (:verdict %)))
                                       (mapv #(select-keys % [:item-id :evidence :graded?])))}
                   {:affordance-passed (:passed affordance)
                    :verification-passed (:passed verification)
                    :reason "candidate did not reach a complete independent measurement"})]
    {:score score
     :feedback (json/write-str feedback)})))

(defn- request-purpose [{:keys [purpose] :as request}]
  (let [value (some-> purpose keyword)]
    (when-not (contains? prompt/purposes value)
      (throw (ex-info "prompt optimization requires purpose fresh-generation or verification-repair"
                      {:request request})))
    value))

(defn- enabled-reflection-model []
  (or (System/getenv "PI_HARNESS_GEPA_MODEL")
      "eu.amazon.nova-2-lite-v1:0"))

(defn optimize-builder-prompt!
  [{:keys [trainset valset reflectionProvider reflectionModel] :as request}]
  (when-not (and (vector? trainset) (seq trainset)
                 (vector? valset) (seq valset))
    (throw (ex-info "optimize-prompt requires non-empty trainset and valset vectors"
                    {:request request})))
  (let [purpose (request-purpose request)]
   (when-not (and (= "bedrock" reflectionProvider)
                  (= (enabled-reflection-model) reflectionModel))
     (throw (ex-info "optimize-prompt requires the Bedrock provider and an exact reflection model"
                     {:reflectionProvider reflectionProvider
                      :reflectionModel reflectionModel
                      :enabledReflectionModel (enabled-reflection-model)})))
   (doseq [example (concat trainset valset)]
    (prompt-evaluation-input example))
  (let [calibration (calibrate-harness!)
        snapshot (:harness-snapshot-digest calibration)
        normalized-trainset (mapv #(assoc (stringify-example-keys %)
                                          "harness-snapshot-digest" snapshot)
                                  trainset)
        normalized-valset (mapv #(assoc (stringify-example-keys %)
                                        "harness-snapshot-digest" snapshot)
                                valset)
        _ (ensure-runtime!)
        {:keys [context sheet-id]} (get @workflows (prompt-registration-key purpose))
        source-version (:version-number (orc/get-latest-version context sheet-id))
        result (gepa/optimize! context
                               {:sheet-id sheet-id
                                :trainset normalized-trainset
                                :valset normalized-valset
                                :metric-fn (fn [input output]
                                             (evaluate-prompt purpose input output))
                                :inherit-from-previous true
                                :block? true
                                :timeout-ms 1800000
                                :config {:max-metric-calls 12
                                         :reflection-lm reflectionModel}})
        winner (prompt/winner-instruction purpose result)
        application (when (= :completed (:status result))
                      (gepa/apply-winner! context (:optimization-id result) source-version))]
    {:status (name (:status result))
     :purpose (name purpose)
     :bestScore (:best-score result)
     :prompt winner
     :error (:error result)
     :optimizationId (some-> (:optimization-id result) str)
     :reflection {:provider "bedrock" :model reflectionModel}
     :activation application})))

(defn- await-gepa-candidate! [context optimization-id candidate-id timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [summary (gepa/get-optimization-summary context optimization-id)
            candidate (when candidate-id
                        (get-in (gepa/get-population-state context optimization-id)
                                [:candidates candidate-id]))]
        (cond
          (= :evaluated (:status candidate)) candidate
          (= :failed (:status summary))
          (throw (ex-info (or (:error-message summary) "GEPA optimization failed")
                          {:optimization-id optimization-id}))
          (> (System/currentTimeMillis) deadline)
          (throw (ex-info "timed out waiting for GEPA candidate evaluation"
                          {:optimization-id optimization-id
                           :candidate-id candidate-id}))
          :else (do (Thread/sleep 250) (recur)))))))

(defn- await-gepa-terminal-if-budget-exhausted! [context optimization-id]
  (when (gepa/budget-exhausted? context optimization-id)
    (loop [remaining 40]
      (let [summary (gepa/get-optimization-summary context optimization-id)]
        (when (and (= :running (:status summary)) (pos? remaining))
          (Thread/sleep 250)
          (recur (dec remaining)))))))

(defn- external-proposal-result [context optimization-id candidate]
  (let [proposal-context (gepa/get-external-proposal-context context optimization-id)
        best (gepa/get-best-candidate context optimization-id)]
    {:status (name (:status proposal-context))
     :optimizationId (str optimization-id)
     :candidateId (some-> (:candidate-id candidate) str)
     :candidateScore (:aggregate-score candidate)
     :candidatePrompt (some-> candidate :instructions vals first)
     :feedback (:feedbacks candidate)
     :metricCalls (:metric-calls proposal-context)
     :bestScore (:best-score proposal-context)
     :bestPrompt (some-> best :instructions vals first)}))

(defn start-external-prompt-optimization! [{:keys [trainset valset] :as request}]
  (when-not (and (vector? trainset) (seq trainset)
                 (vector? valset) (seq valset))
    (throw (ex-info "external prompt optimization requires non-empty trainset and valset vectors"
                    {:request request})))
  (let [purpose (request-purpose request)]
   (doseq [example (concat trainset valset)]
    (prompt-evaluation-input example))
  (let [normalized-trainset (mapv stringify-example-keys trainset)
        normalized-valset (mapv stringify-example-keys valset)
        _ (calibrate-harness!)
        _ (ensure-runtime!)
        {:keys [context sheet-id]} (get @workflows (prompt-registration-key purpose))
        started (gepa/optimize! context
                                {:sheet-id sheet-id
                                 :trainset normalized-trainset
                                 :valset normalized-valset
                                 :metric-fn (fn [input output]
                                              (evaluate-prompt purpose input output))
                                 :inherit-from-previous false
                                 :block? false
                                 ;; One seed plus one proposal authored by the
                                 ;; active Pi/Codex session.
                                 :config {:max-metric-calls 2
                                          :external-proposer? true}})
        optimization-id (:optimization-id started)
        seed-id (loop [remaining 7200]
                  (let [summary (gepa/get-optimization-summary context optimization-id)
                        population (gepa/get-population-state context optimization-id)
                        candidate-id (first (:evaluated population))]
                    (cond
                      candidate-id candidate-id
                      (= :failed (:status summary))
                      (throw (ex-info (or (:error-message summary) "GEPA seed evaluation failed")
                                      {:optimization-id optimization-id}))
                      (zero? remaining)
                      (throw (ex-info "timed out waiting for GEPA seed evaluation"
                                      {:optimization-id optimization-id}))
                      :else (do (Thread/sleep 250) (recur (dec remaining))))))
        seed (get-in (gepa/get-population-state context optimization-id)
                     [:candidates seed-id])]
    (assoc (external-proposal-result context optimization-id seed)
           :purpose (name purpose)))))

(defn- optimization-purpose [context optimization-id]
  (let [sheet-id (:sheet-id (gepa/get-optimization-summary context optimization-id))]
    (some (fn [purpose]
            (when (= sheet-id (:sheet-id (get @workflows (prompt-registration-key purpose))))
              purpose))
          prompt/purposes)))

(defn submit-external-prompt-candidate!
  [{:keys [optimizationId prompt proposer] :as request}]
  (when-not (and (string? optimizationId) (string? prompt) (not (str/blank? prompt)))
    (throw (ex-info "external prompt candidate requires optimizationId and a non-empty prompt"
                    {:request request})))
  (ensure-runtime!)
  (let [optimization-id (java.util.UUID/fromString optimizationId)
        context (:context (get @workflows :generation-prompt))
        purpose (or (optimization-purpose context optimization-id)
                    (throw (ex-info "optimization does not belong to a builder purpose"
                                    {:optimizationId optimizationId})))
        submitted (gepa/submit-external-candidate!
                   context optimization-id
                   {(prompt/component-name purpose) prompt}
                   (or proposer (builder-id)))
        candidate (await-gepa-candidate! context optimization-id
                                         (:candidate-id submitted) 1800000)
        _ (await-gepa-terminal-if-budget-exhausted! context optimization-id)
        result (external-proposal-result context optimization-id candidate)
        summary (gepa/get-optimization-summary context optimization-id)
        application (when (= :completed (:status summary))
                      (let [latest (orc/get-latest-version
                                    context
                                    (:sheet-id (get @workflows (prompt-registration-key purpose))))]
                        (gepa/apply-winner! context optimization-id (:version-number latest))))]
    (assoc result :purpose (name purpose) :reused (:reused? submitted)
           :activation application)))

(defn run-grader-adapter [request]
  (adapters/run-grader! {:project-root (project-root)
                         :cuts-root (cuts-root)}
                        request))

(defn interpret-command [result]
  {:passed (zero? (or (:exitCode result) 1))
   :exit-code (or (:exitCode result) 1)
   :stdout (or (:stdout result) "")
   :stderr (or (:stderr result) "")})

(defn sha256 [value]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest (.getBytes (str value) "UTF-8"))))))

(defn sha256-file [file]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        buffer (byte-array 8192)]
    (with-open [input (io/input-stream file)]
      (loop []
        (let [read (.read input buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur)))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn evidence-results-root [inputs]
  (str (:evidence-root (effective-instrument-result inputs)) "/allure-results"))

(defn emit-allure! [name context result]
  (let [directory (evidence-results-root (:inputs context))
        uuid (str (java.util.UUID/randomUUID))
        now (System/currentTimeMillis)
        history-id (sha256 (str "loyly:" name))
        cycle-id (str (get-in context [:inputs :request-id]))
        cut-digest (str (get-in context [:inputs :cut]))
        oracle-digest (or (:oracle-digest result)
                          (get-in context [:inputs :oracle-result :oracle-digest])
                          (System/getenv "LOYLEY_ORACLE_DIGEST")
                          "unconfigured")
        labels [{:name "project" :value (get-in context [:inputs :selection :projectId])}
                {:name "cycle_id" :value cycle-id}
                {:name "cut_digest" :value cut-digest}
                {:name "execution_backend" :value (str (get-in context [:inputs :execution-backend]))}
                {:name "producer" :value "pi-harness-orc"}
                {:name "role" :value "observed"}
                {:name "evidence_kind" :value (if (= name "oracle") "tool" "grader")}
                {:name "oracle_digest" :value oracle-digest}]
        _ (java.nio.file.Files/createDirectories (java.nio.file.Paths/get directory (into-array String []))
                                                   (into-array java.nio.file.attribute.FileAttribute []))
        attachment (fn [suffix content]
                     (let [source (str uuid "-" suffix ".txt")]
                       (spit (str directory "/" source) content)
                       {:name suffix :source source :type "text/plain"}))
        allure {:uuid uuid
                :historyId history-id
                :name name
                :fullName (str (get-in context [:inputs :selection :projectId]) ".regeneration." name)
                :status (if (:passed result) "passed" "failed")
                :stage "finished"
                :start now
                :stop now
                :labels labels
                :parameters [{:name "cycle_id" :value cycle-id}
                             {:name "cut_digest" :value cut-digest}
                             {:name "execution_backend" :value (str (get-in context [:inputs :execution-backend]))}]
                :attachments [(attachment "stdout" (:stdout result))
                              (attachment "stderr" (:stderr result))]
                :statusDetails (when-not (:passed result) {:message (str "exit code " (:exit-code result))})}]
    (spit (str directory "/" uuid "-result.json") (json/write-str allure))
    (str directory "/" uuid "-result.json")))

(defn emit-grader-observations!
  ([context measurement]
   (emit-grader-observations! context measurement {}))
  ([{:keys [inputs]} measurement {:keys [producer grader-id evidence-kind]}]
  (let [results-root (evidence-results-root inputs)
        builder (effective-builder-result inputs)
        instrument (effective-instrument-result inputs)
        baseline-grade (effective-grade-result inputs)
        base {:role "observed"
              :builder (:builder builder)
              :evidence-kind (or evidence-kind "grader")
              :producer (or producer
                            (get-in inputs [:oracle-result :grader-producer])
                            (:grader-producer baseline-grade))
              :grader-id (or grader-id
                             (get-in inputs [:oracle-result :grader-id])
                             (:judge-id baseline-grade))
              :container-id (get-in inputs [:selection :containerId])
              :component-id (get-in inputs [:selection :componentId])
              :cycle-id (:request-id inputs)
              :cut-digest (get-in inputs [:selection :cut])
              :subject-digest (:subject-digest instrument)
              :oracle-digest (or (get-in inputs [:oracle-result :oracle-digest])
                                 (:normalized-evidence-digest baseline-grade)
                                 (System/getenv "LOYLEY_ORACLE_DIGEST")
                                 "unconfigured")
              :room (:execution-backend inputs)
              :scope (str/join "," (:scope inputs))}]
    (mapv (fn [{:keys [item-id verdict evidence graded?]}]
            (allure-evidence/write-observation!
             results-root
             (merge base {:item-id item-id
                          :result verdict
                          :reached? graded?
                          :evidence evidence})))
          (:items measurement)))))

(defn materialize-allure! [{:keys [inputs] :as context}]
  (let [grade (effective-grade-result inputs)
        conformance (effective-instrument-conformance-result inputs)
        affordance (effective-affordance-conformance-result inputs)
        denominator (count (get-in inputs [:selection :items]))]
    (when-not (and (:passed grade) (:passed conformance) (:passed affordance)
                   (= denominator (get-in grade [:measurement :passed])))
      (throw (ex-info "Allure results require a complete first-grade measurement"
                      {:grade grade :instrument-conformance conformance
                       :affordance-conformance affordance})))
    (let [grade-paths (emit-grader-observations! context (:measurement grade))
          conformance-paths
          (emit-grader-observations!
           context {:items (:items conformance)}
           {:producer "runtime-instrument-conformance"
            :grader-id "runtime-evidence-contract"
            :evidence-kind "instrument-conformance"})
          affordance-path
          (emit-allure! "affordance-conformance" context
                        {:passed (:passed affordance)
                         :exit-code (if (:passed affordance) 0 1)
                         :stdout (:evidence affordance)
                         :stderr ""})
          paths (conj (into grade-paths conformance-paths) affordance-path)]
      (merge (stage-result "materialize-allure" context)
             {:result-count (count paths)
              :paths paths}))))

(defn cycle-observation [inputs producer evidence-kind item]
  (let [instrument (effective-instrument-result inputs)
        cycle-id (:request-id inputs)
        item-id (:item-id item)
        evidence (:evidence item)]
    {:cycle-id cycle-id
     :cut-digest (get-in inputs [:selection :cut])
     :project-id (get-in inputs [:selection :projectId])
     :container-id (get-in inputs [:selection :containerId])
     :component-id (get-in inputs [:selection :componentId])
     :item-id item-id
     :subject-digest (:subject-digest instrument)
     :producer producer
     :producer-authority (if (= "grader" evidence-kind)
                           "independent-grader"
                           "harness-observer")
     :evidence-purpose "assertion"
     :claim-type "gherkin-scenario-verdict"
     :evaluation-id (str "evaluation-" (sha256 (str cycle-id "|" item-id "|" producer)))
     :evidence-digest (sha256 evidence)
     ;; Kept as a presentation hint for the existing Allure/OTel adapters. It
     ;; is not part of the durable event contract.
     :evidence-kind evidence-kind
     :result (or (:result item) (:verdict item))
     :reached? (if (contains? item :reached?) (:reached? item) (:graded? item))
     :evidence evidence
     :collection {:complete? true :sampled? false :dropped 0}
     :correlation {:operation-id cycle-id}
     :artifacts []
     :oracle-digest (or (get-in inputs [:oracle-result :oracle-digest])
                        (get-in inputs [:grade-result
                                        :normalized-evidence-digest])
                        "unpromoted")}))


(defn emit-cycle-observation! [{:keys [inputs evidence-manifest]} value]
  (evidence-projection/project!
   (:evidence-root (effective-instrument-result inputs))
   (merge value
          {:role "observed"
           :builder (:builder (effective-builder-result inputs))
           :room (:execution-backend inputs)
           :scope (str/join "," (:scope inputs))
           :evidence-manifest evidence-manifest})))

(defn- persisted-evaluation [manifest observation]
  (assoc observation
         :evaluation-id
         (str "evaluation-"
              (sha256 (str/join "|" (map observation
                                           [:cycle-id :item-id :evidence-kind :producer]))))
         :evidence-digest (:digest manifest)
         :producer-authority (if (= "grader" (:evidence-kind observation))
                               "independent-grader"
                               "harness-observer")
         :evidence-purpose "assertion"
         :claim-type "gherkin-scenario-verdict"
         :collection {:complete? true :sampled? false :dropped 0}
         :correlation {:operation-id (:cycle-id observation)}
         :artifacts (mapv (fn [{:keys [sha256 media-type path]}]
                            {:digest sha256 :media-type media-type :location path})
                          (:artifacts manifest))))

(defn resolve-accepted-oracle [{:keys [inputs] :as context}]
  (let [selection (:selection inputs)
        adapter (project-adapter selection)
        result (run-oracle (get-in inputs [:selection :cut])
                           selection
                           (product-oracle-options selection))
        accepted-contract-digest (:oracle-digest result)
        harness-snapshot-digest (get-in inputs [:harness-snapshot-result
                                                 :harness-snapshot-digest])
        combined-digest (sha256 (str harness-snapshot-digest "\n"
                                     accepted-contract-digest))]
    (when-not (:passed result)
      (throw (ex-info "oracle rejected the pinned model contract" {:oracle result})))
    (merge (stage-result "oracle" context)
           {:passed true
            :oracle-digest combined-digest
            :accepted-contract-digest accepted-contract-digest
            :harness-snapshot-digest harness-snapshot-digest
            :grader-root (:grader-root result)
            :observability-root (:observability-root result)
            :grader-producer (:graderProducer adapter)
            :grader-id (:graderId adapter)
            :evidence (json/write-str result)})))

(defn- interpret-affordance-conformance [evidence]
  (let [required [:affordance-runtime :affordance-evidence :affordance-up
                  :affordance-grade :affordance-down]
        command-failures (->> required
                              (remove #(zero? (or (get-in evidence [% :exitCode]) 1)))
                              vec)
        payload (try
                  (json/read-str (get-in evidence [:affordance-grade :stdout] "")
                                 :key-fn keyword)
                  (catch Exception _ nil))
        applicable (not (false? (:applicable payload)))
        checks (if (vector? (:checks payload)) (:checks payload) [])
        passed-count (count (filter #(true? (:passed %)) checks))
        passed (and (empty? command-failures)
                    (true? (:passed payload))
                    (or (not applicable) (seq checks))
                    (= passed-count (count checks)))]
    {:passed (boolean passed)
     :applicable applicable
     :total (count checks)
     :passed-count passed-count
     :command-failures (mapv name command-failures)
     :checks checks
     :stdout (get-in evidence [:affordance-grade :stdout] "")
     :stderr (str/join "\n" (remove str/blank?
                                     (map #(get-in evidence [% :stderr] "") required)))}))

(defn affordance-conformance-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "affordance-conformance")
  ;; The effective candidate: after verification repair this is the repaired one.
  (let [builder (effective-builder-result inputs)
        instrument (effective-instrument-result inputs)
        grader-root (or (get-in inputs [:oracle-result :grader-root])
                        (baseline-grader-root))
        evidence (run-grader-adapter
                  {:cycle-id (:request-id inputs)
                   :cut (get-in inputs [:selection :cut])
                   :output-root (:output-root builder)
                   :evidence-root (:evidence-root instrument)
                   :evidence-namespace "affordance-conformance"
                   :execution-backend (:execution-backend inputs)
                   :grader-root grader-root
                   :project-id (get-in inputs [:selection :projectId])
                   :slice (get-in inputs [:selection :slice])
                   :target-stack (:target-stack inputs)
                   :mode "affordance"})
        result (interpret-affordance-conformance evidence)]
    (merge (stage-result "affordance-conformance" context)
           {:passed (:passed result)
            :applicable (:applicable result)
            :total (:total result)
            :passed-count (:passed-count result)
            :evidence (json/write-str (assoc result :commands evidence))})))

(defn verify-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "verify")
  (let [builder (effective-builder-result inputs)
        instrument (effective-instrument-result inputs)
        stack-result (stack-conformance/check (:output-root builder)
                                               (:target-stack inputs))
        evidence (run-grader-adapter {:cycle-id (:request-id inputs)
                                      :cut (get-in inputs [:selection :cut])
                                      :output-root (:output-root builder)
                                      :evidence-root (:evidence-root instrument)
                                      :execution-backend (:execution-backend inputs)
                                      :target-stack (:target-stack inputs)
                                      :mode "compose"})
        compose-result (compose/interpret-verification evidence)
        result {:passed (and (:passed stack-result)
                             (:passed compose-result))
                :stack stack-result
                :compose compose-result}]
    (merge (stage-result "verify" context)
           {:passed (:passed result)
            :evidence (json/write-str result)})))

(def max-verification-rounds 3)

(defn repair-verification-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "repair-verification")
  (let [initial-builder (:builder-result inputs)
        initial-instrument (:instrument-result inputs)
        initial-affordance (:affordance-conformance-result inputs)
        initial-verify (:verify-result inputs)]
    (loop [round 1
           builder initial-builder
           instrument initial-instrument
           affordance initial-affordance
           verification initial-verify
           attempts [{:round 1
                      :subject-digest (:subject-digest initial-builder)
                      :evidence-root (:evidence-root initial-instrument)
                      :passed (boolean (:passed initial-verify))}]]
      (if (or (:passed verification) (>= round max-verification-rounds))
        (merge (stage-result "repair-verification" context)
               {:passed (boolean (:passed verification))
                :rounds round
                :builder builder
                :instrument instrument
                :affordance affordance
                :verify verification
                :attempts attempts})
        (let [next-round (inc round)
              repair-id (format "%s-repair-%02d" (:request-id inputs) next-round)
              builder-session-ref (str (random-uuid))
              _ (record-stage-progress! context "builder" next-round
                                        builder-session-ref "running")
              raw-builder (run-builder-adapter
                           {:request-id repair-id
                            :cut (get-in inputs [:selection :cut])
                            :scope (:scope inputs)
                            :selection (:selection inputs)
                            :target-stack (:target-stack inputs)
                            :execution-backend (:execution-backend inputs)
                            :initial-workspace-root (:output-root builder)
                            :verification-evidence-root (:evidence-root instrument)
                            :round next-round
                            :builder-session-ref builder-session-ref
                            :builder-sessions-root (builder-sessions-root)
                            :attempt next-round})
              repaired-builder (merge (stage-result "builder" {:inputs (assoc inputs :request-id repair-id)})
                                      raw-builder
                                      {:builder (or (:builder raw-builder) (builder-id))
                                       :subject-digest (subject/digest (:output-root raw-builder))})
              repaired-inputs (assoc inputs
                                     :request-id repair-id
                                     :builder-result repaired-builder)
              repaired-context {:inputs repaired-inputs
                                :tick-id (:tick-id context)
                                :cycle-id (:request-id inputs)}
              repaired-instrument (instrument-stage repaired-context)
              repaired-verification (verify-stage
                                     (assoc repaired-context :inputs
                                            (assoc repaired-inputs
                                                   :instrument-result repaired-instrument)))]
          (recur next-round
                 repaired-builder
                 repaired-instrument
                 affordance
                 repaired-verification
                 (conj attempts
                       {:round next-round
                        :subject-digest (:subject-digest repaired-builder)
                        :evidence-root (:evidence-root repaired-instrument)
                        :passed (boolean (:passed repaired-verification))})))))))

(defn grade-against-accepted-oracle [{:keys [inputs] :as context}]
  (let [verification (effective-verify-result inputs)
        _ (when-not (:passed verification)
            (throw (ex-info "verification did not pass; independent grading was not run"
                            {:verification verification})))
        builder (effective-builder-result inputs)
        instrument (effective-instrument-result inputs)
        gondolin? (= "gondolin" (:execution-backend inputs))
        selected-items (get-in inputs [:selection :items])
        adapter (project-adapter (:selection inputs))
        item-runs (when gondolin?
                    (mapv (fn [item]
                            (let [item-id (:id item)
                                  expected-id (str/replace item-id #"^@item-" "")
                                  evidence (run-grader-adapter
                                            {:cycle-id (:request-id inputs)
                                             :cut (get-in inputs [:selection :cut])
                                             :output-root (:output-root builder)
                                             :evidence-root (:evidence-root instrument)
                                             :evidence-namespace (str "independent/" expected-id)
                                             :execution-backend "gondolin"
                                             :item-ids [item-id]
                                             :grader-script
                                             (project-profile/grader-script adapter item-id)
                                             :grader-root (get-in inputs [:oracle-result :grader-root])
                                             :observability-root (get-in inputs [:oracle-result :observability-root])
                                             :target-stack (:target-stack inputs)
                                             :mode "independent"})]
                              {:item-id item-id
                               :evaluation (independent-evidence/interpret [expected-id] evidence)}))
                          selected-items))
        independent (if gondolin?
                      {:passed (every? #(get-in % [:evaluation :passed]) item-runs)
                       :verdicts (vec (mapcat #(get-in % [:evaluation :verdicts]) item-runs))
                       :stdout (str/join "\n" (map #(get-in % [:evaluation :stdout]) item-runs))
                       :stderr (str/join "\n" (remove str/blank? (map #(get-in % [:evaluation :stderr]) item-runs)))}
                      {:passed false :verdicts [] :stdout ""
                       :stderr "independent grading currently requires Gondolin"})
        _ (when-not (seq (:verdicts independent))
            (throw (ex-info "independent grader produced no readable observation"
                            {:independent independent})))
        measurement (model/reduce-result
                     {:cycle-id (:request-id inputs)
                      :selection-id (get-in inputs [:selection :selectionId])
                      :cut (get-in inputs [:selection :cut])
                      :backend (:execution-backend inputs)
                      :selected-items selected-items
                      :verdicts (:verdicts independent)})
        passed (and (get-in inputs [:oracle-result :passed])
                    (:passed verification)
                    (:complete? measurement)
                    (zero? (:failed measurement)))
        observations (mapv #(cycle-observation inputs
                                               (get-in inputs [:oracle-result :grader-producer])
                                               "grader" %)
                           (:items measurement))]
    (merge (stage-result "graders" context)
           {:passed (boolean passed)
            :observations observations
            :measurement measurement
            :evidence (json/write-str {:mode (if gondolin? "independent" "compose-only")
                                       :oracle (get inputs :oracle-result)
                                       :verify verification
                                       :independent independent})})))

(defn baseline-grader-root []
  (let [configured (or (System/getenv "PI_HARNESS_VALIDATOR_ROOT")
                       (System/getenv "LOYLEY_VALIDATOR_ROOT")
                       (System/getenv "PI_HARNESS_BASELINE_GRADER_ROOT")
                       (System/getenv "LOYLEY_BASELINE_GRADER_ROOT"))
        root (when configured (.getCanonicalFile (io/file configured)))]
    (when-not (and root (.isDirectory root)
                   (.isFile (io/file root "affordance-conformance.mjs"))
                   (.isFile (io/file root "runtime-conformance.py")))
      (throw (ex-info "initial generation requires the conformance validator package"
                      {:configured configured})))
    (.getPath root)))

(def max-cycle-oracle-attempts 3)

(defn- failed-calibration-note [failed-cases]
  (when (seq failed-cases)
    (str "\n\nA previously authored grader was discarded because it failed public calibration. "
         "Write a new grader from scratch; do not assume the previous one was close. "
         "These public calibration cases were wrong (carried must pass, uncarried must fail):\n"
         (str/join "\n" (map json/write-str failed-cases)))))

(defn- grader-author-prompt
  ([selection] (grader-author-prompt selection []))
  ([selection failed-cases]
  (str
   "Author an independent black-box grader for the approved selection in /cut. "
   "The only product inputs available are spec.feature, selection.json, and the public "
   "calibration protocol source. Do not infer or inspect an implementation.\n\n"
   "Write /workspace/grader/grade.mjs. It must accept a public base URL as argv[2] "
   "followed by one or more '--item @item-NN-NNN' pairs, drive only that HTTP seam, "
   "and print one JSON array containing exactly one {itemId, verdict, evidence} object "
   "per requested item. itemId omits the '@item-' prefix and evidence must be a concise "
   "non-empty string, never an object or array. Exit zero only when every "
   "requested item passes. Exercise behavior, not a rigid response schema: accept "
   "equivalent kebab/camel field spellings and semantically equivalent representations "
   "unless the Gherkin requires exact wording. The selected Gherkin is authoritative: "
   "do not replace an explicit outcome with a conventional business constraint.\n\n"
   "The public fixture's 'carried' role implements every selected scenario and must pass. "
   "Its 'uncarried' role deliberately violates only the requested item and must fail. "
   "Use that protocol to make every selected item independently discriminating. Drive HTTP "
   "with Playwright's API request context (context.request.fetch or request.newContext), not "
   "page.evaluate/fetch, so calibration works without a browser page origin. Close Playwright "
   "BrowserContext objects with await context.close(); BrowserContext has no dispose() method.\n\n"
   "Use playwright-core and Chromium at /usr/bin/chromium-browser. When "
   "HARNESS_EVIDENCE_ROOT is set, capture a genuine Playwright trace at "
   "$HARNESS_EVIDENCE_ROOT/playwright/baseline/trace.zip and write useful exchanges "
   "under $HARNESS_EVIDENCE_ROOT/baseline-grade. Calibration may omit that variable, "
   "so evidence-file writes must then be skipped while verdict behavior remains identical. "
   "Do not install dependencies and do not access any path except /cut and /workspace.\n\n"
   "Selected identities: " (str/join ", " (map :id (:items selection))) "."
   (failed-calibration-note failed-cases))))

(defn- grader-binding-digest [inputs task-prompt]
  (str "sha256:"
         (sha256 (json/write-str
                (sorted-map :cut (get-in inputs [:selection :cut])
                            :selection-digest (get-in inputs [:selection :selectionDigest])
                            :harness-snapshot-digest
                            (get-in inputs [:harness-snapshot-result :harness-snapshot-digest])
                            :grader-model (or (System/getenv "PI_GRADER_MODEL")
                                              (System/getenv "PI_BUILDER_MODEL")
                                              "gpt-5.6-luna")
                            :system-prompt-digest (prompt/digest grading-prompt/system-instruction)
                            :task-prompt-digest (prompt/digest task-prompt))))))

(defn- valid-calibration-case? [{:keys [itemId role exitCode stdout]}]
  (try
    (let [payload (json/read-str stdout :key-fn keyword)
          verdict (when (and (vector? payload) (= 1 (count payload))) (first payload))
          expected-id (str/replace itemId #"^@item-" "")
          expected-verdict (if (= role "carried") "pass" "fail")]
      (and (= (:itemId verdict) expected-id)
           (= (:verdict verdict) expected-verdict)
           (string? (:evidence verdict))
           (not (str/blank? (:evidence verdict)))
           (if (= role "carried") (zero? exitCode) (not (zero? exitCode)))))
    (catch Exception _ false)))

(defn- grader-calibration! [inputs grader-root]
  (let [selected-items (get-in inputs [:selection :items])
        fixture-source (slurp (str (cuts-root) "/" (get-in inputs [:selection :cut])
                                  "/model/" (get-in inputs [:selection :projectId])
                                  "/fixtures/oracle-fixture-server.mjs"))
        cases (mapv (fn [[item role]]
                      {:itemId (:id item) :role role :graderPath "grade.mjs"})
                    (for [item selected-items role ["carried" "uncarried"]]
                      [item role]))
        result (run-oracle-fixture-adapter {:fixtureSource fixture-source
                                            :graderRoot grader-root
                                            :cases cases})
        failures (filterv (complement valid-calibration-case?) (:cases result))]
    (assoc result :failures failures)))

(defn- failed-case-summary [{:keys [itemId role exitCode stdout]}]
  {:itemId itemId
   :role role
   :expected (if (= role "carried") "pass" "fail")
   :exitCode exitCode
   :output (let [text (str stdout)] (subs text 0 (min 500 (count text))))})

(defn- discard-cycle-oracle! [output-root]
  (let [root (io/file output-root)]
    (when (.exists root)
      (doseq [file (reverse (file-seq root))]
        (.delete file)))))

(defn ensure-cycle-oracle! [inputs]
  (let [snapshot-digest (get-in inputs [:harness-snapshot-result :harness-snapshot-digest])]
    (loop [attempt 1
           failed-cases []]
      (let [task-prompt (grader-author-prompt (:selection inputs) failed-cases)
            binding-digest (grader-binding-digest inputs task-prompt)
            output-root (str (project-root) "/.pi/harness/.state/cycle-oracles/"
                             (subs binding-digest 7))
            result (run-grader-author-adapter
                    {:backend (:execution-backend inputs)
                     :cutId (get-in inputs [:selection :cut])
                     :cutsRoot (cuts-root)
                     :projectId (get-in inputs [:selection :projectId])
                     :slice (get-in inputs [:selection :slice])
                     :itemIds (mapv :id (get-in inputs [:selection :items]))
                     :systemPrompt grading-prompt/system-instruction
                     :prompt task-prompt
                     :outputRoot output-root
                     :bindingDigest binding-digest
                     :harnessSnapshotDigest snapshot-digest
                     :selectionDigest (get-in inputs [:selection :selectionDigest])
                     :sessionsRoot (builder-sessions-root)})
            calibration (grader-calibration! inputs (:graderRoot result))
            failures (:failures calibration)]
        (cond
          (empty? failures)
          {:grader-root (:graderRoot result)
           :cycle-oracle-digest (:cycleOracleDigest result)
           :evaluator-kinds (:evaluatorKinds result)
           :binding-digest binding-digest
           :author-session-id (:sessionId result)
           :reused (:reused result)
           :authoring-attempts attempt
           :calibration (dissoc calibration :failures)}

          (< attempt max-cycle-oracle-attempts)
          (do (discard-cycle-oracle! output-root)
              (recur (inc attempt) (mapv failed-case-summary failures)))

          :else
          (do (discard-cycle-oracle! output-root)
              (throw (ex-info (str "authored black-box grader failed carried/uncarried calibration after "
                                   attempt " attempts: "
                                   (str/join ", " (map #(str (:itemId %) " " (:role %)) failures)))
                              {:failures (mapv failed-case-summary failures)
                               :attempts attempt}))))))))

(defn author-cycle-oracle [{:keys [inputs] :as context}]
  (let [oracle (ensure-cycle-oracle! inputs)]
    (merge (stage-result "cycle-oracle" context)
           {:passed true
            :grader-root (:grader-root oracle)
            :cycle-oracle-digest (:cycle-oracle-digest oracle)
            :binding-digest (:binding-digest oracle)
            :author-session-id (:author-session-id oracle)
            :evidence (json/write-str (dissoc oracle :calibration))})))

(defn- baseline-playwright-trace [evidence-root cycle-id]
  (let [trace (io/file evidence-root "cycles" cycle-id
                       "playwright" "baseline" "trace.zip")]
    (when (and (.isFile trace) (pos? (.length trace)))
      {:path "playwright/baseline/trace.zip"
       :sha256 (sha256-file trace)
       :bytes (.length trace)})))

(defn grade-with-cycle-oracle [{:keys [inputs] :as context}]
  (let [verification (effective-verify-result inputs)
        _ (when-not (:passed verification)
            (throw (ex-info "verification did not pass; baseline grading was not run"
                            {:verification verification})))
        builder (effective-builder-result inputs)
        instrument (effective-instrument-result inputs)
        selected-items (get-in inputs [:selection :items])
        expected-ids (mapv #(str/replace (:id %) #"^@item-" "") selected-items)
        authored-grader (or (:oracle-result inputs)
                            (throw (ex-info "grading requires the cycle oracle sealed by the oracle stage"
                                            {:request-id (:request-id inputs)})))
        grader-root (:grader-root authored-grader)
        evidence (run-grader-adapter
                  {:cycle-id (:request-id inputs)
                   :cut (get-in inputs [:selection :cut])
                   :output-root (:output-root builder)
                   :evidence-root (:evidence-root instrument)
                   :evidence-namespace "baseline-grade"
                   :execution-backend (:execution-backend inputs)
                   :grader-root grader-root
                   :target-stack (:target-stack inputs)
                   :item-ids (mapv :id selected-items)
                   :mode "baseline"})
        grade-command (:baseline-grade evidence)
        interpreted (independent-grader/interpret-verdicts expected-ids (:stdout grade-command))
        required-commands [:baseline-runtime :baseline-evidence :baseline-up
                           :baseline-observe-start :baseline-grade
                           :baseline-observe-stop :baseline-down]
        command-failures (->> required-commands
                              (remove #(zero? (or (get-in evidence [% :exitCode]) 1)))
                              vec)
        measurement (model/reduce-result
                     {:cycle-id (:request-id inputs)
                      :selection-id (get-in inputs [:selection :selectionId])
                      :cut (get-in inputs [:selection :cut])
                      :backend (:execution-backend inputs)
                      :selected-items selected-items
                      :verdicts (:verdicts interpreted)})
        normalized (mapv (fn [item verdict]
                           (sorted-map :item-id (:id item)
                                       :scenario-digest (:scenario-digest item)
                                       :verdict (:verdict verdict)
                                       :evidence (:evidence verdict)))
                         selected-items (:items measurement))
        playwright-trace (baseline-playwright-trace
                          (:evidence-root instrument) (:request-id inputs))
        normalized-evidence-digest
        (sha256 (json/write-str (sorted-map :items normalized
                                            :playwright-trace playwright-trace)))
        passed (and (:passed interpreted)
                    (empty? command-failures)
                    (:complete? measurement)
                    (zero? (:failed measurement))
                    (some? playwright-trace))]
    (merge (stage-result "baseline-grade" context)
           {:passed (boolean passed)
            :judge-id (str "sha256:" (subject/digest grader-root))
            :grader-root grader-root
            :grader-producer "independent-baseline-judge"
            :grader-author-session-id (:author-session-id authored-grader)
            :grader-binding-digest (:binding-digest authored-grader)
            :measurement measurement
            :playwright-trace playwright-trace
            :normalized-evidence-digest normalized-evidence-digest
            :evidence (json/write-str {:commands evidence
                                       :calibration (:calibration authored-grader)
                                       :command-failures (mapv name command-failures)
                                       :normalized normalized
                                       :playwright-trace playwright-trace})})))

(defn propose-oracle! [{:keys [inputs] :as context}]
  (let [grade (effective-grade-result inputs)
        conformance (effective-instrument-conformance-result inputs)
        selection (:selection inputs)
        builder (effective-builder-result inputs)
        denominator (count (:items selection))]
    (when-not (and (:passed grade)
                   (:passed conformance)
                   (= denominator (get-in grade [:measurement :total]))
                   (= denominator (get-in grade [:measurement :passed]))
                   (zero? (get-in grade [:measurement :failed])))
      (throw (ex-info "first black-box grade did not completely pass"
                      {:grade grade :instrument-conformance conformance
                       :denominator denominator})))
    (let [payload (sorted-map
                   :generation-id (:request-id inputs)
                   :selection-id (:selectionId selection)
                   :selection-digest (:selectionDigest selection)
                   :scenario-digests (mapv :scenario-digest (:items selection))
                   :subject-digest (:subject-digest builder)
                   :judge-id (:judge-id grade)
                   :denominator denominator
                   :normalized-evidence-digest (:normalized-evidence-digest grade))
          proposal-digest (str "sha256:" (sha256 (json/write-str payload)))
          proposal (assoc payload
                          :proposal-id (str "oracle-proposal-" (subs proposal-digest 7 31))
                          :proposal-digest proposal-digest
                          :status "proposed")
          persisted (model/record-oracle-proposal! (:context @runtime) proposal)]
      (when-not (= proposal persisted)
        (throw (ex-info "persisted oracle proposal does not match first-grade evidence" {})))
      (record-stage-progress! context "propose-oracle" 1 nil "completed")
      persisted)))

(defn instrument-conformance-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "instrument-conformance")
  (let [builder (effective-builder-result inputs)
        instrument (effective-instrument-result inputs)
        selected-items (get-in inputs [:selection :items])
        adapter (project-adapter (:selection inputs))
        grading-context (effective-grading-context inputs)
        contract-file (str (cuts-root) "/" (get-in inputs [:selection :cut])
                           "/" (:runtimeEvidenceContract adapter))
        contract-items (try
                         (->> (json/read-str (slurp contract-file) :key-fn keyword)
                              :contracts (map :itemId) set)
                         ;; Preserve the selected denominator when the contract cannot be
                         ;; inspected here; the external evaluator will then report the
                         ;; missing or unreadable contract rather than silently skipping it.
                         (catch Exception _ (set (map :id selected-items))))
        instrumented-items (filterv #(contains? contract-items (:id %)) selected-items)
        expected-ids (mapv #(str/replace (:id %) #"^@item-" "") instrumented-items)
        gondolin? (= "gondolin" (:execution-backend inputs))
        ;; No selected item has a runtime-evidence contract: the applicable denominator is
        ;; empty, so record that explicitly instead of invoking the evaluator with no items.
        not-applicable? (empty? instrumented-items)
        conformance-grader-root (or (get-in inputs [:oracle-result :grader-root])
                                    (baseline-grader-root))
        evidence (when (and gondolin? (not not-applicable?))
                   (run-grader-adapter {:cycle-id (:request-id inputs)
                                        :cut (get-in inputs [:selection :cut])
                                        :output-root (:output-root builder)
                                        :evidence-root (:evidence-root instrument)
                                        :execution-backend "gondolin"
                                        :project-id (get-in inputs [:selection :projectId])
                                        :grader-root conformance-grader-root
                                        :observability-root (:observability-root grading-context)
                                        :item-ids (mapv :id instrumented-items)
                                        :mode "instrument"}))
        command (get evidence :instrument-conformance)
        interpreted (cond
                      not-applicable?
                      {:passed true :total 0 :passed-count 0 :verdicts []}

                      gondolin?
                      (independent-grader/interpret-verdicts expected-ids (:stdout command))

                      :else
                      (independent-grader/interpret-verdicts
                       expected-ids
                       (json/write-str
                        (mapv (fn [item-id]
                                {:itemId item-id :verdict "fail"
                                 :evidence "instrument conformance currently requires Gondolin"})
                              expected-ids))))
        command-passed (or not-applicable?
                           (and command (zero? (or (:exitCode command) 1))))
        passed (and (:passed interpreted) command-passed)
        items (mapv (fn [{:keys [item-id verdict evidence]}]
                      {:item-id (str "@item-" item-id)
                       :verdict verdict
                       :evidence evidence
                       :graded? true})
                    (:verdicts interpreted))
        summary {:passed (boolean passed)
                 :applicable (not not-applicable?)
                 :total (:total interpreted)
                 :passed-count (:passed-count interpreted)
                 :items items}
        observations (mapv #(cycle-observation inputs
                                                "runtime-instrument-conformance"
                                                "grader" %)
                           items)]
    (merge (stage-result "instrument-conformance" context)
           summary
           {:observations observations
            :evidence (json/write-str {:command command
                                       :evaluation interpreted})})))

(def max-evaluation-rounds 3)

(defn- evaluation-passed? [verification affordance grade conformance]
  (and (:passed verification) (:passed affordance) (:passed grade) (:passed conformance)))

(defn resample-evaluation [{:keys [inputs] :as context} max-rounds]
  (loop [round 1
         builder (effective-builder-result inputs)
         instrument (effective-instrument-result inputs)
         affordance (effective-affordance-conformance-result inputs)
         verification (effective-verify-result inputs)
         grade (:grade-result inputs)
         conformance (:instrument-conformance-result inputs)
         attempts []]
    (let [passed (evaluation-passed? verification affordance grade conformance)
          attempt {:round round
                   :subject-digest (:subject-digest builder)
                   :evidence-root (:evidence-root instrument)
                   :verification-passed (boolean (:passed verification))
                   :affordance-passed (boolean (:passed affordance))
                   :grade-passed (boolean (:passed grade))
                   :instrument-conformance-passed (boolean (:passed conformance))}
          attempts (conj attempts attempt)]
      (if (or passed (>= round max-rounds))
        (merge (stage-result "repair-evaluation" context)
               {:passed (boolean passed)
                :rounds round
                :builder builder
                :instrument instrument
                :affordance affordance
                :verify verification
                :grade grade
                :instrument-conformance conformance
                :attempts attempts})
        (let [next-round (inc round)
              repair-id (format "%s-evaluation-repair-%02d" (:request-id inputs) next-round)
              builder-session-ref (str (random-uuid))
              _ (record-stage-progress! context "builder" next-round
                                        builder-session-ref "running")
              raw-builder (run-builder-adapter
                           {:request-id repair-id
                            :cut (get-in inputs [:selection :cut])
                            :scope (:scope inputs)
                            :selection (:selection inputs)
                            :target-stack (:target-stack inputs)
                            :execution-backend (:execution-backend inputs)
                            :initial-workspace-root nil
                            :verification-evidence-root nil
                            :round next-round
                            :builder-session-ref builder-session-ref
                            :builder-sessions-root (builder-sessions-root)
                            :attempt next-round})
              repaired-builder (merge
                                (stage-result "builder" {:inputs (assoc inputs :request-id repair-id)})
                                raw-builder
                                {:builder (or (:builder raw-builder) (builder-id))
                                 :subject-digest (subject/digest (:output-root raw-builder))})
              repaired-inputs (-> inputs
                                  (assoc :request-id repair-id
                                         :builder-result repaired-builder)
                                  (dissoc :instrument-result
                                          :affordance-conformance-result
                                          :verify-result
                                          :repair-verification-result
                                          :grade-result
                                          :instrument-conformance-result
                                          :repair-evaluation-result))
              repaired-context {:inputs repaired-inputs
                                :tick-id (:tick-id context)
                                :cycle-id (:request-id inputs)}
              repaired-instrument (instrument-stage repaired-context)
              repaired-affordance (affordance-conformance-stage
                                    (assoc repaired-context :inputs
                                           (assoc repaired-inputs
                                                  :builder-result repaired-builder
                                                  :instrument-result repaired-instrument)))
              verification-inputs (assoc repaired-inputs
                                         :builder-result repaired-builder
                                         :instrument-result repaired-instrument
                                         :affordance-conformance-result repaired-affordance)
              initial-verification (verify-stage
                                    (assoc repaired-context :inputs verification-inputs))
              verification-repair (repair-verification-stage
                                   (assoc repaired-context :inputs
                                          (assoc verification-inputs
                                                 :verify-result initial-verification)))
              evaluated-builder (:builder verification-repair)
              evaluated-instrument (:instrument verification-repair)
              evaluated-affordance (:affordance verification-repair)
              repaired-verification (:verify verification-repair)
              evaluation-inputs (assoc verification-inputs
                                       :repair-verification-result
                                       verification-repair)
              repaired-grade (when (:passed repaired-verification)
                               (grade-with-cycle-oracle
                                (assoc repaired-context :inputs evaluation-inputs)))
              conformance-inputs (assoc evaluation-inputs
                                        :grade-result repaired-grade)
              repaired-conformance (when (:passed repaired-grade)
                                     (instrument-conformance-stage
                                      (assoc repaired-context :inputs conformance-inputs)))]
          (recur next-round
                 evaluated-builder
                 evaluated-instrument
                 evaluated-affordance
                 repaired-verification
                 (or repaired-grade {:passed false})
                 (or repaired-conformance {:passed false})
                 attempts))))))

(defn record-cycle! [{:keys [inputs]}]
  (let [instrument (effective-instrument-result inputs)
        manifest (evidence-manifest/seal!
                  (:evidence-root instrument)
                  {:cycle-id (:request-id inputs)
                   :project-id (get-in inputs [:selection :projectId])
                   :cut-digest (get-in inputs [:selection :cut])
                   :subject-digest (:subject-digest instrument)
                   :oracle-digest (get-in inputs [:oracle-result :oracle-digest])})
        instrument-conformance (select-keys
                                (get inputs :instrument-conformance-result)
                                [:passed :total :passed-count :items])
        measurement (assoc (get-in inputs [:grade-result :measurement])
                           :instrument-conformance instrument-conformance
                           :evidence-manifest manifest)
        observations (->> (into (vec (get-in inputs [:grade-result :observations]))
                                (get-in inputs [:instrument-conformance-result :observations]))
                          (mapv #(persisted-evaluation manifest %)))
        selection (:selection inputs)
        builder (effective-builder-result inputs)
        context (:context @runtime)
        persisted-observations (mapv #(model/record-observation! context %) observations)
        persisted (model/record-measurement! context measurement)
        candidate-data (cond-> {:candidate-id (:request-id inputs)
                                :project-id (:projectId selection)
                                :container-id (:containerId selection)
                                :component-id (:componentId selection)
                                :operation "measured-regeneration"
                                :selection-id (:selectionId selection)
                                :cut (:cut selection)
                                :output-root (:output-root builder)
                                :subject-digest (:subject-digest instrument)
                                :disposition "oracle-measured"
                                :oracle-descriptor-digest
                                (get-in inputs [:oracle-result :accepted-contract-digest])}
                         (:parent-candidate-id inputs)
                         (assoc :parent-candidate-id (:parent-candidate-id inputs)))
        candidate (model/record-generated-candidate! context candidate-data)]
    (when-not (= observations persisted-observations)
      (throw (ex-info "persisted observations do not match black-box evidence" {})))
    (when-not (= measurement persisted)
      (throw (ex-info "persisted cycle measurement does not match the grader result"
                      {:expected measurement :persisted persisted})))
    (when-not (= candidate-data candidate)
      (throw (ex-info "persisted candidate lineage does not match the regenerated output" {})))
    (doseq [value persisted-observations]
      (emit-cycle-observation! {:inputs inputs :evidence-manifest manifest} value))
    persisted))

(defn grade-gate [{:keys [inputs] :as context}]
  (let [verification (effective-verify-result inputs)
        result (merge (stage-result "result" context)
                      {:operation "measured-regeneration"
                       :passed (boolean (and (get-in inputs [:grade-result :passed])
                                             (get-in inputs [:instrument-conformance-result :passed])))
                       :verified? (boolean (:passed verification))
                       :instrumented? (boolean (get-in inputs [:instrument-conformance-result :passed]))
                       :score (select-keys (get-in inputs [:grade-result :measurement])
                                           [:passed :failed])})]
    (record-stage-progress! context "result" 1 nil "completed")
    result))

;; Slice-cycle stages. The persisted route selects what the oracle, grade,
;; repair-evaluation, and conclude stages do; every other stage is shared.

(defn- route! [inputs]
  (let [route (:route inputs)]
    (when-not (#{"fresh-generation" "measured-regeneration"} route)
      (throw (ex-info "slice cycle requires a fresh-generation or measured-regeneration route"
                      {:route route})))
    route))

(defn oracle-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "oracle")
  (case (route! inputs)
    ;; Candidate-blind, so it runs before the builder and fails fast when uncalibratable.
    "fresh-generation" (assoc (author-cycle-oracle context) :stage "oracle" :kind "cycle-oracle")
    ;; Never authors: resolves the pinned accepted oracle or fails before the builder.
    "measured-regeneration" (assoc (resolve-accepted-oracle context)
                                   :stage "oracle" :kind "accepted-oracle")))

(defn grade-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "grade")
  (assoc (case (route! inputs)
           "fresh-generation" (grade-with-cycle-oracle context)
           "measured-regeneration" (grade-against-accepted-oracle context))
         :stage "grade"))

(defn repair-evaluation-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "repair-evaluation")
  (case (route! inputs)
    "fresh-generation" (assoc (resample-evaluation context max-evaluation-rounds) :applicable true)
    ;; Measured regeneration reports its single measurement and never resamples.
    "measured-regeneration" (assoc (resample-evaluation context 1) :applicable false)))

(defn conclude-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "conclude")
  (case (route! inputs)
    "fresh-generation"
    (let [allure (materialize-allure! context)
          proposal (propose-oracle! context)]
      (merge (stage-result "conclude" context)
             {:passed true :allure allure :oracle-proposal proposal}))
    "measured-regeneration"
    (let [cycle (record-cycle! context)
          gate (grade-gate context)]
      (merge (stage-result "conclude" context)
             {:passed (boolean (:passed gate)) :cycle cycle :gate gate}))))

(defn cut-workflow [] (cut-workflow/workflow))

(defn workflow [] (slice-cycle-workflow/workflow))

(defn start-runtime! [start!]
  (locking runtime
    (or @runtime
        (let [started (start!)]
          (reset! runtime started)
          (let [ctx (:context started)]
            (swap! workflows assoc
                   :cut {:context ctx :sheet-id (orc/build-workflow! ctx (cut-workflow))}
                   :slice-cycle {:context ctx :sheet-id (orc/build-workflow! ctx (workflow))}
                   :generation-prompt
                   {:context ctx :sheet-id (orc/build-workflow! ctx (prompt/workflow :fresh-generation))}
                   :repair-prompt
                   {:context ctx :sheet-id (orc/build-workflow! ctx (prompt/workflow :verification-repair))})
            (orc/resume-in-progress! ctx)
            started)))))

(defn ensure-runtime! []
  (or @runtime
      (throw (ex-info "harness runtime has not been started" {}))))

(defn runtime-context []
  (:context (ensure-runtime!)))

(defn stop-runtime!
  "Stop the current runtime with the owning system's stop function."
  [stop!]
  (when-let [started @runtime]
    (try
      (stop! started)
      (finally
        (reset! runtime nil)
        (reset! workflows {})
        (reset! tasks {})
        (reset! next-task 0)))))

(defn- request-uuid [prefix request-id]
  (java.util.UUID/nameUUIDFromBytes
   (.getBytes (str "pi-harness:" prefix ":" request-id)
              java.nio.charset.StandardCharsets/UTF_8)))

(defn- submit-slice-cycle!
  "Start one slice cycle on its persisted route. Only submit-approved-slice! calls this."
  [{:keys [requestId selectionContract scope targetStack executionBackend
           parentCandidateId parentCandidateRoot parentCandidateDigest routingRequest]
    :as request}
   route]
  (when-not (slice-cycle-workflow/routes route)
    (throw (ex-info "unknown slice-cycle route" {:route route})))
  (when (= "measured-regeneration" route)
    (when-not (and (string? parentCandidateId)
                   (string? parentCandidateRoot)
                   (string? parentCandidateDigest))
      (throw (ex-info "measured regeneration requires a persisted parent candidate"
                      {:request request}))))
  (when (and (= "fresh-generation" route)
             (or parentCandidateId parentCandidateRoot parentCandidateDigest))
    (throw (ex-info "fresh generation must not carry a parent candidate" {:request request})))
  (if-let [existing (get @tasks requestId)]
    (if (= routingRequest (:routing-request existing))
      (:receipt existing)
      (throw (ex-info "requestId was already used with a different approved-slice request"
                      {:requestId requestId})))
    (let [_ (ensure-runtime!)
          {:keys [context sheet-id]} (get @workflows :slice-cycle)
          task-id (swap! next-task inc)
          tick-id (request-uuid "tick" requestId)
          correlation-id (request-uuid "correlation" requestId)
          measured? (= "measured-regeneration" route)
          trace (future
                  (orc/execute context sheet-id
                               (cond-> {:request-id requestId :route route
                                        :selection selectionContract
                                        :scope scope :target-stack targetStack
                                        :execution-backend executionBackend
                                        :orc-tick-id (str tick-id)}
                                 measured? (assoc :parent-candidate-id parentCandidateId
                                                  :parent-candidate-root parentCandidateRoot
                                                  :parent-candidate-digest parentCandidateDigest))
                               :tick-id tick-id
                               :correlation-id correlation-id
                               :timeout-ms 900000))
          receipt (cond-> {:operation route :operationId requestId
                           :cycleId requestId :selectionId (:selectionId selectionContract)
                           :tickId (str tick-id) :correlationId (str correlation-id)
                           :taskId task-id :targetStack targetStack
                           :executionBackend executionBackend}
                    measured? (assoc :parentCandidateId parentCandidateId
                                     :parentCandidateDigest parentCandidateDigest))]
      (swap! tasks assoc requestId {:request request :routing-request routingRequest
                                    :receipt receipt :future trace})
      receipt)))

(defn submit-approved-slice! [{:keys [requestId selectionContract scope targetStack executionBackend]
                               :as request}]
  (when-not (and (string? requestId)
                 (map? selectionContract)
                 (= "approved" (:status selectionContract))
                 (seq (:items selectionContract))
                 (vector? scope)
                 (seq scope)
                 (string? targetStack)
                 (not (str/blank? targetStack))
                 (contains? #{"host" "gondolin"} executionBackend))
    (throw (ex-info "submit-approved-slice requires an approved selection, scope, target stack, and execution backend"
                    {:request request})))
  (ensure-runtime!)
  (let [selection selectionContract
        context (runtime-context)
        request-digest (sha256 (json/write-str
                                (select-keys request [:requestId :selectionContract :scope
                                                      :targetStack :executionBackend])))
        existing-route (model/find-approved-slice-route context requestId)
        _ (when (and existing-route
                     (not= request-digest (:request-digest existing-route)))
            (throw (ex-info "requestId was already routed with a different approved-slice payload"
                            {:requestId requestId})))
        route (or existing-route
                  (let [prior (model/find-latest-candidate context
                                                           (:projectId selection)
                                                           (:containerId selection)
                                                           (:componentId selection))
                        base {:request-id requestId
                              :request-digest request-digest
                              :project-id (:projectId selection)
                              :container-id (:containerId selection)
                              :component-id (:componentId selection)
                              :selection-id (:selectionId selection)}
                        decided (if prior
                                  (do
                                    (when-not (and (string? (:candidate-id prior))
                                                   (string? (:output-root prior))
                                                   (string? (:subject-digest prior)))
                                      (throw (ex-info "latest candidate lineage is incomplete"
                                                      {:candidate prior})))
                                    (assoc base :operation "measured-regeneration"
                                           :parent-candidate-id (:candidate-id prior)
                                           :parent-candidate-root (:output-root prior)
                                           :parent-candidate-digest (:subject-digest prior)))
                                  (assoc base :operation "fresh-generation"))]
                    (model/record-approved-slice-route! context decided)))]
    (submit-slice-cycle! (cond-> (assoc request :routing-request request)
                           (= "measured-regeneration" (:operation route))
                           (assoc :parentCandidateId (:parent-candidate-id route)
                                  :parentCandidateRoot (:parent-candidate-root route)
                                  :parentCandidateDigest (:parent-candidate-digest route)))
                         (:operation route))))

(defn start-approved-cycle-preparation!
  "Translate one digest-approved preparation into the existing idempotent ORC
   submission boundary. Called by the Grain todo processor after commit."
  [context preparation-id preparation-digest]
  (let [deadline (+ (System/nanoTime) (* 30 1000000000))
        _ (loop []
            (when-not (and @runtime (get @workflows :slice-cycle))
              (when (>= (System/nanoTime) deadline)
                (throw (ex-info "ORC cycle workflow was not ready for approved preparation"
                                {:preparation-id preparation-id})))
              (Thread/sleep 25)
              (recur)))
        preparation (model/query-cycle-preparation context preparation-id)]
    (when-not (and (= "approved" (:status preparation))
                   (= preparation-digest (:preparation-digest preparation)))
      (throw (ex-info "processor received an unapproved or stale preparation"
                      {:preparation-id preparation-id
                       :preparation-digest preparation-digest})))
    (let [selection (model/query-selection context (:selection-id preparation))
          execution-backend (if (= "gondolin-isolated"
                                   (:execution-profile preparation))
                              "gondolin"
                              "host")]
      (submit-approved-slice!
       {:requestId preparation-id
        :selectionContract selection
        :scope (:scope preparation)
        :targetStack (:target-stack preparation)
        :executionBackend execution-backend}))))

(defn prepare-cut! [{:keys [sourceCommit executionBackend] :as request}]
  (when-not (and (string? sourceCommit)
                 (or (= "host" executionBackend) (= "gondolin" executionBackend)))
    (throw (ex-info "prepare-cut requires sourceCommit and executionBackend (host or gondolin)"
                    {:request request})))
  (let [key (str "cut:" executionBackend ":" sourceCommit)]
    (if-let [existing (get @tasks key)]
      (if (= (:request existing) request)
        (:receipt existing)
        (throw (ex-info "sourceCommit was already used with a different request"
                        {:sourceCommit sourceCommit})))
      (let [_ (ensure-runtime!)
            {:keys [context sheet-id]} (get @workflows :cut)
            task-id (swap! next-task inc)
            trace (future
                    (orc/execute context sheet-id
                                 {:source-commit sourceCommit :execution-backend executionBackend}
                                 :tick-id (random-uuid)
                                 :correlation-id (random-uuid)
                                 :timeout-ms 120000))
            receipt {:cutId sourceCommit :taskId task-id :executionBackend executionBackend}]
        (swap! tasks assoc key {:request request :receipt receipt :future trace})
        receipt))))

(defn result-summary [result]
  {:status (name (:status result))
   :outputs (:outputs result)
   :traceId (some-> (:trace-id result) str)
   :durationMs (:duration-ms result)
   :error (some-> (:error result) str)})

(defn- parse-uuid-field [field value]
  (when-not (string? value)
    (throw (ex-info (str (name field) " must be a UUID string") {field value})))
  (try
    (java.util.UUID/fromString value)
    (catch IllegalArgumentException _
      (throw (ex-info (str (name field) " must be a UUID string") {field value})))))

(defn execution-status!
  "Return transport-safe execution state from ORC's durable tick read model."
  [{:keys [tickId]}]
  (let [tick-id (parse-uuid-field :tickId tickId)
        context (runtime-context)
        tick (orc/get-tick context tick-id)
        progress (model/find-cycle-progress (dissoc context :cache) tickId)]
    (if-not tick
      {:tickId tickId :status "not-started"}
      (cond-> {:tickId tickId
               :sheetId (some-> (:sheet-id tick) str)
               :status (name (:status tick))}
        (:correlation-id tick)
        (assoc :correlationId (str (:correlation-id tick)))

        (:root-status tick)
        (assoc :rootStatus (name (:root-status tick)))

        (:error tick)
        (assoc :error (str (:error tick)))

        (:stage progress)
        (assoc :stage (:stage progress)
               :attempt (:attempt progress))

        (:builder-session-ref progress)
        (assoc :builderSessionRef (:builder-session-ref progress))))))

(defn wait! [{:keys [taskId]}]
  (when-not (number? taskId)
    (throw (ex-info "wait requires numeric taskId" {:taskId taskId})))
  (if-let [entry (some #(when (= taskId (get-in % [1 :receipt :taskId])) %) @tasks)]
    (let [result @(get-in entry [1 :future])
          summary (result-summary result)]
      (if (= :success (:status result))
        {:status "completed" :result summary}
        {:status "failed" :error (or (:error summary) "ORC workflow failed") :result summary}))
    (throw (ex-info "unknown taskId" {:taskId taskId}))))

(defn respond! [id ok value]
  (println (json/write-str (cond-> {:id id :ok ok}
                             ok (assoc :result value)
                             (not ok) (assoc :error value)))
           )
  (flush))
