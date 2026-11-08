(ns loyly.regen.store-test
  "What the gate reads in the Grain store directly: only events of the model, the
   consumed handoffs, ORC's and Grain's; each with exactly its schema's fields; each
   event a command produced written inside a run of a workflow named after a flow
   holding that command, completed with success; each tick completed once; the
   processors checkpointed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [loyly.regen.support :as s :refer [recorded!]]
            [loyly.regen.model :as model]
            [loyly.regen.system :as system]
            [loyly.regen.domain :as d]
            [ai.obney.grain.event-model-validator.interface :as validator])
  (:import [java.time Instant Duration]))

(use-fixtures :each (fn [f] (s/reset-goldens!) (s/with-fresh-product {:scoring-tools []} f)))

(def model-event-types (set (keys (model/all-of :events))))

(defn- schema-keys [schema]
  (let [entries (remove map? (rest schema))]
    {:all (set (map first entries))
     :required (set (map first (remove #(and (map? (second %)) (:optional (second %))) entries)))}))

(defn- producing-flows
  "The flows of the model that hold a command producing `event-type`."
  [event-type]
  (let [commands (for [[c b] (model/all-of :commands) :when (contains? (:produces b) event-type)] c)
        flows (get-in (model/model) [:regeneration-cycle :flows])]
    (set (for [[fk f] flows
               c commands
               :when (some #(= [:command c] (:to %)) (:steps f))]
           (subs (str fk) 1)))))

(defn- busy-cycle!
  "Drives a passing cycle, a failed one (by the policy), refusals and the clock."
  []
  (s/gate-passed!)
  (recorded! (s/file-measurement! "C-7" "D-1"))
  (recorded! (s/report-scoreboard! "C-7"))
  (recorded! (s/self-check {"cycle-id" "C-8" "outcome" "failed" "failing-check" "golden differs"}))
  (recorded! (s/self-check {"cycle-id" "C-9"}))
  (recorded! (s/prove-clean-room {"cycle-id" "C-9"}))
  (recorded! (s/regenerate {"cycle-id" "C-9"}))
  (recorded! (s/install {"cycle-id" "C-9"}))
  (recorded! (s/gate-refusal {"cycle-id" "C-9"}))
  (recorded! (s/gate-refusal {"cycle-id" "C-9"}))
  (recorded! (s/gate-refusal {"cycle-id" "C-9"}))
  (recorded! (s/self-check {"cycle-id" "C-10"}))
  (system/clock-tick! (.plus (Instant/now) (Duration/ofHours 2)))
  (s/prove-clean-room {"cycle-id" "C-10" "root-commits" 2})
  (Thread/sleep 300))

(deftest store-holds-only-known-events-with-exactly-their-fields
  (busy-cycle!)
  (let [evs (s/store-events)]
    (is (some #(contains? model-event-types (:event/type %)) evs))
    (doseq [e evs
            :let [t (:event/type e)]]
      (is (or (contains? model-event-types t)
              (#{"sheet" "grain"} (namespace t)))
          (str "unexpected event type " t))
      (when (contains? model-event-types t)
        (let [schema (model/event-schema t)
              {:keys [all required]} (schema-keys schema)
              fields (set (remove #(= "event" (namespace %)) (keys e)))]
          (is (every? all fields) (str t " has fields outside its schema: " (remove all fields)))
          (is (every? fields required) (str t " lacks " (remove fields required)))
          (is (m/validate schema (into {} (remove (fn [[k _]] (= "event" (namespace k)))) e))
              (str t " does not match its schema")))))))

(deftest every-command-event-is-written-inside-a-successful-run-of-its-flow
  (busy-cycle!)
  (let [evs (s/store-events)
        sheet-names (into {} (for [e evs :when (= :sheet/sheet-created (:event/type e))]
                               [(:sheet-id e) (:name e)]))
        completed (into {} (for [e evs :when (= :sheet/tree-tick-completed (:event/type e))]
                             [(:tick-id e) (:root-status e)]))
        ;; walk the store in order, tracking the ticks open at each position
        checked (atom 0)]
    (loop [[e & more] evs open {}]
      (when e
        (let [t (:event/type e)]
          (cond
            (= t :sheet/tree-tick-started)
            (recur more (assoc open (:tick-id e) (get sheet-names (:sheet-id e))))
            (= t :sheet/tree-tick-completed)
            (recur more (dissoc open (:tick-id e)))
            (and (= "regeneration-cycle" (namespace t)))
            (let [allowed (producing-flows t)
                  runs (filter (fn [[tick wf]] (and (contains? allowed wf) (= :success (completed tick)))) open)]
              (swap! checked inc)
              (is (seq runs) (str t " was not written inside a successful run of " allowed
                                  "; open runs: " (vals open)))
              (recur more open))
            :else (recur more open)))))
    (is (< 5 @checked))
    (testing "the policy and the clock conclude in their own flows"
      (let [failed (filter #(= :regeneration-cycle/cycle-failed (:event/type %)) evs)]
        (is (= #{"harness-self-check-failed" "infrastructure" "budget"} (set (map :reason failed))))))))

(deftest each-tick-completes-exactly-once-and-processors-checkpoint
  (busy-cycle!)
  (let [evs (s/store-events)
        by-type (group-by :event/type evs)
        started (map :tick-id (by-type :sheet/tree-tick-started))]
    (is (seq started))
    (is (= (count started) (count (set started))))
    (doseq [tick started]
      (is (= 1 (count (filter #(= tick (:tick-id %)) (by-type :sheet/tree-tick-completed)))))
      (is (= 1 (count (filter #(= tick (:trace-id %)) (by-type :sheet/execution-traced))))))
    (let [processors (set (map :processor/name (by-type :grain/todo-processor-checkpoint)))]
      (is (contains? processors :regeneration-cycle/conclude-cycle))
      (is (some #(= "sheet" (namespace %)) processors)))))

(deftest queries-and-handoffs-run-no-workflow
  (s/opened!)
  (Thread/sleep 200)
  (let [ticks #(count (filter (comp #{:sheet/tree-tick-started} :event/type) (s/store-events)))
        n (ticks)]
    (s/progress "C-7")
    (s/to-conclude)
    (s/write-golden! "other" "g-9")
    (Thread/sleep 200)
    (is (= n (ticks)))))

(deftest boot-guard-holds-the-running-blocks-to-the-model
  (testing "the running blocks agree with the model"
    (is (:valid? (validator/verify-or-throw!))))
  (testing "a model apart from the blocks is refused"
    (let [apart (update-in (model/model) [:regeneration-cycle :commands :regeneration-cycle/pass-cycle :produces]
                           conj :regeneration-cycle/cycle-failed)]
      (is (thrown? clojure.lang.ExceptionInfo (validator/verify-or-throw! {:model apart}))))
    (let [apart (update-in (model/model) [:regeneration-cycle :read-models :regeneration-cycle/gate-attempts :consumes]
                           disj :regeneration-cycle/gate-attempt-refused)]
      (is (thrown? clojure.lang.ExceptionInfo (validator/verify-or-throw! {:model apart}))))))

(deftest stage-clock-fails-a-stage-that-outlived-its-timeout
  (recorded! (do (s/pin-cut! "K-3")
                 (s/self-check {"budget" (assoc (s/budget 3) "stage-timeouts-seconds"
                                                {"fence" 60 "regenerate" 60 "gate" 60 "score" 60})})))
  (system/clock-tick! (Instant/now))
  (is (= "running" (get (s/progress "C-7") "status")) "not before its timeout")
  (system/clock-tick! (.plus (Instant/now) (Duration/ofSeconds 61)))
  (let [p (s/progress "C-7")]
    (is (= ["failed" "fence"] [(p "status") (p "stage")]))
    (is (= {"stage" "fence" "reason" "budget" "detail" "stage timeout"} (p "failure")))))

(deftest goldens-digest-as-the-seam-defines-it
  (is (= "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" (d/goldens-digest {})))
  (is (= (str "sha256:" (s/sha256 "checklist\tg-1\nscoreboard-01\tg-2\n"))
         (d/goldens-digest {"scoreboard-01" {:digest "g-2"} "checklist" {:digest "g-1"}}))))
