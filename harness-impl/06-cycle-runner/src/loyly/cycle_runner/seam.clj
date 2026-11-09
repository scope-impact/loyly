(ns loyly.cycle-runner.seam
  "The public seam: JSON-RPC 2.0 over stdio, one method per command the
   operator issues and per query of the event model (seam.md)."
  (:require [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.time.interface :as time]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.cycle-runner.advance :as advance]
            [loyly.cycle-runner.blocks :as blocks]
            [loyly.cycle-runner.contexts :as contexts]
            [loyly.cycle-runner.effects :as effects]
            [loyly.cycle-runner.model :as model]
            [loyly.cycle-runner.orc :as orc]
            [loyly.cycle-runner.repo :as repo]
            [loyly.cycle-runner.runtime :as runtime]
            [loyly.cycle-runner.util :as u]
            [malli.core :as m]
            [malli.error :as me]))

;; What is handed in is read when start-run runs, never only at boot. The
;; environment is the process's own; a suite running the product in its own
;; process hands it in here instead.
(defonce env-source (atom (fn [] (into {} (System/getenv)))))

(defn- error [code message & [data]]
  {:error (cond-> {:code code :message message} data (assoc :data data))})

(defn- refusal [r]
  (error -32000 (or (:cognitect.anomalies/message r) "refused")
         (merge {:refusal (:cycle-runner/refusal r)} (:cycle-runner/data r))))

(defn- as-event [e]
  (let [t (:event/type e)]
    (into {:type (str (namespace t) "/" (name t))}
          (remove (fn [[k _]] (= "event" (namespace k)))) e)))

(defn- valid-params [schema-key params]
  (let [schema (or (model/command-schema schema-key) (model/query-schema schema-key))]
    (when-not (m/validate schema params)
      (error -32602 "Invalid params" {:explain (me/humanize (m/explain schema params))}))))

;; ---------------------------------------------------------------------------
;; start-run

(defn- read-contexts-file [path]
  (try
    (let [m (u/<-json (slurp (str path)))]
      (if (map? m) (into {} (for [[k v] m] [(name k) v])) {}))
    (catch Exception _ {})))

(defn- harness-digest [config-bytes self-check-cmd probe-bytes stdio? store-check-cmd]
  (str "sha256:"
       (u/sha256-hex (u/concat-bytes config-bytes
                                     (u/utf8 (or self-check-cmd ""))
                                     probe-bytes
                                     (when stdio? (u/utf8 (or store-check-cmd "")))))))

(defn- prepare!
  "Read what is handed in, read the cut, write the contexts' fixtures and start
   the five contexts for the run."
  [params env]
  (let [{:keys [run-id commit budget grader-digests selection-id cut-id cycle-id]} params
        repo-path (get env "LOYLY_PROJECT_REPO")
        runs-dir (or (not-empty (get env "LOYLY_RUNS_DIR"))
                     (str (System/getProperty "java.io.tmpdir") "/loyly-runs"))
        run-dir (u/abs-path (str runs-dir "/" (u/safe-name run-id)))
        _ (u/delete-tree! run-dir)
        _ (u/mkdirs! run-dir)
        config-path (get env "LOYLY_HARNESS_CONFIG")
        config-bytes (try (u/read-bytes config-path) (catch Exception _ (byte-array 0)))
        config (repo/config-of (String. ^bytes config-bytes "UTF-8"))
        slices (repo/plan-slices repo-path commit config)
        tree (repo/tree-of repo-path commit)
        commits-file (str run-dir "/fixtures/project-commits.json")
        digests-file (str run-dir "/fixtures/scenario-digests.json")
        _ (u/spit-utf8 commits-file (u/->json {(keyword commit) tree}))
        _ (u/spit-utf8 digests-file (u/->json (into {} (for [[k v] (repo/scenario-digests slices)] [k v]))))
        probe (effects/probe-at-cut! {:repo repo-path :commit commit :app-probe (:app-probe config)}
                                     (str run-dir "/probe"))
        self-check-cmd (get env "LOYLY_SELF_CHECK_CMD")
        store-check-cmd (get env "LOYLY_STORE_CHECK_CMD")
        instruction (let [i (get env "LOYLY_BUILDER_INSTRUCTION" "")]
                      (if (and (not (str/blank? i)) (not (str/includes? i "\n")) (.isFile (io/file i)))
                        (slurp i)
                        i))
        base-env (cond-> (assoc (u/strip-env env)
                                "LOYLY_PROJECT_COMMITS" commits-file
                                "LOYLY_SCENARIO_DIGESTS" digests-file)
                   (get env "LOYLY_SCORING_TOOL_PATHS")
                   (assoc "LOYLY_SCORING_TOOL_PATHS" (get env "LOYLY_SCORING_TOOL_PATHS")))
        {:keys [contexts not-responding]} (contexts/start-all! (read-contexts-file (get env "LOYLY_CONTEXTS"))
                                                               run-dir base-env)]
    {:run-id run-id :cycle-id cycle-id :cut-id cut-id :commit commit :selection-id selection-id
     :budget budget :grader-digests (vec grader-digests)
     :run-dir run-dir :repo repo-path :env env
     :config config :config-bytes config-bytes
     :slices slices :checklist (repo/checklist-reading slices)
     :probe probe :probe-digest (str "sha256:" (u/sha256-hex (or (:bytes probe) (byte-array 0))))
     :harness-digest (harness-digest config-bytes self-check-cmd (or (:bytes probe) (byte-array 0))
                                     (= "stdio" (:app-seam config)) store-check-cmd)
     :self-check-cmd self-check-cmd :store-check-cmd store-check-cmd
     :builder-cmd (get env "LOYLY_BUILDER_CMD") :builder-instruction instruction
     :contexts contexts :not-responding not-responding
     :stores (atom {}) :attempts (atom {}) :refusals (atom {})}))

(defn- start-run [sys params]
  (or (valid-params :cycle-runner/start-run params)
      (let [{:keys [run-id cycle-id commit]} params
            env (@env-source)
            all (vals (blocks/runs sys))
            early-refusal? (or (some #(= run-id (:run-id %)) all)
                               (some #(blocks/in-progress (:status %)) all)
                               (some #(= cycle-id (:cycle-id %)) all)
                               (not (repo/commit? (get env "LOYLY_PROJECT_REPO") commit)))
            rt (if early-refusal?
                 {:repo (get env "LOYLY_PROJECT_REPO") :pending? true}
                 (prepare! params env))
            existing (runtime/get-run run-id)
            _ (when-not existing (runtime/put-run! run-id rt))
            t0 (System/nanoTime)
            r (try
                (orc/issue! sys "cycle-runner/run-to-gate"
                            (merge {:command/name :cycle-runner/start-run}
                                   (select-keys params [:run-id :cycle-id :cut-id :commit :selection-id
                                                        :budget :grader-digests])))
                (catch Exception e
                  (u/log "start-run failed:" (.getMessage e))
                  {:cognitect.anomalies/category :cognitect.anomalies/fault
                   :cognitect.anomalies/message (.getMessage e)}))]
        (if (:cognitect.anomalies/category r)
          (do (when-not existing
                (runtime/forget-run! run-id)
                (when-let [cs (:contexts rt)] (contexts/stop-all! cs))
                (when-let [d (:run-dir rt)] (u/delete-tree! d)))
              (if (:cycle-runner/refusal r)
                (refusal r)
                (error -32603 (str (:cognitect.anomalies/message r)))))
          (do (advance/wait-for-rest run-id t0 some?)
              {:result {:events (mapv as-event (:command-result/events r))}})))))

;; ---------------------------------------------------------------------------
;; hand-in-verdicts

(defn- hand-in-verdicts [sys params]
  (or (valid-params :cycle-runner/hand-in-verdicts params)
      (let [run-id (:run-id params)
            t0 (System/nanoTime)
            r (orc/issue! sys "cycle-runner/verdicts-to-conclusion"
                          (merge {:command/name :cycle-runner/hand-in-verdicts}
                                 (select-keys params [:run-id :verdicts-path :grader-digest])))]
        (if (:cognitect.anomalies/category r)
          (if (:cycle-runner/refusal r) (refusal r) (error -32603 (str (:cognitect.anomalies/message r))))
          (do (advance/wait-for-rest run-id t0 #(some? (:verdicts-digest %)))
              {:result {:events (mapv as-event (:command-result/events r))}})))))

;; ---------------------------------------------------------------------------
;; queries

(defn- query [sys qname params]
  (or (valid-params qname params)
      (let [r (qp/process-query (assoc sys :query (merge {:query/name qname :query/id (random-uuid)
                                                          :query/timestamp (time/now)}
                                                         params)))]
        (if (:cognitect.anomalies/category r)
          (error -32603 (str (:cognitect.anomalies/message r)))
          {:result (:query/result r)}))))

(def method-table
  {"cycle-runner/start-run" start-run
   "cycle-runner/hand-in-verdicts" hand-in-verdicts
   "cycle-runner/run-progress" #(query %1 :cycle-runner/run-progress %2)
   "cycle-runner/runs-to-advance" #(query %1 :cycle-runner/runs-to-advance %2)})

(defn handle
  "One request line in, one response line out (nil for a notification)."
  [sys line]
  (let [req (try (u/<-json line) (catch Exception _ ::bad))]
    (if (or (= ::bad req) (not (map? req)))
      (u/->json {:jsonrpc "2.0" :id nil :error {:code -32700 :message "Parse error"}})
      (let [id (:id req)
            method (:method req)
            params (or (:params req) {})
            f (get method-table method)
            reply (cond
                    (nil? f) (error -32601 (str "Method not found: " method))
                    (not (map? params)) (error -32602 "Invalid params: an object is expected")
                    :else (try (f sys params)
                               (catch Exception e
                                 (u/log "method" method "failed:" (.getMessage e))
                                 (.printStackTrace e)
                                 (error -32603 (str "Internal error: " (.getMessage e))))))]
        (u/->json (merge {:jsonrpc "2.0" :id id} reply))))))
