(ns loyly.cycle-runner.system
  "The running system: the runner's own Grain store (SQLite at LOYLY_GRAIN_STORE), ORC on the
   same store and tenant, every Grain todo processor (ORC's and the runner's) started under
   its processor name, and the ORC workflows, one per flow of the model. Every command runs
   inside a tick of the workflow named after its flow."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-model-validator.interface :as validator]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.orc.orc-service.interface :as orc]
            [cognitect.anomalies :as anom]
            [loyly.cycle-runner.blocks :as blocks]
            [loyly.cycle-runner.model :as model]
            [loyly.cycle-runner.util :as u])
  (:import [java.nio.file Files]))

(def default-tenant #uuid "6c6f796c-7900-4000-8000-000000000006")

(def flows
  [:cycle-runner/run-to-gate
   :cycle-runner/verdicts-to-conclusion
   :cycle-runner/cycle-failed-ends-run
   :cycle-runner/effect-not-performed-halts-run])

;; ---------------------------------------------------------------------------
;; The ORC code node that issues one Grain command
;; ---------------------------------------------------------------------------

(defonce ^:private pending (atom {}))

(defn issue-command
  "ORC code node: process the command parked under the request id, inside this tick."
  [{:keys [inputs]}]
  (let [id (:request inputs)
        {:keys [ctx command]} (get @pending id)
        result (if ctx
                 (cp/process-command (assoc ctx :command command))
                 {::anom/category ::anom/not-found ::anom/message "No parked command"})]
    (swap! pending assoc-in [id :result] result)
    {:reply (str id)}))

(defn- workflow-def [flow]
  (orc/workflow (str (namespace flow) "/" (name flow))
    (orc/blackboard {:request [:string {:description "The id of the Grain command this tick issues."}]
                     :reply [:string {:description "The id under which the command's result is kept."}]})
    (orc/sequence "issue"
      (orc/code "issue-command"
                :fn "loyly.cycle-runner.system/issue-command"
                :reads [:request]
                :writes [:reply]))))

(defn issue!
  "Run the command as the ORC workflow of `flow`, one tick at a time on this store.
   Returns the command processor's result (events or an anomaly)."
  [sys flow command-name params]
  (let [id (str (random-uuid))
        command (merge params {:command/id (random-uuid)
                               :command/timestamp (time/now)
                               :command/name command-name})
        sheet-id (get-in sys [:sheets flow])]
    (swap! pending assoc id {:ctx (:ctx sys) :command command})
    (try
      (locking (:lock sys)
        (let [r (orc/execute (:ctx sys) sheet-id {:request id} :timeout-ms 120000)]
          (when-not (= :success (:status r))
            (throw (ex-info (str "ORC workflow " flow " did not complete: " (:error r))
                            {:flow flow :result (dissoc r :node-trace)})))
          (get-in @pending [id :result])))
      (finally (swap! pending dissoc id)))))

(defn query
  "Run a query through Grain's query processor (no ORC workflow: reading changes nothing)."
  [sys query-name params]
  (qp/process-query (assoc (:ctx sys)
                           :query (merge params {:query/name query-name
                                                 :query/id (random-uuid)
                                                 :query/timestamp (time/now)}))))

;; ---------------------------------------------------------------------------
;; Boot
;; ---------------------------------------------------------------------------

(defn register-all!
  "Register the model, the blocks and the processor. Idempotent."
  [processor-handler]
  (model/register-model!)
  (blocks/register-blocks!)
  (let [b (model/block :todo-processors :cycle-runner/advance-run)]
    (tp/register-processor! :cycle-runner/advance-run processor-handler
                            {:topics (:subscribes b)
                             :grain.event-model/produces (:produces b)})))

(defn verify!
  "Grain's boot guard: refuse to start when the running blocks and the model are apart."
  []
  (validator/verify-or-throw! {:model {model/area (model/area-map)}}))

(defn start!
  "Start the system on the SQLite store at store-path. env-fn reads a LOYLY_* variable."
  [{:keys [store-path env-fn tenant-id]}]
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :sqlite :database-file store-path}
                         :event-pubsub ps})
        cache-dir (str (Files/createTempDirectory "loyly-cycle-runner-cache"
                                                  (make-array java.nio.file.attribute.FileAttribute 0)))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "projections"}))
        sys-ref (atom nil)
        ctx {:event-store store
             :event-pubsub ps
             :cache cache
             :tenant-id (or tenant-id default-tenant)
             :command-registry (cp/global-command-registry)
             :query-registry (qp/global-query-registry)
             :loyly/system sys-ref}
        sheets (into {} (map (fn [f] [f (orc/build-workflow! ctx (workflow-def f))])) flows)
        sys {:ctx ctx :store store :pubsub ps :cache cache :cache-dir cache-dir
             :lock (Object.) :env-fn env-fn :sheets sheets
             :sessions (atom {}) :settles (atom {})}
        _ (reset! sys-ref sys)
        processors (doall
                    (for [[pname {:keys [handler-fn topics]}] @tp/processor-registry*]
                      (tp/start {:event-pubsub ps :topics topics :handler-fn handler-fn
                                 :context ctx :processor-name pname})))
        sys (assoc sys :processors processors)]
    (reset! sys-ref sys)
    sys))

(defn stop! [sys]
  (doseq [p (:processors sys)] (try (tp/stop p) (catch Throwable _)))
  (try (pubsub/stop (:pubsub sys)) (catch Throwable _))
  (rmp/l1-clear!)
  (try (es/stop (:store sys)) (catch Throwable _))
  (try (kv/stop (:cache sys)) (catch Throwable _))
  (u/delete-tree! (:cache-dir sys)))

(defn env [sys k] ((:env-fn sys) k))
