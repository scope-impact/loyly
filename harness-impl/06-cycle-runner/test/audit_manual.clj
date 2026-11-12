(require '[runner.model :as model] '[clojure.java.io :as io] '[clojure.string :as str]
         '[malli.core :as m] '[ai.obney.grain.event-store-v3.interface :as es]
         '[ai.obney.grain.event-store-sqlite-v3.interface])
(let [path (str/trim (slurp "target/manual-store-path.txt"))
      store (es/start {:conn {:type :sqlite :database-file path}})]
  (try
    (let [events (into [] (es/read store {:tenant-id #uuid "00000000-0000-0000-0000-000000000000"}))
          domain (filter #(= "cycle-runner" (namespace (:event/type %))) events)
          ticks (filter #(= :sheet/tree-tick-started (:event/type %)) events)
          indexed (map-indexed vector events)
          names (into {} (for [e events :when (= :sheet/sheet-created (:event/type e))] [(:sheet-id e) (:name e)]))]
      (assert (= [:cycle-runner/run-started :cycle-runner/run-halted] (mapv :event/type domain)))
      (doseq [e domain]
        (let [p (model/payload e) schema (get-in model/model [:events (:event/type e) :schema])
              fields (set (map first (filter vector? (rest schema))))]
          (assert (= fields (set (keys p))))
          (assert (m/validate schema p))))
      (doseq [tick ticks]
        (let [ends (filter #(and (= :sheet/tree-tick-completed (:event/type %)) (= (:tick-id tick) (:tick-id %))) events)
              traces (filter #(and (= :sheet/execution-traced (:event/type %)) (= (:tick-id tick) (:trace-id %))) events)]
          (assert (= 1 (count ends))) (assert (= :success (:root-status (first ends)))) (assert (= 1 (count traces)))))
      (doseq [[i e] indexed :when (= "cycle-runner" (namespace (:event/type e)))]
        (let [flow (if (= :cycle-runner/run-halted (:event/type e)) "cycle-runner/effect-not-performed-halts-run" "cycle-runner/run-to-gate")]
          (assert (some (fn [[j tick]]
                          (and (= :sheet/tree-tick-started (:event/type tick)) (< j i) (= flow (names (:sheet-id tick)))
                               (some (fn [[k end]] (and (> k i) (= :sheet/tree-tick-completed (:event/type end)) (= (:tick-id tick) (:tick-id end)) (= :success (:root-status end)))) indexed))) indexed))))
      (assert (some #(= :grain/todo-processor-checkpoint (:event/type %)) events))
      (println "CONFIRMED Grain store:" (count domain) "exact model events;" (count ticks) "successful ticks, each completed once and traced once; checkpoints present."))
    (finally (es/stop store))))
