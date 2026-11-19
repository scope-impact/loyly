(ns loyly.main
  (:require [loyly.domain :as domain] [loyly.cycle :as cycle] [loyly.instrument :as instrument] [loyly.commands :as commands] [loyly.views :as views] [ai.obney.grain.read-model-processor-v2.interface :as rm] [loyly.flow :as flow]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [malli.core :as m]
            [ai.obney.grain.event-model.interface :as model]
            [ai.obney.grain.event-store-v3.interface :as store]
            [ai.obney.grain.event-store-sqlite-v3.interface]))
(defn merge-models [a b]
  (if (and (map? a) (map? b)) (merge-with merge-models a b) b))
(declare progress)
(defn open! [path environment]
  (let [models (reduce merge-models {} (for [file (file-seq (io/file "event-model"))
                                             :when (str/ends-with? (.getName file) ".edn")]
                                         (edn/read-string (slurp file))))]
    (doseq [[area definitions] models] (model/register-event-model! area definitions))
    (domain/register! models (merge domain/handlers cycle/handlers instrument/handlers))
    (views/register! models progress)
    (let [sys (flow/attach {:starting (atom nil) :models models :environment environment :tenant-id #uuid "00000000-0000-0000-0000-000000000000"
     :event-store (store/start {:conn {:type :sqlite :database-file path}})})]
      (assoc sys :recovery (flow/resume! sys)))))
(defn close! [system] (flow/stop system) (store/stop (:event-store system)))
(defn event-rows [system types]
  (into [] (store/read (:event-store system) {:tenant-id (:tenant-id system) :types types})))
(defn config [path]
  (into {} (for [line (str/split-lines (slurp path))
                 :let [[_ key value] (re-matches #"\s*([A-Z_]+)\s*=\s*(.*?)\s*" line)]
                 :when key]
             [key (str/replace value #"^([\"'])(.*)\1$" "$2")])))
(defn refuse [reason] (throw (ex-info reason {:code -32000 :data {:refusal reason}})))
(def handed-keys (mapv #(str "LOYLY_" %) ["RUNS_DIR" "PROJECT_REPO" "HARNESS_CONFIG" "SELF_CHECK_CMD" "STORE_CHECK_CMD" "BUILDER_HOST"
                                            "GATE_CMD" "BUILDER_IMAGES" "BUILDER_INSTRUCTION" "MODEL_API_BASE" "RENDER_MODEL" "GRADER_MODEL"
                                            "GRADER_RUBRIC" "GRADER_IMAGE" "SCORING_TOOL_PATHS"]))
(defn start-run [system p]
  (let [env ((:environment system)) reservation (:starting system)]
    (locking reservation
      (let [rows (vals (rm/project system :cycle-runner/runs))]
        (when (or (= (:run-id p) (:run-id @reservation)) (some #(= (:run-id p) (:run-id %)) rows)) (refuse "run-already-started"))
        (when (or @reservation (some #(= "running" (:status %)) rows)) (refuse "run-in-progress"))
        (when (some #(= (:cycle-id p) (:cycle-id %)) rows) (refuse "cycle-already-run"))
        (when-not (zero? (:exit (shell/sh "git" "-C" (env "LOYLY_PROJECT_REPO") "cat-file" "-e" (str (:commit p) "^{commit}")))) (refuse "not-a-commit"))
        (let [image ((config (env "LOYLY_HARNESS_CONFIG")) "BUILDER_IMAGE")]
          (when (and (seq image) (not (.isDirectory (io/file (env "LOYLY_BUILDER_IMAGES") image)))) (refuse "builder-image-missing")))
        (reset! reservation p)))
    (try
      (flow/execute system {:env (select-keys env handed-keys) :config (config (env "LOYLY_HARNESS_CONFIG")) :p p})
      {:events (mapv domain/public (filter #(= (:run-id p) (:run-id %)) (domain/rows system :cycle-runner/run-started)))}
      (finally (reset! reservation nil)))))
(defn tick-data [sys id]
  (let [root (flow/tick-id sys id) starts (domain/rows sys :sheet/tree-tick-started)
        ids (reduce (fn [ids e] (if (ids (:parent-tick-id e)) (conj ids (:tick-id e)) ids)) #{root} starts)
        ticks (filter #(ids (:tick-id %)) starts)
        names (into {} (mapcat #(get-in % [:execution-snapshot :nodes-by-id]) ticks))
        lifecycle (filter #(ids (:tick-id %)) (domain/rows sys :sheet/node-execution-started :sheet/node-execution-completed))
        finished (filter #(= :sheet/node-execution-completed (:event/type %)) lifecycle)
        active (vals (reduce (fn [m e] ((if (= :sheet/node-execution-started (:event/type e)) #(assoc %1 %2 e) dissoc) m [(:tick-id e) (:node-id e)])) {} lifecycle))
        nodes (mapv (fn [e] {:name (get-in names [(:node-id e) :name]) :status (name (or (:status e) :running))}) (concat finished active))
        terminal (some #(when (= root (:tick-id %)) %) (domain/rows sys :sheet/tree-tick-completed))]
    {:tick {:tick-id (str root) :status (name (or (:root-status terminal) (:status terminal) :running)) :nodes nodes}
     :scores (mapv (fn [e] {:item-id (last (str/split (:judge-name e) #"/")) :verdict (if (pos? (:score e)) "pass" "fail") :reason (:feedback e)})
                   (filter #(ids (:tick-id %)) (domain/rows sys :judge/score-emitted)))}))
(defn progress [system {:keys [run-id]}]
  (let [runs (rm/project system :cycle-runner/runs) effects (rm/project system :cycle-runner/effect-results)
        enrich (fn [r] (merge r {:effect-results (get effects (:run-id r) [])} (tick-data system (:run-id r))))]
    (if run-id (when-let [r (get runs run-id)] {:run (enrich r)})
        {:runs (mapv #(enrich (runs (:run-id %))) (domain/rows system :cycle-runner/run-started))})))
(defn route [sys k]
  (let [area (get-in sys [:models (some-> k namespace keyword)])]
    (cond (= :cycle-runner/start-run k) [:commands start-run]
          (get-in area [:queries k]) [:queries (fn [sys p] (views/read-query sys k p))]
          (and (not= "cycle-runner" (namespace k)) (get-in area [:commands k]))
          [:commands (fn [sys p] (let [r (commands/invoke sys k p)] (if (:refusal r) (refuse (:refusal r)) r)))])))
(defn respond [system {:keys [id method params]}]
  (let [reply {:jsonrpc "2.0" :id id} k (keyword method)]
    (try
      (let [[kind f] (or (route system k) (throw (ex-info "Unknown method" {:code -32601})))
            schema (get-in system [:models (keyword (namespace k)) kind k :schema])]
        (when-not (m/validate schema params) (throw (ex-info "Invalid params" {:code -32602})))
        (assoc reply :result (f system params)))
      (catch Exception e (assoc reply :error (merge {:code -32603 :message (.getMessage e)} (ex-data e)))))))
(defn -main [& _]
  (let [system (open! (System/getenv "LOYLY_GRAIN_STORE") #(into {} (System/getenv)))
        workers (java.util.concurrent.Executors/newCachedThreadPool)
        out *out*]
    (try
      (doseq [line (line-seq (java.io.BufferedReader. *in*))]
        (.submit workers ^Runnable
                 (fn []
                   (let [reply (try (respond system (json/read-str line :key-fn keyword))
                                    (catch Exception _ {:jsonrpc "2.0" :id nil :error {:code -32700 :message "Invalid JSON"}}))]
                     (locking out (.write out (str (json/write-str reply) "\n")) (.flush out))))))
      (finally (.shutdown workers) (.awaitTermination workers 1 java.util.concurrent.TimeUnit/DAYS)
               (close! system) (shutdown-agents)))))
