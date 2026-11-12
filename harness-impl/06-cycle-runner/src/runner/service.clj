(ns runner.service
  (:require [runner.util :as u] [runner.model :as model] [runner.contexts :as contexts]
            [runner.repository :as repo] [runner.gate :as gate] [runner.engine :as engine]
            [clojure.java.io :as io] [clojure.string :as str]
            [ai.obney.grain.query-processor.interface :as qp]))
(def sessions (atom {}))
(def runtime (atom nil))
(defn refuse [code & [data]] {:runner/refusal (merge {:refusal code} data)})
(defn started [ctx]
  (let [p (model/payload (:command ctx)) p (dissoc p :command/name :command/id :command/timestamp)
        runs (model/ordered-runs ctx) env (into {} (System/getenv)) repo (env "LOYLY_PROJECT_REPO")]
    (cond
      (some #(= (:run-id p) (:run-id %)) runs) (refuse "run-already-started")
      (some #(#{"running" "grading" "awaiting-verdicts"} (:status %)) runs) (refuse "run-in-progress")
      (some #(= (:cycle-id p) (:cycle-id %)) runs) (refuse "cycle-already-run")
      (not (try (u/git repo "cat-file" "-e" (str (:commit p) "^{commit}")) true (catch Throwable _ false))) (refuse "not-a-commit")
      :else
      (let [config (u/config (env "LOYLY_HARNESS_CONFIG"))
            dir (u/mkdir (str (env "LOYLY_RUNS_DIR") "/run-" (random-uuid)))
            slices (repo/slices repo (:commit p) config)
            tree (str/trim (u/git repo "rev-parse" (str (:commit p) "^{tree}")))
            cs (contexts/start env p dir slices tree)
            image (:BUILDER_IMAGE config)
            image-path (when-not (str/blank? image) (str (env "LOYLY_BUILDER_IMAGES") "/" image))]
        (cond
          (:missing cs) (refuse "contexts-not-responding" {:contexts (:missing cs)})
          (and image-path (or (str/blank? (env "LOYLY_BUILDER_IMAGES")) (not (.isDirectory (io/file image-path))))) (do (contexts/stop cs) (refuse "builder-image-missing"))
          :else
          (let [s (merge cs {:env env :params p :config config :dir dir :tree tree :slices slices :image-path image-path
                            :phase (atom :gate) :attempt (atom 0) :busy (atom false) :settled (atom (promise)) :effect (atom "stamp")})]
            (swap! sessions assoc (:run-id p) s)
            {:command-result/events [(model/event :cycle-runner/run-started (assoc p :started-at (u/now)))]}))))))
(defn emit [type timekey enrich]
  (fn [ctx]
    (let [p (select-keys (:command ctx) (map first (drop 1 (get-in model/model [:commands (get-in ctx [:command :command/name]) :schema]))))]
      {:command-result/events [(model/event type (assoc (enrich ctx p) timekey (u/now)))]})))
(defn verdicts [ctx]
  (let [{:keys [run-id verdicts-path grader-digest]} (:command ctx)
        r (model/progress ctx run-id)
        start (get-in @sessions [run-id :params])
        file (try (u/decode (slurp verdicts-path)) (catch Throwable _ nil))]
    (cond
      (nil? r) (refuse "no-run")
      (not (some #(= "regeneration-cycle/gate-attempt-passed" (:type %)) (mapcat :events (:effect-results r)))) (refuse "gate-not-passed")
      (:verdicts-digest r) (refuse "verdicts-already-handed-in")
      (not= "awaiting-verdicts" (:status r)) (refuse "run-not-awaiting-verdicts")
      (not (some #{grader-digest} (:grader-digests start))) (refuse "grader-not-approved")
      (not (and (vector? file) (every? map? file))) (refuse "verdicts-not-readable")
      :else
      (let [s (@sessions run-id)]
        (reset! (:settled s) (promise))
        (reset! (:phase s) :verdicts)
        (swap! sessions update run-id assoc :verdicts file :grader-digest grader-digest)
        {:command-result/events [(model/event :cycle-runner/verdicts-handed-in
          {:run-id run-id :verdicts-path verdicts-path :verdicts-digest (u/sha (u/bytes-of verdicts-path)) :grader-digest grader-digest :verdicts (count file) :handed-in-at (u/now)})]}))))
(defn register-handlers! []
  (reset! model/command-handlers
    {:cycle-runner/start-run started
     :cycle-runner/hand-in-verdicts verdicts
     :cycle-runner/record-effect (emit :cycle-runner/effect-recorded :performed-at (fn [ctx p] (assoc p :position (inc (:effects (model/progress ctx (:run-id p)))))))
     :cycle-runner/relay-handoff (emit :cycle-runner/handoff-relayed :relayed-at (fn [_ p] p))
     :cycle-runner/conclude-run (emit :cycle-runner/run-concluded :concluded-at (fn [ctx p] (assoc p :cycle-id (:cycle-id (model/progress ctx (:run-id p))))))
     :cycle-runner/halt-run (emit :cycle-runner/run-halted :halted-at (fn [_ p] p))}))
(defn flow [s] (if (= :verdicts @(:phase s)) "verdicts-to-conclusion" "run-to-gate"))
(defn own! [s command params]
  (engine/command! @runtime (flow s) command (assoc params :run-id (get-in s [:params :run-id]))))
(defn halt! [s reason detail]
  (engine/command! @runtime "effect-not-performed-halts-run" "halt-run"
                   {:run-id (get-in s [:params :run-id]) :effect @(:effect s) :reason reason :detail detail}))
(defn cycle-progress [s]
  (get-in (contexts/call s "regeneration-cycle" "regeneration-cycle/cycle-progress" {:cycle-id (get-in s [:params :cycle-id])}) [:result :cycle]))
(defn conclude-if-due! [s]
  (let [cycle (cycle-progress s)]
    (when (#{"passed" "failed"} (:status cycle))
      (engine/command! @runtime (if (= "failed" (:status cycle)) "cycle-failed-ends-run" "verdicts-to-conclusion") "conclude-run"
                       {:run-id (get-in s [:params :run-id]) :cycle-status (:status cycle) :cycle cycle})
      (throw (ex-info "Cycle concluded" {:finished true})))))
(defn relay! [s]
  (contexts/relay! s #(own! s "relay-handoff" %)))
(defn record! [s effect attempt context method params detail]
  (reset! (:effect s) effect)
  (let [reply (contexts/call s context method params)
        error (:error reply)
        refusal (get-in error [:data :refusal])]
    (when (and error (not= -32000 (:code error)))
      (throw (ex-info (:message error) {:reason "effect-could-not-run"})))
    (own! s "record-effect" (cond-> {:effect effect :attempt attempt :context context :method method :events (get-in reply [:result :events] []) :outcome (if error "refused" "recorded") :detail (str detail)} refusal (assoc :refusal refusal)))
    (relay! s)
    (conclude-if-due! s)
    (when (and refusal (#{"stamp" "pin" "checklist"} effect))
      (throw (ex-info refusal {:reason "refused-before-the-cycle"})))
    (when (and refusal (not (#{"fence" "gate" "gate-refusal" "file-verdicts" "file-score"} effect)))
      (throw (ex-info refusal {:reason "effect-could-not-run"})))
    reply))
(defn regeneration! [s effect attempt method params detail]
  (record! s effect attempt "regeneration-cycle" (str "regeneration-cycle/" method) (assoc params :cycle-id (get-in s [:params :cycle-id])) detail))
(defn self-check! [s attempt]
  (reset! (:effect s) "self-check")
  (let [{:keys [env params]} s
        r (gate/checked-command (env "LOYLY_SELF_CHECK_CMD") [] (env "LOYLY_PROJECT_REPO") false 300000 {})
        skipped (vec (keep #(second (re-matches #"SKIP\s+(.*)" (str/trim %))) (str/split-lines (:out r))))
        goldens (get-in (contexts/call s "grader-lifecycle" "grader-lifecycle/instrument-goldens" {}) [:result :goldens] [])
        goldens-digest (u/sha (apply str (for [g (sort-by :golden-id goldens)] (str (:golden-id g) "\t" (:digest g) "\n"))))
        detail (or (gate/last-line (:out r)) "")]
    (regeneration! s "self-check" attempt "record-harness-self-check"
                   (cond-> {:cut-id (:cut-id params) :occasion (if (zero? attempt) "before-fence" "before-gate") :attempt attempt
                            :harness-digest (gate/harness-digest s) :goldens-digest goldens-digest :identity-probe-digest (:digest (gate/probe s))
                            :outcome (if (zero? (:exit r)) "passed" "failed") :skipped-checks skipped :missing-goldens [] :reworded-goldens []
                            :grader-digests (:grader-digests params) :budget (:budget params) :detail detail}
                     (not (zero? (:exit r))) (assoc :failing-check detail))
                   (str detail (when (seq skipped) (str " Skipped: " (str/join ", " skipped)))))))
(defn fail-cycle! [s stage reason detail]
  (regeneration! s "fail-cycle" @(:attempt s) "fail-cycle" {:stage stage :reason reason :detail detail} detail))
(defn stamp-pin-checklist! [s]
  (doseq [slice (:slices s)]
    (reset! (:effect s) "stamp")
    (let [p (repo/stamp-copy (:dir s) slice)
          r (record! s "stamp" 0 "specification" "specification/stamp-slice" p (:source p))
          actual (mapv :item-id (filter #(= "specification/scenario-stamped" (:type %)) (get-in r [:result :events])))
          expected (vec (keep :item-id (:items slice)))]
      (when-not (= expected actual)
        (throw (ex-info (str "Plan identities " expected " differ from stamp identities " actual) {:reason "refused-before-the-cycle"})))))
  (let [p (:params s)]
    (record! s "pin" 0 "cycle-preparation" "cycle-preparation/pin-source-cut" {:cut-id (:cut-id p) :commit (:commit p) :tree-digest (:tree s)} (:tree s))
    (record! s "checklist" 0 "measurement" "measurement/derive-checklist" (repo/checklist (:slices s) (:cut-id p) (:selection-id p)) "Read checklist from selected plan at cut")))
(defn budget-ms [s stage] (* 1000 (get-in s [:params :budget :stage-timeouts-seconds stage])))
(defn remaining-ms [s stage]
  (let [cycle (cycle-progress s) budget (budget-ms s stage)
        entered (when (= (name stage) (:stage cycle))
                  (try (java.time.Instant/parse (:stage-entered-at cycle)) (catch Throwable _ nil)))]
    (max 1 (if entered (- budget (- (System/currentTimeMillis) (.toEpochMilli entered))) budget))))
(defn build! [s attempt previous evidence clean]
  (reset! (:effect s) (if (= attempt 1) "regenerate" "repair"))
  (let [{:keys [env config dir image-path]} s
        room (u/copy-tree clean (str dir "/builder-" attempt))
        candidate (u/mkdir (str room "/" (:APP_DIR config)))
        instruction (str (slurp (env "LOYLY_BUILDER_INSTRUCTION"))
                         (when previous
                           (let [prev (u/copy-tree previous (str room "/previous-candidate"))
                                 ev (u/copy-tree evidence (str room "/gate-evidence"))]
                             (u/readonly prev) (u/readonly ev)
                             (str "\nRepair the previous candidate at " prev "; read the gate evidence at " ev ". Both are read-only. Write only the candidate scope.\n"))))
        instruction-path (u/put (str dir "/instruction-" attempt ".md") instruction)
        r (gate/checked-command (env "LOYLY_BUILDER_CMD") (cond-> [room instruction-path] image-path (conj image-path)) (env "LOYLY_PROJECT_REPO") false (remaining-ms s :regenerate) {})]
    (cond
      (:timeout r) (fail-cycle! s "regenerate" "budget" "Builder exceeded regenerate timeout")
      (not (seq (filter #(.isFile %) (file-seq (io/file candidate))))) (fail-cycle! s "regenerate" "candidate-not-regenerated" "Builder left no candidate")
      :else
      (let [snapshot (u/copy-tree candidate (str dir "/candidate-" attempt))
            digest (u/digest snapshot)
            prior-digest (when previous (u/digest previous))]
        (regeneration! s @(:effect s) attempt "record-regenerated-candidate"
                       (cond-> {:attempt attempt :purpose (if (= attempt 1) "fresh-generation" "verification-repair") :candidate-digest digest :mounted-evidence (if evidence [evidence] [])}
                         prior-digest (assoc :parent-candidate-digest prior-digest))
                       (str "Builder room " room (when image-path (str "; builder image " (:BUILDER_IMAGE config) " " (u/digest image-path)))))
        {:candidate snapshot :digest digest}))))
(defn gate-attempt! [s attempt candidate digest clean]
  (reset! (:effect s) "install")
  (let [{:keys [dir env config params]} s
        command? (not (str/blank? (env "LOYLY_GATE_CMD")))
        room (str dir "/gate-" attempt)
        _ (if command? (do (repo/archive! (env "LOYLY_PROJECT_REPO") (:commit params) room) (u/remove-tree (str room "/" (:APP_DIR config))))
              (do (u/copy-tree clean room) (u/remove-tree (str room "/.git"))))
        installed (u/copy-tree candidate (if command? (str dir "/installed-" attempt) (str room "/" (:APP_DIR config))))
        digest (u/digest installed)]
    (regeneration! s "install" attempt "install-candidate-in-gate-room"
                   {:attempt attempt :candidate-digest digest :purpose (if (= attempt 1) "fresh-generation" "verification-repair") :gate-room room} room)
    (self-check! s attempt)
    (let [deadline (+ (System/currentTimeMillis) (remaining-ms s :gate))]
      (loop [refusal-number 1]
        (reset! (:effect s) "gate")
        (let [evidence (str dir "/evidence-" attempt "-" refusal-number)
              p (gate/run! s attempt digest room installed evidence (max 1 (- deadline (System/currentTimeMillis))))
              r (regeneration! s "gate" attempt "record-gate-attempt" p (:detail p))
              code (get-in r [:error :data :refusal])]
          (cond
            (nil? code) {:evidence evidence :passed (= "passed" (:outcome p))}
            (#{"seam-answered-before-boot" "identity-probe-missing" "identity-probe-not-the-instruments"} code)
            (let [refused (regeneration! s "gate-refusal" attempt "record-gate-refusal" {:attempt attempt :refusal code :detail (:detail p)} (:detail p))]
              (when-let [code (get-in refused [:error :data :refusal])] (throw (ex-info code {:reason "effect-could-not-run"})))
              (recur (inc refusal-number)))
            :else (throw (ex-info code {:reason "effect-could-not-run"}))))))))
(defn to-gate! [s]
  (stamp-pin-checklist! s)
  (self-check! s 0)
  (reset! (:effect s) "fence")
  (let [p (:params s) env (:env s) clean (str (:dir s) "/clean-room")
        proof (repo/fence! (env "LOYLY_PROJECT_REPO") (:commit p) clean (vec (remove str/blank? (str/split (get-in s [:config :FENCED_PATHS] "") #"\s+"))))
        r (regeneration! s "fence" 0 "prove-clean-room" (assoc proof :cut-id (:cut-id p)) clean)]
    (when-let [code (get-in r [:error :data :refusal])] (fail-cycle! s "fence" "fence-refused" code))
    (loop [attempt 1 previous nil evidence nil]
      (let [_ (reset! (:attempt s) attempt)
            {:keys [candidate digest]} (build! s attempt previous evidence clean)
            result (gate-attempt! s attempt candidate digest clean)]
        (when-not (:passed result)
          (recur (inc attempt) candidate (:evidence result)))))))
(defn file-verdicts! [s]
  (let [{:keys [params verdicts grader-digest]} s
        cycle (:cycle-id params)
        candidate (:candidate-digest (model/progress @runtime (:run-id params)))
        record-score (fn [effect method p] (record! s effect (:attempts (model/progress @runtime (:run-id params))) "measurement" (str "measurement/" method) p "Filed handed-in verdicts unchanged"))
        failure (fn [r] (when-let [code (get-in r [:error :data :refusal])]
                          (record-score "fail-score-stage" "fail-score-stage" {:cycle-id cycle :refusal code :detail code})
                          (throw (ex-info code {:reason "effect-could-not-run"}))))]
    (failure (record-score "file-verdicts" "file-item-verdicts"
                          {:cycle-id cycle :candidate-digest candidate :grader-digest grader-digest :approved-grader-digests (:grader-digests params)
                           :verdicts (mapv #(assoc (dissoc % :id) :item-id (:id %)) verdicts)}))
    (failure (record-score "file-score" "file-score" {:cycle-id cycle :approved-grader-digests (:grader-digests params)}))
    ;; Policy-owned handoffs can appear just after the command reply.
    (loop [i 0]
      (relay! s) (conclude-if-due! s)
      (when (>= i 200) (throw (ex-info "Cycle did not conclude after score" {:reason "context-did-not-respond"})))
      (Thread/sleep 25) (recur (inc i)))))
(defn advance! [run-id]
  (when-let [s (@sessions run-id)]
    (when (and (#{"running" "grading"} (:status (model/progress @runtime run-id))) (compare-and-set! (:busy s) false true))
      (try
        (if (= :verdicts @(:phase s)) (file-verdicts! s) (to-gate! s))
        (catch Throwable t
          (when-not (:finished (ex-data t))
            (if (= "budget" (:reason (ex-data t)))
              (try (fail-cycle! s (or (:stage (ex-data t)) "regenerate") "budget" (.getMessage t))
                   (catch Throwable e (when-not (:finished (ex-data e)) (halt! s "effect-could-not-run" (.getMessage e)))))
              (halt! s (or (:reason (ex-data t)) "effect-could-not-run") (or (.getMessage t) (str t))))))
        (finally
          (reset! (:busy s) false)
          (when (#{"passed" "failed" "halted"} (:status (model/progress @runtime run-id))) (contexts/stop s))
          (deliver @(:settled s) true))))))
(defn install-processor! []
  (reset! model/advance-handler
    (fn [{:keys [event]}]
      (if (#{:cycle-runner/run-started :cycle-runner/verdicts-handed-in} (:event/type event))
        {:result/effect #(advance! (:run-id event)) :result/checkpoint :after}
        {}))))
(defn reconcile! []
  (doseq [r (model/ordered-runs @runtime) :when (#{"running" "grading" "awaiting-verdicts"} (:status r))]
    (when-let [s (@sessions (:run-id r))]
      (try (conclude-if-due! s)
           (catch Throwable t
             (when (:finished (ex-data t)) (contexts/stop s)))))))
(defn invoke! [method params]
  (let [ctx @runtime]
    (if (contains? (:queries model/model) method)
      (let [r (qp/process-query (assoc ctx :query (merge params {:query/name method :query/id (random-uuid) :query/timestamp (java.time.OffsetDateTime/now java.time.ZoneOffset/UTC)})))]
        (if (contains? r :query/result) (:query/result r) (throw (ex-info "Grain query failed" r))))
      (do
        (reconcile!)
        (let [events (engine/command! ctx (if (= method :cycle-runner/start-run) "run-to-gate" "verdicts-to-conclusion") (name method) params)
              s (@sessions (:run-id params))]
          (when s @@(:settled s))
          {:events (mapv #(assoc (model/payload %) :type (subs (str (:event/type %)) 1)) events)})))))
