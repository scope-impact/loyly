(ns loyly.cycle-runner.orc
  "Every command runs as the ORC workflow named after the flow its command
   belongs to (seam.md, \"The event store\"), on the product's own store and
   tenant: the command is processed inside a code node of that workflow's
   tick, so the events it produces are written inside the run."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.interface :as orc]))

(def flows
  ["cycle-runner/run-to-gate"
   "cycle-runner/verdicts-to-conclusion"
   "cycle-runner/cycle-failed-ends-run"
   "cycle-runner/effect-not-performed-halts-run"])

(defonce ^:private system-ctx (atom nil))
(defonce ^:private pending (atom {}))

(defn issue-command
  "The code node: process the command the token names, with the product's
   Grain context, and keep its result for the caller."
  [{:keys [inputs]}]
  (let [token (:command-token inputs)
        {:keys [command result]} (get @pending token)]
    (deliver result (cp/process-command (assoc @system-ctx :command command)))
    {:command-outcome "processed"}))

(defn- workflow [flow]
  (orc/workflow flow
    (orc/blackboard {:command-token :string :command-outcome :string})
    (orc/sequence "issue"
      (orc/code "process-command"
        :fn "loyly.cycle-runner.orc/issue-command"
        :reads [:command-token]
        :writes [:command-outcome]))))

(defonce ^:private sheet-ids (atom {}))

(defn build! [ctx]
  (reset! system-ctx ctx)
  (reset! sheet-ids (into {} (for [f flows] [f (orc/build-workflow! ctx (workflow f))]))))

(defn issue!
  "Run the command as a tick of the flow's ORC workflow; return the command
   processor's result."
  [ctx flow command]
  (let [token (str (random-uuid))
        result (promise)
        command (merge {:command/id (random-uuid) :command/timestamp (time/now)} command)]
    (swap! pending assoc token {:command command :result result})
    (try
      (let [r (orc/execute ctx (get @sheet-ids flow) {:command-token token}
                           :timeout-ms (* 10 60 1000))]
        (if (realized? result)
          @result
          (throw (ex-info (str "The ORC workflow " flow " did not process the command: " (:error r))
                          {:orc r}))))
      (finally (swap! pending dissoc token)))))
