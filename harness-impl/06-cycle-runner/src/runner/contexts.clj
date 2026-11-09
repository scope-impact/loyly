(ns runner.contexts
  (:require [runner.util :as u] [runner.model :as model] [clojure.java.io :as io] [clojure.string :as str]
            [ai.obney.grain.event-store-v3.interface :as es])
  (:import [java.lang ProcessBuilder$Redirect]))
(def names ["specification" "cycle-preparation" "grader-lifecycle" "regeneration-cycle" "measurement"])
(def probes {"specification" "specification/stamped-items" "cycle-preparation" "cycle-preparation/source-cuts"
             "grader-lifecycle" "grader-lifecycle/instrument-goldens" "regeneration-cycle" "regeneration-cycle/cycle-progress"
             "measurement" "measurement/measurements-to-report"})
(def contracts (delay (into {} (for [n names] [n (get (model/load-model n) (keyword n))]))))
(defn foreign-events [file]
  (if-not (.isFile (io/file file)) []
    (let [store (es/start {:conn {:type :sqlite :database-file file}})]
      (try (vec (mapcat (fn [t] (into [] (es/read store {:tenant-id t}))) (keys (es/tenants store))))
           (finally (es/stop store))))))
(defn stop! [clients] (doseq [[_ c] clients] (when-let [p (:process c)] (u/destroy p))))
(defn call [clients context method params]
  (let [{:keys [process writer reader lock]} (get clients context)]
    (when-not process (throw (ex-info (str context " not launched") {:reason "context-did-not-respond"})))
    (locking lock
      (let [id (str (random-uuid))
            task (future
                   (.write ^java.io.Writer writer (str (u/encode {:jsonrpc "2.0" :id id :method method :params params}) "\n"))
                   (.flush ^java.io.Writer writer)
                   (when-let [line (.readLine ^java.io.BufferedReader reader)] (u/decode line)))
            response (try (deref task 60000 ::timeout) (catch Exception _ nil))]
        (when-not (and (map? response) (= id (:id response)) (or (contains? response :result) (:error response)))
          (u/destroy process)
          (throw (ex-info (str context " did not respond to " method) {:reason "context-did-not-respond"})))
        response))))
(defn launch! [launchers dir env]
  (into {} (for [n names]
             [n (try
                  (let [entry (get launchers (keyword n))
                        store (or (when (= n "measurement") (not-empty (get env "LOYLY_MEASUREMENT_STORE"))) (u/path dir (str n ".sqlite")))
                        _ (io/make-parents store)
                        baseline (set (map :event/id (foreign-events store)))
                        pb (ProcessBuilder. ^java.util.List [entry])
                        _ (.redirectError pb ProcessBuilder$Redirect/INHERIT)
                        _ (doseq [[k v] (assoc env "LOYLY_GRAIN_STORE" store)] (when v (.put (.environment pb) k v)))
                        p (.start pb)]
                    {:process p :writer (io/writer (.getOutputStream p)) :reader (io/reader (.getInputStream p))
                     :lock (Object.) :store store :baseline baseline})
                  (catch Exception _ nil))])))
(defn responding [clients]
  (->> names (mapv (fn [n] (future (try (call clients n (probes n) {}) nil (catch Exception _ n)))))
       (mapv deref) (remove nil?) vec))
(defn handoff-types [producer]
  ;; The explicit handoff contract, not an event reply, defines the relay stream.
  (let [file (str "../../.pi/spec/" producer "/event-model/handoffs.edn")]
    (set (keys (get-in (clojure.edn/read-string (slurp file)) [(keyword producer) :events])))))
(defn consumers [type]
  (for [n names :when (and (not= n (namespace type)) (some #(contains? (:consumes %) type) (vals (get-in @contracts [n :read-models]))))] n))
(defn relay! [clients seen record-fn run-id]
  (loop []
    (let [changed (atom false)]
      (doseq [producer names
              :let [c (get clients producer) types (handoff-types producer)
                    events (->> (foreign-events (:store c)) (filter #(and (contains? types (:event/type %)) (not (contains? (:baseline c) (:event/id %)))))) ]
              [index event] (map-indexed vector events)
              consumer (consumers (:event/type event))
              :let [key [producer (:event/id event) consumer]]
              :when (not (contains? @seen key))]
        (let [type (:event/type event)
              params (into {} (remove (fn [[k _]] (= "event" (namespace k))) event))
              reply (call clients consumer (subs (str type) 1) (u/normalize params))]
          (record-fn {:run-id run-id :handoff-type (subs (str type) 1) :producer producer :position (inc index) :consumer consumer
                      :outcome (if (:error reply) "refused" "accepted")})
          (swap! seen conj key) (reset! changed true)))
      (when @changed (recur)))))
