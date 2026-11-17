(ns loyly.store-checks
  (:require [clojure.test :refer [is testing]] [loyly.model :as model] [loyly.domain :as d]
            [ai.obney.grain.event-store-v3.interface :as es] [malli.core :as m] [clojure.string :as str]))
(defn events [ctx] (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)})))
(defn check! [ctx]
  (let [events (events ctx) by-tick (group-by :tick-id events)
        names (into {} (keep #(when (= :sheet/node-name-set (:event/type %)) [(:node-id %) (:name %)])) events)
        ticks (filter #(= :sheet/tree-tick-started (:event/type %)) events)
        domain (filter #(get (model/blocks :events) (:event/type %)) events)]
    (testing "Every tick completes exactly once and has one trace"
      (doseq [tick ticks :let [own (get by-tick (:tick-id tick))]]
        (is (= 1 (count (filter #(= :sheet/tree-tick-completed (:event/type %)) own))))
        (is (= 1 (count (filter #(and (= :sheet/execution-traced (:event/type %)) (= (:tick-id tick) (:trace-id %))) events))))))
    (is (some #(= :grain/todo-processor-checkpoint (:event/type %)) events))
    (testing "Domain events match the copied model exactly"
      (doseq [e domain :let [schema (:schema (get (model/blocks :events) (:event/type e))) fields (set (map first (rest schema)))]]
        (is (m/validate schema (d/body e)) (str (:event/type e)))
        (is (every? fields (keys (d/body e))) (str (:event/type e)))))
    (testing "@item-06-059 Every effect is within its own successful ORC leaf"
      (doseq [[index e] (map-indexed vector events) :when (= :cycle-runner/effect-recorded (:event/type e))]
        (let [before (take index events) after (drop (inc index) events)
              start (last (filter #(and (= :sheet/node-execution-started (:event/type %))
                                       (= (:effect e) (first (str/split (get names (:node-id %) "") #":")))) before))
              completion (first (filter #(and (= :sheet/node-execution-completed (:event/type %)) (= (:node-id start) (:node-id %)) (= (:tick-id start) (:tick-id %))) after))
              tick-complete (first (filter #(and (= :sheet/tree-tick-completed (:event/type %)) (= (:tick-id start) (:tick-id %))) after))]
          (is start (:effect e)) (is (= :success (:status completion)) (:effect e)) (is (= :success (:root-status tick-complete)) (:effect e)))))))
(defn check-gate-delegation! [ctx]
  (testing "@item-06-061 each attempt is a child tick and repair belongs to the preceding attempt"
    (let [events (events ctx)
          names (into {} (keep #(when (= :sheet/node-name-set (:event/type %)) [(:node-id %) (:name %)])) events)
          ticks (into {} (keep #(when (= :sheet/tree-tick-started (:event/type %)) [(:tick-id %) %])) events)
          effects (for [[index e] (map-indexed vector events)
                        :when (and (= :cycle-runner/effect-recorded (:event/type e)) (pos? (:attempt e)) (#{"install" "self-check" "gate" "repair"} (:effect e)))
                        :let [start (last (filter #(and (= :sheet/node-execution-started (:event/type %))
                            (= (:effect e) (first (str/split (get names (:node-id %) "") #":")))) (take index events)))]]
                    (assoc e :tick-id (:tick-id start)))
          attempts (group-by :attempt (remove #(= "repair" (:effect %)) effects))
          first-tick (:tick-id (first (get attempts 1))) second-tick (:tick-id (first (get attempts 2)))]
      (is (not= first-tick second-tick))
      (is (= (:parent-tick-id (ticks first-tick)) (:parent-tick-id (ticks second-tick))))
      (is (:parent-tick-id (ticks first-tick)))
      (is (= (:sheet-id (ticks first-tick)) (:sheet-id (ticks second-tick))))
      (doseq [[_ rows] attempts] (is (= 1 (count (set (map :tick-id rows))))))
      (is (= first-tick (:tick-id (first (filter #(= "repair" (:effect %)) effects))))))))
