(ns loyly.policy
  (:require [loyly.commands :as commands] [loyly.cycle :as cycle] [loyly.views :as views]
            [ai.obney.grain.todo-processor-v2.interface :as tp] [ai.obney.grain.periodic-task.interface :as periodic]
            [ai.obney.grain.read-model-processor-v2.interface :as rm]))
(defn report [{:keys [event] :as ctx}]
  (let [id (:cycle-id event)]
    (when (empty? (cycle/of ctx id :measurement/scoreboard-reported))
      {:result/checkpoint :after :result/effect #(try (commands/invoke ctx :measurement/report-scoreboard {:cycle-id id}) (catch Throwable e (binding [*out* *err*] (println "Scoreboard policy" (.getMessage e))) (throw e)))})))
(defn conclude [{:keys [event] :as ctx}]
  (let [id (:cycle-id event) cycle (get (rm/project ctx :regeneration-cycle/regeneration-cycles) id)
        kind (:event/type event)
        [stage reason detail flow]
        (case kind
          :regeneration-cycle/harness-self-check-failed [(if (= "before-gate" (:occasion event)) "gate" "fence") "harness-self-check-failed" (:failing-check event) "self-check-failed-fails-cycle"]
          :regeneration-cycle/gate-attempt-failed (when (>= (:attempt event) (get-in cycle [:budget :gate-attempt-limit])) ["gate" "gate-attempts-exhausted" (:detail event) "gate-attempts-exhausted"])
          :regeneration-cycle/gate-attempt-refused (when (>= (:consecutive-gate-refusals cycle) 3) ["gate" "infrastructure" (:detail event) "gate-refusals-fail-cycle"])
          :measurement/score-stage-failed ["score" "score-stage-failed" (:detail event) "refused-score-fails-cycle"] nil)]
    (when (= "running" (:status cycle))
      (cond (= kind :measurement/scoreboard-reported) {:result/checkpoint :after :result/effect #(commands/invoke ctx :regeneration-cycle/pass-cycle {:cycle-id id})}
            stage {:result/checkpoint :after :result/effect #(commands/invoke ctx :regeneration-cycle/fail-cycle {:cycle-id id :stage stage :reason reason :detail detail}
                                                   (str "regeneration-cycle/" flow))}))))
(defn register! [models sys]
  (doseq [[_ a] models [k {:keys [subscribes reads produces]}] (:todo-processors a)]
    (tp/register-processor! k (case k :measurement/report-scoreboard-once report :regeneration-cycle/conclude-cycle conclude)
                            {:topics subscribes :grain.event-model/reads reads :grain.event-model/produces produces}))
  (let [k :regeneration-cycle/stage-clock rule (get-in models [:regeneration-cycle :periodic-tasks k])]
    (periodic/register-periodic-trigger! k
      (fn [tenant now]
        (let [ctx (assoc sys :tenant-id tenant)]
          (doseq [[id cycle] (rm/project ctx :regeneration-cycle/regeneration-cycles)
                  :let [stage (:stage cycle) limit (get-in cycle [:budget :stage-timeouts-seconds (keyword stage)])]
                  :when (and (= "running" (:status cycle)) limit
                             (.isAfter now (.plusSeconds (.toInstant (java.time.OffsetDateTime/parse (:stage-entered-at cycle))) limit)))]
            (commands/invoke ctx :regeneration-cycle/fail-cycle {:cycle-id id :stage stage :reason "budget" :detail "stage timeout"} "regeneration-cycle/stage-timeout-fails-cycle"))))
      {:schedule (:schedule rule) :grain.event-model/produces (:produces rule)})))
