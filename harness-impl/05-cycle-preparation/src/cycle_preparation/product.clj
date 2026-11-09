(ns cycle-preparation.product
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.walk :as walk]
            [ai.obney.grain.event-model.interface :as model]
            [ai.obney.grain.event-model-validator.interface :as guard]
            [ai.obney.grain.schema-util.interface :as schema]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rm]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-v3.interface.event-definition :as definition]
            [ai.obney.grain.kv-store.interface.protocol :as kv]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.interface :as orc]
            [malli.core :as m])
  (:import [java.util Date UUID]))

(defn deep-merge [& xs]
  (apply merge-with (fn [& vs] (if (every? map? vs) (apply deep-merge vs) (last vs))) xs))

(defn load-model []
  (let [dir (io/file "../../.pi/spec/cycle-preparation/event-model")
        files (sort-by #(.getName %) (filter #(.endsWith (.getName %) ".edn") (.listFiles dir)))]
    (when (empty? files) (throw (ex-info "No event model fragments" {})))
    (apply deep-merge (map #(edn/read-string (slurp %)) files))))

;; The cache is disposable. Only Grain's event store holds domain or ORC facts.
(defrecord MemoryCache [state]
  kv/KVStore
  (start [this] this)
  (stop [_] (reset! state {}))
  (get! [_ {:keys [k]}] (get @state (vec k)))
  (put! [_ {:keys [k v]}] (swap! state assoc (vec k) v) nil)
  (put-batch! [_ {:keys [entries]}]
    (swap! state into (map (fn [{:keys [k v]}] [(vec k) v]) entries)) nil))

(defn pinned [ctx] (:cuts (rm/project ctx :cycle-preparation/source-cuts) []))

(defn project-cut [state event]
  (update state :cuts (fnil #(into [(select-keys event [:cut-id :commit :tree-digest :pinned-at])] %) [])))

(defn repository []
  (if-let [path (System/getenv "LOYLY_PROJECT_COMMITS")]
    (try (let [facts (json/read-str (slurp path))] (if (map? facts) facts {}))
         (catch java.io.FileNotFoundException _ {})
         (catch Exception _ {}))
    {}))

(defn refusal [code]
  {:cognitect.anomalies/category :cognitect.anomalies/conflict
   :cognitect.anomalies/message code
   :refusal code})

(defn pin-source-cut [{:keys [command] :as ctx}]
  (let [{:keys [cut-id commit tree-digest]} command
        commits (repository)]
    (cond
      (some #(= cut-id (:cut-id %)) (pinned ctx)) (refusal "cut-already-pinned")
      (not (contains? commits commit)) (refusal "not-a-commit")
      (not= tree-digest (get commits commit)) (refusal "tree-digest-not-the-commits")
      :else
      {:command-result/events
       [(es/->event {:type :cycle-preparation/source-cut-pinned
                    :body {:cut-id cut-id :commit commit :tree-digest tree-digest :pinned-at (Date.)}})]})))

(defn source-cuts [ctx] {:query/result {:cuts (pinned ctx)}})

(defn register-blocks! [area]
  (doseq [[name block] (:commands area)]
    (schema/register! {name (:schema block)})
    (cp/register-command! name pin-source-cut
                          {:grain.event-model/reads (:reads block)
                           :grain.event-model/produces (:produces block)}))
  (doseq [[name block] (:queries area)]
    (schema/register! {name (:schema block)})
    (qp/register-query! name source-cuts {:grain.event-model/reads (:reads block)}))
  (doseq [[name block] (:read-models area)]
    (rm/register-read-model! name project-cut {:events (:consumes block) :schema (:schema block) :version 1}))
  (doseq [[name block] (:events area)]
    (definition/register-event-definition! name (:description block) {:schema (:schema block)}
                                          {:ns (str *ns*) :file *file*})))

(defn wire-event [event]
  (assoc (select-keys event [:cut-id :commit :tree-digest :pinned-at])
         :type (subs (str (:event/type event)) 1)))

(defn command-node [{:keys [inputs] :as ctx}]
  (let [params (or (get inputs :params) (get inputs "params"))
        result (cp/process-command
                 (assoc ctx :command (merge params {:command/name :cycle-preparation/pin-source-cut
                                                   :command/id (random-uuid)
                                                   :command/timestamp (time/now)})))]
    (cond
      (:refusal result) {:response {:error {:code -32000 :message (:refusal result)
                                            :data {:refusal (:refusal result)}}}}
      (:cognitect.anomalies/category result) (throw (ex-info "Command processing failed" result))
      :else {:response {:result {:events (mapv wire-event (:command-result/events result))}}})))

(defn start! []
  (let [event-model (load-model)
        area (:cycle-preparation event-model)
        _ (register-blocks! area)
        _ (model/register-event-model! :cycle-preparation area)
        _ (guard/verify-or-throw!)
        ps (pubsub/start {:type :core-async :topic-fn :event/type})
        path (System/getenv "LOYLY_GRAIN_STORE")
        store (es/start {:conn (if path {:type :sqlite :database-file path} {:type :in-memory})
                         :event-pubsub ps :logger nil})
        ctx {:event-store store :event-pubsub ps
             :cache (->MemoryCache (atom {}))
             :tenant-id (UUID/fromString "00000000-0000-0000-0000-000000000000")}
        processors (mapv (fn [[name {:keys [handler-fn topics]}]]
                           (tp/start {:processor-name name :handler-fn handler-fn :topics topics
                                      :event-pubsub ps :context ctx}))
                         @tp/processor-registry*)
        flow (first (keys (:flows area)))
        sheet-id (orc/build-workflow! ctx
                    (orc/workflow (subs (str flow) 1)
                      (orc/blackboard
                        {:params (get-in area [:commands :cycle-preparation/pin-source-cut :schema])
                         :response [:or
                                    [:map [:result [:map [:events [:vector
                                      [:map [:type :string] [:cut-id :string] [:commit :string]
                                       [:tree-digest :string] [:pinned-at inst?]]]]]]]
                                    [:map [:error [:map [:code :int] [:message :string]
                                                  [:data [:map [:refusal :string]]]]]]]})
                      (orc/sequence "cut-to-fence"
                        (orc/code "pin-source-cut" :fn "cycle-preparation.product/command-node"
                                  :reads [:params] :writes [:response]))))]
    (assoc ctx :processors processors :sheet-id sheet-id :area area)))

(defn all-events [ctx]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)})))

(defn await-trace! [ctx tick-id]
  ;; Completion and trace assembly are separate ORC processors. Do not answer or
  ;; close the store until its trace and terminal event have both arrived.
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (if (some #(and (= :sheet/execution-traced (:event/type %)) (= tick-id (:trace-id %))) (all-events ctx))
        true
        (if (> (System/currentTimeMillis) deadline)
          (throw (ex-info "ORC trace did not settle" {:tick-id tick-id}))
          (do (Thread/sleep 10) (recur)))))))

(defn stop! [ctx]
  (doseq [p (:processors ctx)] (tp/stop p))
  (pubsub/stop (:event-pubsub ctx))
  (es/stop (:event-store ctx))
  (kv/stop (:cache ctx)))

(defn error [code message data] {:error {:code code :message message :data data}})

(defn dispatch [ctx {:keys [method params]}]
  (let [params (if (nil? params) {} params)
        kind (case method "cycle-preparation/pin-source-cut" :commands
                          "cycle-preparation/source-cuts" :queries nil)
        schema (get-in ctx [:area kind (keyword method) :schema])]
    (cond
      (nil? kind) (error -32601 "Unknown method" {})
      (not (m/validate schema params)) (error -32602 "Invalid params" {})
      (= kind :queries)
      (let [result (qp/process-query (assoc ctx :query {:query/name :cycle-preparation/source-cuts
                                                       :query/id (random-uuid) :query/timestamp (time/now)}))]
        (if-let [r (:query/result result)] {:result r} (throw (ex-info "Query failed" result))))
      :else
      (let [tick-id (random-uuid)
            result (orc/execute ctx (:sheet-id ctx) {:params params} :tick-id tick-id :timeout-ms 10000)]
        (when-not (= :success (:status result)) (throw (ex-info "Workflow failed" result)))
        (await-trace! ctx tick-id)
        (or (get-in result [:outputs "response"]) (get-in result [:outputs :response])
            (throw (ex-info "Workflow returned no response" result)))))))

(defn json-value [value]
  (walk/postwalk (fn [x] (if (instance? Date x) (str (.toInstant ^Date x)) x)) value))

(defn respond [ctx line]
  (let [request (try (json/read-str line :key-fn keyword) (catch Exception _ ::parse-error))]
    (if (= request ::parse-error)
      (merge {:jsonrpc "2.0" :id nil} (error -32700 "Parse error" {}))
      (merge {:jsonrpc "2.0" :id (:id request)}
             (try (dispatch ctx request)
                  (catch Exception e
                    (binding [*out* *err*] (println (.getMessage e) (pr-str (ex-data e))))
                    (error -32603 "Internal error" {})))))))

(defn -main [& _]
  (let [stdout *out*
        ctx (binding [*out* *err*] (start!))]
    (try
      (doseq [line (line-seq (java.io.BufferedReader. *in*))]
        (let [response (binding [*out* *err*] (respond ctx line))]
          (binding [*out* stdout] (println (json/write-str (json-value response))) (flush))))
      (finally (binding [*out* *err*] (stop! ctx)) (shutdown-agents)))))
