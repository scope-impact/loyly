(ns loyly.pi-harness.grain.core.read-models
  "Registered Grain projections for the harness domain model."
  (:require [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]))

(def selection-event-types
  #{:loyly/selection-recorded :loyly/selection-approved})

(def observation-event-types #{:loyly/observation-recorded})
(def measurement-event-types #{:loyly/cycle-measurement-recorded})

(def selection-keys
  [:selectionId :selectionDigest :status :projectId :containerId :componentId
   :cut :slice :items])

(def observation-keys
  [:cycle-id :cut-digest :project-id :container-id :component-id :item-id :subject-digest
   :producer :evidence-kind :artifact-digests :result :reached? :evidence :oracle-digest])

(def measurement-keys
  [:cycle-id :selection-id :cut :backend :total :passed :failed :complete? :items
   :instrument-conformance :evidence-manifest])

(defn uuid-tag [tag value]
  [tag (java.util.UUID/nameUUIDFromBytes (.getBytes value "UTF-8"))])

(defn selection-tag [selection-id] (uuid-tag :selection selection-id))
(defn observation-cycle-tag [cycle-id] (uuid-tag :observation-cycle cycle-id))
(defn measurement-tag [cycle-id] (uuid-tag :cycle-measurement cycle-id))

(defn selection-reducer [selection event]
  (case (:event/type event)
    :loyly/selection-recorded (select-keys event selection-keys)
    :loyly/selection-approved (when selection (assoc selection :status "approved"))
    selection))

(defreadmodel :loyly selection
  {:events selection-event-types :version 1}
  [state event]
  (selection-reducer state event))

(defn observations-reducer [observations event]
  (if (= :loyly/observation-recorded (:event/type event))
    (conj (or observations []) (select-keys event observation-keys))
    observations))

(defreadmodel :loyly observations
  {:events observation-event-types :version 1}
  [state event]
  (observations-reducer state event))

(defn measurement-reducer [measurement event]
  (if (= :loyly/cycle-measurement-recorded (:event/type event))
    (select-keys event measurement-keys)
    measurement))

(defreadmodel :loyly cycle-measurement
  {:events measurement-event-types :version 1}
  [state event]
  (measurement-reducer state event))

(defn- direct-project [context tags reducer initial-state]
  (reduce reducer initial-state
          (event-store/read (:event-store context)
                            {:tenant-id (:tenant-id context) :tags tags})))

(defn find-selection [context selection-id]
  (let [tags #{(selection-tag selection-id)}]
    (if (:cache context)
      (not-empty (rmp/project context :loyly/selection {:tags tags}))
      (direct-project context tags selection-reducer nil))))

(defn find-cycle [context cycle-id]
  (let [tags #{(observation-cycle-tag cycle-id)}
        observations (if (:cache context)
                       (rmp/project context :loyly/observations {:tags tags})
                       (direct-project context tags observations-reducer []))]
    (->> observations
         (sort-by (juxt :evidence-kind :producer))
         vec)))

(defn find-measurement [context cycle-id]
  (let [tags #{(measurement-tag cycle-id)}]
    (if (:cache context)
      (not-empty (rmp/project context :loyly/cycle-measurement {:tags tags}))
      (direct-project context tags measurement-reducer nil))))
