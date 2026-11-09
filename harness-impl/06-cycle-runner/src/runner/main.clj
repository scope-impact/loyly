(ns runner.main
  (:require [runner.util :as u] [runner.model :as model] [runner.runtime :as rt] [runner.contexts :as contexts] [runner.effects :as effects]
            [clojure.java.io :as io] [clojure.string :as str] [malli.core :as m]
            [ai.obney.grain.query-processor.interface :as qp]))
(def sessions (atom {}))
(declare advance! hand-in!)
(defn processor [ctx]
  (let [e (:event ctx)]
    (if (#{:cycle-runner/run-started :cycle-runner/verdicts-handed-in} (:event/type e))
      {:result/effect (fn [] (try (advance! ctx (:run-id e) (= :cycle-runner/verdicts-handed-in (:event/type e))) (finally (deliver (get-in @sessions [(:run-id e) :done]) true)))) :result/checkpoint :after}
      {})))
(defn boot! [] (rt/start! (System/getenv "LOYLY_GRAIN_STORE") processor))
(defn halt! [ctx id effect reason detail]
  (rt/workflow! ctx "cycle-runner/effect-not-performed-halts-run"
                #(model/emit-command ctx :cycle-runner/halt-run {:run-id id :effect effect :reason reason :detail detail})))
(defn config [file]
  (into {} (keep (fn [line] (when-let [[_ k v] (re-matches #"\s*([A-Z_]+)\s*=\s*(.*?)\s*" line)]
                             [(keyword k) (str/replace v #"^['\"]|['\"]$" "")])) (str/split-lines (slurp file)))))
(defn read-env [] (into {} (for [[k v] (System/getenv) :when (str/starts-with? k "LOYLY_")] [k v])))
(defn wait-run [ctx id]
  (loop [] (let [r (get (model/runs ctx) id)] (if (#{"running" "grading"} (:status r)) (do (Thread/sleep 10) (recur)) r))))
(defn start! [ctx p]
  (let [runs (model/runs ctx) env (read-env) repo (get env "LOYLY_PROJECT_REPO")]
    (when (contains? runs (:run-id p)) (u/refuse "run-already-started"))
    (when (some #(#{"running" "grading" "awaiting-verdicts"} (:status %)) (vals runs)) (u/refuse "run-in-progress"))
    (when (some #(= (:cycle-id p) (:cycle-id %)) (vals runs)) (u/refuse "cycle-already-run"))
    (when-not (and repo (zero? (:exit (u/git repo "cat-file" "-e" (str (:commit p) "^{commit}"))))) (u/refuse "not-a-commit"))
    (let [dir (u/mkdir (u/path (get env "LOYLY_RUNS_DIR") (:run-id p)))
          tree (u/git! repo "rev-parse" (str (:commit p) "^{tree}"))
          commits (u/json-file (u/path dir "commits.json") {(:commit p) tree})
          digests (u/json-file (u/path dir "digests.json") {})
          clients (contexts/launch! (try (u/read-json (get env "LOYLY_CONTEXTS")) (catch Exception _ {})) dir
                                    (assoc env "LOYLY_PROJECT_COMMITS" commits "LOYLY_SCENARIO_DIGESTS" digests))
          missing (contexts/responding clients)]
      (when (seq missing) (contexts/stop! clients) (u/refuse "contexts-not-responding" {:contexts missing}))
      (let [cfg (config (get env "LOYLY_HARNESS_CONFIG")) image (:BUILDER_IMAGE cfg)]
        (when (and (not (str/blank? image)) (not (.isDirectory (io/file (or (get env "LOYLY_BUILDER_IMAGES") "") image))))
          (contexts/stop! clients) (u/refuse "builder-image-missing"))
        (swap! sessions assoc (:run-id p) {:params p :env env :config cfg :dir dir :clients clients :tree tree :digests digests :seen (atom #{}) :done (promise)})
        (let [events (atom nil)]
          (rt/workflow! ctx "cycle-runner/run-to-gate" #(reset! events (model/emit-command ctx :cycle-runner/start-run p)))
          @(get-in @sessions [(:run-id p) :done])
          (wait-run ctx (:run-id p))
          (rt/await-idle! ctx)
          {:events (mapv #(assoc (model/body %) :type (subs (str (:event/type %)) 1)) @events)})))))
(defn advance! [ctx id after?]
  (let [s (assoc (get @sessions id) :effect (atom (if after? "file-verdicts" "stamp")))]
    (try
      (rt/workflow! ctx (if after? "cycle-runner/verdicts-to-conclusion" "cycle-runner/run-to-gate")
                    (fn []
                      (try ((if after? effects/verdicts-to-conclusion effects/run-to-gate) ctx s)
                           (catch Exception e
                             (when-not (:ended (ex-data e))
                               (halt! ctx id @(:effect s) (or (:reason (ex-data e)) "effect-could-not-run") (.getMessage e))
                               (effects/stop s))))))
      (catch Exception e
        (binding [*out* *err*] (println "advance failed" (.getMessage e) (ex-data e)))
        (when (#{"running" "grading"} (get-in (model/runs ctx) [id :status]))
          (halt! ctx id @(:effect s) "effect-could-not-run" (.getMessage e)))))))
(defn hand-in! [ctx p]
  (let [r (get (model/runs ctx) (:run-id p)) s (get @sessions (:run-id p))]
    (when-not r (u/refuse "no-run"))
    (when-not (some #(some (fn [e] (= "regeneration-cycle/gate-attempt-passed" (:type e))) (:events %)) (:effect-results (model/progress ctx (:run-id p)))) (u/refuse "gate-not-passed"))
    (when (:verdicts-digest r) (u/refuse "verdicts-already-handed-in"))
    (when-not (= "awaiting-verdicts" (:status r)) (u/refuse "run-not-awaiting-verdicts"))
    (when-not (some #{(:grader-digest p)} (get-in s [:params :grader-digests])) (u/refuse "grader-not-approved"))
    (let [vs (try (u/read-json (:verdicts-path p)) (catch Exception _ nil))]
      (when-not (and (vector? vs) (every? map? vs)) (u/refuse "verdicts-not-readable"))
      (swap! sessions update (:run-id p) assoc :done (promise) :verdicts vs :grader-digest (:grader-digest p))
      (let [ev (atom nil)]
        (rt/workflow! ctx "cycle-runner/verdicts-to-conclusion"
                      #(reset! ev (model/emit-command ctx :cycle-runner/hand-in-verdicts (assoc p :verdicts-digest (u/digest (slurp (:verdicts-path p))) :verdicts (count vs)))))
        @(get-in @sessions [(:run-id p) :done])
        (wait-run ctx (:run-id p))
        (rt/await-idle! ctx)
        {:events (mapv #(assoc (model/body %) :type (subs (str (:event/type %)) 1)) @ev)}))))
(def public #{"cycle-runner/start-run" "cycle-runner/hand-in-verdicts" "cycle-runner/run-progress" "cycle-runner/runs-to-advance"})
(defn request [ctx {:keys [id method params]}]
  (merge {:jsonrpc "2.0" :id id}
    (try
      (when-not (public method) (u/fault -32601 "Unknown method"))
      (let [k (keyword method) schema (or (get-in model/area [:commands k :schema]) (get-in model/area [:queries k :schema]))]
        (when-not (m/validate schema (or params {})) (u/fault -32602 "Invalid params")))
      {:result (case method
                 "cycle-runner/start-run" (start! ctx params)
                 "cycle-runner/hand-in-verdicts" (hand-in! ctx params)
                 (:query/result (qp/process-query (assoc ctx :query (merge params {:query/name (keyword method) :query/id (random-uuid) :query/timestamp (java.time.OffsetDateTime/now)})))))}
      (catch Exception e
        (when-not (:rpc-code (ex-data e)) (binding [*out* *err*] (println (.getMessage e) (ex-data e))))
        {:error {:code (get (ex-data e) :rpc-code -32001) :message (.getMessage e) :data (:data (ex-data e))}}))))
(defn -main [& _]
  (let [ctx (boot!)]
    (try (doseq [line (line-seq (io/reader *in*))]
           (let [response (try (request ctx (u/decode line)) (catch Exception _ {:jsonrpc "2.0" :id nil :error {:code -32700 :message "Parse error"}}))]
             (println (u/encode response)) (flush)))
         (finally (doseq [s (vals @sessions)] (contexts/stop! (:clients s))) (rt/stop! ctx) (shutdown-agents)))))
