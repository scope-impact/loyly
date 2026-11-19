#!/usr/bin/env bb
;; Public domain event types and relative times from a cycle's final Grain stores.
(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str])
(import '[java.time Instant Duration])

(def root (str (-> (java.io.File. *file*) .getCanonicalFile .getParentFile .getParentFile .getParentFile .getParentFile)))
(def cycle (or (first *command-line-args*) "25"))
(assert (re-matches #"[0-9]+" cycle))
(def work (str root "/.pi/harness/.state/grading/06-c" cycle "/work"))
(def output (str root "/tools/spec/replay/src/replay/cycle-" cycle "-grain-events.json"))
(def scores (json/parse-string (slurp (str root "/harness/v2/cycles/06/" cycle "/scores.json")) true))
(def query "select time,type from events where type not like ':grain/%' and type not like ':sheet/%' order by time,id")
(defn read-case [id]
  (let [suffix (if (and (= cycle "26") (#{"06-061" "06-070"} id)) "-retry" "")
        file (str work "/" id suffix "/events.sqlite")
        _ (assert (.isFile (java.io.File. file)) (str "missing canonical store " id))
        r (p/sh {:out :string :err :string} "sqlite3" "-separator" "\u001f" "-newline" "\u001e" file query)
        _ (when-not (zero? (:exit r)) (throw (ex-info (:err r) {:case id})))
        rows (for [line (str/split (:out r) #"\u001e") :when (not (str/blank? line))]
               (let [[time type] (str/split line #"\u001f" 2)]
                 [(Instant/parse time) type]))
        base (ffirst rows)]
    (vec (for [[at type] rows]
           (do (assert (re-matches #":[a-z][a-z0-9-]*/[a-z][a-z0-9-]*" type) type)
               [(quot (.toNanos (Duration/between base at)) 1000000) (subs type 1)])))))
(let [cases (into (sorted-map) (for [id (sort (map :id (:items scores)))] [id (read-case id)]))]
  (assert (= (count (:items scores)) (count cases)))
  (when (= cycle "25") (assert (= 1910 (reduce + (map count (vals cases))))))
  (spit output (str (json/generate-string {:version 1 :source (str "cycle-" cycle " final grader Grain stores") :cases cases}) "\n"))
  (println output ":" (count cases) "stores," (reduce + (map count (vals cases))) "domain events"))
