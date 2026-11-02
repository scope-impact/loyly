(ns loyly.pi-harness.selection-registry
  (:require [ai.obney.grain.anomalies.interface :as anomalies]
            [ai.obney.grain.command-processor-v2.interface :as command-processor :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

(def item-schema [:map [:id :string] [:name :string] [:rule :string] [:source :string]])
(def contract-schema
  [:map [:selectionId :string] [:selectionDigest :string]
   [:status [:enum "awaiting-approval" "approved"]]
   [:projectId :string] [:containerId :string] [:componentId :string]
   [:cut :string] [:slice :string] [:items [:vector item-schema]]])

(defschemas commands
  {:loyly/record-selection [:map [:contract contract-schema]]
   :loyly/approve-selection [:map [:selection-id :string] [:selection-digest :string]]})
(defschemas events
  {:loyly/selection-recorded contract-schema
   :loyly/selection-approved [:map [:selectionId :string] [:selectionDigest :string]]})

(defn- selection-tag [selection-id]
  [:selection (java.util.UUID/nameUUIDFromBytes (.getBytes selection-id "UTF-8"))])

(defn find-selection [{:keys [event-store tenant-id]} selection-id]
  (reduce (fn [selection event]
            (case (:event/type event)
              :loyly/selection-recorded
              (select-keys event [:selectionId :selectionDigest :status
                                  :projectId :containerId :componentId
                                  :cut :slice :items])
              :loyly/selection-approved
              (when selection (assoc selection :status "approved"))
              selection))
          nil
          (event-store/read event-store
                            {:tenant-id tenant-id :tags #{(selection-tag selection-id)}})))

(defcommand :loyly record-selection
  {:grain.event-model/produces #{:loyly/selection-recorded}}
  "Record the deterministic selection contract shown to the user."
  [{:keys [command] :as context}]
  (let [contract (:contract command)
        selection-id (:selectionId contract)
        existing (find-selection context selection-id)]
    (cond
      (= (dissoc existing :status) (dissoc contract :status))
      {:command/result existing}
      existing {::anom/category ::anom/conflict
                ::anom/message "selection identity already names a different contract"}
      :else {:command-result/events
             [(event-store/->event {:type :loyly/selection-recorded
                                    :tags #{(selection-tag selection-id)}
                                    :body contract})]})))

(defcommand :loyly approve-selection
  {:grain.event-model/produces #{:loyly/selection-approved}}
  "Approve only the exact digest of the recorded selection contract."
  [{:keys [command] :as context}]
  (let [selection-id (:selection-id command)
        selection-digest (:selection-digest command)
        selection (find-selection context selection-id)]
    (cond
      (nil? selection) {::anom/category ::anom/not-found ::anom/message "unknown selectionId"}
      (not= selection-digest (:selectionDigest selection))
      {::anom/category ::anom/conflict
       ::anom/message "selection digest does not match the reviewed contract"}
      (= "approved" (:status selection)) {:command/result selection}
      :else {:command-result/events
             [(event-store/->event {:type :loyly/selection-approved
                                    :tags #{(selection-tag selection-id)}
                                    :body {:selectionId selection-id
                                           :selectionDigest selection-digest}})]})))

(defn- process! [context command]
  (let [result (command-processor/process-command
                (assoc context :command
                       (merge {:command/id (random-uuid) :command/timestamp (time/now)} command)))]
    (when (anomalies/anomaly? result)
      (throw (ex-info (or (::anom/message result) "selection command failed") result)))
    result))

(defn record! [context contract]
  (process! context {:command/name :loyly/record-selection :contract contract})
  (find-selection context (:selectionId contract)))

(defn approve! [context selection-id selection-digest]
  (process! context {:command/name :loyly/approve-selection
                     :selection-id selection-id :selection-digest selection-digest})
  (find-selection context selection-id))
