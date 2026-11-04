(ns loyly.pi-harness.core.runtime.durable-system
  (:require [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as todo]
            [ai.obney.orc.orc-service.interface]
            [ai.obney.orc.llm.interface :as llm]
            [litellm.providers.bedrock]
            [loyly.pi-harness.grain.runtime-event-model :as runtime-event-model]))

(def tenant-id #uuid "27e205ec-80c4-3e7c-86ca-9a8cc66e51e7")

(def reflection-provider :gepa-reflection)

(defn reflection-model []
  (or (System/getenv "PI_HARNESS_GEPA_MODEL")
      "eu.amazon.nova-2-lite-v1:0"))

(defn reflection-region []
  (or (System/getenv "AWS_REGION")
      (System/getenv "AWS_DEFAULT_REGION")
      "eu-central-1"))

(defn- state-root []
  (or (System/getenv "PI_HARNESS_STATE_ROOT")
      (System/getenv "LOYLEY_STATE_ROOT")
      (some-> (or (System/getenv "PI_HARNESS_PROJECT_ROOT")
                  (System/getenv "LOYLEY_PROJECT_ROOT"))
              (str "/.pi/harness/.state"))
      (throw (ex-info "PI_HARNESS_STATE_ROOT or PI_HARNESS_PROJECT_ROOT is required" {}))))

(defn start! []
  (llm/register-provider! reflection-provider
                          {:provider :bedrock
                           :model (reflection-model)
                           :config {:region (reflection-region)}})
  (let [root (java.io.File. (state-root))
        _ (.mkdirs root)
        event-pubsub (pubsub/start {:type :core-async :topic-fn :event/type})
        store (event-store/start
               {:conn {:type :sqlite
                       :database-file (str root "/orc-events.sqlite")}
                :event-pubsub event-pubsub
                :logger nil})
        _ (runtime-event-model/load-and-verify! store)
        cache (kv/start
               (lmdb/->KV-Store-LMDB
                {:storage-dir (str root "/projections") :db-name "pi-harness-orc"}))
        context {:tenant-id tenant-id
                 :system-tenant-id tenant-id
                 :event-store store
                 :event-pubsub event-pubsub
                 :cache cache
                 :llm-provider reflection-provider}
        poller (todo/start-tenant-poller
                {:event-store store :tenant-ids #{tenant-id}
                 :context context :poll-interval-ms 10})]
    {:context context :poller poller :cache cache
     :event-store store :event-pubsub event-pubsub}))

(defn stop! [{:keys [poller cache event-store event-pubsub]}]
  (when poller (todo/stop-tenant-poller poller))
  (when cache (kv/stop cache))
  (when event-store (event-store/stop event-store))
  (when event-pubsub (pubsub/stop event-pubsub)))
