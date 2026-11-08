(ns loyly.regen.system
  "The running product: the Grain store LOYLY_GRAIN_STORE names, ORC on that same
   store and tenant, the todo processors (ORC's and the conclude-cycle policy) each
   started under its processor name, the stage clock, and the boot guard.

   Every command runs as the ORC workflow named after a flow of the event model
   that holds it; its one leaf issues the Grain command. Methods, the policy and
   the clock are serialised: one write at a time."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.todo-processor-v2.interface :as tp :refer [defprocessor]]
            [ai.obney.grain.periodic-task.interface :as pt :refer [defperiodic]]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.event-model-validator.interface :as validator]
            [ai.obney.grain.anomalies.interface :refer [anomaly?]]
            [ai.obney.orc.orc-service.interface :as orc]
            [loyly.regen.model :as model]
            [loyly.regen.domain :as d]
            [loyly.regen.blocks :as blocks])
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.locks ReentrantLock]
           [java.time Instant OffsetDateTime]))

(def tenant-id
  "The product's one tenant, which ORC shares."
  #uuid "6c6f796c-7900-4004-8000-000000000004")

(defonce ^:private app (atom nil))
(defonce ^ReentrantLock write-lock (ReentrantLock.))

(defn ctx [] (:ctx @app))

(defmacro with-write-lock [& body]
  `(do (.lock write-lock)
       (try ~@body (finally (.unlock write-lock)))))

;; ---------------------------------------------------------------------------
;; ORC: one workflow per flow, a leaf that issues the command
;; ---------------------------------------------------------------------------

(def workflow-names
  (->> (get-in (model/model) [:regeneration-cycle :flows]) keys (map #(subs (str %) 1)) sort vec))

(defn- workflow [wf-name]
  (orc/workflow wf-name
    (orc/blackboard
     {:request-id [:string {:description "The request whose command this run issues."}]
      :command [:string {:description "The Grain command issued, as EDN."}]
      :outcome [:string {:description "recorded, or refused with its code."}]})
    (orc/sequence "issue"
      (orc/code "issue-command"
        :fn "loyly.regen.system/issue-command-leaf"
        :reads [:request-id :command]
        :writes [:outcome]))))

(defonce ^:private pending (ConcurrentHashMap.))
(defonce ^:private results (ConcurrentHashMap.))

(defn issue-command-leaf
  "The ORC leaf: issues the pending Grain command of this run's request."
  [{:keys [inputs]}]
  (let [rid (:request-id inputs)
        command (.get pending rid)
        result (cp/process-command (assoc (ctx) :command command))]
    (.put results rid result)
    {:outcome (if (anomaly? result)
                (str "refused " (or (:refusal/code result) (:cognitect.anomalies/message result)))
                "recorded")}))

(defn- run-in-workflow!
  "Runs `command-name` with `params` as the ORC workflow `wf-name`; returns the
   Grain command result (events, or a refusal anomaly)."
  [wf-name command-name params]
  (let [{:keys [sheets]} @app
        rid (str (random-uuid))
        command (merge params {:command/id (random-uuid)
                               :command/timestamp (OffsetDateTime/now)
                               :command/name command-name})]
    (.put pending rid command)
    (try
      (let [r (orc/execute (ctx) (get sheets wf-name)
                           {:request-id rid :command (pr-str (assoc params :command/name command-name))}
                           :timeout-ms 60000)
            result (.get results rid)]
        (cond
          result result
          :else {:cognitect.anomalies/category :cognitect.anomalies/fault
                 :cognitect.anomalies/message (str "ORC run of " wf-name " did not complete: "
                                                   (:status r) " " (:error r))}))
      (finally
        (.remove pending rid)
        (.remove results rid)))))

;; ---------------------------------------------------------------------------
;; The conclude-cycle policy and the stage clock
;; ---------------------------------------------------------------------------

(defn conclude-due!
  "Concludes every cycle the policy's to-do list holds, each in its flow's workflow.
   Called with the write lock held."
  []
  (loop [seen #{}]
    (let [due (remove seen (:cycle-ids (:query/result (qp/process-query
                                                       (assoc (ctx) :query {:query/name :regeneration-cycle/cycles-to-conclude
                                                                            :query/id (random-uuid)
                                                                            :query/timestamp (OffsetDateTime/now)})))))]
      (when-let [id (first due)]
        (when-let [{:keys [command params workflow]} (d/conclusion (get (blocks/cycles (ctx)) id))]
          (run-in-workflow! workflow command params))
        (recur (conj seen id))))))

(defprocessor :regeneration-cycle conclude-cycle
  {:topics #{:regeneration-cycle/harness-self-check-failed :regeneration-cycle/gate-attempt-failed
             :regeneration-cycle/gate-attempt-refused
             :measurement/score-stage-failed :measurement/scoreboard-reported}
   :grain.event-model/reads #{:regeneration-cycle/cycles-to-conclude}
   :grain.event-model/produces #{:regeneration-cycle/pass-cycle :regeneration-cycle/fail-cycle}}
  "Concludes a cycle once it is due: passed after its scoreboard, failed on a failed
   self-check, exhausted gate attempts, three gate refusals in a row or a failed score stage."
  [_context]
  (if (and @app (seq (d/due-cycle-ids (blocks/project (ctx) :regeneration-cycle/cycles-to-conclude))))
    {:result/effect (fn [] (with-write-lock (conclude-due!)))
     :result/checkpoint :after}
    {}))

(defn clock-tick!
  "The stage clock at `now`: fails every running cycle whose stage outlived its timeout."
  [^Instant now]
  (with-write-lock
    (doseq [c (vals (blocks/cycles (ctx)))
            :when (d/timed-out? c now)]
      (let [{:keys [command params workflow]} (d/stage-timeout-command c)]
        (run-in-workflow! workflow command params)))))

(defperiodic :regeneration-cycle stage-clock
  {:schedule {:every 30 :duration :seconds}
   :grain.event-model/produces #{:regeneration-cycle/fail-cycle}}
  "Fails a running cycle whose stage outlived its timeout in the budget, with reason budget."
  [tid _time]
  (when (and @app (= tid tenant-id))
    (try (clock-tick! (Instant/now))
         (catch Throwable t (binding [*out* *err*] (println "stage clock:" (ex-message t))))))
  {})

;; ---------------------------------------------------------------------------
;; The methods
;; ---------------------------------------------------------------------------

(def command-workflow
  "Each command method's ORC workflow (seam.md, The event store)."
  {:regeneration-cycle/record-harness-self-check "regeneration-cycle/fence-to-scoreboard"
   :regeneration-cycle/prove-clean-room "regeneration-cycle/fence-to-scoreboard"
   :regeneration-cycle/record-regenerated-candidate "regeneration-cycle/fence-to-scoreboard"
   :regeneration-cycle/install-candidate-in-gate-room "regeneration-cycle/fence-to-scoreboard"
   :regeneration-cycle/record-gate-attempt "regeneration-cycle/fence-to-scoreboard"
   :regeneration-cycle/pass-cycle "regeneration-cycle/fence-to-scoreboard"
   :regeneration-cycle/record-gate-refusal "regeneration-cycle/gate-refusals-fail-cycle"
   :regeneration-cycle/fail-cycle "regeneration-cycle/fence-refused-fails-cycle"})

(defn command!
  "Runs a command method: its workflow, then the policy, before returning."
  [command-name params]
  (with-write-lock
    (let [result (run-in-workflow! (command-workflow command-name) command-name params)]
      (conclude-due!)
      result)))

(defn handoff!
  "Appends a consumed handoff event exactly as given, then runs the policy."
  [event-type params]
  (with-write-lock
    (let [event (es/->event {:type event-type :body params})
          r (es/append (:event-store (ctx)) {:tenant-id tenant-id :events [event]})]
      (if (anomaly? r)
        r
        (do (conclude-due!)
            {:events [event]})))))

(defn query [query-name params]
  (qp/process-query (assoc (ctx) :query (merge params {:query/name query-name
                                                       :query/id (random-uuid)
                                                       :query/timestamp (OffsetDateTime/now)}))))

;; ---------------------------------------------------------------------------
;; Start and stop
;; ---------------------------------------------------------------------------

(defn- scoring-tools-from-env []
  (let [raw (System/getenv "LOYLY_SCORING_TOOL_PATHS")]
    (if (or (nil? raw) (empty? (.trim ^String raw)))
      []
      (let [v (json/read-str raw)]
        (when-not (and (sequential? v) (every? string? v))
          (throw (ex-info "LOYLY_SCORING_TOOL_PATHS is not a JSON array of paths" {:value raw})))
        (vec v)))))

(defn- delete-tree [^java.io.File f]
  (when (.isDirectory f) (run! delete-tree (.listFiles f)))
  (.delete f))

(defn start!
  "Starts the product. `opts`: :store-file (default LOYLY_GRAIN_STORE, else in
   memory), :scoring-tools (default LOYLY_SCORING_TOOL_PATHS), :periodic? (default true)."
  ([] (start! {}))
  ([{:keys [store-file scoring-tools periodic?] :or {periodic? true}}]
   (model/register!)
   ;; Boot guard: the running blocks and the event model must agree.
   (validator/verify-or-throw!)
   (rmp/l1-clear!)
   (reset! blocks/scoring-tool-paths (vec (or scoring-tools (scoring-tools-from-env))))
   (let [store-file (or store-file (System/getenv "LOYLY_GRAIN_STORE"))
         store (es/start {:conn (if store-file
                                  {:type :sqlite :database-file store-file}
                                  {:type :in-memory})})
         cache-dir (doto (java.io.File/createTempFile "loyly-regen-cache" "")
                     (.delete) (.mkdirs))
         cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir (str cache-dir) :db-name "projections"}))
         c {:event-store store :cache cache :tenant-id tenant-id
            :command-registry (cp/global-command-registry)
            :query-registry (qp/global-query-registry)}
         poller (tp/start-tenant-poller {:event-store store
                                         :tenant-ids #{tenant-id}
                                         :context (dissoc c :tenant-id)
                                         :poll-interval-ms 5})]
     (reset! app {:ctx c :poller poller :cache-dir cache-dir})
     (let [sheets (into {} (for [n workflow-names] [n (orc/build-workflow! c (workflow n))]))]
       (swap! app assoc :sheets sheets))
     (when periodic?
       (swap! app assoc :periodic
              (pt/start-periodic-triggers! {:append-fn (fn [args] (es/append store args))
                                            :tenant-ids-fn (fn [] #{tenant-id})})))
     @app)))

(defn stop! []
  (when-let [{:keys [ctx poller periodic cache-dir]} @app]
    (when periodic (pt/stop-periodic-triggers! periodic))
    (tp/stop-tenant-poller poller)
    (reset! app nil)
    (rmp/l1-clear!)
    (kv/stop (:cache ctx))
    (es/stop (:event-store ctx))
    (delete-tree (io/file (str cache-dir))))
  nil)
