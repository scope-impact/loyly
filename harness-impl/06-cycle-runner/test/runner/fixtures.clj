(ns runner.fixtures
  "Test-only seam programs. All domain decisions here are scripted stand-in behavior,
  never loaded by the product. Their companion writes actual Grain handoff stores."
  (:require [runner.util :as u] [runner.model :as model] [runner.contexts :as c]
            [clojure.java.io :as io] [clojure.string :as str] [malli.core :as m]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.event-definition :as evdef])
  (:import [java.net ServerSocket Socket]))
(def contracts @c/contracts)
(def event-models (into {} (mapcat (comp :events val) contracts)))
(defn event [type values]
  (let [schema (:schema (get event-models type))
        fields (map first (rest schema))
        body (into {} (for [f fields :let [v (get values f ::none)] :when (not= v ::none)] [f v]))
        body (reduce (fn [b [k & _]] (if (or (contains? b k) (not (str/ends-with? (name k) "-at"))) b (assoc b k (if (= k :passed-at) (java.time.Instant/now) (java.util.Date.))))) body (rest schema))]
    (when-not (m/validate schema body) (throw (ex-info (str "bad standin event " type) {:body body :schema schema :explain (m/explain schema body)})))
    (assoc body :type (subs (str type) 1))))
(defn append! [store events]
  (when (seq events)
    (let [s (es/start {:conn {:type :sqlite :database-file store}})]
      (try (let [r (es/append s {:tenant-id #uuid "00000000-0000-0000-0000-000000000000"
                               :events (mapv #(es/->event {:type (keyword (:type %)) :body (dissoc % :type)}) events)})]
             (when (:cognitect.anomalies/category r) (throw (ex-info "standin append failed" r))))
           (finally (es/stop s))))))
(defn refusal [code] {:error {:code -32000 :message code :data {:refusal code}}})
(defn status-fail [state cycle stage reason detail]
  (assoc-in state [:cycles cycle] (merge (get-in state [:cycles cycle]) {:cycle-id cycle :status "failed" :stage stage :failure {:stage stage :reason reason :detail detail}})))
(defn handle! [world {:keys [context store request]}]
  (locking world
    (let [{:keys [method params id]} request p params type (keyword method)
          script (:script @world) global (:global @world) local (get-in @world [:stores store] {})
          state (merge global local)
          commands (get-in contracts [context :commands]) handoffs (into {} (for [[k b] event-models :when (not= context (namespace k))] [k b]))
          schema (or (:schema (get commands type)) (:schema (get handoffs type)))
          _ (swap! world update :calls (fnil conj []) {:context context :method method :params p :store store})
          emit (fn [t values] {:events [(event t values)]})
          cycle (:cycle-id p)
          result
          (cond
            (or (= (:exit-at script) method) (contains? (set (:exit-contexts script)) context)) {:exit true}
            (and schema (not (m/validate schema (if (get handoffs type)
                                                 (reduce-kv (fn [v k x] (assoc v k (if (and (string? x) (str/ends-with? (name k) "-at")) (java.util.Date/from (java.time.Instant/parse x)) x))) {} p) p))))
            {:error {:code -32602 :message "standin rejects params" :data {:method method :params p}}}
            (get handoffs type) {:events [] :accepted-handoff true}
            (= method "specification/stamped-items") {:slices []}
            (= method "cycle-preparation/source-cuts") {:cuts []}
            (= method "grader-lifecycle/instrument-goldens") {:goldens []}
            (= method "measurement/measurements-to-report") {:cycle-ids []}
            (= method "regeneration-cycle/cycles-to-conclude") {:cycle-ids []}
            (= method "regeneration-cycle/cycle-progress") (if cycle {:cycle (get-in state [:cycles cycle])} {:cycles (vec (vals (:cycles state)))})
            (= method "measurement/scoreboard") {:scoreboard (get-in state [:boards cycle])}
            (= method "specification/stamp-slice")
            (let [events (mapv (fn [i r] (event :specification/scenario-stamped (merge r {:item-id (format "%s-%03d" (:slice p) (inc i)) :slice (:slice p) :source (:source p)}))) (range) (:unstamped p))]
              ;; Like Specification, writes the file it was asked to stamp.
              (u/put (:source p) (str/join "\n" (map-indexed (fn [i line] (if-let [e (some #(when (= (inc i) (:line %)) %) events)] (str "    @item-" (:item-id e) "\n" line) line)) (str/split-lines (slurp (:source p))))))
              {:events events})
            (= method "cycle-preparation/pin-source-cut") (emit :cycle-preparation/source-cut-pinned p)
            (= method "measurement/derive-checklist")
            (if (empty? (:items p)) (refusal "empty-checklist")
              (emit :measurement/checklist-derived (assoc p :checklist-digest (u/digest (pr-str (:items p))) :total (count (:items p)))))
            (= method "regeneration-cycle/record-harness-self-check")
            (emit (if (= "passed" (:outcome p)) :regeneration-cycle/harness-self-check-passed :regeneration-cycle/harness-self-check-failed) p)
            (= method "regeneration-cycle/prove-clean-room")
            (if (seq (:unmatched-paths p)) (refusal "fenced-path-matched-nothing") (emit :regeneration-cycle/clean-room-proved p))
            (= method "regeneration-cycle/record-regenerated-candidate") (emit :regeneration-cycle/candidate-regenerated p)
            (= method "regeneration-cycle/install-candidate-in-gate-room") (emit :regeneration-cycle/candidate-installed-in-gate-room p)
            (= method "regeneration-cycle/record-gate-attempt")
            (cond
              (not (:identity-probe-present p)) (refusal "identity-probe-missing")
              (:seam-answered-before-boot p) (refusal "seam-answered-before-boot")
              :else (emit (if (= "passed" (:outcome p)) :regeneration-cycle/gate-attempt-passed :regeneration-cycle/gate-attempt-failed)
                          (assoc p :cut-id (get-in state [:cycles cycle :cut-id]))))
            (= method "regeneration-cycle/record-gate-refusal") (emit :regeneration-cycle/gate-attempt-refused p)
            (= method "regeneration-cycle/fail-cycle") (emit :regeneration-cycle/cycle-failed p)
            (= method "measurement/file-item-verdicts")
            (let [items (set (map :item-id (get-in state [:checklists (get-in state [:gates cycle :cut-id]) :items])))]
              (if (some #(not (items (:item-id %))) (:verdicts p)) (refusal "unknown-item")
                (emit :measurement/item-verdicts-filed (update p :verdicts (fn [vs] (mapv #(merge {:evidence ""} %) vs))))))
            (= method "measurement/fail-score-stage") (emit :measurement/score-stage-failed p)
            (= method "measurement/file-score")
            (let [gate (get-in state [:gates cycle]) cut (:cut-id gate) checklist (get-in state [:checklists cut])
                  vs (get-in state [:verdicts cycle]) by-id (into {} (map (juxt :item-id identity) vs))
                  items (mapv #(or (by-id (:item-id %)) {:item-id (:item-id %) :verdict "fail" :reason "unreached" :evidence ""}) (:items checklist))
                  passes (count (filter #(= "pass" (:verdict %)) items))
                  measurement (event :measurement/measurement-filed {:cycle-id cycle :cut-id cut :selection-id (:selection-id checklist) :candidate-digest (:candidate-digest gate)
                                :checklist-digest (:checklist-digest checklist) :grader-digests (:approved-grader-digests p) :total (count items) :passes passes
                                :unreached (count (filter #(= "unreached" (:reason %)) items)) :items items :superseded []})
                  previous (last (filter #(= (:selection-id checklist) (:selection-id %)) (get state :history [])))
                  board (event :measurement/scoreboard-reported {:cycle-id cycle :selection-id (:selection-id checklist) :passes passes :total (count items)
                           :rate (format "%.1f" (* 100.0 (/ passes (count items)))) :previous-cycle-id (:cycle-id previous) :previous-selection-id (:selection-id previous)
                           :new-items (if previous [] (mapv :item-id items)) :dropped-items []})]
              {:events [measurement] :policy-events [board]})
            :else {:error {:code -32601 :message (str "unscripted method " method)}})
          events (:events result)
          new-state
          (reduce (fn [st e]
                    (let [cycle (:cycle-id e)]
                      (case (:type e)
                        "regeneration-cycle/harness-self-check-passed" (if (zero? (:attempt e)) (assoc-in st [:cycles cycle] {:cycle-id cycle :cut-id (:cut-id e) :budget (:budget e) :status "running" :stage "fence"}) st)
                        "regeneration-cycle/harness-self-check-failed" (status-fail st cycle (if (zero? (:attempt e)) "fence" "gate") "harness-self-check-failed" (:detail e))
                        "regeneration-cycle/clean-room-proved" (assoc-in st [:cycles cycle :stage] "regenerate")
                        "regeneration-cycle/candidate-regenerated" (-> st (assoc-in [:cycles cycle :stage] "gate") (assoc-in [:cycles cycle :candidate-digest] (:candidate-digest e)))
                        "regeneration-cycle/gate-attempt-passed" (-> st (assoc-in [:cycles cycle :stage] "score") (assoc-in [:gates cycle] e))
                        "regeneration-cycle/gate-attempt-failed" (if (>= (:attempt e) (get-in st [:cycles cycle :budget :gate-attempt-limit]))
                                                                  (status-fail st cycle "gate" "gate-attempts-exhausted" (:detail e)) st)
                        "regeneration-cycle/gate-attempt-refused" (let [st (update-in st [:refusals cycle] (fnil inc 0))] (if (= 3 (get-in st [:refusals cycle])) (status-fail st cycle "gate" "infrastructure" (:detail e)) st))
                        "regeneration-cycle/cycle-failed" (status-fail st cycle (:stage e) (:reason e) (:detail e))
                        "measurement/checklist-derived" (assoc-in st [:checklists (:cut-id e)] e)
                        "measurement/item-verdicts-filed" (assoc-in st [:verdicts cycle] (:verdicts e))
                        "measurement/measurement-filed" (update st :history (fnil conj []) e)
                        "measurement/score-stage-failed" (status-fail st cycle "score" "score-stage-failed" (:refusal e))
                        "measurement/scoreboard-reported" (-> st (assoc-in [:boards cycle] e) (assoc-in [:cycles cycle :status] "passed") (assoc-in [:cycles cycle :stage] "result"))
                        st))) state (concat events (:policy-events result) (when (:accepted-handoff result) [(assoc p :type method)]))) ]
      (append! store (concat events (:policy-events result)))
      (swap! world assoc :global new-state)
      (swap! world assoc-in [:stores store] new-state)
      (cond
        (:exit result) {:exit true}
        (:error result) (merge {:jsonrpc "2.0" :id id} result)
        :else {:jsonrpc "2.0" :id id :result (dissoc (u/normalize result) :policy-events :accepted-handoff)}))))
(defn server! [script]
  (doseq [[k b] event-models] (evdef/register-event-definition! k (:description b) {:schema (:schema b)} {:ns "runner.fixtures"}))
  (let [server (ServerSocket. 0) world (atom {:script script})
        task (future (while (not (.isClosed server))
                       (try (let [socket (.accept server)]
                              (future (with-open [socket socket reader (io/reader socket) writer (io/writer socket)]
                                        (let [response (try (handle! world (u/decode (.readLine reader)))
                                                            (catch Throwable e (binding [*out* *err*] (println "standin error" (.getMessage e) (ex-data e))) {:error {:code -32099 :message (.getMessage e)}}))]
                                          (.write writer (str (u/encode response) "\n")) (.flush writer)))))
                            (catch java.net.SocketException _))))]
    {:world world :port (.getLocalPort server) :stop #(.close server) :task task}))
(defn script! [path content] (u/put path (str "#!/bin/sh\nset -eu\n" content "\n")) (u/run ["chmod" "+x" path] nil) path)
(defn fixture!
  ([] (fixture! {}))
  ([opts]
   (let [dir (u/mkdir (u/path "work" (str "fixture-" (random-uuid)))) repo (u/mkdir (u/path dir "project"))
         server (server! opts)
         spec (or (:spec opts) "Feature: fixture\n  Rule: example\n    @item-01-001\n    Scenario: first\n      Given first\n    @item-01-002\n    Scenario: second\n      Given second\n")
         stdio? (not= false (:stdio opts))
         app (u/mkdir (u/path repo "impl"))
         _ (u/put (u/path app "old") "fenced")
         _ (u/put (u/path repo "harness/cycles/old") "fenced")
         _ (u/put (u/path repo "plan/01-a/spec.feature") spec)
         _ (script! (u/path repo "harness/probe.sh") (or (:probe opts) (if stdio? "echo answered as the context" "curl -sf \"$1\" | grep -qi sauna")))
         _ (u/put (u/path repo "harness/config.sh") "APP_MARKER=sauna\n")
         config (u/put (u/path dir "config.sh") (str "APP_DIR=out\nPLAN_DIR=plan\nFENCED_PATHS=\"impl harness/cycles\"\nAPP_PROBE=harness/probe.sh\nAPP_MARKER=sauna\nAPP_SEAM=" (if stdio? "stdio" "http") "\nPORT=" (or (:port opts) 18977) "\n" (:config opts)))
         candidate (u/mkdir (u/path dir "fixture-candidate"))
         _ (script! (u/path candidate "test.sh") (or (:suite opts) "exit 0"))
         _ (when-not (:no-start opts) (script! (u/path candidate "start.sh") (or (:start opts) "exit 0")))
         builder (script! (u/path dir "builder.sh")
                         (or (:builder opts)
                             (str "echo begin >> '" dir "/launches'\n"
                                  "printf '%s\\n' \"$@\" >> '" dir "/builder-args'\n"
                                  "cp -a '" candidate "/.' \"$1/out/\"\n"
                                  "git -c safe.directory='*' -C \"$1\" rev-list --all --count >> '" dir "/builder-commits'\n"
                                  "echo end >> '" dir "/launches'\nexit " (or (:builder-exit opts) 0))))
         self (script! (u/path dir "check.sh") (or (:self-check opts) "exit 0"))
         store-check (script! (u/path dir "store-check.sh") (or (:store-check opts) "exit 0"))
         contexts (u/json-file (u/path dir "contexts.json")
                              (into {} (for [n c/names]
                                         [n (script! (u/path dir (str n ".sh"))
                                                     (str "exec bb '" (u/path "test/runner/standin.clj") "' '" n "' '" (:port server) "'"))])))
         instruction (u/put (u/path dir "instruction.md") "Build the fixture app")]
     (u/git! repo "init" "-q") (u/git! repo "add" ".")
     (u/git! repo "-c" "user.name=Fixture" "-c" "user.email=fixture@local" "commit" "-qm" "fixture cut")
     {:dir dir :repo repo :server server :commit (u/git! repo "rev-parse" "HEAD") :config config :candidate candidate
      :env {"LOYLY_PROJECT_REPO" repo "LOYLY_RUNS_DIR" (u/mkdir (u/path dir "runs")) "LOYLY_HARNESS_CONFIG" config "LOYLY_CONTEXTS" contexts
            "LOYLY_SELF_CHECK_CMD" self "LOYLY_STORE_CHECK_CMD" store-check "LOYLY_BUILDER_CMD" builder "LOYLY_BUILDER_INSTRUCTION" instruction}})))
(defn close! [fixture] ((get-in fixture [:server :stop])))
(defn params [fixture]
  {:run-id "R-1" :cycle-id "C-1" :cut-id "K-1" :commit (:commit fixture) :selection-id "selection"
   :budget {:gate-attempt-limit 3 :max-outer-cycles 1 :max-continuations 0 :stage-timeouts-seconds {:fence 30 :regenerate 30 :gate 30 :score 30}}
   :grader-digests ["r-1"]})
