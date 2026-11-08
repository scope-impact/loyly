(ns measurement.store-check
  "The gate's reading of the Grain store, played in the tests: every event is an
   event of the model, a consumed handoff or ORC's or Grain's own; each model
   event carries exactly its schema's fields; each event a command produced lies
   inside a run of its command's ORC workflow that completed with success; every
   tick completes once and is traced once; todo processors checkpoint."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [malli.core :as m]
            [malli.util :as mu]
            [measurement.model :as model]
            [measurement.read-models :as rm]))

(def own-namespaces #{"sheet" "grain" "rlm" "intervention"})

(defn- model-events []
  (into {}
        (for [[_ area-map] model/model
              [event-name block] (:events area-map)]
          [event-name (:schema block)])))

(defn- producer-workflow
  "Event type -> the workflow (flow) of the command that produces it."
  []
  (into {}
        (for [[_ area-map] model/model
              [command-name block] (:commands area-map)
              event-name (:produces block)]
          [event-name (str (namespace (model/flow-of-command command-name)) "/"
                           (name (model/flow-of-command command-name)))])))

(defn check-events
  "Problems found in `events` (in store order); empty when the store passes."
  [events]
  (let [schemas (model-events)
        producers (producer-workflow)
        sheet-names (into {} (keep #(when (= :sheet/sheet-created (:event/type %)) [(:sheet-id %) (:name %)])) events)
        completed (reduce (fn [acc e]
                            (if (= :sheet/tree-tick-completed (:event/type e))
                              (update acc (:tick-id e) (fnil conj []) (:root-status e))
                              acc))
                          {} events)
        traced (frequencies (keep #(when (= :sheet/execution-traced (:event/type %)) (:trace-id %)) events))
        started (into {} (keep #(when (= :sheet/tree-tick-started (:event/type %)) [(:tick-id %) (:sheet-id %)])) events)
        problems (atom [])
        problem! (fn [p] (swap! problems conj p))]
    (when (and (seq started)
               (not (some #(= :grain/todo-processor-checkpoint (:event/type %)) events)))
      (problem! {:problem :no-checkpoints}))
    ;; Ticks: one completion and one trace each.
    (doseq [[tick-id _] started]
      (when (not= 1 (count (get completed tick-id)))
        (problem! {:problem :tick-completions :tick tick-id :completions (get completed tick-id)}))
      (when (not= 1 (get traced tick-id 0))
        (problem! {:problem :tick-traces :tick tick-id :traces (get traced tick-id 0)})))
    ;; Event types and fields; command events inside a successful run.
    (loop [[e & more] events open {}]
      (when e
        (let [t (:event/type e)
              open (case t
                     :sheet/tree-tick-started (assoc open (:tick-id e) (get sheet-names (:sheet-id e)))
                     :sheet/tree-tick-completed (dissoc open (:tick-id e))
                     open)]
          (cond
            (contains? own-namespaces (namespace t)) nil

            (contains? schemas t)
            (let [body (rm/body e)
                  s (mu/closed-schema (m/schema (get schemas t)))]
              (when-not (m/validate s body)
                (problem! {:problem :fields :type t :body body}))
              (if-let [wf (producers t)]
                (when-not (some (fn [[tick-id wf-name]]
                                  (and (= wf wf-name) (= [:success] (get completed tick-id))))
                                open)
                  (problem! {:problem :outside-run :type t :workflow wf}))
                (when (seq open)
                  (problem! {:problem :handoff-inside-run :type t}))))

            :else (problem! {:problem :foreign-type :type t}))
          (recur more open))))
    @problems))

(defn read-events [store tenant-id]
  (into [] (remove #(= :grain/tx (:event/type %)))
        (es/read store {:tenant-id tenant-id})))

(defn check [{:keys [event-store tenant-id]}]
  (check-events (read-events event-store tenant-id)))
