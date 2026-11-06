;; Merge .pi/spec/*/event-model/ for every context into ONE Grain model, refusing a block
;; defined twice, then run Grain's structural validator (strict) and its Allium composition
;; gate. Run from this folder: clojure -M -i grain-validate.clj
;;
;; Exit status: 1 when the structural verdict or the composition verdict has any error or
;; warning (the gate is 0 errors, 0 warnings for both). Since R2 every command links an
;; Allium rule and every screen an Allium surface, so the composition gate is binding.
;; Info findings are listed, not counted.
;;
;; Then each accepted context (a .pi/plan/NN-<context> link) alone, as its candidate registers it
;; (decisions.md, "First light" 3 and "Slice 02"): its own fragments plus only the handoff events
;; it consumes, each taken from its producer's event-model/handoffs.edn. The same verdicts, the
;; same gate: 0 errors, 0 warnings.
(require '[load :as l] '[ai.obney.grain.event-model-validator.interface :as v]
         '[ai.obney.grain.code-agent-tools.interface :as cat] '[clojure.java.io :as io]
         '[clojure.java.shell :as sh] '[clojure.string :as str])

(def root (str/trim (:out (sh/sh "git" "rev-parse" "--show-toplevel"))))
(def spec (str root "/.pi/spec"))

(defn consumed-handoffs
  "The handoff events MODEL of context CTX names from other contexts, from each producer's
  handoffs.edn: {area {:events {type block}}}."
  [ctx model]
  (let [refs (set (filter #(and (keyword? %) (namespace %) (not= (namespace %) ctx))
                          (tree-seq coll? seq model)))]
    (apply merge-with (partial merge-with merge)
           (for [k refs
                 :let [f (io/file spec (namespace k) "event-model" "handoffs.edn")]
                 :when (.isFile f)
                 :let [area (keyword (namespace k))
                       block (get-in (clojure.edn/read-string (slurp f)) [area :events k])]
                 :when block]
             {area {:events {k block}}}))))

(defn accepted-contexts []
  (sort (for [l (.listFiles (io/file root ".pi/plan"))
              :when (java.nio.file.Files/isSymbolicLink (.toPath l))]
          (.getName (.getCanonicalFile l)))))

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
  (doseq [f (:findings r1)]
    (prn (select-keys f [:type :severity :area :block :flow :step :ref :from :to :message])))
  (println "composition " (fmt r2) "valid?" (:valid? r2))
  (doseq [f (:findings r2) :when (not (some #{f} (:findings r1)))]
    (prn (select-keys f [:type :severity :area :kind :block :spec :declaration/kind :declaration/name :message])))
  (let [clean? (fn [r] (and (:valid? r) (zero? (get-in r [:summary :errors] 0))
                            (zero? (get-in r [:summary :warnings] 0))))
        alone (for [ctx (accepted-contexts)
                    :let [own (:model (l/merge-model (str spec "/" ctx "/event-model")))
                          handoffs (consumed-handoffs ctx own)
                          m (merge-with (partial merge-with merge) own handoffs)
                          r (v/validate-event-model m {:structural-only true :strict true})]]
                (do (println "structural alone" ctx "with" (vec (sort (mapcat (comp keys :events) (vals handoffs))))
                             (fmt r) "valid?" (:valid? r))
                    (doseq [f (:findings r) :when (#{:error :warning} (:severity f))]
                      (prn (select-keys f [:type :severity :area :block :flow :step :ref :message])))
                    (clean? r)))
        alone-ok (every? true? (doall alone))]
    (shutdown-agents)
    (System/exit (if (and (clean? r1) (clean? r2) alone-ok) 0 1))))
