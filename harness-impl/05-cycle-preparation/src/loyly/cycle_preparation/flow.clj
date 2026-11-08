(ns loyly.cycle-preparation.flow
  "The ORC workflows of Cycle preparation. Each command runs as the workflow
   named after the flow its command belongs to in the event model; its one leaf
   issues the Grain command, so every event the command produces is written
   inside that workflow's run, in the product's own store."
  (:require [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [loyly.cycle-preparation.model :as model]
            [loyly.cycle-preparation.domain :as domain]))

(defn- event->outcome [event]
  {:type (str (namespace (:event/type event)) "/" (name (:event/type event)))
   :cut-id (:cut-id event)
   :commit (:commit event)
   :tree-digest (:tree-digest event)
   :pinned-at (str (:pinned-at event))})

(defn run-pin-source-cut
  "The leaf of cycle-preparation/cut-to-fence: issue the Grain command
   pin-source-cut through Grain's command processor with the run's context."
  [{:keys [inputs] :as context}]
  (let [command {:command/id (random-uuid)
                 :command/timestamp (time/now)
                 :command/name domain/pin-source-cut
                 :cut-id (:cut-id inputs)
                 :commit (:commit inputs)
                 :tree-digest (:tree-digest inputs)}
        ctx (-> context
                (select-keys [:event-store :cache :tenant-id])
                (assoc :command command))
        result (cp/process-command ctx)]
    (cond
      (:refusal result)
      {:outcome {:refusal (:refusal result)
                 :message (str (:cognitect.anomalies/message result))
                 :events []}}

      ;; The store's compare-and-set refused: another pin of this identity won.
      (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result))
      {:outcome {:refusal "cut-already-pinned"
                 :message (str "Source cut " (:cut-id inputs) " is already pinned.")
                 :events []}}

      (:cognitect.anomalies/category result)
      (throw (ex-info (str "pin-source-cut failed: " (:cognitect.anomalies/message result))
                      {:anomaly (dissoc result :command-result/cas)}))

      :else
      {:outcome {:events (mapv event->outcome (:command-result/events result))}})))

(def outcome-schema
  [:map
   [:refusal {:optional true} :string]
   [:message {:optional true} :string]
   [:events [:vector [:map
                      [:type :string]
                      [:cut-id :string]
                      [:commit :string]
                      [:tree-digest :string]
                      [:pinned-at :string]]]]])

(defn workflow-name [command]
  (let [flow (model/flow-of-command command)]
    (when-not flow
      (throw (ex-info "Command belongs to no flow of the event model" {:command command})))
    (str (namespace flow) "/" (name flow))))

(defn cut-to-fence []
  (orc/workflow (workflow-name domain/pin-source-cut)
    (orc/blackboard
     {:cut-id :string
      :commit :string
      :tree-digest :string
      :outcome outcome-schema})
    (orc/sequence "cut-to-fence"
      (orc/code "pin-source-cut"
        :fn "loyly.cycle-preparation.flow/run-pin-source-cut"
        :reads [:cut-id :commit :tree-digest]
        :writes [:outcome]))))

(defn build!
  "Build (idempotently) every workflow; returns {command sheet-id}."
  [ctx]
  {domain/pin-source-cut (orc/build-workflow! ctx (cut-to-fence))})
