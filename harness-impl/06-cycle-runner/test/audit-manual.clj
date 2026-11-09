(require '[runner.contexts :as c] '[runner.model :as model] '[runner.runtime] '[malli.core :as m]
         '[ai.obney.orc.orc-service.core.dsl :as dsl])
(let [events (c/foreign-events (slurp "work/manual-store-path"))
      started (filter #(= :sheet/tree-tick-started (:event/type %)) events)
      ended (filter #(= :sheet/tree-tick-completed (:event/type %)) events)
      traces (filter #(= :sheet/execution-traced (:event/type %)) events)
      expected {:cycle-runner/run-started "cycle-runner/run-to-gate" :cycle-runner/run-halted "cycle-runner/effect-not-performed-halts-run"}
      spans (for [[i e] (map-indexed vector events) :when (= :sheet/tree-tick-started (:event/type e))
                  :let [j (first (keep-indexed #(when (and (= :sheet/tree-tick-completed (:event/type %2)) (= (:tick-id e) (:tick-id %2))) %1) events))]]
              {:begin i :end j :sheet-id (:sheet-id e)})]
  (assert (= 3 (count started) (count ended) (count traces)))
  (assert (every? #(= :success (:root-status %)) ended))
  (assert (every? #(= 1 %) (vals (frequencies (map :tick-id ended)))))
  (assert (every? #(= 1 %) (vals (frequencies (map :trace-id traces)))))
  (assert (some #(= :grain/todo-processor-checkpoint (:event/type %)) events))
  (doseq [[i e] (map-indexed vector events) :when (= "cycle-runner" (namespace (:event/type e)))]
    (let [body (into {} (remove (fn [[k _]] (= "event" (namespace k))) e))]
      (assert (m/validate (get-in model/area [:events (:event/type e) :schema]) body))
      (assert (= (set (keys body)) (set (keys (model/body e)))))
      (assert (some #(and (< (:begin %) i (:end %)) (= (:sheet-id %) (dsl/sheet-id-for-name (expected (:event/type e))))) spans))))
  (println "Direct Grain audit passed:" (count events) "events; 3 successful ticks, 3 terminal events, 3 traces; checkpoints present."))
(shutdown-agents)
