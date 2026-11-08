(ns loyly.grader-lifecycle.workflow
  "The ORC workflow each command method runs as: named after the flow its
   command belongs to in the event model, grader-lifecycle/golden-written-then-moved.
   Its one code leaf processes the Grain command inside the run, so every event
   the command produces is written between the run's tick start and completion."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.interface :as orc]
            [clojure.data.json :as json]
            [cognitect.anomalies :as anom]
            [loyly.grader-lifecycle.domain :as domain]
            [loyly.grader-lifecycle.model :as model]))

(def flow-name :grader-lifecycle/golden-written-then-moved)

(defn workflow-name
  "The ORC workflow name: the flow's keyword without its leading colon."
  []
  (subs (str flow-name) 1))

(defn- render-value [v]
  (if (inst? v)
    (str (.toInstant ^java.util.Date (if (instance? java.util.Date v) v (java.util.Date/from v))))
    v))

(defn render-event
  "An event as the seam returns it: its type and exactly its schema's fields."
  [event]
  (let [t (:event/type event)]
    (into {"type" (subs (str t) 1)}
          (for [f (model/event-fields t)]
            [(name f) (render-value (get event f))]))))

(defn- outcome-of [result]
  (cond
    (:refusal result)
    {"refusal" (:refusal result) "message" (::anom/message result)}

    (::anom/category result)
    {"error" (str (::anom/message result))
     "category" (name (::anom/category result))}

    :else
    {"events" (mapv render-event (:command-result/events result))}))

(defn run-command
  "The workflow's code leaf. Reads the request (a command name and its params)
   from the blackboard, processes it through Grain's command processor in the
   run's own context, and writes the outcome."
  [{:keys [inputs] :as context}]
  (let [{:strs [command params]} (json/read-str (get inputs :request))
        command-name (keyword command)
        params (into {} (map (fn [[k v]] [(keyword k) v])) params)
        ctx (dissoc context :inputs :execution-context :node :call-tool-fn)
        result (if (and (= command-name :grader-lifecycle/move-instrument-golden)
                        (= "" (:note params)))
                 ;; An empty note is the refusal empty-note (requires 4), in the
                 ;; Allium's order; the schema's {:min 1} would hide it.
                 (let [state (domain/goldens-state ctx)
                       path (:loyly/scenario-digests-path ctx)]
                   (domain/refusal
                    (domain/decide-move state #(domain/digest-of-scenario path %) params)))
                 (cp/process-command
                  (assoc ctx :command
                         (merge params
                                {:command/name command-name
                                 :command/id (random-uuid)
                                 :command/timestamp (time/now)}))))]
    {:outcome (json/write-str (outcome-of result))}))

(def definition
  (orc/workflow (workflow-name)
    (orc/blackboard
     {:request [:string {:description "The command name and its params, as JSON."}]
      :outcome [:string {:description "The command's events or its refusal, as JSON."}]})
    (orc/sequence "golden-written-then-moved"
      (orc/code "process-command"
        :fn "loyly.grader-lifecycle.workflow/run-command"
        :reads [:request]
        :writes [:outcome]))))
