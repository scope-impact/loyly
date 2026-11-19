#!/usr/bin/env bb
;; Public, chronological ORC grading facts from a cycle's final Grain stores.
;; Only event time, workflow/node name and transition leave this extractor.
(require '[replay.fressian :as fr]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[cheshire.core :as json])
(import '[java.time Instant Duration])

(def root (str (-> (java.io.File. *file*) .getCanonicalFile .getParentFile .getParentFile .getParentFile .getParentFile)))
(def cycle (or (first *command-line-args*) "25"))
(assert (re-matches #"[0-9]+" cycle))
(def work (str root "/.pi/harness/.state/grading/06-c" cycle "/work"))
(def output (str root "/tools/spec/replay/src/replay/cycle-" cycle "-orc-events.json"))
(def scores (json/parse-string (slurp (str root "/harness/v2/cycles/06/" cycle "/scores.json")) true))
(def wanted #{"grader" "grading-vm" "clear-evidence" "drive-seam" "judge" "file-verdicts" "file-score"})
(def types [":sheet/sheet-created" ":sheet/node-name-set" ":sheet/tree-tick-started"
            ":sheet/tree-tick-completed" ":sheet/node-execution-started"
            ":sheet/node-execution-completed" ":judge/score-emitted"])
(def quoted (fn [s] (str "'" s "'")))
(def sql (str "select time,type,hex(data) from events where type in ("
              (str/join "," (map quoted types)) ") order by time,id"))
(defn rows [file]
  (let [r (p/sh {:out :string :err :string} "sqlite3" "-separator" "\u001f" "-newline" "\u001e" file sql)]
    (when-not (zero? (:exit r)) (throw (ex-info (:err r) {:file file})))
    (for [line (str/split (:out r) #"\u001e") :when (not (str/blank? line))
          :let [[time type hex] (str/split line #"\u001f" 3)]]
      {:at (Instant/parse time) :type type :data (fr/decode hex)})))
(defn first-domain-ms [file]
  (let [q "select time from events where type not like ':grain/%' and type not like ':sheet/%' order by time,id limit 1"
        r (p/sh {:out :string :err :string} "sqlite3" file q)]
    (when-not (zero? (:exit r)) (throw (ex-info (:err r) {:file file})))
    (when-let [time (not-empty (str/trim (:out r)))] (Instant/parse time))))
(defn extract [file]
  (let [rs (vec (rows file))
        sheets (into {} (for [{:keys [type data]} rs :when (= type ":sheet/sheet-created")]
                          [(:sheet-id data) (:name data)]))
        nodes (into {} (for [{:keys [type data]} rs :when (= type ":sheet/node-name-set")]
                         [[(:sheet-id data) (:node-id data)] (:name data)]))
        origin (or (first-domain-ms file) (:at (first rs)))
        result (for [{:keys [at type data]} rs
                     :let [sheet (get sheets (:sheet-id data))
                           node (get nodes [(:sheet-id data) (:node-id data)])
                           entry (cond
                                   (and (#{":sheet/tree-tick-started" ":sheet/tree-tick-completed"} type)
                                        (= sheet "cycle-runner/grade.v1"))
                                   [(if (str/ends-with? type "started") "start" "done") "grade.v1"]
                                   (and (#{":sheet/node-execution-started" ":sheet/node-execution-completed"} type)
                                        (contains? wanted node)
                                        (or (= sheet "cycle-runner/grade.v1")
                                            (= sheet "cycle-runner/cycle.v1")))
                                   [(if (str/ends-with? type "started") "start" "done") node]
                                   (= type ":judge/score-emitted") ["score" "judge"])]
                     :when entry]
                 [(quot (.toNanos (Duration/between origin at)) 1000000) (second entry) (first entry)])]
    (vec result)))
(let [ids (set (map :id (:items scores)))
      cases (into (sorted-map)
                  (for [id (sort ids)
                        :let [suffix (if (and (= cycle "26") (#{"06-061" "06-070"} id)) "-retry" "")
                              file (str work "/" id suffix "/events.sqlite")]]
                    (do (assert (.isFile (java.io.File. file)) (str "missing " id))
                        [id (extract file)])))]
  (spit output (str (json/generate-string {:version 1 :source (str "cycle-" cycle " final grader Grain stores; selected ORC grading transitions") :cases cases}) "\n"))
  (println output ":" (count cases) "stores," (reduce + (map count (vals cases))) "ORC grading transitions"))
