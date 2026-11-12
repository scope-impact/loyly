(ns runner.contexts
  (:require [runner.util :as u] [runner.model :as model]
            [clojure.java.io :as io] [clojure.string :as str] [clojure.edn :as edn]
            [ai.obney.grain.event-store-v3.interface :as es]))
(def names ["specification" "cycle-preparation" "grader-lifecycle" "regeneration-cycle" "measurement"])
(def queries {"specification" "specification/stamped-items" "cycle-preparation" "cycle-preparation/source-cuts" "grader-lifecycle" "grader-lifecycle/instrument-goldens" "regeneration-cycle" "regeneration-cycle/cycle-progress" "measurement" "measurement/checklist"})
(def contracts (delay (into {} (map (fn [c] [c (get (model/load-model c) (keyword c))]) names))))
(defn rpc [context method params ms]
  (locking (:lock context)
    (let [id (str (random-uuid))
          task (future
                 (try
                   (.write ^java.io.Writer (:in context) (str (u/encode {:jsonrpc "2.0" :id id :method method :params params}) "\n"))
                   (.flush ^java.io.Writer (:in context))
                   (when-let [s (.readLine ^java.io.BufferedReader (:out context))]
                     (let [r (u/decode s)]
                       (when-not (= id (:id r)) (.println System/err (str "Context response id mismatch: " s " expected " id)))
                       (when (= id (:id r)) r)))
                   (catch Throwable t (.println System/err (str "Context transport error: " (.getMessage t))) nil)))
          reply (deref task ms nil)]
      (when-not reply (future-cancel task) (u/stop-process (:process context)))
      reply)))
(defn call [session name method params]
  (or (rpc (get-in session [:contexts name]) method params 15000)
      (throw (ex-info (str name " did not respond to " method) {:reason "context-did-not-respond"}))))
(defn stop [session]
  (doseq [[_ c] (:contexts session)]
    (u/stop-process (:process c))
    (when-let [s (:store c)] (es/stop s))))
(defn start [env params dir slices tree]
  (let [entrypoints (try (u/decode (slurp (env "LOYLY_CONTEXTS"))) (catch Throwable _ {}))
        commits (u/put (str dir "/commits.json") (u/encode {(:commit params) tree}))
        digests (u/put (str dir "/scenarios.json") (u/encode (into {} (for [slice slices item (:items slice) key (cond-> [(:title item)] (:item-id item) (conj (:item-id item) (str "@item-" (:item-id item))))] [key (:scenario-digest item)]))))
        launch (fn [name]
                 (try
                   (when-let [entry (get entrypoints (keyword name))]
                     (let [_ (.println System/err (str "Starting context " name " via " entry))
                           path (if (and (= name "measurement") (not (str/blank? (env "LOYLY_MEASUREMENT_STORE")))) (env "LOYLY_MEASUREMENT_STORE") (str dir "/" name ".sqlite"))
                           p (u/launch [entry] nil {"LOYLY_GRAIN_STORE" path "LOYLY_PROJECT_COMMITS" commits "LOYLY_SCENARIO_DIGESTS" digests "LOYLY_SCORING_TOOL_PATHS" (env "LOYLY_SCORING_TOOL_PATHS")} false)
                           _ (.println System/err (str "Context " name " pid " (.pid p)))
                           _ (future (with-open [r (.getErrorStream p) w (io/output-stream (str dir "/" name ".stderr"))] (io/copy r w)))
                           c {:process p :in (io/writer (.getOutputStream p)) :out (io/reader (.getInputStream p)) :path path :lock (Object.)}
                           reply (rpc c (queries name) (case name "regeneration-cycle" {:cycle-id (:cycle-id params)} "measurement" {:cut-id (:cut-id params)} {}) 20000)]
                       (if reply c (do (.println System/err (str "Context " name " did not respond during start")) (u/stop-process p) nil))))
                   (catch Throwable t (.printStackTrace t System/err) nil)))
        launched (mapv (fn [n] [n (future (launch n))]) names)
        contexts (into {} (map (fn [[n f]] [n @f]) launched))
        missing (vec (filter #(nil? (contexts %)) names))]
    (if (seq missing)
      (do (stop {:contexts (into {} (remove (comp nil? val) contexts))}) {:missing missing})
      {:contexts
       (into {} (for [[n c] contexts]
                  (let [store (es/start {:conn {:type :sqlite :database-file (:path c)}})
                        tenants (keys (es/tenants store))
                        tenant (or (first tenants) #uuid "00000000-0000-0000-0000-000000000000")
                        events (vec (es/read store {:tenant-id tenant}))]
                    [n (assoc c :store store :tenant tenant :baseline (set (map :event/id events)) :relayed (atom #{}) :position (atom 0))])))})))
(defn handoff-types [producer]
  (set (filter #(= producer (namespace %)) (keys (get-in (edn/read-string (slurp (io/file "../../.pi/spec" producer "event-model/handoffs.edn"))) [(keyword producer) :events])))))
(defn consumers [type producer]
  (filterv (fn [n] (and (not= n producer) (some #(contains? (:consumes %) type) (vals (:read-models (get @contracts n)))))) names))
(defn relay! [session record!]
  (loop []
    (let [counted (atom 0)]
      (doseq [producer names
              :let [c (get-in session [:contexts producer])]
              e (into [] (es/read (:store c) {:tenant-id (:tenant c) :types (handoff-types producer)}))
              :when (and (not ((:baseline c) (:event/id e))) (not (@(:relayed c) (:event/id e))))]
        (let [position (swap! (:position c) inc) type (:event/type e) params (u/wire (model/payload e))]
          (doseq [consumer (consumers type producer)]
            (let [r (call session consumer (subs (str type) 1) params)]
              (when (and (:error r) (not= -32000 (get-in r [:error :code])))
                (throw (ex-info (get-in r [:error :message]) {:reason "effect-could-not-run"})))
              (record! {:handoff-type (subs (str type) 1) :producer producer :position position :consumer consumer :outcome (if (:error r) "refused" "accepted")})))
          (swap! (:relayed c) conj (:event/id e)) (swap! counted inc)))
      (when (pos? @counted) (recur)))))
