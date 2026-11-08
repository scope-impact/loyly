(ns loyly.grader-lifecycle.domain
  "The InstrumentGolden aggregate as Grain building blocks, one per block of the
   event model and named as it: the commands write-instrument-golden and
   move-instrument-golden, the read model and the query instrument-goldens. The
   rules are those of allium/instrument-golden.allium; each refusal is one of its
   `requires`, checked in its order."
  (:require [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [cognitect.anomalies :as anom]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [loyly.grader-lifecycle.model :as model]))

;; ---------------------------------------------------------------------------
;; The contract ScenarioDigests: handed in, read at every command
;; ---------------------------------------------------------------------------

(defn scenario-digests
  "The current scenario digests: the JSON object in the file at `path`, read
   now. An unset path, an absent file or a file that is not a JSON object gives
   no digests."
  [path]
  (try
    (when (and path (.isFile (io/file path)))
      (let [v (json/read-str (slurp path))]
        (when (map? v) v)))
    (catch Exception _ nil)))

(defn digest-of-scenario
  "digest_of_scenario(scenario): the file's value for the key `scenario`,
   exactly; nil when it names none."
  [path scenario]
  (let [d (get (scenario-digests path) scenario)]
    (when (string? d) d)))

;; ---------------------------------------------------------------------------
;; The read model
;; ---------------------------------------------------------------------------

(defreadmodel :grader-lifecycle instrument-goldens
  {:events #{:grader-lifecycle/instrument-golden-written
             :grader-lifecycle/instrument-golden-moved}
   :version 1
   :schema (model/read-model-schema :grader-lifecycle/instrument-goldens)}
  "Each instrument golden: its path, current digest, scenario digest and the
   moves with their reasons, in the order written and moved."
  [state event]
  (case (:event/type event)
    :grader-lifecycle/instrument-golden-written
    (let [{:keys [golden-id scenario scenario-digest path digest]} event]
      (if (get-in state [:goldens golden-id])
        state
        (-> state
            (update :order (fnil conj []) golden-id)
            (assoc-in [:goldens golden-id]
                      {:golden-id golden-id
                       :scenario scenario
                       :path path
                       :digest digest
                       :scenario-digest scenario-digest
                       :moves []}))))

    :grader-lifecycle/instrument-golden-moved
    (let [{:keys [golden-id from-digest to-digest reason note scenario-digest]} event]
      (if (get-in state [:goldens golden-id])
        (update-in state [:goldens golden-id]
                   (fn [g]
                     (-> g
                         (assoc :digest to-digest :scenario-digest scenario-digest)
                         (update :moves conj {:from-digest from-digest
                                              :to-digest to-digest
                                              :reason reason
                                              :note note}))))
        state))
    state))

(defn goldens-state [context]
  (rmp/project context :grader-lifecycle/instrument-goldens))

(defn find-golden [state golden-id]
  (get-in state [:goldens golden-id]))

(defn row
  "A golden as the read model's row: exactly the fields of its schema."
  [g]
  (select-keys g [:golden-id :path :digest :scenario-digest :moves]))

;; ---------------------------------------------------------------------------
;; The rules, as pure decisions
;; ---------------------------------------------------------------------------

(defn decide-write
  "WriteInstrumentGolden: nil when allowed, else the refusal code."
  [state current-digest {:keys [golden-id scenario-digest]}]
  (cond
    (find-golden state golden-id) "golden-exists"
    (not= scenario-digest current-digest) "scenario-digest-mismatch"
    :else nil))

(defn decide-move
  "MoveInstrumentGolden: nil when allowed, else the refusal code. `digest-of`
   is digest_of_scenario."
  [state digest-of {:keys [golden-id digest reason additions-only note scenario-digest]}]
  (let [golden (find-golden state golden-id)]
    (cond
      (nil? golden) "no-such-golden"
      (= digest (:digest golden)) "same-digest"
      (and (= reason "spec-grew") (not (true? additions-only))) "spec-grew-not-additions-only"
      (= note "") "empty-note"
      (not= scenario-digest (digest-of (:scenario golden))) "stale-scenario-digest"
      :else nil)))

(def refusal-messages
  {"golden-exists" "the golden already exists"
   "scenario-digest-mismatch" "the scenario digest is not the digest of the scenario it is written from"
   "no-such-golden" "no such golden"
   "same-digest" "the same digest: nothing moved"
   "spec-grew-not-additions-only" "spec grew without an additions-only diff"
   "empty-note" "a move needs a note"
   "stale-scenario-digest" "the scenario digest is not the current digest of the golden's scenario"})

(defn refusal [code]
  {::anom/category ::anom/conflict
   ::anom/message (get refusal-messages code code)
   :refusal code})

(defn- now [] (java.util.Date.))

;; ---------------------------------------------------------------------------
;; The commands
;; ---------------------------------------------------------------------------

(defcommand :grader-lifecycle write-instrument-golden
  {:authorized? (constantly true)
   :grain.event-model/reads #{:grader-lifecycle/instrument-goldens}
   :grain.event-model/produces #{:grader-lifecycle/instrument-golden-written}}
  "Write the exact expected output of one instrument scenario, from that
   scenario, carrying the digest of that scenario."
  [context]
  (let [{:keys [golden-id scenario scenario-digest path digest] :as cmd} (:command context)
        state (goldens-state context)
        current (digest-of-scenario (:loyly/scenario-digests-path context) scenario)]
    (if-let [code (decide-write state current cmd)]
      (refusal code)
      {:command-result/events
       [(->event {:type :grader-lifecycle/instrument-golden-written
                  :body {:golden-id golden-id
                         :scenario scenario
                         :scenario-digest scenario-digest
                         :path path
                         :digest digest
                         :written-at (now)}})]})))

(defcommand :grader-lifecycle move-instrument-golden
  {:authorized? (constantly true)
   :grain.event-model/reads #{:grader-lifecycle/instrument-goldens}
   :grain.event-model/produces #{:grader-lifecycle/instrument-golden-moved}}
  "Move a golden to new content, with its one recorded reason and a note."
  [context]
  (let [{:keys [golden-id digest reason additions-only note scenario-digest] :as cmd} (:command context)
        state (goldens-state context)
        path (:loyly/scenario-digests-path context)]
    (if-let [code (decide-move state #(digest-of-scenario path %) cmd)]
      (refusal code)
      {:command-result/events
       [(->event {:type :grader-lifecycle/instrument-golden-moved
                  :body {:golden-id golden-id
                         :from-digest (:digest (find-golden state golden-id))
                         :to-digest digest
                         :reason reason
                         :additions-only additions-only
                         :note note
                         :scenario-digest scenario-digest
                         :moved-at (now)}})]})))
