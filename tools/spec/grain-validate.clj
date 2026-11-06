;; Load every Grain Event Model of .pi/model/event-model (the `defeventmodel` forms register
;; themselves), then run Grain's structural validator ({:structural-only true :strict true})
;; and its Allium composition gate over the registered model.
;; Run from the repo root (tools/spec/gates.sh does):
;;   clojure -Sdeps '{:paths [".pi/model/event-model"] :deps {g/cat {:local/root "grain/projects/grain-code-agent-tools"}}}' -M tools/spec/grain-validate.clj
;; With --dump <file> it only writes the registered model as EDN (tools/spec/views/build.sh).
(require '[ai.obney.grain.event-model.interface :as em]
         '[ai.obney.grain.code-agent-tools.interface :as cat]
         '[clojure.java.io :as io] '[clojure.string :as str])

(def root (.getCanonicalPath (io/file ".")))
(def em-dir (io/file root ".pi/model/event-model"))

;; every namespace under event-model/, from its path (loyly/pi_harness/event_model.clj -> loyly.pi-harness.event-model)
(def nss
  (->> (file-seq em-dir)
       (filter #(str/ends-with? (.getName %) ".clj"))
       (map #(-> (str (.relativize (.toPath em-dir) (.toPath %)))
                 (str/replace #"\.clj$" "") (str/replace "/" ".") (str/replace "_" "-") symbol))
       sort))
(doseq [n nss] (require n))
(def model (em/registered-model))
;; the areas these files declare, and where: Grain also registers its own (:grain, :grain.control)
(def homes
  (into (sorted-map)
        (for [f (file-seq em-dir) :when (str/ends-with? (.getName f) ".clj")
              [_ a] (re-seq #"\(defeventmodel\s+:([\w.-]+)" (slurp f))]
          [(keyword a) (str (.relativize (.toPath (io/file root)) (.toPath f)))])))

(let [[flag out] *command-line-args*]
  (if (= flag "--dump")
    (do (binding [*print-namespace-maps* false]
          (spit out (pr-str {:model (select-keys model (keys homes)) :files homes})))
        (println "dumped areas" (vec (keys homes)) "to" out))
    (let [fmt (fn [r] (select-keys (:summary r) [:errors :warnings :fatal :findings]))
          r1 (cat/validate-event-model model {:structural-only true :strict true})
          r2 (cat/validate-spec-composition model {:project-root root :event-model-opts {:structural-only true :strict true}})]
      (println "namespaces:" (mapv str nss))
      (doseq [[area am] model]
        (println " " area (into (sorted-map) (for [k [:commands :events :read-models :queries :todo-processors :periodic-tasks :screens :flows] :when (seq (k am))] [k (count (k am))]))))
      (println "structural  " (fmt r1) "valid?" (:valid? r1))
      (println "composition " (fmt r2) "valid?" (:valid? r2))
      (doseq [f (concat (:findings r1) (:findings r2)) :when (not= :info (:severity f))]
        (prn (select-keys f [:type :severity :area :block :flow :from :to :declaration/name :message]))))))
(shutdown-agents)
