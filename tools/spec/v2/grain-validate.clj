;; Merge .pi/spec/*/event-model/ for every context into ONE Grain model, refusing a block
;; defined twice, then run Grain's structural validator (strict) and its Allium composition
;; gate. Run from this folder: clojure -M -i grain-validate.clj
;;
;; Exit status: 1 when the structural verdict has any error or warning (the gate is
;; 0 errors, 0 warnings). The composition gate is REPORTED but does not fail the run until
;; round R2 writes the Allium and adds the :grain/allium links.
(require '[load :as l] '[ai.obney.grain.event-model-validator.interface :as v]
         '[ai.obney.grain.code-agent-tools.interface :as cat] '[clojure.java.io :as io]
         '[clojure.java.shell :as sh] '[clojure.string :as str])

(def root (str/trim (:out (sh/sh "git" "rev-parse" "--show-toplevel"))))
(def spec (str root "/.pi/spec"))

(let [dirs (->> (.listFiles (io/file spec))
                (map #(io/file % "event-model"))
                (filter #(.isDirectory %))
                (sort-by str))
      parts (map #(l/merge-model (str %)) dirs)
      dup (for [a parts b parts :when (not (identical? a b))
                k (keys (:homes a)) :when (contains? (:homes b) k)] k)
      _ (when (seq dup) (throw (ex-info (str "block defined in two contexts: " (first dup)) {})))
      model (apply merge-with merge (map :model parts))
      fmt (fn [r] (select-keys (:summary r) [:errors :warnings :fatal :findings]))
      r1 (v/validate-event-model model {:structural-only true :strict true})
      r2 (try (cat/validate-spec-composition model {:project-root root
                                                    :event-model-opts {:structural-only true :strict true}})
              (catch Exception e {:summary {:errors 1} :valid? false
                                  :findings [{:type :composition/crashed :severity :error :message (.getMessage e)}]}))]
  (println "contexts:" (mapv #(.getName (.getParentFile %)) dirs))
  (doseq [[area am] (sort-by key model)]
    (println " " area (into (sorted-map) (for [k [:commands :events :read-models :queries :todo-processors :screens :flows]]
                                           [k (count (k am))]))))
  (println "structural  " (fmt r1) "valid?" (:valid? r1))
  (doseq [f (:findings r1) :when (not= :info (:severity f))]
    (prn (select-keys f [:type :severity :area :block :flow :step :ref :from :to :message])))
  (println "composition " (fmt r2) "valid?" (:valid? r2) "(reported only: the Allium arrives in R2)")
  (let [cfs (remove #(= :info (:severity %)) (:findings r2))]
    (doseq [[t n] (sort-by key (frequencies (map :type cfs)))]
      (println "   " t n)))
  (shutdown-agents)
  (System/exit (if (and (zero? (get-in r1 [:summary :errors] 0))
                        (zero? (get-in r1 [:summary :warnings] 0)))
                 0 1)))
