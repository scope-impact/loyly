(ns loyly.specification.system
  "Starts the product: the room's event model registered and verified, the
   Grain store (LOYLY_GRAIN_STORE, or in memory), every Grain todo processor
   (ORC's included) polling that store under its processor name, and the ORC
   workflow built on the same store and tenant."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-model-validator.interface :as event-model-validator]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.orc.orc-service.interface]
            [clojure.java.io :as io]
            [loyly.specification.domain]
            [loyly.specification.queries]
            [loyly.specification.model :as model]
            [loyly.specification.workflow :as workflow]))

(def tenant-id
  "The one tenant the product and ORC share."
  #uuid "6c6f796c-7900-4000-8000-000000000001")

(defn- delete-tree! [^java.io.File f]
  (when (.isDirectory f)
    (run! delete-tree! (.listFiles f)))
  (.delete f))

(defn- cache-dir ^java.io.File []
  (let [base (io/file (model/impl-dir) "target" "run")]
    (.mkdirs base)
    (io/file base (str "cache-" (random-uuid)))))

(defn verify-model!
  "Grain's boot guard: throws when the running blocks and the model are apart."
  []
  (event-model-validator/verify-or-throw!))

(defn start
  "Start the product. `opts`: :store-file (a SQLite path; nil for in memory)."
  [{:keys [store-file poll-interval-ms] :or {poll-interval-ms 20}}]
  (model/register!)
  (verify-model!)
  (rmp/l1-clear!)
  (when store-file
    (some-> (io/file store-file) .getAbsoluteFile .getParentFile .mkdirs))
  (let [event-store (es/start {:conn (if store-file
                                       {:type :sqlite
                                        :database-file (str (.getAbsolutePath (io/file store-file)))}
                                       {:type :in-memory})
                               :event-pubsub nil
                               :logger nil})
        dir (cache-dir)
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir (str dir) :db-name "read-models"}))
        ctx {:event-store event-store
             :cache cache
             :tenant-id tenant-id
             :command-registry (cp/global-command-registry)
             :query-registry (qp/global-query-registry)}
        _ (reset! workflow/app-context ctx)
        ;; Every registered todo processor runs under its own :processor-name,
        ;; so Grain checkpoints it and drops a redelivered event.
        poller (tp/start-tenant-poller {:event-store event-store
                                        :tenant-ids #{tenant-id}
                                        :context ctx
                                        :poll-interval-ms poll-interval-ms
                                        :batch-size 100
                                        :thread-pool-size 16})
        sheet-id (workflow/build! ctx)]
    {:ctx ctx
     :poller poller
     :cache-dir dir
     :sheet-id sheet-id}))

(defn stop [{:keys [ctx poller cache-dir]}]
  (when poller (tp/stop-tenant-poller poller))
  (rmp/l1-clear!)
  (when-let [cache (:cache ctx)] (kv/stop cache))
  (when-let [store (:event-store ctx)] (es/stop store))
  (when cache-dir (delete-tree! cache-dir))
  (reset! workflow/app-context nil)
  nil)
