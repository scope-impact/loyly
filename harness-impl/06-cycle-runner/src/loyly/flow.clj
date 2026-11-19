(ns loyly.flow
  (:require [loyly.host :as host] [loyly.views :as views] [loyly.policy :as policy] [ai.obney.grain.periodic-task.interface :as periodic] [ai.obney.grain.event-model-validator.interface :as guard] [ai.obney.grain.event-store-v3.interface :as es] [loyly.gate :as gates] [loyly.grading :as grading] [clojure.data.json :as json] [loyly.cycle :as cycle] [ai.obney.orc.llm.interface :as llm]
            [loyly.domain :as d] [loyly.plan :as plan]
            [clojure.edn :as edn] [clojure.string :as str] [clojure.java.io :as io]
            [ai.obney.orc.orc-service.core.dsl :as bt]
            [ai.obney.orc.orc-service.core.runtime :as runtime] [ai.obney.orc.orc-service.core.value-log :as values]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.queries]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface.protocol :as kv]
            [ai.obney.grain.read-model-processor-v2.interface :as rm]))
(defn cache []
  (let [entries (atom {})]
    (reify kv/KVStore
      (start [this] this) (stop [_] (reset! entries {}))
      (get! [_ {:keys [k]}] (get @entries (vec k)))
      (put! [_ {:keys [k v]}] (swap! entries assoc (vec k) v))
      (put-batch! [_ batch] (doseq [{:keys [k v]} (:entries batch)] (swap! entries assoc (vec k) v))))))
(defn attach [system]
  (rm/l1-clear!)
  (let [ctx (assoc system :cache (cache) :orc/execution-lease-ms 4000)
        _ (policy/register! (:models system) ctx)
        _ (guard/verify-or-throw! {:model (:models system) :event-store (:event-store system)})
        timers (periodic/start-periodic-triggers! {:tenant-ids-fn #(set (keys (es/tenants (:event-store ctx))))
                                                  :append-fn #(es/append (:event-store ctx) %)})]
    (assoc ctx :timers timers :processors (mapv (fn [[k {:keys [topics handler-fn]}]]
                                   (tp/start-polling {:event-store (:event-store ctx) :tenant-id (:tenant-id ctx)
                                                      :processor-name k :topics topics :poll-interval-ms 25
                                                      :handler-fn #(handler-fn (merge ctx %))}))
                                 @tp/processor-registry*))))
(defn stop [system] (when-let [recovery (:recovery system)] (future-cancel recovery)) (periodic/stop-periodic-triggers! (:timers system)) (doseq [processor (:processors system)] (tp/stop-polling processor)))
(defn state [ctx] (edn/read-string (get-in ctx [:inputs :state])))
(defn output [value] {:state (pr-str value) :running (not (or (:halt value) (:failure value))) :gated (boolean (:gated value))})
(defn halted [s effect reason detail] (assoc s :halt {:effect effect :reason reason :detail detail}))
(defn run-stage [ctx effect f]
  (let [s (state ctx)]
    (output (if (or (:halt s) (:failure s)) s (try (f s) (catch Exception e (if (:budget (ex-data e)) (assoc s :failure {:stage (if (= effect "repair") "regenerate" effect) :reason "budget" :detail (str e)}) (halted s effect "effect-could-not-run" (str e)))))))))
(defn record [ctx s effect method p]
  (let [{:keys [events refusal]} (d/issue ctx method p)]
    (d/issue ctx :cycle-runner/record-effect
             (cond-> {:run-id (get-in s [:p :run-id]) :effect effect :context (namespace method)
                      :method (subs (str method) 1) :attempt (get s :attempt 0) :events (or events [])
                      :detail (or refusal (:detail s) (str (count events) " event(s)")) :outcome (if refusal "refused" "recorded")}
               refusal (assoc :refusal refusal)))
    (if refusal (halted s effect "refused-before-the-cycle" refusal) s)))
(defn start [ctx]
  (run-stage ctx "start" (fn [{:keys [p] :as s}] (d/issue ctx :cycle-runner/start-run p) s)))
(defn stamp [ctx]
  (run-stage ctx "stamp"
    (fn [{:keys [p env config] :as s}]
      (let [slices (plan/slices (env "LOYLY_PROJECT_REPO") (:commit p) config)]
        (reduce (fn [acc {:keys [slice name scenarios text]}]
                  (let [source (io/file (env "LOYLY_RUNS_DIR") (:run-id p) name "spec.feature")
                        previous (filter #(= slice (:slice %)) (d/rows ctx :specification/scenario-stamped))
                        offered (if (seq previous) (remove :item-id scenarios) scenarios)]
                    (io/make-parents source)
                    (spit source (str/replace text #"(?m)^\s*@item-\d+-\d+\s*$\n?" ""))
                    (let [next (record ctx acc "stamp" :specification/stamp-slice
                                       {:slice slice :source (str source) :unstamped (mapv #(select-keys % [:line :title]) (remove #(str/blank? (:title %)) offered))
                                        :empty-title-lines (mapv :line (filter #(str/blank? (:title %)) offered))})
                          ids (mapv :item-id (filter #(= slice (:slice %)) (d/rows ctx :specification/scenario-stamped)))]
                      (if (= ids (mapv :item-id scenarios)) next
                          (halted next "stamp" "refused-before-the-cycle" "Stamped identities differ from the cut")))))
                (assoc s :slices slices) slices)))))
(defn pin [ctx]
  (run-stage ctx "pin" (fn [{:keys [p env] :as s}]
                         (record ctx s "pin" :cycle-preparation/pin-source-cut
                                 (assoc (select-keys p [:cut-id :commit]) :tree-digest
                                        (str/trim (plan/git (env "LOYLY_PROJECT_REPO") "rev-parse" (str (:commit p) "^{tree}"))))))))
(defn checklist [ctx]
  (run-stage ctx "checklist" (fn [{:keys [p slices] :as s}]
    (let [items (vec (for [{:keys [path scenarios]} slices row scenarios]
                       (assoc (dissoc row :line) :source (str path ":" (:line row)))))]
      (record ctx (assoc s :items items) "checklist" :measurement/derive-checklist
              (merge (select-keys p [:cut-id :selection-id]) {:items items :unstamped [] :skipped-slices []}))))))
(defn grader [ctx]
  (run-stage ctx "grader" (fn [{:keys [p env] :as s}]
    (let [digest (d/digest (str "cycle-runner/grade.v1\n" (slurp (env "LOYLY_GRADER_RUBRIC"))))]
      (if (some #{digest} (:grader-digests p)) (assoc s :grader digest)
          (halted s "grader" "refused-before-the-cycle" (str "Grader digest unapproved: " digest)))))))
(defn halt [ctx]
  (let [{:keys [p halt] :as s} (state ctx)]
    (when halt (d/issue ctx :cycle-runner/halt-run (assoc halt :run-id (:run-id p))))
    (output s)))
(defn run-dir [{:keys [env p]}] (host/child (env "LOYLY_RUNS_DIR") (:run-id p)))
(defn timeout [s stage] (get-in s [:p :budget :stage-timeouts-seconds stage]))
(defn failed [s stage reason detail] (assoc s :failure {:stage stage :reason reason :detail detail}))
(defn self-check [ctx]
  (run-stage ctx "self-check"
    (fn [{:keys [p env config attempt] :as s}]
      (let [repo (env "LOYLY_PROJECT_REPO") command (env "LOYLY_SELF_CHECK_CMD")
            probe (plan/git repo "show" (str (:commit p) ":" (config "APP_PROBE")))
            harness (d/digest (str (slurp (env "LOYLY_HARNESS_CONFIG")) command probe
                                   (or (not-empty (env "LOYLY_GATE_CMD")) (when (= "stdio" (config "APP_SEAM")) (env "LOYLY_STORE_CHECK_CMD")))))
            {:keys [out exit]} (host/script repo (timeout s (if attempt :gate :fence)) {} command)
            lines (str/split-lines out)
            fields (merge (select-keys p [:cut-id :cycle-id :grader-digests :budget])
                          {:occasion (if attempt "before-gate" "before-fence") :attempt (or attempt 0) :harness-digest harness
                           :goldens-digest (d/digest "") :identity-probe-digest (d/digest probe) :outcome (if (zero? exit) "passed" "failed")
                           :skipped-checks (vec (keep #(second (re-find #"^\s*SKIP\s+(.+?)\s*$" %)) lines))
                           :missing-goldens [] :reworded-goldens []})
            s (record ctx (assoc s :harness harness :probe (d/digest probe) :detail out) "self-check" :regeneration-cycle/record-harness-self-check
                      (cond-> fields (not (zero? exit)) (assoc :failing-check (or (last lines) "self-check") :detail out)))]
        (if (zero? exit) s (failed s (if attempt "gate" "fence") "harness-self-check-failed" (or (last lines) out)))))))
(defn fence [ctx]
  (run-stage ctx "fence"
    (fn [{:keys [p env config] :as s}]
      (let [room (io/file (run-dir s) "clean")
            facts (host/fence (env "LOYLY_PROJECT_REPO") (:commit p) room (vec (str/split (config "FENCED_PATHS") #"\s+")))
            next (record ctx (assoc s :clean (str room)) "fence" :regeneration-cycle/prove-clean-room
                         (merge facts (select-keys p [:cycle-id :cut-id])))]
        (if (:halt next) (failed (dissoc next :halt) "fence" "fence-refused" (get-in next [:halt :detail])) next)))))
(declare tick-id)
(defn build [ctx s repair?]
  (let [{:keys [p env config clean]} s attempt (inc (get s :attempt 0))
        dir (run-dir s) builder (io/file dir (str "builder-" attempt)) instruction (io/file dir (str "instruction-" attempt ".md"))
        session (str (:run-id p) "/" (:root-tick s) "/" attempt) image (not-empty (config "BUILDER_IMAGE"))
        image-path (when image (host/child (env "LOYLY_BUILDER_IMAGES") image))
        args (cond-> [builder instruction] image-path (conj image-path))
        prior (io/file builder "previous-candidate") evidence (io/file builder "gate-evidence")
        text (or (:instruction s) (get-in ctx [:inputs :builder-instruction]))
        detail (str (when repair? (str "evidence " (:evidence s) " ")) "session " session
                    (when image (str " image " image " " (host/tree-digest image-path))))]
    (when-not (.exists builder) (host/copy! (io/file clean) builder))
    (when repair?
      (host/copy! (io/file (:candidate s)) prior)
      (host/copy! (io/file (:evidence s)) evidence)
      (doseq [f [prior evidence]] (host/checked builder ["chmod" "-R" "a-w" f])))
    (spit instruction (str text (when repair? (str "\n\nRepair the previous candidate at " prior " using the gate evidence at " evidence ". Both are read-only."))))
    (host/builder! dir attempt (env "LOYLY_PROJECT_REPO") (timeout s :regenerate) session (env "LOYLY_BUILDER_HOST") args)
    (let [scope (host/child builder (config "APP_DIR")) digest (when (.isDirectory scope) (host/tree-digest scope))]
      (if digest (record ctx (assoc s :builder (str builder) :digest digest :attempt attempt :detail detail :repair-needed false :instruction text)
                         (if repair? "repair" "regenerate") :regeneration-cycle/record-regenerated-candidate
                         (cond-> {:cycle-id (:cycle-id p) :attempt attempt :purpose (if repair? "verification-repair" "fresh-generation")
                                  :candidate-digest digest :mounted-evidence (if repair? [(:evidence s)] [])}
                           repair? (assoc :parent-candidate-digest (:digest s))))
          (failed s "regenerate" "candidate-not-regenerated" "Builder left an empty candidate")))))
(defn regenerate [ctx] (run-stage ctx "regenerate" #(build ctx % false)))
(defn repair [ctx] (run-stage ctx "repair" #(if (:repair-needed %) (build ctx % true) %)))
(defn install [ctx]
  (run-stage ctx "install"
    (fn [{:keys [p env config builder digest attempt clean] :as s}]
      (let [dir (run-dir s) gate (io/file dir (str "gate-" attempt)) candidate (io/file dir (str "candidate-" attempt))
            archive (io/file dir (str "gate-" attempt ".tar")) ext? (seq (env "LOYLY_GATE_CMD")) repo (env "LOYLY_PROJECT_REPO")]
        (.mkdir gate)
        (host/checked (if ext? repo clean) ["git" "archive" "--format=tar" "-o" archive (if ext? (:commit p) "HEAD")])
        (host/checked gate ["tar" "-xf" archive])
        (let [scope (host/child gate (config "APP_DIR"))]
          (host/checked gate ["rm" "-rf" (str scope)]))
        (host/copy! (host/child builder (config "APP_DIR")) candidate)
        (when-not ext? (let [scope (host/child gate (config "APP_DIR"))] (io/make-parents scope) (host/copy! candidate scope)))
        (record ctx (assoc s :gate (str gate) :candidate (str candidate) :detail (str candidate)) "install" :regeneration-cycle/install-candidate-in-gate-room
                {:cycle-id (:cycle-id p) :attempt attempt :purpose (if (= attempt 1) "fresh-generation" "verification-repair") :candidate-digest (host/tree-digest candidate) :gate-room (str gate)})))))
(def guard-reasons {"seam-answered-before-boot" "boot-budget-exhausted" "identity-probe-missing" "identity-probe-failed"})
(defn gate [ctx]
  (run-stage ctx "gate"
    (fn [{:keys [p env config digest harness probe candidate gate attempt] :as s}]
      (if (or (:gated s) (:repair-needed s)) s
        (let [evidence (io/file (run-dir s) (str "evidence-" attempt "-" (inc (get s :refusals 0)))) _ (.mkdir evidence)
              external (not-empty (env "LOYLY_GATE_CMD"))
              exists? (str/starts-with? (plan/git (env "LOYLY_PROJECT_REPO") "ls-tree" (:commit p) "--" (config "APP_PROBE")) "100755 ")
              {:keys [out exit]} (cond (not external) (gates/host-gate s evidence (timeout s :gate))
                                       (not exists?) (gates/result 1 "identity-probe-missing identity probe unavailable" 20 10)
                                       :else (host/script (env "LOYLY_PROJECT_REPO") (timeout s :gate) {} external gate candidate evidence))
              [_ polls seconds] (re-find #"(?m)^BOOT (\d+) (\d+)\s*$" out)
              [_ poll] (re-find #"(?m)^ANSWERED (\d+)\s*$" out)
              line (or (gates/last-line out) "") [reason detail] (str/split line #"\s+" 2)
              guard (guard-reasons reason)
              valid? (or guard (contains? #{"contract-missing" "suite-discovery-empty" "suite-red" "boot-budget-exhausted" "identity-probe-failed"} reason))]
          (if (and (not (zero? exit)) (not valid?)) (halted s "gate" "effect-could-not-run" line)
              (let [next (record ctx (assoc s :detail (or detail line) :evidence (str evidence)) "gate" :regeneration-cycle/record-gate-attempt
                                 (cond-> {:cycle-id (:cycle-id p) :attempt attempt :candidate-digest digest :harness-digest harness :outcome (if (zero? exit) "passed" "failed")
                                          :identity-probe-present (not= "identity-probe-missing" reason) :identity-probe-digest probe :seam-answered-before-boot (= reason "seam-answered-before-boot")
                                          :boot-polls (or (some-> polls parse-long) 20) :boot-budget-seconds (or (some-> seconds parse-long) 10) :evidence-root (str evidence)
                                          :detail (or detail line) :grader-digests (:grader-digests p)}
                                   (and poll (zero? exit)) (assoc :answered-on-poll (parse-long poll))
                                   (zero? exit) (assoc :identity-probe-result line)
                                   (not (zero? exit)) (assoc :reason (or guard reason))))]
                (cond guard (assoc (dissoc next :halt) :gate-refusal (get-in next [:halt :detail]))
                      (zero? exit) (assoc next :gated true)
                      (< attempt (get-in p [:budget :gate-attempt-limit])) (assoc next :repair-needed true)
                      :else (failed next "gate" "gate-attempts-exhausted" line)))))))))
(defn gate-refusal [ctx]
  (run-stage ctx "gate-refusal"
    (fn [{:keys [p attempt gate-refusal] :as s}]
      (if-not gate-refusal s
        (let [n (inc (get s :refusals 0)) next (record ctx (assoc (dissoc s :gate-refusal) :refusals n) "gate-refusal"
                                                      :regeneration-cycle/record-gate-refusal
                                                      {:cycle-id (:cycle-id p) :attempt attempt :refusal gate-refusal :detail (:detail s)})]
          (if (>= n 3) (failed next "gate" "infrastructure" gate-refusal) next))))))
(declare wait-for)
(defn fail-cycle [ctx]
  (let [{:keys [p failure] :as s} (state ctx)]
    (when (and failure (empty? (cycle/of ctx (:cycle-id p) :regeneration-cycle/cycle-failed)))
      (if (contains? #{"harness-self-check-failed" "gate-attempts-exhausted" "infrastructure" "score-stage-failed"} (:reason failure))
        (wait-for #(seq (cycle/of ctx (:cycle-id p) :regeneration-cycle/cycle-failed)))
        (record ctx s "fail-cycle" :regeneration-cycle/fail-cycle (assoc failure :cycle-id (:cycle-id p)))))
    (output s)))
(defn conclude [ctx]
  (let [{:keys [p failure halt] :as s} (state ctx) id (:cycle-id p)]
    (when-not halt
      (let [status (if failure "failed" "passed")
            cycle (cond-> {:cycle-id id :cut-id (:cut-id p) :stage (or (:stage failure) "result") :status status
                           :gate-attempts (count (cycle/of ctx id :regeneration-cycle/candidate-regenerated))
                           :attempts (mapv #(select-keys % [:attempt :candidate-digest :evidence-root]) (cycle/of ctx id :regeneration-cycle/gate-attempt-passed))}
                    failure (assoc :failure failure))]
        (when (and (not failure) (empty? (cycle/of ctx id :regeneration-cycle/cycle-passed))) (wait-for #(seq (cycle/of ctx id :regeneration-cycle/cycle-passed))))
        (d/issue ctx :cycle-runner/conclude-run {:run-id (:run-id p) :cycle-status status :cycle (:cycle (views/cycle-progress ctx {:cycle-id id}))})))
    (output s)))
(defn grading-vm [ctx]
  (run-stage ctx "drive-seam" (fn [{:keys [env] :as s}] (grading/with-vm (env "LOYLY_GRADER_IMAGE") nil (timeout s :score) (constantly s)))))
(defn grade-items [ctx]
  (run-stage ctx "drive-seam" (fn [s] (assoc s :verdicts (vec (keep #(grading/grade-item ctx s %) (:items s)))))))
(defn file-verdicts [ctx]
  (run-stage ctx "file-verdicts"
    (fn [{:keys [p grader digest verdicts] :as s}]
      (let [next (record ctx s "file-verdicts" :measurement/file-item-verdicts
              {:cycle-id (:cycle-id p) :candidate-digest digest :grader-digest grader :approved-grader-digests (:grader-digests p) :verdicts verdicts})]
        (if (:halt next) (assoc (dissoc next :halt) :score-refusal (get-in next [:halt :detail])) next)))))
(defn allure [s receipt]
  (let [dir (io/file (run-dir s) "allure-results")]
    (.mkdirs dir)
    (doseq [{:keys [item-id verdict evidence]} (:items receipt)]
      (let [id (str (random-uuid)) status (if (= "pass" verdict) "passed" "failed")
            title (:title (some #(when (= item-id (:item-id %)) %) (:items s)))
            doc (cond-> {:uuid id :historyId item-id :testCaseId item-id :fullName (str item-id " " title) :name title :description evidence
                         :status status :labels [{:name "tag" :value (str "item-" item-id)}]}
                  (= "failed" status) (assoc :statusDetails {:message evidence}))]
        (spit (io/file dir (str id "-result.json")) (json/write-str doc))))))
(defn wait-for [pred]
  (let [deadline (+ (System/nanoTime) 10000000000)]
    (loop [] (or (pred) (if (< (System/nanoTime) deadline) (do (Thread/sleep 15) (recur)) (throw (ex-info "Policy did not conclude" {})))))))
(defn fail-score-stage [ctx]
  (run-stage ctx "fail-score-stage" (fn [{:keys [p score-refusal] :as s}]
    (if-not score-refusal s (failed (record ctx s "fail-score-stage" :measurement/fail-score-stage
      {:cycle-id (:cycle-id p) :refusal score-refusal :detail score-refusal}) "score" "score-stage-failed" score-refusal)))))
(defn file-score [ctx]
  (run-stage ctx "file-score"
    (fn [{:keys [p] :as s}]
      (let [next (record ctx s "file-score" :measurement/file-score {:cycle-id (:cycle-id p) :approved-grader-digests (:grader-digests p)})
            receipt (last (cycle/of ctx (:cycle-id p) :measurement/measurement-filed))]
        (when receipt (allure s receipt) (wait-for #(seq (cycle/of ctx (:cycle-id p) :measurement/scoreboard-reported))))
        next))))
(defn render-failed [ctx] (output (halted (state ctx) "render" "effect-could-not-run" "Instruction renderer failed")))
(defn leaf
  ([label] (leaf label label))
  ([label function & reads]
   (bt/code label :fn (str "loyly.flow/" function) :reads (into [:state] reads) :writes [:state :running :gated])))
(defn ok [label] (bt/condition label :check {:key :running :op :equals :value true}))
(defn tick-id [sys id]
  (let [start (some #(when (= id (:run-id %)) %) (d/rows sys :cycle-runner/run-started))]
    (some-> (last (filter #(and (= "cycle-runner/cycle.v1" (get-in % [:execution-snapshot :sheet-name]))
                                 (neg? (compare (:event/id %) (:event/id start)))) (d/rows sys :sheet/tree-tick-started))) :tick-id)))
(defn providers! [env]
  (doseq [[provider model] [[:cycle-render "LOYLY_RENDER_MODEL"] [:cycle-grader "LOYLY_GRADER_MODEL"]]
          :when (env "LOYLY_MODEL_API_BASE")]
    (llm/register-provider! provider {:provider :openai :model (env model) :config {:api-key "local" :api-base (env "LOYLY_MODEL_API_BASE") :timeout 600000}})))
(defn resume! [sys]
  (let [running (set (for [[id r] (rm/project sys :cycle-runner/runs) :when (= "running" (:status r))] id))]
    (doseq [id running :let [tick (tick-id sys id) seeds (values/tick-seeds sys (:tenant-id sys) tick)]]
      (when-let [s (:state seeds)] (providers! (:env (edn/read-string s)))))
    (when (seq running)
      (future (loop []
                (runtime/resume-in-progress! sys)
                (when (some #(= "running" (:status (get (rm/project sys :cycle-runner/runs) %))) running)
                  (Thread/sleep 350) (recur)))))))
(defn execute [system {:keys [env p] :as s}]
  (providers! env)
  (let [s (assoc s :root-tick (random-uuid)) schema {:state :string :running :boolean :gated :boolean :builder-instruction :string :selection :string}
        child (bt/build-workflow! system
                (bt/workflow "cycle-runner/gate-attempt.v1" (bt/blackboard schema)
                  (apply bt/sequence "gate-attempt" (concat [(leaf "install") (leaf "self-check")]
                    (mapcat (fn [i] [(leaf (str "gate:" i) "gate") (leaf (str "gate-refusal:" i) "gate-refusal")]) [1 2 3]) [(leaf "repair")]))))
        wf (bt/workflow "cycle-runner/cycle.v1" (bt/blackboard schema)
            (bt/sequence "cycle"
              (leaf "start") (leaf "stamp") (leaf "pin") (leaf "checklist") (leaf "grader") (leaf "self-check:0" "self-check") (leaf "fence")
              (bt/fallback "build-or-end"
                (bt/sequence "build"
                  (ok "ready-to-render")
                  (bt/fallback "render-or-halt"
                    (bt/llm "render" :provider :cycle-render :model (or (env "LOYLY_RENDER_MODEL") "render")
                            :instruction (or (env "LOYLY_BUILDER_INSTRUCTION") "Render the builder instruction for this selection.")
                            :reads [:selection] :writes [:builder-instruction] :options {:use-function-calling? false})
                    (leaf "render-failed"))
                  (leaf "regenerate" "regenerate" :builder-instruction) (ok "ready-to-gate")
                  (apply bt/fallback "attempts"
                    (for [i (range 1 (inc (get-in p [:budget :gate-attempt-limit])))]
                      (bt/sequence (str "try:" i)
                        (bt/delegate (str "gate-attempt:" i) :target-sheet-id child :reads [:state :running :gated] :writes [:state :running :gated]
                                     :timeout-ms (* 1000 (+ 30 (timeout s :gate) (timeout s :regenerate))))
                        (bt/condition (str "attempt-ended:" i) :check {:key :gated :op :equals :value true}))))
                  (leaf "grading-vm") (leaf "grade-items") (leaf "file-verdicts") (leaf "fail-score-stage") (leaf "file-score"))
                (bt/condition "stopped" :check {:key :running :op :equals :value false}))
              (leaf "fail-cycle") (leaf "conclude") (leaf "halt")))
        id (bt/build-workflow! system wf)
        result (runtime/execute system id {:state (pr-str s) :running true :gated false :selection (:selection-id p)} :tick-id (:root-tick s) :timeout-ms (* 1000 (+ 60 (reduce + (vals (get-in p [:budget :stage-timeouts-seconds]))))))]
    (when-not (= :success (:status result)) (throw (ex-info (str "Cycle tick failed: " result) {})))))
