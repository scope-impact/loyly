(ns runner.engine
  (:require [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [runner.cache :as cache]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-model-validator.interface :as guard]
            [runner.model :as model] [runner.util :as u]))
(def jobs (atom {}))
(defn perform [{:keys [inputs]}]
  (let [{:keys [f answer]} (get @jobs (:job inputs))]
    (try (deliver answer {:value (f)}) (catch Throwable t (deliver answer {:throw t})))
    {:done true}))
(def built (atom #{}))
(defn ensure-workflow! [ctx flow]
  (locking built
    (when-not (@built [(:event-store ctx) flow])
      (dsl/build-workflow! ctx
        (dsl/workflow (str "cycle-runner/" flow)
          (dsl/blackboard {:job :string :done :boolean})
          (dsl/code "perform-command" :fn "runner.engine/perform" :reads [:job] :writes [:done])))
      (swap! built conj [(:event-store ctx) flow]))))
(defn workflow! [ctx flow f]
  (ensure-workflow! ctx flow)
  (let [id (str (random-uuid)) answer (promise) tick-id (random-uuid)]
    (swap! jobs assoc id {:f f :answer answer})
    (try
      (let [r (orc/execute ctx (dsl/sheet-id-for-name (str "cycle-runner/" flow)) {:job id} :tick-id tick-id :timeout-ms 86400000 :durability-mode :legacy)]
        (when-not (= :success (:status r)) (throw (ex-info "ORC workflow did not complete successfully" r)))
        ;; ORC delivers the result and assembles its trace on separate processors.
        ;; Do not let EOF close the shared store before the trace is durable.
        (loop [remaining 1000]
          (when-not (seq (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types #{:sheet/execution-traced} :tags #{[:tick tick-id]}})))
            (when (zero? remaining) (throw (ex-info "ORC trace did not become durable" {:tick-id tick-id})))
            (Thread/sleep 10) (recur (dec remaining))))
        (let [v @answer] (if-let [t (:throw v)] (throw t) (:value v))))
      (finally (swap! jobs dissoc id)))))
(defn command! [ctx flow command params]
  (workflow! ctx flow
    #(let [r (cp/process-command (assoc ctx :command (merge params {:command/name (keyword "cycle-runner" command) :command/id (random-uuid) :command/timestamp (java.time.OffsetDateTime/now java.time.ZoneOffset/UTC)})))]
       (when (:runner/refusal r) (throw (ex-info (get-in r [:runner/refusal :refusal]) {:refusal (:runner/refusal r)})))
       (when-not (contains? r :command-result/events) (throw (ex-info "Grain command failed" r)))
       (:command-result/events r))))
(defn start [path]
  (model/register!)
  (guard/verify-or-throw! {:model {:cycle-runner model/model}})
  (let [path (u/abs path)
        _ (clojure.java.io/make-parents path)
        ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :sqlite :database-file path :maximum-pool-size 4} :event-pubsub ps})
        cache (kv/start (cache/cache))
        ctx {:event-store store :event-pubsub ps :tenant-id #uuid "00000000-0000-0000-0000-000000000000" :cache cache}
        processors (into {} (for [[name {:keys [topics handler-fn]}] @tp/processor-registry*]
                             [name (tp/start {:event-pubsub ps :topics topics :handler-fn handler-fn :processor-name name :context ctx})]))
        ctx (assoc ctx :processors processors)]
    ctx))
(defn stop [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (pubsub/stop (:event-pubsub ctx))
  (es/stop (:event-store ctx))
  (kv/stop (:cache ctx)))
