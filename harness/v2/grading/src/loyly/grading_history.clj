(ns loyly.grading-history
  "List the grading runs the grading history keeps: one line per run of the score workflow,
   oldest first: slice, cycle, start time, status, verdicts kept (pass/fail), model, prompt
   digest, the score tick (its trace id) and the delegated open-grader tick.

   Usage, from harness/v2/grading: clojure -M:jvm -m loyly.grading-history [STORE]
   STORE defaults to LOYLY_GRADING_STORE, else history.sqlite. The store is copied and the copy
   read, because opening a Grain store writes to it."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.grading :as grading]))

(defn runs [events]
  (let [score-sheets (set (keep #(when (and (= :sheet/sheet-created (:event/type %)) (= "score" (:name %)))
                                   (:sheet-id %))
                                events))
        by-tick (group-by :tick-id (filter :tick-id events))
        parent (fn [e] (some #(when (= :parent-tick (first %)) (second %)) (:event/tags e)))]
    (for [started events
          :when (and (= :sheet/tree-tick-started (:event/type started))
                     (score-sheets (:sheet-id started)))
          :let [tick (:tick-id started)
                evs (by-tick tick)
                value (fn [k] (some #(when (and (= :sheet/execution-value-written (:event/type %))
                                                (= k (:key %)))
                                       (:value %))
                                    (reverse evs)))
                done (last (filter #(= :sheet/tree-tick-completed (:event/type %)) evs))
                kept (try (json/read-str (or (value :kept) "")) (catch Exception _ nil))
                child (keep #(when (and (= :sheet/tree-tick-started (:event/type %)) (= tick (parent %)))
                               (:tick-id %))
                            events)]]
      {:slice (value :slice) :cycle (some-> (value :cycle) io/file .getName)
       :time (str (:event/timestamp started))
       :status (some-> done :root-status name)
       :pass (count (filter #(= "pass" (get % "verdict")) kept))
       :fail (count (filter #(= "fail" (get % "verdict")) kept))
       :kept (if kept (count kept) "-")
       :model (value :model) :prompt (some-> (value :prompt-digest) (subs 0 (min 19 (count (value :prompt-digest)))))
       :trace (str tick) :open-grader (str/join "," child)})))

(defn -main [& [store]]
  (let [src (io/file (or store (grading/store-path)))
        _ (when-not (.isFile src) (println "no grading history at" (str src)) (System/exit 1))
        copy (java.io.File/createTempFile "grading-history-" ".sqlite")
        _ (io/copy src copy)
        s (es/start {:conn {:type :sqlite :database-file (str copy)} :logger nil})
        events (into [] (es/read s {:tenant-id grading/tenant-id}))]
    (println (str/join "\t" ["slice" "cycle" "time" "status" "kept" "pass" "fail" "model" "prompt" "trace" "open-grader"]))
    (doseq [r (runs events)]
      (println (str/join "\t" (map #(get r %) [:slice :cycle :time :status :kept :pass :fail :model :prompt :trace :open-grader]))))
    (es/stop s)
    (.delete copy)
    (shutdown-agents)
    (System/exit 0)))
