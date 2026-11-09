(ns loyly.cycle-runner.blocks
  "The Grain building blocks of the cycle runner's event model: the schemas and
   event definitions as the room's model gives them, the commands with their
   requires (the Allium rules of cycle-run.allium), the read models and the
   queries. The processor advance-run is in loyly.cycle-runner.advance."
  (:require [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [ai.obney.grain.event-store-v3.interface.event-definition :as event-definition]
            [ai.obney.grain.query-processor.interface :refer [defquery]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [cognitect.anomalies :as anom]
            [loyly.cycle-runner.model :as model]
            [loyly.cycle-runner.runtime :as runtime]
            [loyly.cycle-runner.util :as u]))

;; ---------------------------------------------------------------------------
;; Schemas and event definitions: the model's, verbatim.

(def command-names
  [:cycle-runner/start-run :cycle-runner/record-effect :cycle-runner/relay-handoff
   :cycle-runner/hand-in-verdicts :cycle-runner/conclude-run :cycle-runner/halt-run])

(def query-names [:cycle-runner/run-progress :cycle-runner/runs-to-advance])

(def event-names
  [:cycle-runner/run-started :cycle-runner/effect-recorded :cycle-runner/handoff-relayed
   :cycle-runner/verdicts-handed-in :cycle-runner/run-concluded :cycle-runner/run-halted])

(defn register-schemas! []
  (schema-util/register!
   (into {} (for [k (concat command-names query-names)]
              [k (or (model/command-schema k) (model/query-schema k))])))
  (doseq [k event-names]
    (event-definition/register-event-definition!
     k (model/event-description k) {:schema (model/event-schema k)}
     {:ns (str *ns*) :file "event-model/cycle-run.edn"})))

(register-schemas!)

(defn- event [type body]
  (->event {:type type :body body}))

(defn- refuse
  ([code message] (refuse code message nil))
  ([code message data]
   {::anom/category ::anom/conflict
    ::anom/message message
    :cycle-runner/refusal code
    :cycle-runner/data data}))

;; ---------------------------------------------------------------------------
;; Read models

(def in-progress #{"running" "awaiting-verdicts" "grading"})

(def run-fields
  [:run-id :cycle-id :cut-id :commit :status :effects :attempts
   :candidate-digest :verdicts-digest :cycle :halt])

(defn- reply-event [events type]
  (first (filter #(= type (:type %)) events)))

(defreadmodel :cycle-runner runs
  {:events #{:cycle-runner/run-started :cycle-runner/effect-recorded :cycle-runner/verdicts-handed-in
             :cycle-runner/run-concluded :cycle-runner/run-halted}
   :version 1
   :schema (model/read-model-schema :cycle-runner/runs)}
  "Each run: its cycle, cut and commit, its status, the effects recorded, the candidates regenerated, the verdicts digest, and how it ended."
  [state e]
  (let [run-id (:run-id e)]
    (case (:event/type e)
      :cycle-runner/run-started
      (if (contains? state run-id)
        state
        (assoc state run-id
               {:run-id run-id :cycle-id (:cycle-id e) :cut-id (:cut-id e) :commit (:commit e)
                :status "running" :effects 0 :attempts 0
                :run/order (count state)
                :run/selection-id (:selection-id e)
                :run/budget (:budget e)
                :run/grader-digests (vec (:grader-digests e))
                :run/gate-passed? false}))

      :cycle-runner/effect-recorded
      (if-not (contains? state run-id)
        state
        (update state run-id
                (fn [run]
                  (let [recorded? (= "recorded" (:outcome e))
                        effect (:effect e)
                        regenerated (reply-event (:events e) "regeneration-cycle/candidate-regenerated")
                        passed (reply-event (:events e) "regeneration-cycle/gate-attempt-passed")]
                    (cond-> (assoc run :effects (:position e))
                      (and recorded? (#{"regenerate" "repair"} effect))
                      (assoc :attempts (:attempt e))
                      (and recorded? (#{"regenerate" "repair"} effect) (:candidate-digest regenerated))
                      (assoc :candidate-digest (:candidate-digest regenerated))
                      (and recorded? (= "gate" effect) passed)
                      (assoc :status "awaiting-verdicts"
                             :run/gate-passed? true
                             :run/gated-attempt (:attempt e)
                             :run/gated-candidate-digest (:candidate-digest passed)))))))

      :cycle-runner/verdicts-handed-in
      (if-not (contains? state run-id)
        state
        (update state run-id assoc
                :status "grading"
                :verdicts-digest (:verdicts-digest e)
                :run/verdicts-path (:verdicts-path e)
                :run/grader-digest (:grader-digest e)))

      :cycle-runner/run-concluded
      (if-not (contains? state run-id)
        state
        (update state run-id assoc :status (:cycle-status e) :cycle (:cycle e)))

      :cycle-runner/run-halted
      (if-not (contains? state run-id)
        state
        (update state run-id assoc :status "halted"
                :halt {:effect (:effect e) :reason (:reason e) :detail (:detail e)}))

      state)))

(def effect-result-fields
  [:run-id :position :effect :attempt :context :method :outcome :refusal :events :detail])

(defreadmodel :cycle-runner effect-results
  {:events #{:cycle-runner/effect-recorded}
   :version 1
   :schema (model/read-model-schema :cycle-runner/effect-results)}
  "Each run's effect results, in the order performed."
  [state e]
  (case (:event/type e)
    :cycle-runner/effect-recorded
    (update state (:run-id e) (fnil conj [])
            (cond-> (select-keys e [:run-id :position :effect :attempt :context :method
                                    :outcome :events :detail])
              (:refusal e) (assoc :refusal (:refusal e))))
    state))

(defreadmodel :cycle-runner relayed-handoffs
  {:events #{:cycle-runner/handoff-relayed}
   :version 1
   :schema (model/read-model-schema :cycle-runner/relayed-handoffs)}
  "Each run's relayed handoffs, in the order relayed."
  [state e]
  (case (:event/type e)
    :cycle-runner/handoff-relayed
    (update state (:run-id e) (fnil conj [])
            (select-keys e [:run-id :handoff-type :producer :position :consumer :outcome]))
    state))

(defreadmodel :cycle-runner runs-to-advance
  {:events #{:cycle-runner/run-started :cycle-runner/effect-recorded :cycle-runner/handoff-relayed
             :cycle-runner/verdicts-handed-in :cycle-runner/run-concluded :cycle-runner/run-halted}
   :version 1}
  "The advance-run processor's to-do list: runs running or grading."
  [state e]
  (let [run-id (:run-id e)
        order (or (:order state) [])
        status (or (:status state) {})]
    (case (:event/type e)
      :cycle-runner/run-started
      (if (contains? status run-id)
        state
        {:order (conj order run-id) :status (assoc status run-id "running")})
      :cycle-runner/effect-recorded
      (if (and (contains? status run-id)
               (= "recorded" (:outcome e))
               (= "gate" (:effect e))
               (reply-event (:events e) "regeneration-cycle/gate-attempt-passed"))
        (assoc-in state [:status run-id] "awaiting-verdicts")
        state)
      :cycle-runner/verdicts-handed-in
      (cond-> state (contains? status run-id) (assoc-in [:status run-id] "grading"))
      :cycle-runner/run-concluded
      (cond-> state (contains? status run-id) (assoc-in [:status run-id] (:cycle-status e)))
      :cycle-runner/run-halted
      (cond-> state (contains? status run-id) (assoc-in [:status run-id] "halted"))
      state)))

(defn runs [ctx] (rmp/project ctx :cycle-runner/runs))
(defn run-of [ctx run-id] (get (runs ctx) run-id))
(defn effect-results-of [ctx run-id] (get (rmp/project ctx :cycle-runner/effect-results) run-id []))
(defn relayed-of [ctx run-id] (get (rmp/project ctx :cycle-runner/relayed-handoffs) run-id []))

(defn- run-view [run]
  (when run (into {} (filter (comp some? val)) (select-keys run run-fields))))

;; ---------------------------------------------------------------------------
;; Queries

(defn progress [ctx run]
  (when run
    (assoc (run-view run)
           :effect-results (vec (effect-results-of ctx (:run-id run)))
           :relayed-handoffs (vec (relayed-of ctx (:run-id run))))))

(defn runs-to-advance [ctx]
  (let [{:keys [order status]} (rmp/project ctx :cycle-runner/runs-to-advance)]
    (filterv #(#{"running" "grading"} (get status %)) order)))

;; ---------------------------------------------------------------------------
;; Commands

(defcommand :cycle-runner start-run
  {:authorized? (constantly true)
   :grain.event-model/produces #{:cycle-runner/run-started}
   :grain.event-model/reads #{:cycle-runner/runs}}
  "Start a run of one cycle (rule StartRun)."
  [ctx]
  (let [{:keys [run-id cycle-id cut-id commit selection-id budget grader-digests]} (:command ctx)
        all (vals (runs ctx))]
    (cond
      (some #(= run-id (:run-id %)) all)
      (refuse "run-already-started" (str "Run " run-id " was already started."))
      (some #(in-progress (:status %)) all)
      (refuse "run-in-progress" "Another run is in progress: one run at a time.")
      (some #(= cycle-id (:cycle-id %)) all)
      (refuse "cycle-already-run" (str "Cycle " cycle-id " was run before."))
      (not (runtime/commit? run-id commit))
      (refuse "not-a-commit" (str commit " is no commit of the project repository."))
      (seq (runtime/contexts-not-responding run-id))
      (let [names (runtime/contexts-not-responding run-id)]
        (refuse "contexts-not-responding"
                (str "These contexts do not respond on their seam: " (clojure.string/join ", " names))
                {:contexts names}))
      :else
      {:command-result/events
       [(event :cycle-runner/run-started
               {:run-id run-id :cycle-id cycle-id :cut-id cut-id :commit commit
                :selection-id selection-id :budget budget
                :grader-digests (vec grader-digests) :started-at (u/now)})]})))

(defcommand :cycle-runner record-effect
  {:authorized? (constantly true)
   :grain.event-model/produces #{:cycle-runner/effect-recorded}
   :grain.event-model/reads #{:cycle-runner/runs}}
  "Record one effect's result (rules RecordEffect and RecordEffectAfterTheVerdicts)."
  [ctx]
  (let [{:keys [run-id effect attempt context method outcome refusal events detail]} (:command ctx)
        run (run-of ctx run-id)]
    (if-not (#{"running" "grading"} (:status run))
      (refuse "run-not-performing-effects" (str "Run " run-id " performs no effect now."))
      {:command-result/events
       [(event :cycle-runner/effect-recorded
               (cond-> {:run-id run-id :position (inc (:effects run)) :effect effect
                        :attempt attempt :context context :method method :outcome outcome
                        :events (vec events) :detail detail :performed-at (u/now)}
                 refusal (assoc :refusal refusal)))]})))

(defcommand :cycle-runner relay-handoff
  {:authorized? (constantly true)
   :grain.event-model/produces #{:cycle-runner/handoff-relayed}
   :grain.event-model/reads #{:cycle-runner/runs :cycle-runner/relayed-handoffs}}
  "Hand one producer's handoff in to one consumer and record how it took it (rule RelayHandoff)."
  [ctx]
  (let [{:keys [run-id handoff-type producer position consumer outcome]} (:command ctx)
        run (run-of ctx run-id)]
    (cond
      (not (in-progress (:status run)))
      (refuse "run-ended" (str "Run " run-id " concluded or halted."))
      (some #(and (= producer (:producer %)) (= position (:position %)) (= consumer (:consumer %)))
            (relayed-of ctx run-id))
      (refuse "handoff-already-relayed" "This handoff already reached this consumer.")
      :else
      {:command-result/events
       [(event :cycle-runner/handoff-relayed
               {:run-id run-id :handoff-type handoff-type :producer producer :position position
                :consumer consumer :outcome outcome :relayed-at (u/now)})]})))

(defn- verdicts-file [path]
  (try
    (let [f (clojure.java.io/file (str path))]
      (when (.isFile f)
        (let [bs (u/read-bytes f)
              text (String. ^bytes bs "UTF-8")]
          (when-not (clojure.string/blank? text)
            (let [data (u/<-json text)]
              (when (and (sequential? data)
                         (every? #(and (map? %) (string? (:id %))) data))
                {:digest (str "sha256:" (u/sha256-hex bs)) :verdicts data}))))))
    (catch Exception _ nil)))

(defn read-verdicts [path] (verdicts-file path))

(defcommand :cycle-runner hand-in-verdicts
  {:authorized? (constantly true)
   :grain.event-model/produces #{:cycle-runner/verdicts-handed-in}
   :grain.event-model/reads #{:cycle-runner/runs}}
  "Hand in the verdicts file of the gated candidate (rule HandInVerdicts)."
  [ctx]
  (let [{:keys [run-id verdicts-path grader-digest]} (:command ctx)
        run (run-of ctx run-id)
        file (delay (verdicts-file verdicts-path))]
    (cond
      (nil? run) (refuse "no-run" (str "No run " run-id " exists."))
      (not (:run/gate-passed? run)) (refuse "gate-not-passed" "The gate never passed this run's candidate.")
      (some? (:verdicts-digest run)) (refuse "verdicts-already-handed-in" "This run's verdicts were already handed in.")
      (not= "awaiting-verdicts" (:status run)) (refuse "run-not-awaiting-verdicts" "The run no longer awaits its verdicts.")
      (not (some #{grader-digest} (:run/grader-digests run)))
      (refuse "grader-not-approved" (str "Grader digest " grader-digest " is not one of the run's approved grader digests."))
      (nil? @file) (refuse "verdicts-not-readable" (str "The verdicts file " verdicts-path " is absent, blank or not a list of verdicts."))
      :else
      {:command-result/events
       [(event :cycle-runner/verdicts-handed-in
               {:run-id run-id :verdicts-path verdicts-path :verdicts-digest (:digest @file)
                :grader-digest grader-digest :verdicts (count (:verdicts @file))
                :handed-in-at (u/now)})]})))

(defcommand :cycle-runner conclude-run
  {:authorized? (constantly true)
   :grain.event-model/produces #{:cycle-runner/run-concluded}
   :grain.event-model/reads #{:cycle-runner/runs}}
  "Conclude the run as its cycle concluded (rules ConcludeRunPassed and ConcludeRunFailed)."
  [ctx]
  (let [{:keys [run-id cycle-status cycle]} (:command ctx)
        run (run-of ctx run-id)]
    (cond
      (not (in-progress (:status run))) (refuse "run-ended" (str "Run " run-id " concluded or halted."))
      (and (= "passed" cycle-status) (not= "grading" (:status run)))
      (refuse "run-not-grading" "A run passes only once its verdicts were handed in.")
      :else
      {:command-result/events
       [(event :cycle-runner/run-concluded
               {:run-id run-id :cycle-id (:cycle-id run) :cycle-status cycle-status
                :cycle cycle :concluded-at (u/now)})]})))

(defcommand :cycle-runner halt-run
  {:authorized? (constantly true)
   :grain.event-model/produces #{:cycle-runner/run-halted}
   :grain.event-model/reads #{:cycle-runner/runs}}
  "Halt the run (rule HaltRun)."
  [ctx]
  (let [{:keys [run-id effect reason detail]} (:command ctx)
        run (run-of ctx run-id)]
    (if-not (in-progress (:status run))
      (refuse "run-ended" (str "Run " run-id " concluded or halted."))
      {:command-result/events
       [(event :cycle-runner/run-halted
               {:run-id run-id :effect effect :reason reason :detail detail :halted-at (u/now)})]})))
