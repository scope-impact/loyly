(ns loyly.regen.blocks
  "The Grain building blocks of the event model, one per block and named as it:
   read models, commands and queries. Schemas are the model's (loyly.regen.model);
   the rules are loyly.regen.domain."
  (:require [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.query-processor.interface :refer [defquery]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [cognitect.anomalies :as anom]
            [loyly.regen.model :as model]
            [loyly.regen.domain :as d]
            [loyly.regen.read-models])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Configuration handed in at start: the scoring tools (seam.md)
;; ---------------------------------------------------------------------------

(defonce scoring-tool-paths (atom []))

(defn project [ctx rm] (or (rmp/project ctx rm) {}))
(defn cycles [ctx] (:cycles (project ctx :regeneration-cycle/regeneration-cycles) {}))

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(defn- schema-keys [schema]
  (->> (rest schema)
       (remove map?)
       (map first)))

(defn ->model-event
  "An event of the model with exactly its schema's fields (absent optional ones left out)."
  [event-type body]
  (let [ks (schema-keys (model/event-schema event-type))]
    (es/->event {:type event-type
                 :body (into {} (keep (fn [k] (when-some [v (get body k)] [k v]))) ks)})))

(defn refusal-anomaly [{:keys [refusal paths goldens] :as r}]
  (cond-> {::anom/category ::anom/conflict
           ::anom/message (str "refused: " refusal
                               (when (seq paths) (str " " (pr-str paths)))
                               (when (seq goldens) (str " " (pr-str goldens))))
           :refusal/code refusal}
    paths (assoc :refusal/paths paths)
    goldens (assoc :refusal/goldens goldens)))

(defn- decide [ctx refusal-fn views events-fn]
  (let [cmd (:command ctx)]
    (if-let [r (refusal-fn views cmd)]
      (refusal-anomaly r)
      (let [events (events-fn views cmd (Instant/now))]
        {:command-result/events events
         :command/result {:events events}}))))

(defcommand :regeneration-cycle record-harness-self-check
  {:authorized? (constantly true)
         :grain.event-model/reads #{:regeneration-cycle/regeneration-cycles :regeneration-cycle/pinned-cuts
                                     :regeneration-cycle/instrument-goldens :regeneration-cycle/harness-self-checks}
          :grain.event-model/produces #{:regeneration-cycle/harness-self-check-passed
                                        :regeneration-cycle/harness-self-check-failed}}
  "RecordHarnessSelfCheck."
  [ctx]
  (decide ctx d/record-harness-self-check-refusal
          {:pinned-cuts (project ctx :regeneration-cycle/pinned-cuts)
           :goldens (project ctx :regeneration-cycle/instrument-goldens)
           :self-checks (project ctx :regeneration-cycle/harness-self-checks)
           :cycles (cycles ctx)}
          (fn [_ cmd now]
            (if (= "passed" (:outcome cmd))
              [(->model-event :regeneration-cycle/harness-self-check-passed
                              (assoc cmd :checked-at now))]
              [(->model-event :regeneration-cycle/harness-self-check-failed
                              (assoc cmd :checked-at now :detail (or (:detail cmd) "")))]))))

(defcommand :regeneration-cycle prove-clean-room
  {:authorized? (constantly true)
         :grain.event-model/reads #{:regeneration-cycle/regeneration-cycles}
          :grain.event-model/produces #{:regeneration-cycle/clean-room-proved}}
  "ProveCleanRoom."
  [ctx]
  (decide ctx d/prove-clean-room-refusal
          {:cycles (cycles ctx) :scoring-tools @scoring-tool-paths}
          (fn [_ cmd now]
            [(->model-event :regeneration-cycle/clean-room-proved (assoc cmd :proved-at now))])))

(defcommand :regeneration-cycle record-regenerated-candidate
  {:authorized? (constantly true)
         :grain.event-model/reads #{:regeneration-cycle/regeneration-cycles}
          :grain.event-model/produces #{:regeneration-cycle/candidate-regenerated}}
  "RecordRegeneratedCandidate."
  [ctx]
  (decide ctx d/record-regenerated-candidate-refusal
          {:cycles (cycles ctx)}
          (fn [_ cmd now]
            [(->model-event :regeneration-cycle/candidate-regenerated (assoc cmd :regenerated-at now))])))

(defcommand :regeneration-cycle install-candidate-in-gate-room
  {:authorized? (constantly true)
         :grain.event-model/reads #{:regeneration-cycle/gate-attempts :regeneration-cycle/regeneration-cycles}
          :grain.event-model/produces #{:regeneration-cycle/candidate-installed-in-gate-room}}
  "InstallCandidateInGateRoom."
  [ctx]
  (decide ctx d/install-candidate-refusal
          {:cycles (cycles ctx) :gate-attempts (project ctx :regeneration-cycle/gate-attempts)}
          (fn [_ cmd now]
            [(->model-event :regeneration-cycle/candidate-installed-in-gate-room
                            (assoc cmd :installed-at now))])))

(defcommand :regeneration-cycle record-gate-attempt
  {:authorized? (constantly true)
         :grain.event-model/reads #{:regeneration-cycle/gate-attempts :regeneration-cycle/harness-self-checks
                                     :regeneration-cycle/regeneration-cycles}
          :grain.event-model/produces #{:regeneration-cycle/gate-attempt-passed
                                        :regeneration-cycle/gate-attempt-failed}}
  "RecordGateAttempt."
  [ctx]
  (let [cs (cycles ctx)]
    (decide ctx d/record-gate-attempt-refusal
            {:cycles cs
             :gate-attempts (project ctx :regeneration-cycle/gate-attempts)
             :self-checks (project ctx :regeneration-cycle/harness-self-checks)
             ;; requires 7, `check.at_current_goldens`: the current goldens
             :goldens (project ctx :regeneration-cycle/instrument-goldens)}
            (fn [_ cmd now]
              (if (= "passed" (:outcome cmd))
                [(->model-event :regeneration-cycle/gate-attempt-passed
                                (assoc cmd :cut-id (get-in cs [(:cycle-id cmd) :cut-id]) :passed-at now))]
                [(->model-event :regeneration-cycle/gate-attempt-failed
                                (assoc cmd :failed-at now))])))))

(defcommand :regeneration-cycle record-gate-refusal
  {:authorized? (constantly true)
         :grain.event-model/reads #{:regeneration-cycle/gate-attempts :regeneration-cycle/regeneration-cycles}
          :grain.event-model/produces #{:regeneration-cycle/gate-attempt-refused}}
  "RecordGateRefusal."
  [ctx]
  (decide ctx d/record-gate-refusal-refusal
          {:cycles (cycles ctx) :gate-attempts (project ctx :regeneration-cycle/gate-attempts)}
          (fn [_ cmd now]
            [(->model-event :regeneration-cycle/gate-attempt-refused (assoc cmd :refused-at now))])))

(defcommand :regeneration-cycle pass-cycle
  {:authorized? (constantly true)
         :grain.event-model/reads #{:regeneration-cycle/regeneration-cycles}
          :grain.event-model/produces #{:regeneration-cycle/cycle-passed}}
  "PassCycle."
  [ctx]
  (let [cs (cycles ctx)]
    (decide ctx d/pass-cycle-refusal {:cycles cs}
            (fn [_ cmd now]
              [(->model-event :regeneration-cycle/cycle-passed
                              {:cycle-id (:cycle-id cmd)
                               :candidate-digest (get-in cs [(:cycle-id cmd) :candidate-digest])
                               :concluded-at now})]))))

(defcommand :regeneration-cycle fail-cycle
  {:authorized? (constantly true)
         :grain.event-model/reads #{:regeneration-cycle/regeneration-cycles}
          :grain.event-model/produces #{:regeneration-cycle/cycle-failed}}
  "FailCycle."
  [ctx]
  (let [cs (cycles ctx)]
    (decide ctx d/fail-cycle-refusal {:cycles cs}
            (fn [_ cmd now]
              [(->model-event :regeneration-cycle/cycle-failed
                              (assoc cmd
                                     :candidate-digest (get-in cs [(:cycle-id cmd) :candidate-digest])
                                     :concluded-at now))]))))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn cycle-progress-of [c gate-attempts]
  (assoc (d/cycle-row c)
         :attempts (mapv (fn [row] (into {} (remove (comp nil? val)) row))
                         (d/attempts-of gate-attempts (:cycle-id c)))))

(defquery :regeneration-cycle cycle-progress
  {:authorized? (constantly true) :grain.event-model/reads #{:regeneration-cycle/regeneration-cycles
                                          :regeneration-cycle/gate-attempts}}
  "One cycle's progress, or every cycle's in the order they opened."
  [ctx]
  (let [q (:query ctx)
        state (project ctx :regeneration-cycle/regeneration-cycles)
        gas (project ctx :regeneration-cycle/gate-attempts)
        cs (:cycles state {})]
    {:query/result
     (if (contains? q :cycle-id)
       {:cycle (when-let [c (get cs (:cycle-id q))] (cycle-progress-of c gas))}
       {:cycles (mapv #(cycle-progress-of (get cs %) gas) (:order state []))})}))

(defquery :regeneration-cycle cycles-to-conclude
  {:authorized? (constantly true) :grain.event-model/reads #{:regeneration-cycle/cycles-to-conclude}}
  "The conclude-cycle policy's to-do list: the running cycles due to conclude, in the order they became due."
  [ctx]
  {:query/result {:cycle-ids (d/due-cycle-ids (project ctx :regeneration-cycle/cycles-to-conclude))}})
