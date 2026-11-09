(ns loyly.cycle-runner.contexts
  "The five contexts started for one run, each its handed-in launch entrypoint
   on a fresh store, reached over its public seam (JSON-RPC 2.0 over stdio),
   and their stores read for the relay (contract ContextSeams)."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.cycle-runner.model :as model]
            [loyly.cycle-runner.util :as u])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter Writer]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def boot-timeout-ms
  "How long a freshly started context may take to answer its first query."
  (* 120 1000))

(def call-timeout-ms
  "How long one recording may take before the context counts as not responding."
  (* 600 1000))

(def responds-query
  "A query of each context's own seam, asked to see that it responds."
  {"specification" ["specification/stamped-items" {}]
   "cycle-preparation" ["cycle-preparation/source-cuts" {}]
   "grader-lifecycle" ["grader-lifecycle/instrument-goldens" {}]
   "regeneration-cycle" ["regeneration-cycle/cycles-to-conclude" {}]
   "measurement" ["measurement/measurements-to-report" {}]})

(defn- reader-thread [^Process p ^LinkedBlockingQueue q]
  (doto (Thread.
         (fn []
           (try
             (with-open [r (BufferedReader. (InputStreamReader. (.getInputStream p) "UTF-8"))]
               (loop []
                 (when-let [line (.readLine r)]
                   (.put q line)
                   (recur))))
             (catch Exception _ nil)
             (finally (.put q ::eof)))))
    (.setDaemon true)
    (.start)))

(defn start-context!
  "Start one context's launch entrypoint with its environment."
  [name entrypoint env log-file]
  (try
    (let [f (io/file entrypoint)
          argv (if (u/executable? f) [(str f)] ["/bin/sh" (str f)])
          pb (doto (ProcessBuilder. ^java.util.List argv)
               (.redirectError (java.lang.ProcessBuilder$Redirect/appendTo (io/file log-file))))
          _ (when-let [d (.getParentFile (.getAbsoluteFile f))] (.directory pb d))
          m (.environment pb)
          _ (do (.clear m) (doseq [[k v] env] (.put m (str k) (str v))))
          p (.start pb)
          q (LinkedBlockingQueue.)]
      (reader-thread p q)
      {:name name :process p :queue q
       :writer (OutputStreamWriter. (.getOutputStream p) "UTF-8")
       :ids (atom 0) :lock (Object.) :dead (atom false)})
    (catch Exception e
      (u/log "context" name "could not be started:" (.getMessage e))
      {:name name :dead (atom true)})))

(defn call
  "Ask a context one method. Returns {:result r}, {:error e} or
   {:no-response true} (it exited, closed its stdout, or did not answer in time)."
  ([ctx method params] (call ctx method params call-timeout-ms))
  ([{:keys [process queue writer ids lock dead]} method params timeout-ms]
   (if (or (nil? process) @dead)
     {:no-response true}
     (locking lock
       (let [id (swap! ids inc)
             line (u/->json {:jsonrpc "2.0" :id id :method method :params (or params {})})]
         (try
           (.write ^Writer writer (str line "\n"))
           (.flush ^Writer writer)
           (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
             (loop []
               (let [left (- deadline (System/currentTimeMillis))
                     msg (when (pos? left) (.poll ^LinkedBlockingQueue queue left TimeUnit/MILLISECONDS))]
                 (cond
                   (nil? msg) (do (reset! dead true) {:no-response true})
                   (= ::eof msg) (do (reset! dead true) {:no-response true})
                   :else
                   (let [resp (try (u/<-json msg) (catch Exception _ nil))]
                     (cond
                       (not (and (map? resp) (= id (:id resp)))) (recur)
                       (contains? resp :error) {:error (:error resp)}
                       :else {:result (:result resp)}))))))
           (catch java.io.IOException _
             (reset! dead true)
             {:no-response true})))))))

(defn stop-context! [{:keys [^Process process ^Writer writer]}]
  (when process
    (try (.close writer) (catch Exception _ nil))
    (when-not (.waitFor process 2 TimeUnit/SECONDS)
      (u/kill-tree! process))))

(defn start-all!
  "Start the five contexts for a run, each with a fresh store in the run's
   directory, and see which respond. Returns {:contexts {name ctx}
   :not-responding [names]}."
  [entrypoints run-dir base-env]
  (let [started (into {}
                      (for [c model/contexts
                            :let [ep (get entrypoints c)]
                            :when (and (string? ep) (not (str/blank? ep)))]
                        (let [store (str run-dir "/stores/" c ".sqlite")
                              env (assoc base-env "LOYLY_GRAIN_STORE" store)]
                          (u/mkdirs! (str run-dir "/stores"))
                          (u/mkdirs! (str run-dir "/logs"))
                          [c (assoc (start-context! c ep env (str run-dir "/logs/" c ".stderr"))
                                    :store store)])))
        answers (into {}
                      (pmap (fn [[c ctx]]
                              (let [[m p] (responds-query c)]
                                [c (call ctx m p boot-timeout-ms)]))
                            started))
        not-responding (vec (for [c model/contexts
                                  :when (or (not (contains? started c))
                                            (:no-response (get answers c)))]
                              c))]
    {:contexts started :not-responding not-responding}))

(defn stop-all! [contexts]
  (doall (pmap stop-context! (vals contexts))))

;; ---------------------------------------------------------------------------
;; Reading a producer's store

(defn open-store [path]
  (when (and path (u/exists? path))
    (es/start {:conn {:type :sqlite :database-file path} :event-pubsub nil :logger nil})))

(defn close-store [store]
  (when store (try (es/stop store) (catch Exception _ nil))))

(defn handoffs
  "The producer's handoff events in its store, in store order, within its
   tenant: [{:type :fields :position}]."
  [store producer]
  (if-not store
    []
    (let [types (model/handoff-types producer)]
      (if (empty? types)
        []
        (->> (keys (es/tenants store))
             (mapcat (fn [t] (into [] (es/read store {:tenant-id t :types types}))))
             (sort-by (comp str :event/id))
             (map-indexed (fn [i e]
                            {:position (inc i)
                             :type (let [k (:event/type e)] (str (namespace k) "/" (name k)))
                             :fields (into {} (remove (fn [[k _]] (= "event" (namespace k)))) e)}))
             vec)))))
