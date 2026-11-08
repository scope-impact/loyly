(ns loyly.cycle-preparation.system
  "Boots the candidate: the Grain store LOYLY_GRAIN_STORE names (SQLite), the
   room's event model registered and verified by Grain's boot guard, ORC on the
   same store and tenant, and every Grain todo processor started by name."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.event-model-validator.interface :as emv]
            [ai.obney.orc.orc-service.interface]
            [loyly.cycle-preparation.model :as model]
            [loyly.cycle-preparation.domain]
            [loyly.cycle-preparation.queries]
            [loyly.cycle-preparation.flow :as flow]))

(def tenant-id #uuid "c9c1e000-0005-4000-8000-000000000005")

(defn- delete-dir! [^java.io.File f]
  (when (.isDirectory f) (run! delete-dir! (.listFiles f)))
  (.delete f))

(defn verify!
  "Grain's boot guard: refuse to start when the running blocks and the
   registered model are apart."
  [event-store]
  (model/register!)
  (emv/verify-or-throw! {:event-store event-store}))

(defn start
  "Start the system. `store-path` is the SQLite file; nil means in memory."
  [{:keys [store-path]}]
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn (if store-path
                                       {:type :sqlite :database-file store-path}
                                       {:type :in-memory})
                               :event-pubsub ps
                               :logger nil})
        _ (try (verify! event-store)
               (catch Throwable t
                 (es/stop event-store)
                 (pubsub/stop ps)
                 (throw t)))
        cache-dir (str (System/getProperty "java.io.tmpdir") "/loyly-cycle-preparation-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "projections"}))
        ctx {:tenant-id tenant-id
             :event-store event-store
             :event-pubsub ps
             :cache cache}
        processors (into {}
                         (map (fn [[proc-name {:keys [handler-fn topics]}]]
                                [proc-name (tp/start {:event-pubsub ps
                                                      :topics topics
                                                      :handler-fn handler-fn
                                                      :context ctx
                                                      :processor-name proc-name})]))
                         @tp/processor-registry*)
        sheets (flow/build! ctx)]
    (assoc ctx
           :processors processors
           :sheets sheets
           ::cache-dir cache-dir)))

(defn stop [{:keys [processors event-pubsub event-store cache] :as sys}]
  (doseq [[_ p] processors] (try (tp/stop p) (catch Throwable _)))
  (when event-pubsub (try (pubsub/stop event-pubsub) (catch Throwable _)))
  (rmp/l1-clear!)
  (when event-store (try (es/stop event-store) (catch Throwable _)))
  (when cache (try (kv/stop cache) (catch Throwable _)))
  (when-let [d (::cache-dir sys)] (delete-dir! (java.io.File. ^String d))))
