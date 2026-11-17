(ns loyly.runtime
  (:require [loyly.model :as model] [loyly.domain :as domain]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.pubsub.interface :as ps]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.periodic-task.interface :as periodic]
            [ai.obney.orc.orc-service.interface :as orc]))
(def ^:dynamic tenant #uuid "00000000-0000-0000-0000-000000000000")
(defn start! [path]
  (let [pubsub (ps/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn (if path {:type :sqlite :database-file path} {:type :in-memory}) :event-pubsub pubsub})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir (str "target/cache-" (random-uuid)) :db-name "projections"}))
        ctx {:tenant-id tenant :event-store store :event-pubsub pubsub :pubsub pubsub :cache cache :domain-lock (Object.) :orc/execution-lease-ms 3000 :stopped (atom false)}
        _ (model/install! domain/command-handler domain/query-handler domain/reduce-row domain/processor-handler #(domain/periodic-handler ctx %1 %2 %3))
        timers (periodic/start-periodic-triggers! {:append-fn #(es/append store %) :tenant-ids-fn (constantly #{tenant})})
        processors (mapv (fn [[name config]] (tp/start (merge config {:event-store store :event-pubsub pubsub :processor-name name :context ctx}))) @tp/processor-registry*)]
    (assoc ctx :processors processors :timers timers)))
(defn stop! [ctx]
 (when (compare-and-set! (:stopped ctx) false true)
  (periodic/stop-periodic-triggers! (:timers ctx))
  (doseq [p (:processors ctx)] (tp/stop p))
  (kv/stop (:cache ctx)) (es/stop (:event-store ctx)) (ps/stop (:event-pubsub ctx))))
