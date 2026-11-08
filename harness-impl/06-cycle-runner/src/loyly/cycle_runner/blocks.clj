(ns loyly.cycle-runner.blocks
  "The Grain building blocks of the model, each under the block's own name: the read models
   (pure reducers), the commands (the runner's own rules, StartRun to HaltRun of
   cycle-run.allium) and the queries. Wiring (events consumed, reads, produces, schemas) is
   taken from the model itself."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [cognitect.anomalies :as anom]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.cycle-runner.model :as model]
            [loyly.cycle-runner.util :as u]))

;; ---------------------------------------------------------------------------
;; Refusals
;; ---------------------------------------------------------------------------

(defn refusal
  "A refusal records nothing: an anomaly carrying the refusal code (seam.md, Refusals)."
  ([code message] (refusal code message nil))
  ([code message data]
   {::anom/category ::anom/conflict
    ::anom/message message
    :refusal code
    :refusal/data data}))

(defn refusal? [x] (some? (:refusal x)))

;; ---------------------------------------------------------------------------
;; Read models
;; ---------------------------------------------------------------------------

(def in-progress #{"running" "awaiting-verdicts" "grading"})

(defn- gate-passed-in? [{:keys [effect outcome events]}]
  (and (= "gate" effect) (= "recorded" outcome)
       (some #(= "regeneration-cycle/gate-attempt-passed" (str (:type %))) events)))

(defn- candidate-digest-in [events]
  (some #(when (= "regeneration-cycle/candidate-regenerated" (str (:type %)))
           (:candidate-digest %))
        events))

(defn runs-reducer
  "Each run's row: the read model's fields, plus the runner's own facts under :cr/*
   (never shown: the budget, the grader digests, the selection, gate passed, start order)."
  [state event]
  (let [{:keys [run-id] t :event/type} event]
    (case t
      :cycle-runner/run-started
      (assoc state run-id
             {:run-id run-id :cycle-id (:cycle-id event) :cut-id (:cut-id event)
              :commit (:commit event) :status "running" :effects 0 :attempts 0
              :cr/order (count state) :cr/budget (:budget event)
              :cr/grader-digests (:grader-digests event) :cr/selection-id (:selection-id event)
              :cr/gate-passed false})

      :cycle-runner/effect-recorded
      (if-let [row (get state run-id)]
        (assoc state run-id
               (cond-> (assoc row :effects (:position event))
                 (and (= "recorded" (:outcome event)) (#{"regenerate" "repair"} (:effect event)))
                 (assoc :attempts (:attempt event))

                 (and (= "recorded" (:outcome event)) (#{"regenerate" "repair"} (:effect event))
                      (candidate-digest-in (:events event)))
                 (assoc :candidate-digest (candidate-digest-in (:events event)))

                 (and (gate-passed-in? event) (= "running" (:status row)))
                 (assoc :cr/gate-passed true :status "awaiting-verdicts")))
        state)

      :cycle-runner/verdicts-handed-in
      (if-let [row (get state run-id)]
        (assoc state run-id (assoc row :verdicts-digest (:verdicts-digest event) :status "grading"))
        state)

      :cycle-runner/run-concluded
      (if-let [row (get state run-id)]
        (assoc state run-id (assoc row :status (:cycle-status event) :cycle (:cycle event)))
        state)

      :cycle-runner/run-halted
      (if-let [row (get state run-id)]
        (assoc state run-id (assoc row :status "halted"
                                   :halt (select-keys event [:effect :reason :detail])))
        state)
      state)))

(defn effect-results-reducer [state event]
  (if (= :cycle-runner/effect-recorded (:event/type event))
    (update state (:run-id event) (fnil conj [])
            (cond-> (select-keys event [:run-id :position :effect :attempt :context :method
                                        :outcome :events :detail])
              (contains? event :refusal) (assoc :refusal (:refusal event))))
    state))

(defn relayed-handoffs-reducer [state event]
  (if (= :cycle-runner/handoff-relayed (:event/type event))
    (update state (:run-id event) (fnil conj [])
            (select-keys event [:run-id :handoff-type :producer :position :consumer :outcome]))
    state))

(defn runs-to-advance-reducer
  "Runs running or grading: the processor's to-do list."
  [state event]
  (let [{:keys [run-id] t :event/type} event]
    (case t
      :cycle-runner/run-started (assoc state run-id "running")
      :cycle-runner/effect-recorded (if (and (gate-passed-in? event) (= "running" (get state run-id)))
                                      (assoc state run-id "awaiting-verdicts")
                                      state)
      :cycle-runner/verdicts-handed-in (assoc state run-id "grading")
      (:cycle-runner/run-concluded :cycle-runner/run-halted) (dissoc state run-id)
      state)))

;; ---------------------------------------------------------------------------
;; Projections
;; ---------------------------------------------------------------------------

(defn runs [ctx] (or (rmp/project ctx :cycle-runner/runs) {}))
(defn run [ctx run-id] (get (runs ctx) run-id))
(defn effect-results [ctx run-id] (get (rmp/project ctx :cycle-runner/effect-results) run-id []))
(defn relayed-handoffs [ctx run-id] (get (rmp/project ctx :cycle-runner/relayed-handoffs) run-id []))

(defn public-row [row]
  (into {} (remove (fn [[k _]] (= "cr" (namespace k)))) row))

(defn progress [ctx row]
  (assoc (public-row row)
         :effect-results (effect-results ctx (:run-id row))
         :relayed-handoffs (relayed-handoffs ctx (:run-id row))))

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(defn- ->event [type body]
  (es/->event {:type type :body body}))

(defn start-run-refusal
  "StartRun requires 1 to 3, the runner's own rules: one run per identity, one run in
   progress, one run per cycle."
  [ctx {:keys [run-id cycle-id]}]
  (let [rs (vals (runs ctx))]
    (cond
      (some #(= run-id (:run-id %)) rs)
      (refusal "run-already-started" (str "Run " run-id " was already started."))

      (some #(in-progress (:status %)) rs)
      (refusal "run-in-progress" (str "Run " (:run-id (first (filter #(in-progress (:status %)) rs)))
                                      " is in progress: one run at a time."))

      (some #(= cycle-id (:cycle-id %)) rs)
      (refusal "cycle-already-run" (str "Cycle " cycle-id " was already run.")))))

(defn budget-of
  "The budget exactly as the model's schema spells it (the gate closes every map)."
  [{:keys [budget]}]
  (-> (select-keys budget [:gate-attempt-limit :max-outer-cycles :max-continuations])
      (assoc :stage-timeouts-seconds (select-keys (:stage-timeouts-seconds budget)
                                                  [:fence :regenerate :gate :score]))))

(defn start-run [{:keys [command] :as ctx}]
  (or (start-run-refusal ctx command)
      {:command-result/events
       [(->event :cycle-runner/run-started
                 (assoc (select-keys command [:run-id :cycle-id :cut-id :commit :selection-id
                                              :grader-digests])
                        :budget (budget-of command)
                        :started-at (u/now)))]}))

(defn record-effect
  "RecordEffect (status running) and RecordEffectAfterTheVerdicts (status grading)."
  [{:keys [command] :as ctx}]
  (let [row (run ctx (:run-id command))
        after? (#{"file-verdicts" "file-score" "fail-score-stage"} (:effect command))]
    (cond
      (nil? row) (refusal "no-run" "No such run.")
      (and (not after?) (not= "running" (:status row)))
      (refusal "run-not-running" (str "Run " (:run-id row) " is " (:status row) "."))
      (and after? (not= "grading" (:status row)))
      (refusal "run-not-grading" (str "Run " (:run-id row) " is " (:status row) "."))
      :else
      {:command-result/events
       [(->event :cycle-runner/effect-recorded
                 (cond-> (assoc (select-keys command [:run-id :effect :attempt :context :method
                                                      :outcome :events :detail])
                                :position (inc (:effects row))
                                :performed-at (u/now))
                   (:refusal command) (assoc :refusal (:refusal command))))]})))

(defn relay-handoff [{:keys [command] :as ctx}]
  (let [row (run ctx (:run-id command))
        done (relayed-handoffs ctx (:run-id command))]
    (cond
      (or (nil? row) (not (in-progress (:status row))))
      (refusal "run-ended" "The run is concluded or halted.")
      (some #(= (select-keys % [:producer :position :consumer])
                (select-keys command [:producer :position :consumer])) done)
      (refusal "handoff-already-relayed" "That handoff already reached that consumer.")
      :else
      {:command-result/events
       [(->event :cycle-runner/handoff-relayed
                 (assoc (select-keys command [:run-id :handoff-type :producer :position :consumer :outcome])
                        :relayed-at (u/now)))]})))

(defn verdicts-reading
  "The verdicts file: a JSON list of {id, verdict, evidence?, reason?}, or nil when absent,
   blank or not such a list."
  [path]
  (try
    (let [f (io/file path)]
      (when (.isFile f)
        (let [text (slurp f)]
          (when-not (str/blank? text)
            (let [v (u/parse-json text)]
              (when (and (vector? v)
                         (every? #(and (map? %) (string? (:id %)) (string? (:verdict %))) v))
                {:verdicts v :digest (u/sha256-of-file f)}))))))
    (catch Exception _ nil)))

(defn hand-in-verdicts-refusal [ctx {:keys [run-id grader-digest verdicts-path]}]
  (let [row (run ctx run-id)]
    (cond
      (nil? row) (refusal "no-run" (str "No run " run-id "."))
      (not (:cr/gate-passed row)) (refusal "gate-not-passed" (str "The gate never passed run " run-id "."))
      (:verdicts-digest row) (refusal "verdicts-already-handed-in" "A run takes one verdicts file.")
      (not= "awaiting-verdicts" (:status row)) (refusal "run-not-awaiting-verdicts" (str "Run " run-id " is " (:status row) "."))
      (not (some #{grader-digest} (:cr/grader-digests row)))
      (refusal "grader-not-approved" (str "Grader digest " grader-digest " is not one of the run's approved grader digests."))
      (nil? (verdicts-reading verdicts-path))
      (refusal "verdicts-not-readable" (str "The verdicts file " verdicts-path " is absent, blank or not a list of verdicts.")))))

(defn hand-in-verdicts [{:keys [command] :as ctx}]
  (or (hand-in-verdicts-refusal ctx command)
      (let [{:keys [verdicts digest]} (verdicts-reading (:verdicts-path command))]
        {:command-result/events
         [(->event :cycle-runner/verdicts-handed-in
                   {:run-id (:run-id command) :verdicts-path (:verdicts-path command)
                    :verdicts-digest digest :grader-digest (:grader-digest command)
                    :verdicts (count verdicts) :handed-in-at (u/now)})]})))

(defn conclude-run
  "ConcludeRunPassed and ConcludeRunFailed: the run ends as its cycle ended."
  [{:keys [command] :as ctx}]
  (let [row (run ctx (:run-id command))
        status (:cycle-status command)]
    (cond
      (or (nil? row) (not (in-progress (:status row))))
      (refusal "run-ended" "The run is concluded or halted.")
      (and (= "passed" status) (not= "grading" (:status row)))
      (refusal "run-not-grading" "A run passes only after its verdicts.")
      :else
      {:command-result/events
       [(->event :cycle-runner/run-concluded
                 {:run-id (:run-id row) :cycle-id (:cycle-id row) :cycle-status status
                  :cycle (:cycle command) :concluded-at (u/now)})]})))

(defn halt-run [{:keys [command] :as ctx}]
  (let [row (run ctx (:run-id command))]
    (if (or (nil? row) (not (in-progress (:status row))))
      (refusal "run-ended" "The run is concluded or halted.")
      {:command-result/events
       [(->event :cycle-runner/run-halted
                 (assoc (select-keys command [:run-id :effect :reason :detail])
                        :halted-at (u/now)))]})))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn run-progress [{:keys [query] :as ctx}]
  {:query/result
   (if (contains? query :run-id)
     {:run (some->> (run ctx (:run-id query)) (progress ctx))}
     {:runs (->> (vals (runs ctx)) (sort-by :cr/order) (mapv #(progress ctx %)))})})

(defn runs-to-advance [ctx]
  {:query/result
   {:run-ids (let [st (or (rmp/project ctx :cycle-runner/runs-to-advance) {})
                   order (into {} (map (fn [[k r]] [k (:cr/order r)])) (runs ctx))]
               (->> st (filter (fn [[_ s]] (#{"running" "grading"} s))) (map key)
                    (sort-by order) vec))}})

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(def ^:private command-handlers
  {:cycle-runner/start-run start-run
   :cycle-runner/record-effect record-effect
   :cycle-runner/relay-handoff relay-handoff
   :cycle-runner/hand-in-verdicts hand-in-verdicts
   :cycle-runner/conclude-run conclude-run
   :cycle-runner/halt-run halt-run})

(def ^:private reducers
  {:cycle-runner/runs runs-reducer
   :cycle-runner/effect-results effect-results-reducer
   :cycle-runner/relayed-handoffs relayed-handoffs-reducer
   :cycle-runner/runs-to-advance runs-to-advance-reducer})

(def ^:private query-handlers
  {:cycle-runner/run-progress run-progress
   :cycle-runner/runs-to-advance runs-to-advance})

(defn register-blocks! []
  (let [am (model/area-map)]
    (doseq [[k b] (:commands am)]
      (cp/register-command! k (or (command-handlers k) (throw (ex-info "No handler" {:command k})))
                            {:authorized? (constantly true)
                             :grain.event-model/reads (:reads b)
                             :grain.event-model/produces (:produces b)}))
    (doseq [[k b] (:read-models am)]
      (rmp/register-read-model! k (or (reducers k) (throw (ex-info "No reducer" {:read-model k})))
                                (cond-> {:events (:consumes b) :version 1}
                                  (:schema b) (assoc :schema (:schema b)))))
    (doseq [[k b] (:queries am)]
      (qp/register-query! k (or (query-handlers k) (throw (ex-info "No handler" {:query k})))
                          {:authorized? (constantly true)
                           :grain.event-model/reads (:reads b)}))))
