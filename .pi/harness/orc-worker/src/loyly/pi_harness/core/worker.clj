(ns loyly.pi-harness.core.worker
  (:require [clojure.data.json :as json]
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
            [loyly.pi-harness.core.build.prompt :as prompt]
            [loyly.pi-harness.core.build.project-profile :as project-profile]
            [loyly.pi-harness.core.build.stack-conformance :as stack-conformance]
            [loyly.pi-harness.core.build.subject :as subject]
            [loyly.pi-harness.workflows.cut :as cut-workflow]
            [loyly.pi-harness.workflows.regeneration :as regeneration-workflow]))

(defonce ^:private runtime (atom nil))
(defonce ^:private workflows (atom {}))
(defonce ^:private tasks (atom {}))
(defonce ^:private next-task (atom 0))
(defonce ^:private active-cycles (atom {}))
(defonce ^:private builder-prompt (atom prompt/seed-instruction))

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
                (when (.isDirectory (java.io.File. candidate ".pi/harness/config/projects"))
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
  ([{:keys [cycle-id inputs tick-id]} stage attempt builder-session-ref status]
   (let [tick-id (or tick-id (:orc-tick-id inputs))]
    (when-let [grain-context (and tick-id (some-> @runtime :context))]
     (model/record-cycle-progress!
      grain-context
      (cond-> {:cycle-id (or cycle-id (:request-id inputs) (str tick-id))
               :tick-id (str tick-id)
               :stage stage
               :attempt attempt
               :status status}
        builder-session-ref (assoc :builder-session-ref builder-session-ref)))))))

(defn effective-builder-result [inputs]
  (or (get-in inputs [:repair-verification-result :builder])
      (:builder-result inputs)))

(defn effective-instrument-result [inputs]
  (or (get-in inputs [:repair-verification-result :instrument])
      (:instrument-result inputs)))

(defn effective-verify-result [inputs]
  (or (get-in inputs [:repair-verification-result :verify])
      (:verify-result inputs)))

(defn run-cut-adapter [source-commit execution-backend]
  (adapters/run-cut! (project-root) source-commit execution-backend))

(defn prepare-cut-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "prepare-cut")
  (merge {:stage "cut" :source-commit (:source-commit inputs) :execution-backend (:execution-backend inputs)}
         (run-cut-adapter (:source-commit inputs) (:execution-backend inputs))))

(defn cut-stage [context]
  (record-stage-progress! context "cut")
  (stage-result "cut" context))

(defn calibrate-harness! []
  (let [root (project-root)
        pack-ref (or (System/getenv "PI_HARNESS_INSTRUMENT_ORACLE_REF")
                     (System/getenv "LOYLEY_INSTRUMENT_ORACLE_REF")
                     (str root "/.pi/harness/config/instrument-oracles/pi-harness:accepted"))
        result (harness-calibration/calibrate! root pack-ref)
        suite-summary (into {}
                            (map (fn [[suite value]]
                                   [suite (select-keys value [:exit-code :timed-out?])]))
                            (:suites result))]
    (when-not (:passed result)
      (throw (ex-info "full harness calibration failed"
                      {:instrument-oracle-digest (:oracle-digest result)
                       :mismatches (:mismatches result)
                       :suites suite-summary})))
    result))

(defn harness-calibration-stage [context]
  (record-stage-progress! context "harness-calibration")
  (let [result (calibrate-harness!)
        suite-summary (into {}
                            (map (fn [[suite value]]
                                   [suite (select-keys value [:exit-code :timed-out?])]))
                            (:suites result))]
    (merge (stage-result "harness-calibration" context)
           {:passed true
            :instrument-oracle-digest (:oracle-digest result)
            :calibration-results
            (json/write-str (->> (:actual result)
                                 (sort-by key)
                                 (mapv val)))
            :evidence (json/write-str {:mismatches (:mismatches result)
                                       :suites suite-summary})})))

(defn run-builder-adapter [request]
  (adapters/run-builder! {:project-root (project-root)
                          :cuts-root (cuts-root)
                          :builder-id builder-id
                          :builder-prompt builder-prompt
                          :project-adapter project-adapter}
                         request))

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
        _ (record-stage-progress! context "builder" 1 builder-session-ref "running")
        builder-result (run-builder-adapter {:request-id (:request-id inputs)
                                             :cut (get-in inputs [:selection :cut])
                                             :scope (:scope inputs)
                                             :selection (:selection inputs)
                                             :target-stack (:target-stack inputs)
                                             :execution-backend (:execution-backend inputs)
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
        calibration-results (some-> (get-in inputs [:harness-calibration-result
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

(declare ensure-runtime! run-grader-adapter graders-stage sha256)

(defn run-oracle-fixture-adapter [request]
  (adapters/run-oracle-fixture! (project-root) request))

(defn run-oracle
  ([cut] (run-oracle cut {:allItems true}))
  ([cut selection]
   (run-oracle cut selection {}))
  ([cut selection options]
  (let [cuts-root (cuts-root)
        evaluate-cut (requiring-resolve 'loyly.pi-harness.core.oracle.oracle/evaluate-cut)]
    (evaluate-cut (str cuts-root "/" cut) selection options))))

(defn- rooted [root path]
  (if (.isAbsolute (java.io.File. path)) path (str root "/" path)))

(defn product-oracle-options [selection]
  (let [root (project-root)
        adapter (project-adapter selection)]
    {:oracle-pack-ref (or (System/getenv "PI_HARNESS_PRODUCT_ORACLE_REF")
                          (System/getenv "LOYLEY_PRODUCT_ORACLE_REF")
                          (rooted root (:productOracleRef adapter)))
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
     :execution-backend execution-backend}))

(defn evaluate-prompt [input output]
  (let [candidate (or (:prompt output) (get output "prompt"))
        _ (when-not (and (string? candidate) (not (str/blank? candidate)))
            (throw (ex-info "prompt metric requires a generated instruction" {:output output})))
        {:keys [request-id selection cut scope target-stack execution-backend]}
        (prompt-evaluation-input input)
        evaluation-id (str "gepa-" request-id "-" (subs (sha256 candidate) 0 12)
                           "-" (random-uuid))
        evidence (run-builder-adapter {:request-id evaluation-id
                                       :cut cut
                                       :scope scope
                                       :selection selection
                                       :target-stack target-stack
                                       :execution-backend execution-backend
                                       :prompt-text candidate})
        subject-digest (subject/digest (:output-root evidence))
        evidence-root (str (:output-root evidence) ".evidence")
        adapter (project-adapter selection)
        oracle (run-oracle cut selection (product-oracle-options selection))
        oracle-result {:passed (:passed oracle)
                       :oracle-digest (:oracle-digest oracle)
                       :grader-root (:grader-root oracle)
                       :observability-root (:observability-root oracle)
                       :grader-producer (:graderProducer adapter)
                       :grader-id (:graderId adapter)}
        grader-request {:cut cut :output-root (:output-root evidence)
                        :cycle-id evaluation-id
                        :evidence-root evidence-root
                        :grader-root (:grader-root oracle)
                        :observability-root (:observability-root oracle)
                        :execution-backend execution-backend
                        :target-stack target-stack}
        verification (compose/interpret-verification
                      (run-grader-adapter (assoc grader-request :mode "compose")))
        context {:inputs {:request-id evaluation-id
                          :selection selection
                          :scope scope
                          :target-stack target-stack
                          :execution-backend execution-backend
                          :builder-result (assoc evidence :subject-digest subject-digest)
                          :instrument-result {:subject-digest subject-digest
                                              :evidence-root evidence-root}
                          :oracle-result oracle-result
                          :verify-result verification}}
        grading (when (and (:passed oracle-result) (:passed verification))
                  (graders-stage context))
        measurement (:measurement grading)
        valid-measurement? (and (:passed oracle-result)
                                (:passed verification)
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
                   {:oracle-passed (:passed oracle-result)
                    :verification-passed (:passed verification)
                    :reason "candidate did not reach a complete independent measurement"})]
    {:score score
     :feedback (json/write-str feedback)}))

(defn optimize-builder-prompt! [{:keys [trainset valset] :as request}]
  (when-not (and (vector? trainset) (seq trainset)
                 (vector? valset) (seq valset))
    (throw (ex-info "optimize-prompt requires non-empty trainset and valset vectors"
                    {:request request})))
  (doseq [example (concat trainset valset)]
    (prompt-evaluation-input example))
  (let [normalized-trainset (mapv stringify-example-keys trainset)
        normalized-valset (mapv stringify-example-keys valset)
        _ (calibrate-harness!)
        _ (ensure-runtime!)
        {:keys [context sheet-id]} (get @workflows :prompt)
        result (gepa/optimize! context
                               {:sheet-id sheet-id
                                :trainset normalized-trainset
                                :valset normalized-valset
                                :metric-fn evaluate-prompt
                                :inherit-from-previous true
                                :block? true
                                :timeout-ms 1800000
                                :config {:max-metric-calls 12}})
        winner (prompt/winner-instruction result)]
    (when (string? winner) (reset! builder-prompt winner))
    {:status (name (:status result))
     :bestScore (:best-score result)
     :prompt winner
     :error (:error result)
     :optimizationId (some-> (:optimization-id result) str)}))

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
     :candidatePrompt (get-in candidate [:instructions "builder-instruction"])
     :feedback (:feedbacks candidate)
     :metricCalls (:metric-calls proposal-context)
     :bestScore (:best-score proposal-context)
     :bestPrompt (get-in best [:instructions "builder-instruction"])}))

(defn start-external-prompt-optimization! [{:keys [trainset valset] :as request}]
  (when-not (and (vector? trainset) (seq trainset)
                 (vector? valset) (seq valset))
    (throw (ex-info "external prompt optimization requires non-empty trainset and valset vectors"
                    {:request request})))
  (doseq [example (concat trainset valset)]
    (prompt-evaluation-input example))
  (let [normalized-trainset (mapv stringify-example-keys trainset)
        normalized-valset (mapv stringify-example-keys valset)
        _ (calibrate-harness!)
        _ (ensure-runtime!)
        {:keys [context sheet-id]} (get @workflows :prompt)
        started (gepa/optimize! context
                                {:sheet-id sheet-id
                                 :trainset normalized-trainset
                                 :valset normalized-valset
                                 :metric-fn evaluate-prompt
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
    (external-proposal-result context optimization-id seed)))

(defn submit-external-prompt-candidate!
  [{:keys [optimizationId prompt proposer] :as request}]
  (when-not (and (string? optimizationId) (string? prompt) (not (str/blank? prompt)))
    (throw (ex-info "external prompt candidate requires optimizationId and a non-empty prompt"
                    {:request request})))
  (ensure-runtime!)
  (let [{:keys [context]} (get @workflows :prompt)
        optimization-id (java.util.UUID/fromString optimizationId)
        submitted (gepa/submit-external-candidate!
                   context optimization-id
                   {"builder-instruction" prompt}
                   (or proposer (builder-id)))
        candidate (await-gepa-candidate! context optimization-id
                                         (:candidate-id submitted) 1800000)
        _ (await-gepa-terminal-if-budget-exhausted! context optimization-id)
        result (external-proposal-result context optimization-id candidate)]
    (when (= (:candidateScore result) (:bestScore result))
      (reset! builder-prompt (:bestPrompt result)))
    (assoc result :reused (:reused? submitted))))

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

(defn emit-grader-observations! [{:keys [inputs]} measurement]
  (let [results-root (evidence-results-root inputs)
        builder (effective-builder-result inputs)
        instrument (effective-instrument-result inputs)
        base {:role "observed"
              :builder (:builder builder)
              :evidence-kind "grader"
              :producer (get-in inputs [:oracle-result :grader-producer])
              :grader-id (get-in inputs [:oracle-result :grader-id])
              :container-id (get-in inputs [:selection :containerId])
              :component-id (get-in inputs [:selection :componentId])
              :cycle-id (:request-id inputs)
              :cut-digest (get-in inputs [:selection :cut])
              :subject-digest (:subject-digest instrument)
              :oracle-digest (or (get-in inputs [:oracle-result :oracle-digest])
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
          (:items measurement))))

(defn cycle-observation [inputs producer evidence-kind item]
  (let [instrument (effective-instrument-result inputs)]
   {:cycle-id (:request-id inputs)
   :cut-digest (get-in inputs [:selection :cut])
   :project-id (get-in inputs [:selection :projectId])
   :container-id (get-in inputs [:selection :containerId])
   :component-id (get-in inputs [:selection :componentId])
   :item-id (:item-id item)
   :subject-digest (:subject-digest instrument)
   :producer producer
   :evidence-kind evidence-kind
   :result (or (:result item) (:verdict item))
   :reached? (if (contains? item :reached?) (:reached? item) (:graded? item))
   :evidence (:evidence item)
   :oracle-digest (get-in inputs [:oracle-result :oracle-digest])}))


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
         :artifact-digests (mapv :sha256 (:artifacts manifest))))

(defn oracle-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "oracle")
  (let [selection (:selection inputs)
        adapter (project-adapter selection)
        result (run-oracle (get-in inputs [:selection :cut])
                           selection
                           (product-oracle-options selection))
        accepted-contract-digest (:oracle-digest result)
        instrument-oracle-digest (get-in inputs [:harness-calibration-result
                                                  :instrument-oracle-digest])
        combined-digest (sha256 (str instrument-oracle-digest "\n"
                                     accepted-contract-digest))]
    (when-not (:passed result)
      (throw (ex-info "oracle rejected the pinned model contract" {:oracle result})))
    (merge (stage-result "oracle" context)
           {:passed true
            :oracle-digest combined-digest
            :accepted-contract-digest accepted-contract-digest
            :instrument-oracle-digest instrument-oracle-digest
            :grader-root (:grader-root result)
            :observability-root (:observability-root result)
            :grader-producer (:graderProducer adapter)
            :grader-id (:graderId adapter)
            :evidence (json/write-str result)})))

(defn verify-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "verify")
  (let [stack-result (stack-conformance/check (get-in inputs [:builder-result :output-root])
                                               (:target-stack inputs))
        evidence (run-grader-adapter {:cycle-id (:request-id inputs)
                                      :cut (get-in inputs [:selection :cut])
                                      :output-root (get-in inputs [:builder-result :output-root])
                                      :evidence-root (get-in inputs [:instrument-result :evidence-root])
                                      :grader-root (get-in inputs [:oracle-result :grader-root])
                                      :observability-root (get-in inputs [:oracle-result :observability-root])
                                      :execution-backend (:execution-backend inputs)
                                      :target-stack (:target-stack inputs)
                                      :mode "compose"})
        compose-result (compose/interpret-verification evidence)
        result {:passed (and (:passed stack-result) (:passed compose-result))
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
        initial-verify (:verify-result inputs)]
    (loop [round 1
           builder initial-builder
           instrument initial-instrument
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
                 repaired-verification
                 (conj attempts
                       {:round next-round
                        :subject-digest (:subject-digest repaired-builder)
                        :evidence-root (:evidence-root repaired-instrument)
                        :passed (boolean (:passed repaired-verification))})))))))

(defn graders-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "graders")
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

(defn instrument-conformance-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "instrument-conformance")
  (let [builder (effective-builder-result inputs)
        instrument (effective-instrument-result inputs)
        selected-items (get-in inputs [:selection :items])
        adapter (project-adapter (:selection inputs))
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
        evidence (when gondolin?
                   (run-grader-adapter {:cycle-id (:request-id inputs)
                                        :cut (get-in inputs [:selection :cut])
                                        :output-root (:output-root builder)
                                        :evidence-root (:evidence-root instrument)
                                        :execution-backend "gondolin"
                                        :project-id (get-in inputs [:selection :projectId])
                                        :grader-root (get-in inputs [:oracle-result :grader-root])
                                        :observability-root (get-in inputs [:oracle-result :observability-root])
                                        :item-ids (mapv :id instrumented-items)
                                        :mode "instrument"}))
        command (get evidence :instrument-conformance)
        interpreted (if gondolin?
                      (independent-grader/interpret-verdicts expected-ids (:stdout command))
                      (independent-grader/interpret-verdicts
                       expected-ids
                       (json/write-str
                        (mapv (fn [item-id]
                                {:itemId item-id :verdict "fail"
                                 :evidence "instrument conformance currently requires Gondolin"})
                              expected-ids))))
        command-passed (and command (zero? (or (:exitCode command) 1)))
        passed (and (:passed interpreted) command-passed)
        items (mapv (fn [{:keys [item-id verdict evidence]}]
                      {:item-id (str "@item-" item-id)
                       :verdict verdict
                       :evidence evidence
                       :graded? true})
                    (:verdicts interpreted))
        summary {:passed (boolean passed)
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

(defn record-cycle-stage [{:keys [inputs] :as stage-context}]
  (record-stage-progress! stage-context "record-cycle")
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
        measurement (assoc (get-in inputs [:graders-result :measurement])
                           :instrument-conformance instrument-conformance
                           :evidence-manifest manifest)
        observations (->> (into (vec (get-in inputs [:graders-result :observations]))
                                (get-in inputs [:instrument-conformance-result :observations]))
                          (mapv #(persisted-evaluation manifest %)))
        context (:context @runtime)
        persisted-observations (mapv #(model/record-observation! context %) observations)
        persisted (model/record-measurement! context measurement)]
    (when-not (= observations persisted-observations)
      (throw (ex-info "persisted observations do not match black-box evidence" {})))
    (when-not (= measurement persisted)
      (throw (ex-info "persisted cycle measurement does not match the grader result"
                      {:expected measurement :persisted persisted})))
    (doseq [value persisted-observations]
      (emit-cycle-observation! {:inputs inputs :evidence-manifest manifest} value))
    persisted))

(defn grade-gate-stage [{:keys [inputs] :as context}]
  (record-stage-progress! context "result")
  (let [verification (effective-verify-result inputs)
        result (merge (stage-result "result" context)
                      {:passed (boolean (and (get-in inputs [:graders-result :passed])
                                             (get-in inputs [:instrument-conformance-result :passed])))
                       :verified? (boolean (:passed verification))
                       :instrumented? (boolean (get-in inputs [:instrument-conformance-result :passed]))
                       :score (select-keys (get-in inputs [:graders-result :measurement])
                                           [:passed :failed])})]
    (record-stage-progress! context "result" 1 nil "completed")
    result))

(defn cut-workflow [] (cut-workflow/workflow))

(defn workflow [] (regeneration-workflow/workflow))

(defn start-runtime! [start!]
  (locking runtime
    (or @runtime
        (let [started (start!)]
          (reset! runtime started)
          (let [ctx (:context started)
                sheet-id (orc/build-workflow! ctx (workflow))]
            (swap! workflows assoc
                   :cut {:context ctx :sheet-id (orc/build-workflow! ctx (cut-workflow))}
                   :prompt {:context ctx :sheet-id (orc/build-workflow! ctx (prompt/workflow))}
                   :cycle {:context ctx :sheet-id sheet-id})
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
        (reset! next-task 0)
        (reset! active-cycles {})))))

(defn submit! [{:keys [requestId selectionContract scope targetStack executionBackend] :as request}]
  (when-not (and (string? requestId)
                 (map? selectionContract)
                 (= "approved" (:status selectionContract))
                 (seq (:items selectionContract))
                 (vector? scope)
                 (string? targetStack)
                 (not (str/blank? targetStack))
                 (or (= "host" executionBackend) (= "gondolin" executionBackend)))
    (throw (ex-info "submit requires requestId, an approved selectionContract, scope, targetStack, and executionBackend (host or gondolin)"
                    {:request request})))
  (if-let [existing (get @tasks requestId)]
    (if (= (:request existing) request)
      (:receipt existing)
      (throw (ex-info "requestId was already used with a different payload"
                      {:requestId requestId})))
    (let [_ (ensure-runtime!)
          {:keys [context sheet-id]} (get @workflows :cycle)
          task-id (swap! next-task inc)
          tick-id (random-uuid)
          correlation-id (random-uuid)
          trace (future
                  (orc/execute context sheet-id
                               {:request-id requestId :selection selectionContract
                                :scope scope :target-stack targetStack
                                :execution-backend executionBackend
                                :orc-tick-id (str tick-id)}
                               :tick-id tick-id
                               :correlation-id correlation-id
                               :timeout-ms 900000))
          receipt {:cycleId requestId :selectionId (:selectionId selectionContract)
                   :taskId task-id :targetStack targetStack
                   :executionBackend executionBackend}]
      (swap! tasks assoc requestId {:request request :receipt receipt :future trace})
      receipt)))

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

(defn- cycle-receipt [cycle-id tick-id correlation-id sheet-id status]
  {:cycleId cycle-id
   :tickId (str tick-id)
   :correlationId (str correlation-id)
   :sheetId (str sheet-id)
   :status status})

(defn- existing-cycle-receipt
  [cycle-id tick-id correlation-id sheet-id tick]
  (when-not (= sheet-id (:sheet-id tick))
    (throw (ex-info "tickId belongs to a different ORC workflow"
                    {:tickId (str tick-id)
                     :expectedSheetId (str sheet-id)
                     :actualSheetId (some-> (:sheet-id tick) str)})))
  (when-not (= correlation-id (:correlation-id tick))
    (throw (ex-info "tickId belongs to a different ORC correlation"
                    {:tickId (str tick-id)
                     :expectedCorrelationId (str correlation-id)
                     :actualCorrelationId (some-> (:correlation-id tick) str)})))
  (cycle-receipt cycle-id tick-id correlation-id sheet-id (name (:status tick))))

(defn start-cycle!
  "Start one ORC cycle with caller-supplied durable execution identities.

   Repeated calls in one process return the same receipt. After restart, an
   existing ORC tick is discovered from the durable tick projection."
  [{:keys [cycleId tickId correlationId selectionContract scope targetStack
           executionBackend]
    :as request}]
  (when-not (and (string? cycleId)
                 (map? selectionContract)
                 (= "approved" (:status selectionContract))
                 (seq (:items selectionContract))
                 (vector? scope)
                 (string? targetStack)
                 (not (str/blank? targetStack))
                 (contains? #{"host" "gondolin"} executionBackend))
    (throw (ex-info "start-cycle requires cycleId, durable identities, an approved selectionContract, scope, targetStack, and executionBackend"
                    {:request request})))
  (let [tick-id (parse-uuid-field :tickId tickId)
        correlation-id (parse-uuid-field :correlationId correlationId)
        _ (ensure-runtime!)
        {:keys [context sheet-id]} (get @workflows :cycle)
        receipt (cycle-receipt cycleId tick-id correlation-id sheet-id "running")]
    (locking active-cycles
      (if-let [existing (get @active-cycles tick-id)]
        (if (= request (:request existing))
          (:receipt existing)
          (throw (ex-info "tickId was already used with a different payload"
                          {:tickId tickId})))
        (if-let [tick (orc/get-tick context tick-id)]
          (existing-cycle-receipt cycleId tick-id correlation-id sheet-id tick)
          (let [execution (future
                            (orc/execute context sheet-id
                                         {:request-id cycleId
                                          :selection selectionContract
                                          :scope scope
                                          :target-stack targetStack
                                          :execution-backend executionBackend
                                          :orc-tick-id (str tick-id)}
                                         :tick-id tick-id
                                         :correlation-id correlation-id
                                         :timeout-ms 900000))]
            (swap! active-cycles assoc tick-id
                   {:request request :receipt receipt :future execution})
            receipt))))))

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
