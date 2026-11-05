(ns loyly.pi-harness.grain.core.read-models
  "Registered Grain projections for the harness domain model."
  (:require [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]))

(def selection-event-types
  #{:loyly.pi-harness/selection-recorded :loyly.pi-harness/selection-approved})
(def preparation-event-types
  #{:loyly.pi-harness/cycle-preparation-recorded
    :loyly.pi-harness/cycle-preparation-approved})

(def observation-event-types #{:loyly.pi-harness/observation-recorded})
(def measurement-event-types #{:loyly.pi-harness/cycle-measurement-recorded})
(def progress-event-types
  #{:sheet/tree-tick-started
    :sheet/node-execution-started
    :sheet/node-execution-completed
    :sheet/tree-tick-completed
    :sheet/tick-cancelled})
(def candidate-event-types #{:loyly.pi-harness/generated-candidate-recorded})
(def operation-route-event-types #{:loyly.pi-harness/approved-slice-operation-routed})
(def task-receipt-event-types #{:loyly.pi-harness/task-receipt-issued})
(def task-receipt-keys [:task-id :tick-id :cycle-id])
(def grader-approval-event-types #{:loyly.pi-harness/grader-approved})
(def grader-approval-keys [:grader-digest :entry :items :approved-by :approved-at])

(def selection-keys
  [:selectionId :selectionDigest :status :projectId :containerId :componentId
   :cut :slice :items])

(def preparation-keys
  [:preparation-id :preparation-digest :status :selection-id :selection-digest
   :scope :target-stack :execution-profile :cut-digest :oracle-digest
   :diagram-digest :max-outer-cycles])

(def observation-keys
  [:cycle-id :cut-digest :project-id :container-id :component-id :item-id :subject-digest
   :producer :producer-authority :evidence-purpose :claim-type :evaluation-id
   :evidence-digest :result :reached? :evidence :collection :correlation :artifacts
   :oracle-digest :evidence-kind])

(def measurement-keys
  [:cycle-id :selection-id :cut :backend :total :passed :failed :complete? :items
   :instrument-conformance :evidence-manifest])

(def progress-keys
  [:cycle-id :tick-id :stage :attempt :builder-session-ref :status])

(def candidate-keys
  [:candidate-id :project-id :container-id :component-id :operation :parent-candidate-id :selection-id
   :cut :output-root :subject-digest :disposition :oracle-descriptor-digest])

(def operation-route-keys
  [:request-id :request-digest :project-id :container-id :component-id :selection-id
   :operation :parent-candidate-id :parent-candidate-root :parent-candidate-digest])

(defn uuid-tag [tag value]
  [tag (java.util.UUID/nameUUIDFromBytes (.getBytes value "UTF-8"))])

(defn selection-tag [selection-id] (uuid-tag :selection selection-id))
(defn preparation-tag [preparation-id] (uuid-tag :cycle-preparation preparation-id))
(defn observation-cycle-tag [cycle-id] (uuid-tag :observation-cycle cycle-id))
(defn measurement-tag [cycle-id] (uuid-tag :cycle-measurement cycle-id))
(defn progress-tag [tick-id] [:tick (java.util.UUID/fromString (str tick-id))])
(defn candidate-tag [candidate-id] (uuid-tag :generated-candidate candidate-id))
(defn operation-route-tag [request-id] (uuid-tag :approved-slice-operation request-id))
(defn task-receipt-tag [task-id] (uuid-tag :task-receipt (str task-id)))
(def all-task-receipts-tag (uuid-tag :task-receipts "all"))
(def approved-graders-tag (uuid-tag :approved-graders "all"))
(defn component-candidates-tag [project-id container-id component-id]
  (uuid-tag :component-candidates (str project-id "|" container-id "|" component-id)))

(defn selection-reducer [selection event]
  (case (:event/type event)
    :loyly.pi-harness/selection-recorded (select-keys event selection-keys)
    :loyly.pi-harness/selection-approved (when selection (assoc selection :status "approved"))
    selection))

(defreadmodel :loyly.pi-harness selections
  {:events selection-event-types :version 1}
  [state event]
  (selection-reducer state event))

(defn preparation-reducer [preparation event]
  (case (:event/type event)
    :loyly.pi-harness/cycle-preparation-recorded (select-keys event preparation-keys)
    :loyly.pi-harness/cycle-preparation-approved
    (when preparation (assoc preparation :status "approved"))
    preparation))

(defreadmodel :loyly.pi-harness cycle-preparations
  {:events preparation-event-types :version 1}
  [state event]
  (preparation-reducer state event))

(defn observations-reducer [observations event]
  (if (= :loyly.pi-harness/observation-recorded (:event/type event))
    (conj (or observations []) (select-keys event observation-keys))
    observations))

(defreadmodel :loyly.pi-harness cycle-observations
  {:events observation-event-types :version 1}
  [state event]
  (observations-reducer state event))

(defn measurement-reducer [measurement event]
  (if (= :loyly.pi-harness/cycle-measurement-recorded (:event/type event))
    (select-keys event measurement-keys)
    measurement))

(defreadmodel :loyly.pi-harness cycle-measurements
  {:events measurement-event-types :version 1}
  [state event]
  (measurement-reducer state event))

(defn progress-reducer [progress event]
  (let [tick-id (some-> (:tick-id event) str)]
    (case (:event/type event)
      :sheet/tree-tick-started
      {:cycle-id tick-id :tick-id tick-id :stage "workflow" :attempt 1 :status "running"}

      :sheet/node-execution-started
      (cond-> (assoc (or progress {})
                     :tick-id tick-id
                     :stage (str (:node-id event))
                     :attempt 1
                     :status "running")
        (string? (get-in event [:inputs :request-id]))
        (assoc :cycle-id (get-in event [:inputs :request-id])))

      :sheet/node-execution-completed
      (assoc (or progress {})
             :tick-id tick-id
             :stage (str (:node-id event))
             :status (if (= :success (:status event)) "completed" "failed"))

      :sheet/tree-tick-completed
      (assoc (or progress {})
             :tick-id tick-id
             :status (if (= :success (:root-status event)) "completed" "failed"))

      :sheet/tick-cancelled
      (assoc (or progress {}) :tick-id tick-id :status "failed")

      progress)))

(defreadmodel :loyly.pi-harness cycle-progress
  {:events progress-event-types :version 1}
  [state event]
  (progress-reducer state event))

(defn candidates-reducer [candidates event]
  (if (= :loyly.pi-harness/generated-candidate-recorded (:event/type event))
    (conj (or candidates []) (select-keys event candidate-keys))
    candidates))

(defreadmodel :loyly.pi-harness component-candidates
  {:events candidate-event-types :version 1}
  [state event]
  (candidates-reducer state event))

(defreadmodel :loyly.pi-harness task-receipts
  {:events task-receipt-event-types :version 1}
  [state event]
  (if (= :loyly.pi-harness/task-receipt-issued (:event/type event))
    (select-keys event task-receipt-keys)
    state))

(defn- approved-graders-reducer [state event]
  (if (= :loyly.pi-harness/grader-approved (:event/type event))
    (assoc state (:grader-digest event) (select-keys event grader-approval-keys))
    state))

(defreadmodel :loyly.pi-harness approved-graders
  {:events grader-approval-event-types :version 1}
  [state event]
  (approved-graders-reducer state event))

(defreadmodel :loyly.pi-harness approved-slice-operation-routes
  {:events operation-route-event-types :version 1}
  [state event]
  (if (= :loyly.pi-harness/approved-slice-operation-routed (:event/type event))
    (select-keys event operation-route-keys)
    state))

(defn- direct-project [context tags reducer initial-state]
  (reduce reducer initial-state
          (event-store/read (:event-store context)
                            {:tenant-id (:tenant-id context) :tags tags})))

(defn find-selection [context selection-id]
  (let [tags #{(selection-tag selection-id)}]
    (if (:cache context)
      (not-empty (rmp/project context :loyly.pi-harness/selections {:tags tags}))
      (direct-project context tags selection-reducer nil))))

(defn find-cycle-preparation [context preparation-id]
  (let [tags #{(preparation-tag preparation-id)}]
    (if (:cache context)
      (not-empty (rmp/project context :loyly.pi-harness/cycle-preparations {:tags tags}))
      (direct-project context tags preparation-reducer nil))))

(defn find-cycle [context cycle-id]
  (let [tags #{(observation-cycle-tag cycle-id)}
        observations (if (:cache context)
                       (rmp/project context :loyly.pi-harness/cycle-observations {:tags tags})
                       (direct-project context tags observations-reducer []))]
    (->> observations
         (sort-by (juxt :claim-type :producer))
         vec)))

(defn find-measurement [context cycle-id]
  (let [tags #{(measurement-tag cycle-id)}]
    (if (:cache context)
      (not-empty (rmp/project context :loyly.pi-harness/cycle-measurements {:tags tags}))
      (direct-project context tags measurement-reducer nil))))

(defn find-cycle-progress [context tick-id]
  (let [tags #{(progress-tag tick-id)}]
    (if (:cache context)
      (not-empty (rmp/project context :loyly.pi-harness/cycle-progress {:tags tags}))
      (direct-project context tags progress-reducer nil))))

(defn find-component-candidates [context project-id container-id component-id]
  (let [tags #{(component-candidates-tag project-id container-id component-id)}]
    ;; Candidate lineage controls whether ORC starts measured regeneration. Read it
    ;; from the durable event store rather than the general projection cache, whose
    ;; tag-scoped projection can return state from another read-model partition.
    (direct-project (dissoc context :cache) tags candidates-reducer [])))

(defn find-latest-candidate [context project-id container-id component-id]
  (last (find-component-candidates context project-id container-id component-id)))

(defn find-task-receipt [context task-id]
  (let [tags #{(task-receipt-tag task-id)}]
    (if (:cache context)
      (not-empty (rmp/project context :loyly.pi-harness/task-receipts {:tags tags}))
      (direct-project context tags
                      (fn [state event]
                        (if (= :loyly.pi-harness/task-receipt-issued (:event/type event))
                          (select-keys event task-receipt-keys)
                          state))
                      nil))))

(defn highest-task-id [context]
  (direct-project context #{all-task-receipts-tag}
                  (fn [highest event]
                    (if (= :loyly.pi-harness/task-receipt-issued (:event/type event))
                      (max highest (:task-id event))
                      highest))
                  0))

(defn find-approved-graders
  "Every grader an operator approved, by digest, in approval order."
  [context]
  (->> (direct-project context #{approved-graders-tag} approved-graders-reducer {})
       vals
       (sort-by :approved-at)
       vec))

(defn find-approved-slice-route [context request-id]
  (let [tags #{(operation-route-tag request-id)}]
    (if (:cache context)
      (not-empty (rmp/project context :loyly.pi-harness/approved-slice-operation-routes {:tags tags}))
      (direct-project context tags
                      (fn [state event]
                        (if (= :loyly.pi-harness/approved-slice-operation-routed (:event/type event))
                          (select-keys event operation-route-keys)
                          state))
                      nil))))
