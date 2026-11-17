(ns loyly.progress
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.orc-service.interface :as orc]
            [clojure.string :as str]))
(defn augment [ctx run]
  (let [events (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))
        preceding (take-while #(not (and (= :cycle-runner/run-started (:event/type %)) (= (:run-id run) (:run-id %)))) events)
        root (:tick-id (last (filter #(= :sheet/tree-tick-started (:event/type %)) preceding)))
        family (loop [ids #{root}]
          (let [more (into ids (keep #(when (and (= :sheet/tree-tick-started (:event/type %)) (ids (:parent-tick-id %))) (:tick-id %)) events))]
            (if (= more ids) ids (recur more))))
        own (filter #(family (:tick-id %)) events)
        completed (filter #(= :sheet/node-execution-completed (:event/type %)) own)
        node-name (fn [e] (or (:name (orc/get-node ctx (:sheet-id e) (:node-id e))) (str (:node-id e))))
        done (set (map (juxt :tick-id :node-id) completed))
        running (remove #(done ((juxt :tick-id :node-id) %)) (filter #(= :sheet/node-execution-started (:event/type %)) own))
        nodes (into (mapv #(hash-map :name (node-name %) :status (name (:status %))) completed)
                    (map #(hash-map :name (node-name %) :status "running") running))
        scores (mapv (fn [e] {:item-id (subs (:judge-name e) (count "checklist/")) :verdict (if (= 1.0 (:score e)) "pass" "fail") :reason (:feedback e)})
          (filter #(and (= :judge/score-emitted (:event/type %)) (str/starts-with? (:judge-name %) "checklist/")) own))
        finished (last (filter #(and (= root (:tick-id %)) (= :sheet/tree-tick-completed (:event/type %))) own))]
    (assoc run :tick {:tick-id (str root) :status (if finished (name (or (:status finished) (:result finished) (:root-status finished) :unknown)) "running") :nodes nodes} :scores scores)))
