(ns runner.runtime
  (:require [runner.model :as model] [runner.util :as u]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.pubsub.interface :as pub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store.interface.protocol :as kvp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [clj-uuid :as uuid]))
(defrecord MemoryCache [data]
  kvp/KVStore
  (start [this] this)
  (stop [_] nil)
  (get! [_ {:keys [k]}] (get @data (vec k)))
  (put! [_ {:keys [k v]}] (swap! data assoc (vec k) v) nil)
  (put-batch! [_ {:keys [entries]}] (swap! data into (map (fn [{:keys [k v]}] [(vec k) v]) entries)) nil))
(def actions (atom {}))
(defn action-node [ctx] ((get @actions (or (get-in ctx [:inputs "ticket"]) (get-in ctx [:inputs :ticket])))) {:done true})
(defn workflow! [ctx name f]
  (let [ticket (str (random-uuid))
        _ (swap! actions assoc ticket f)
        definition (orc/workflow name (orc/blackboard {:ticket :string :done :boolean}) (orc/sequence "effects" (orc/code "perform" :fn "runner.runtime/action-node" :reads [:ticket] :writes [:done])))
        built (orc/build-workflow! ctx definition)
        sheet-id built
        result (try (orc/execute ctx sheet-id {"ticket" ticket} :timeout-ms 86400000 :durability-mode :legacy) (finally (swap! actions dissoc ticket)))]
    (when-not (= :success (:status result)) (throw (ex-info (str "Workflow failed: " name) {:built built :result result})))
    result))
(defn start! [file processor]
  (model/register! processor)
  (let [ps (pub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn (if file {:type :sqlite :database-file file} {:type :in-memory}) :event-pubsub ps})
        cache-dir (u/mkdir (u/path "work" (str "cache-" (random-uuid))))
        cache (kv/start (->MemoryCache (atom {})))
        ctx {:event-store store :cache cache :tenant-id #uuid "00000000-0000-0000-0000-000000000000" :event-pubsub ps}
        processors (mapv (fn [[n {:keys [handler-fn topics]}]] (tp/start {:event-pubsub ps :topics topics :handler-fn handler-fn :context ctx :processor-name n})) @tp/processor-registry*)]
    (assoc ctx :processors processors :cache-dir cache-dir
               :processor-topics (into {} (map (fn [[n b]] [n (:topics b)]) @tp/processor-registry*)))))
(defn pending-work
  "Inspect one store snapshot, including ORC's checkpoint-before trace effects.
   A checkpoint alone does not prove those effects finished: each started tick
   must also have its terminal fact and trace before the command may respond."
  [topics events]
  (let [latest (reduce (fn [m e] (assoc m (:event/type e) (:event/id e))) {} events)
        checkpoints (reduce (fn [m e]
                              (if (= :grain/todo-processor-checkpoint (:event/type e))
                                (assoc m (:processor/name e) (:triggered-by e)) m)) {} events)
        ticks (group-by :event/type events)]
    {:processors
     (vec (for [[processor subscriptions] topics
                topic subscriptions
                :let [event-id (get latest topic)
                      checkpoint (get checkpoints processor)]
                :when (and event-id (or (nil? checkpoint) (uuid/< checkpoint event-id)))]
            [processor topic]))
     :ticks
     (let [completed (set (map :tick-id (get ticks :sheet/tree-tick-completed)))
           traced (set (map :trace-id (get ticks :sheet/execution-traced)))]
       (vec (for [e (get ticks :sheet/tree-tick-started)
                  :when (not (and (completed (:tick-id e)) (traced (:tick-id e))))]
              (:tick-id e))))}))
(defn await-idle!
  "Command-side completion barrier. Queries never call this or write anything.
   Wait on durable evidence, not an elapsed grace period or a stable event count."
  [ctx]
  (let [deadline (+ (System/nanoTime) (* 30 1000000000))]
    (loop []
      (let [events (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))
            pending (pending-work (:processor-topics ctx) events)]
        (when (some seq (vals pending))
          (when (> (System/nanoTime) deadline)
            (throw (ex-info "Grain processors did not finish before the command response" pending)))
          (Thread/sleep 10)
          (recur))))))
(defn stop! [ctx]
  (doseq [p (:processors ctx)] (tp/stop p))
  (pub/stop (:event-pubsub ctx)) (es/stop (:event-store ctx)) (kv/stop (:cache ctx))
  (u/remove-tree (:cache-dir ctx)))
