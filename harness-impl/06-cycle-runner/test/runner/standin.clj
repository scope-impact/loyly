(ns runner.standin
  "Suite-only scripted context peers. They are not dependencies of runner.main."
  (:gen-class)
  (:require [runner.util :as u] [runner.model :as model] [runner.repository :as repo]
            [clojure.java.io :as io] [clojure.string :as str] [clojure.walk :as walk]
            [malli.core :as m] [ai.obney.grain.schema-util.interface :as schema]
            [ai.obney.grain.event-store-v3.interface :as es] [ai.obney.grain.event-store-sqlite-v3.interface]))
(def tenant #uuid "00000000-0000-0000-0000-000000000000")
(def ^:dynamic state (atom {}))
(def ^:dynamic *environment* (into {} (System/getenv)))
(defn refusal [code] (throw (ex-info code {:code -32000 :data {:refusal code}})))
(defn failure! [stage reason detail] (swap! state update :cycle merge {:status "failed" :stage stage :failure {:stage stage :reason reason :detail detail}}))
(defn event [type body contracts]
  (let [schema (get-in contracts [:events type :schema])
        fields (mapv first (filter vector? (rest schema)))
        body (select-keys body fields)]
    (when-not (m/validate schema body) (throw (ex-info (str "Bad stand-in event " type) {:schema schema :body body :explain (m/explain schema body)})))
    (assoc body :type (subs (str type) 1))))
(defn command [context method p contract]
  (let [e (fn [name body] [(event (keyword context name) body contract)])
        cycle (:cycle-id p) at (u/now)]
    (case method
      :specification/stamp-slice
      (let [events (mapv (fn [i s] (event :specification/scenario-stamped (merge s {:slice (:slice p) :source (:source p) :item-id (format "%s-%03d" (:slice p) (inc i)) :stamped-at (java.util.Date.)}) contract)) (range) (:unstamped p))
            lines (vec (str/split (slurp (:source p)) #"\n" -1))
            by-line (into {} (map (juxt :line :item-id) events))]
        (spit (:source p) (str/join "\n" (mapcat (fn [i line] (if-let [id (by-line (inc i))] [(str "  @item-" id) line] [line])) (range) lines)))
        events)
      :cycle-preparation/pin-source-cut (e "source-cut-pinned" (assoc p :pinned-at (java.util.Date.)))
      :measurement/derive-checklist
      (do (when (empty? (:items p)) (refusal "empty-checklist"))
          (swap! state assoc :checklist p)
          (e "checklist-derived" (assoc p :checklist-digest (u/sha (pr-str (:items p))) :total (count (:items p)) :derived-at at)))
      :regeneration-cycle/record-harness-self-check
      (do
        (when (= 0 (:attempt p)) (swap! state assoc :cycle {:cycle-id cycle :cut-id (:cut-id p) :status "running" :stage "fence" :budget (:budget p) :attempts []}))
        (when (= "failed" (:outcome p)) (failure! (if (zero? (:attempt p)) "fence" "gate") "harness-self-check-failed" (:failing-check p)))
        (e (if (= "passed" (:outcome p)) "harness-self-check-passed" "harness-self-check-failed") (assoc p :checked-at at)))
      :regeneration-cycle/prove-clean-room
      (do (when (seq (:unmatched-paths p)) (refusal "fenced-path-matched-nothing"))
          (swap! state assoc-in [:cycle :stage] "regenerate")
          (e "clean-room-proved" (assoc p :proved-at at)))
      :regeneration-cycle/record-regenerated-candidate
      (do (swap! state assoc-in [:cycle :stage] "gate")
          (e "candidate-regenerated" (assoc p :regenerated-at at)))
      :regeneration-cycle/install-candidate-in-gate-room (e "candidate-installed-in-gate-room" (assoc p :installed-at at))
      :regeneration-cycle/fail-cycle (do (failure! (:stage p) (:reason p) (:detail p)) (e "cycle-failed" (assoc p :concluded-at at)))
      :regeneration-cycle/record-gate-attempt
      (do
        ;; The refusal guards are checked in the seam's stated order.
        (when (or (and (= "passed" (:outcome p)) (:reason p)) (and (= "failed" (:outcome p)) (nil? (:reason p)))) (refusal "reason-does-not-fit-outcome"))
        (when-not (:identity-probe-present p) (refusal "identity-probe-missing"))
        (when (:seam-answered-before-boot p) (refusal "seam-answered-before-boot"))
        (if (= "passed" (:outcome p))
          (do (swap! state assoc-in [:cycle :stage] "score")
              (e "gate-attempt-passed" (assoc p :cut-id (get-in @state [:cycle :cut-id]) :passed-at at)))
          (do (when (>= (:attempt p) (get-in @state [:cycle :budget :gate-attempt-limit])) (failure! "gate" "gate-attempts-exhausted" "Gate attempt budget exhausted"))
              (e "gate-attempt-failed" (assoc p :failed-at at)))))
      :regeneration-cycle/record-gate-refusal
      (do (swap! state update :refusals (fnil inc 0))
          (when (>= (:refusals @state) 3) (failure! "gate" "infrastructure" (:detail p)))
          (e "gate-attempt-refused" (assoc p :refused-at at)))
      :measurement/file-item-verdicts
      (do (when (some #(not (contains? (set (map :item-id (get-in @state [:checklist :items]))) (:item-id %))) (:verdicts p)) (refusal "unknown-item"))
          (when (empty? (:verdicts p)) (refusal "zero-verdicts"))
          (swap! state assoc :verdicts (:verdicts p))
          (e "item-verdicts-filed" (assoc p :verdicts (mapv #(merge {:evidence ""} %) (:verdicts p)) :filed-at at)))
      :measurement/file-score
      (let [cl (:checklist @state) filed (into {} (map (juxt :item-id identity) (:verdicts @state)))
            items (mapv #(merge {:item-id (:item-id %) :verdict "fail" :reason "unreached" :evidence ""} (when-let [v (filed (:item-id %))] (merge {:reason nil} v))) (:items cl))
            items (mapv #(if (nil? (:reason %)) (dissoc % :reason) %) items)
            passes (count (filter #(= "pass" (:verdict %)) items))
            base (merge cl {:cycle-id cycle :candidate-digest (:candidate-digest (:gated @state)) :checklist-digest (u/sha (pr-str (:items cl))) :grader-digests (:approved-grader-digests p) :total (count items) :passes passes :unreached (count (filter #(= "unreached" (:reason %)) items)) :items items :superseded [] :filed-at at})]
        (e "measurement-filed" base))
      :measurement/fail-score-stage (e "score-stage-failed" (assoc p :failed-at at))
      [])))
(defn instant-params [schema p]
  (reduce (fn [p [field & xs]]
            (if (= 'inst? (last xs))
              (let [v (get p field)]
                (when-not (string? v) (throw (ex-info "Instant must travel as ISO-8601 text" {:code -32602})))
                (assoc p field (java.time.Instant/parse v))) p)) p (filter vector? (rest schema))))
(defn -main [context]
  (let [script (u/decode (slurp (*environment* "RUNNER_TEST_SCRIPT")))
        own-contract (get (model/load-model context) (keyword context))
        consumed (set (mapcat :consumes (vals (:read-models own-contract))))
        external-events (into {} (for [c ["specification" "cycle-preparation" "grader-lifecycle" "regeneration-cycle" "measurement"]
                                      [k d] (:events (get (model/load-model c) (keyword c))) :when (consumed k)] [k d]))
        contract (update own-contract :events merge external-events)
        store (es/start {:conn {:type :sqlite :database-file (*environment* "LOYLY_GRAIN_STORE")}})]
    (doseq [[k v] (:events contract)] (schema/register! {k (:schema v)}))
    (when (= context "measurement")
      (swap! state assoc :history (into [] (es/read store {:tenant-id tenant :types #{:measurement/measurement-filed}}))
             :scoreboard (some-> (last (into [] (es/read store {:tenant-id tenant :types #{:measurement/scoreboard-reported}}))) model/payload)))
    (try
      (doseq [line (line-seq (java.io.BufferedReader. *in*))]
        (let [{:keys [id method params]} (u/decode line) method (keyword method)
              log (:peer-log script)
              _ (when log (spit log (str (u/encode {:context context :method method :params params :store (*environment* "LOYLY_GRAIN_STORE")}) "\n") :append true))
              _ (when (= (subs (str method) 1) (:exit-on script)) (throw (ex-info "Scripted disconnect" {:disconnect true})))
              reply
              (try
                (let [d (or (get-in contract [:commands method]) (get-in contract [:queries method]) (get-in contract [:events method]))]
                  (when-not d (throw (ex-info "Unknown method" {:code -32601})))
                  (let [p (instant-params (:schema d) params)]
                    (when-not (m/validate (:schema d) p) (throw (ex-info (str "Invalid params " method " " (m/explain (:schema d) p)) {:code -32602})))
                    (cond
                      (contains? (:queries contract) method)
                      (case method
                        :regeneration-cycle/cycle-progress (when-let [c (:cycle @state)] {:cycle c})
                        :grader-lifecycle/instrument-goldens {:goldens []}
                        :measurement/scoreboard {:scoreboard (:scoreboard @state)}
                        {})
                      (and (not= context (namespace method)) (contains? (:events contract) method))
                      (do
                        (when (= method :regeneration-cycle/gate-attempt-passed) (swap! state assoc :gated p))
                        (when (= method :measurement/measurement-filed) (swap! state update :cycle merge {:status "passed" :stage "result"}))
                        (when (= method :measurement/score-stage-failed) (failure! "score" "score-stage-failed" (:detail p)))
                        (let [r (es/append store {:tenant-id tenant :events [(es/->event {:type method :body p})]})]
                          (when (map? r) (throw (ex-info "Stand-in handoff append failed" r))))
                        {:events [(assoc (u/wire p) :type (subs (str method) 1))]})
                      :else
                      (let [events (command context method p contract)]
                        (when (seq events)
                          (let [r (es/append store {:tenant-id tenant :events (mapv #(es/->event {:type (keyword (:type %)) :body (dissoc % :type)}) events)})]
                            (when (map? r) (throw (ex-info "Stand-in append failed" r)))))
                        (when (= method :measurement/file-score)
                          (let [measurement (first events) previous (last (:history @state))
                                sb (event :measurement/scoreboard-reported
                                      {:cycle-id (:cycle-id p) :selection-id (:selection-id measurement)
                                       :passes (:passes measurement) :total (:total measurement) :rate (format "%.1f" (* 100.0 (/ (:passes measurement) (:total measurement))))
                                       :previous-cycle-id (:cycle-id previous) :previous-selection-id (:selection-id previous)
                                       :new-items [] :dropped-items [] :reported-at (u/now)} contract)]
                            (es/append store {:tenant-id tenant :events [(es/->event {:type :measurement/scoreboard-reported :body (dissoc sb :type)})]})
                            (swap! state assoc :scoreboard sb)))
                        {:events events}))))
                (catch Throwable t
                  {:error {:code (or (:code (ex-data t)) -32001) :message (.getMessage t) :data (or (:data (ex-data t)) {})}}))]
          (println (u/encode (if (:error reply) {:jsonrpc "2.0" :id id :error (:error reply)} {:jsonrpc "2.0" :id id :result reply})))
          (flush)
          (when log (spit (str log ".replies") (str id " " method "\n") :append true))))
      (finally (es/stop store)))))
