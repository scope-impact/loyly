(ns measurement.system
  "Starts the product: registers the room's event model, refuses to start when
   Grain's boot guard finds the running blocks and the model apart, opens the
   Grain store LOYLY_GRAIN_STORE names (in-memory when unset), and runs ORC and
   every Grain todo processor on that same store and tenant."
  (:require [ai.obney.grain.event-model.interface :as em]
            [ai.obney.grain.event-model-validator.interface :as validator]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.orc.orc-service.interface]
            [measurement.model :as model]
            [measurement.schemas]
            [measurement.read-models]
            [measurement.commands]
            [measurement.queries]
            [measurement.policy]
            [measurement.workflows :as wf])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def default-tenant-id #uuid "6c6f796c-7900-4000-8000-000000000002")

(defn register-event-model!
  "Registers the room's model, one service area at a time, as given."
  []
  (doseq [[area area-map] model/model]
    (em/register-event-model! area area-map)))

(defn verify!
  "Grain's boot guard: throws when the running blocks and the model are apart.
   `model` defaults to the registered model."
  ([] (validator/verify-or-throw!))
  ([model] (if model (validator/verify-or-throw! {:model model}) (validator/verify-or-throw!))))

(defn- delete-tree [^File f]
  (when (.isDirectory f)
    (run! delete-tree (.listFiles f)))
  (.delete f))

(defn start
  "Start the product. Options: :store-path (a SQLite file; in-memory when nil),
   :tenant-id, and :model (verify against this model instead of the registered
   one; the tests use it to show start refuses a model apart from the blocks)."
  [{:keys [store-path tenant-id model] :or {tenant-id default-tenant-id}}]
  (register-event-model!)
  (verify! model)
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn (if store-path
                                 {:type :sqlite :database-file store-path}
                                 {:type :in-memory})
                         :event-pubsub ps})
        cache-dir (str (Files/createTempDirectory "measurement-cache" (make-array FileAttribute 0)))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "projections"}))
        ctx {:tenant-id tenant-id
             :event-store store
             :event-pubsub ps
             :cache cache}
        ;; One poller runs every registered Grain todo processor, ORC's and this
        ;; context's, each under its processor name, so each is checkpointed.
        poller (tp/start-tenant-poller {:event-store store
                                        :tenant-ids #{tenant-id}
                                        :context ctx
                                        :poll-interval-ms 5})]
    (wf/build-all! ctx)
    {:ctx ctx :poller poller :pubsub ps :store store :cache cache :cache-dir cache-dir}))

(defn stop [{:keys [poller pubsub store cache cache-dir]}]
  (try (tp/stop-tenant-poller poller) (catch Throwable _))
  (try (pubsub/stop pubsub) (catch Throwable _))
  (try (es/stop store) (catch Throwable _))
  (try (kv/stop cache) (catch Throwable _))
  (rmp/l1-clear!)
  (when cache-dir (delete-tree (File. ^String cache-dir))))
