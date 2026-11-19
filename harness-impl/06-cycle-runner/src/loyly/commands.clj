(ns loyly.commands
  (:require [loyly.domain :as d] [clojure.edn :as edn]
            [ai.obney.orc.orc-service.core.dsl :as bt] [ai.obney.orc.orc-service.core.runtime :as runtime]))
(defn flow [k]
  (case (namespace k)
    "specification" "specification/stamping" "cycle-preparation" "cycle-preparation/cut-to-fence"
    "grader-lifecycle" "grader-lifecycle/golden-written-then-moved"
    "regeneration-cycle" (case k :regeneration-cycle/record-gate-refusal "regeneration-cycle/gate-refusals-fail-cycle"
                              :regeneration-cycle/fail-cycle "regeneration-cycle/fence-refused-fails-cycle" "regeneration-cycle/fence-to-scoreboard")
    "measurement" (case k :measurement/derive-checklist "measurement/stamping-to-checklist"
                        :measurement/report-scoreboard "measurement/scoreboard-restates-moved-item-set" "measurement/scoring")))
(defn execute [ctx]
  (let [{:keys [method params]} (edn/read-string (get-in ctx [:inputs :request]))]
    {:reply (pr-str (d/issue (assoc ctx :external-command? true) method params))}))
(defn invoke
  ([sys k p] (invoke sys k p (flow k)))
  ([sys k p workflow]
   (let [sheet (bt/build-workflow! sys (bt/workflow workflow (bt/blackboard {:request :string :reply :string})
                                                    (bt/code "issue-command" :fn "loyly.commands/execute" :reads [:request] :writes [:reply])))
         result (runtime/execute sys sheet {:request (pr-str {:method k :params p})} :timeout-ms 60000)]
     (if-let [reply (or (get-in result [:outputs :reply]) (get-in result [:outputs "reply"]))]
       (edn/read-string reply)
       (throw (ex-info (str "Command tick failed " k " " result) {}))))))
