;; Merge .pi/spec/*/event-model/ for every context into ONE Grain model, refusing a block
;; defined twice, then run Grain's structural validator (strict) and its Allium composition
;; gate. Run from this folder: clojure -M -i grain-validate.clj
;;
;; Exit status: 1 when the structural verdict or the composition verdict has any error or
;; warning (the gate is 0 errors, 0 warnings for both). Since R2 every command links an
;; Allium rule and every screen an Allium surface, so the composition gate is binding.
;; Info findings are listed, not counted.
;;
;; Since spec round 18 (ADR 0001) the six contexts are one candidate on one store, so the one
;; merged model is the only model a candidate registers: no context is checked alone.
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
  (doseq [f (:findings r1)]
    (prn (select-keys f [:type :severity :area :block :flow :step :ref :from :to :message])))
  (println "composition " (fmt r2) "valid?" (:valid? r2))
  (doseq [f (:findings r2) :when (not (some #{f} (:findings r1)))]
    (prn (select-keys f [:type :severity :area :kind :block :spec :declaration/kind :declaration/name :message])))
  (let [clean? (fn [r] (and (:valid? r) (zero? (get-in r [:summary :errors] 0))
                            (zero? (get-in r [:summary :warnings] 0))))]
    (shutdown-agents)
    (System/exit (if (and (clean? r1) (clean? r2)) 0 1))))
