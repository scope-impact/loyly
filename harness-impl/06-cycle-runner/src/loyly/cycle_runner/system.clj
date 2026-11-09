(ns loyly.cycle-runner.system
  "Start and stop the product: the room's event model registered and verified
   by Grain's boot guard, Grain's event store (SQLite at LOYLY_GRAIN_STORE),
   every Grain todo processor (ORC's included) started with its processor name,
   and the ORC workflows of the flows built on the same store and tenant."
  (:require [ai.obney.grain.event-model.interface :as event-model]
            [ai.obney.grain.event-model-validator.interface :as validator]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.queries]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.todo-processors]
            [loyly.cycle-runner.advance]
            [loyly.cycle-runner.blocks]
            [loyly.cycle-runner.queries]
            [loyly.cycle-runner.model :as model]
            [loyly.cycle-runner.orc :as orc]
            [loyly.cycle-runner.util :as u])
  (:import [java.nio.file Files]))

(def tenant-id #uuid "6c6f796c-7900-4c79-8000-000000000006")

(defn register-model!
  "Register the room's event model, as given, and refuse to start when the
   running blocks and the model are apart."
  []
  (event-model/register-event-model! :cycle-runner (model/area))
  (validator/verify-or-throw!))

(defn start
  "Start on the store at `store-path` (nil: in memory)."
  [store-path]
  (register-model!)
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn (if store-path
                                 {:type :sqlite :database-file store-path}
                                 {:type :in-memory})
                         :event-pubsub ps :logger nil})
        cache-dir (str (Files/createTempDirectory "cycle-runner-cache" (make-array java.nio.file.attribute.FileAttribute 0)))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "cycle-runner"}))
        ctx {:event-store store :cache cache :tenant-id tenant-id :event-pubsub ps
             :command-registry (cp/global-command-registry)
             :query-registry (qp/global-query-registry)}
        processors (into {}
                         (for [[n {:keys [handler-fn topics]}] @tp/processor-registry*]
                           [n (tp/start {:event-pubsub ps :topics topics :handler-fn handler-fn
                                         :context ctx :processor-name n})]))]
    (orc/build! ctx)
    (assoc ctx :processors processors :cache-dir cache-dir)))

(defn stop [{:keys [processors event-pubsub event-store cache cache-dir]}]
  (doseq [[_ p] processors] (try (tp/stop p) (catch Exception _ nil)))
  (when event-pubsub (try (pubsub/stop event-pubsub) (catch Exception _ nil)))
  (rmp/l1-clear!)
  (when event-store (try (es/stop event-store) (catch Exception _ nil)))
  (when cache (try (kv/stop cache) (catch Exception _ nil)))
  (when cache-dir (u/delete-tree! cache-dir)))
