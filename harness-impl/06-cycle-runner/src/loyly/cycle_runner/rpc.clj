(ns loyly.cycle-runner.rpc
  "The public seam (seam.md): one JSON-RPC 2.0 method per command the operator issues and per
   query, named as the block. A method runs the run before it responds."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.cycle-runner.advance :as advance]
            [loyly.cycle-runner.blocks :as blocks]
            [loyly.cycle-runner.contexts :as contexts]
            [loyly.cycle-runner.model :as model]
            [loyly.cycle-runner.repo :as repo]
            [loyly.cycle-runner.system :as system]
            [loyly.cycle-runner.util :as u]
            [malli.core :as m]
            [malli.error :as me]))

(defn- error [id code message data]
  {:jsonrpc "2.0" :id id :error (cond-> {:code code :message message} data (assoc :data data))})

(defn- ok [id result] {:jsonrpc "2.0" :id id :result result})

(defn- refusal-response [id r]
  (error id -32000 (or (:cognitect.anomalies/message r) (:refusal r))
         (merge {:refusal (:refusal r)} (:refusal/data r))))

(defn event->json
  "An event as the seam shows it: its type and exactly its schema's fields."
  [e]
  (let [t (:event/type e)]
    (assoc (into {} (remove (fn [[k _]] (= "event" (namespace k)))) e)
           :type (str (namespace t) "/" (name t)))))

(defn- params-error [id schema-key params]
  (when-not (m/validate (model/schema-of (if (#{:cycle-runner/run-progress :cycle-runner/runs-to-advance} schema-key)
                                            :queries :commands) schema-key)
                        params)
    (error id -32602 "Params do not match the schema."
           {:explain (me/humanize (m/explain (model/schema-of (if (#{:cycle-runner/run-progress :cycle-runner/runs-to-advance} schema-key)
                                                                 :queries :commands) schema-key)
                                             params))})))

(defn- resolve-path [sys p]
  (when-not (str/blank? p)
    (let [f (io/file p)]
      (if (.isAbsolute f) p
          (u/abs-path (io/file (or (system/env sys "LOYLY_CALLER_DIR") ".") p))))))

(defn- env-snapshot
  "Everything handed in, read now (never only at boot), paths against the caller's directory."
  [sys]
  (let [path-keys #{"LOYLY_RUNS_DIR" "LOYLY_PROJECT_REPO" "LOYLY_HARNESS_CONFIG" "LOYLY_CONTEXTS"}
        ks ["LOYLY_RUNS_DIR" "LOYLY_PROJECT_REPO" "LOYLY_HARNESS_CONFIG" "LOYLY_CONTEXTS"
            "LOYLY_SELF_CHECK_CMD" "LOYLY_BUILDER_CMD" "LOYLY_BUILDER_INSTRUCTION"
            "LOYLY_SCORING_TOOL_PATHS" "LOYLY_CONTEXT_TIMEOUT_SECONDS" "LOYLY_CALL_TIMEOUT_SECONDS"]
        snap (into {} (map (fn [k] (let [v (system/env sys k)]
                                     [k (if (path-keys k) (resolve-path sys v) v)])))
                   ks)]
    (fn [k] (get snap k))))

(defn- context-entrypoints [env]
  (try
    (let [f (env "LOYLY_CONTEXTS")
          m (when (and f (.isFile (io/file f))) (u/parse-json (slurp f)))]
      (into {} (keep (fn [n] (when-let [p (get m (keyword n))]
                               [n (if (.isAbsolute (io/file p)) p
                                      (u/abs-path (io/file (.getParentFile (io/file f)) p)))])))
            contexts/names))
    (catch Exception _ {})))

(defn- start-contexts
  "Start each named context with a fresh store and the run's fixtures; return
   {:clients ... :not-responding [...]}."
  [env run-dir fixtures]
  (let [entry (context-entrypoints env)
        base (into {} (remove (fn [[k _]] (str/starts-with? k "LOYLY_"))) (System/getenv))
        probe-ms (contexts/timeout-ms env "LOYLY_CONTEXT_TIMEOUT_SECONDS" 60)
        started (into {}
                      (pmap (fn [n]
                              (if-let [ep (get entry n)]
                                (try
                                  (let [c (contexts/start-client
                                           n ep
                                           (cond-> (assoc base
                                                          "LOYLY_GRAIN_STORE" (str (io/file run-dir "stores" (str n ".sqlite")))
                                                          "LOYLY_PROJECT_COMMITS" (:commits fixtures)
                                                          "LOYLY_SCENARIO_DIGESTS" (:digests fixtures))
                                             (env "LOYLY_SCORING_TOOL_PATHS")
                                             (assoc "LOYLY_SCORING_TOOL_PATHS" (env "LOYLY_SCORING_TOOL_PATHS")))
                                           (io/file run-dir "logs" (str n ".log")))]
                                    [n {:client c :responds? (contexts/responds? c probe-ms)}])
                                  (catch Exception e
                                    (u/log "context " n " could not start: " (.getMessage e))
                                    [n {:responds? false}]))
                                [n {:responds? false}]))
                            contexts/names))]
    {:clients (into {} (keep (fn [[n {:keys [client]}]] (when client [n client]))) started)
     :not-responding (filterv #(not (get-in started [% :responds?])) contexts/names)}))

(defn- wait-settled [p] @p)

(defn start-run [sys id params]
  (or (params-error id :cycle-runner/start-run params)
      (when-let [r (blocks/start-run-refusal (:ctx sys) params)] (refusal-response id r))
      (let [env (env-snapshot sys)
            repo-path (env "LOYLY_PROJECT_REPO")]
        (if-not (repo/is-commit? repo-path (:commit params))
          (refusal-response id (blocks/refusal "not-a-commit"
                                               (str (:commit params) " is no commit of the project repository "
                                                    repo-path ".")))
          (let [runs-dir (or (env "LOYLY_RUNS_DIR") (u/abs-path "runs"))
                run-dir (io/file runs-dir (str (str/replace (:run-id params) #"[^A-Za-z0-9._-]" "_")
                                               "-" (System/currentTimeMillis)))
                _ (u/mkdirs (io/file run-dir "logs"))
                _ (u/mkdirs (io/file run-dir "fixtures"))
                _ (u/mkdirs (io/file run-dir "stores"))
                base {:env env :run-id (:run-id params) :cycle-id (:cycle-id params)
                      :cut-id (:cut-id params) :commit (:commit params)
                      :selection-id (:selection-id params) :budget (blocks/budget-of params)
                      :grader-digests (:grader-digests params) :dir (str run-dir)}
                session (try (advance/prepare base)
                             (catch Exception e (assoc base :plan [] :stamp-slices [] :config {}
                                                       :prep-error (str "The run cannot be prepared: " (.getMessage e)))))
                commits-file (io/file run-dir "fixtures" "project-commits.json")
                digests-file (io/file run-dir "fixtures" "scenario-digests.json")
                _ (spit commits-file (u/->json {(:commit params) (or (repo/tree-of repo-path (:commit params)) "")}))
                _ (spit digests-file (u/->json (advance/scenario-digests (:plan session))))
                {:keys [clients not-responding]} (start-contexts env run-dir {:commits (str commits-file)
                                                                              :digests (str digests-file)})]
            (if (seq not-responding)
              (do (contexts/stop-all clients)
                  (refusal-response id (blocks/refusal "contexts-not-responding"
                                                       (str "Not responding on their seams: " (str/join ", " not-responding) ".")
                                                       {:contexts not-responding})))
              (let [session (assoc session
                                   :clients clients
                                   :stores (atom {})
                                   :store-paths (into {} (map (fn [n] [n (str (io/file run-dir "stores" (str n ".sqlite")))]))
                                                      contexts/names)
                                   :call-timeout-ms (contexts/timeout-ms env "LOYLY_CALL_TIMEOUT_SECONDS" 600))
                    settled (promise)
                    run-id (:run-id params)]
                (swap! (:sessions sys) assoc run-id session)
                (swap! (:settles sys) assoc run-id settled)
                (let [r (system/issue! sys :cycle-runner/run-to-gate :cycle-runner/start-run params)]
                  (if (blocks/refusal? r)
                    (do (swap! (:sessions sys) dissoc run-id)
                        (swap! (:settles sys) dissoc run-id)
                        (advance/close-session! session)
                        (refusal-response id r))
                    (do (wait-settled settled)
                        (swap! (:settles sys) dissoc run-id)
                        (ok id {:events (mapv event->json (:command-result/events r))})))))))))))

(defn hand-in-verdicts [sys id params]
  (or (params-error id :cycle-runner/hand-in-verdicts params)
      (let [params (update params :verdicts-path #(or (resolve-path sys %) %))]
        (if-let [r (blocks/hand-in-verdicts-refusal (:ctx sys) params)]
          (refusal-response id r)
          (let [settled (promise)
                run-id (:run-id params)]
            (swap! (:settles sys) assoc run-id settled)
            (let [r (system/issue! sys :cycle-runner/verdicts-to-conclusion :cycle-runner/hand-in-verdicts params)]
              (if (blocks/refusal? r)
                (do (swap! (:settles sys) dissoc run-id) (refusal-response id r))
                (do (wait-settled settled)
                    (swap! (:settles sys) dissoc run-id)
                    (ok id {:events (mapv event->json (:command-result/events r))})))))))))

(defn run-query [sys id query-name params]
  (or (params-error id query-name params)
      (let [r (system/query sys query-name params)]
        (if (:cognitect.anomalies/category r)
          (error id -32603 (str (:cognitect.anomalies/message r)) nil)
          (ok id (:query/result r))))))

(defn handle
  "Handle one parsed request; returns the response map, or nil for a notification."
  [sys req]
  (let [id (:id req)
        method (:method req)
        params (let [p (:params req)] (if (nil? p) {} p))]
    (cond
      (not (and (map? req) (string? method)))
      (error id -32600 "Invalid Request" nil)

      (not (map? params))
      (error id -32602 "Params must be an object." nil)

      :else
      (case method
        "cycle-runner/start-run" (start-run sys id params)
        "cycle-runner/hand-in-verdicts" (hand-in-verdicts sys id params)
        "cycle-runner/run-progress" (run-query sys id :cycle-runner/run-progress params)
        "cycle-runner/runs-to-advance" (run-query sys id :cycle-runner/runs-to-advance params)
        (error id -32601 (str "Method not found: " method) nil)))))

(defn handle-line
  "One request line in, one response line out (nil for a notification)."
  [sys ^String line]
  (let [req (try (u/parse-json line) (catch Exception _ ::parse-error))]
    (cond
      (= ::parse-error req) (u/->json (error nil -32700 "Parse error" nil))
      (and (map? req) (not (contains? req :id))) (do (handle sys req) nil)
      :else (u/->json (try (handle sys req)
                           (catch Throwable t
                             (u/log "request failed: " t)
                             (error (when (map? req) (:id req)) -32603 (str "Internal error: " (.getMessage t)) nil)))))))
