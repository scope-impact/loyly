(ns loyly.pi-harness.grain.core.queries
  "Registered Grain queries for the Pi-harness service area."
  (:require [ai.obney.grain.anomalies.interface :as anomalies]
            [ai.obney.grain.query-processor.interface :as query-processor :refer [defquery]]
            [ai.obney.grain.time.interface :as time]
            [loyly.pi-harness.grain.core.read-models :as read-models]
            [loyly.pi-harness.grain.interface.schemas]))

(defquery :loyly.pi-harness selection
  {:authorized? (constantly true)
   :grain.event-model/reads #{:loyly.pi-harness/selections}}
  "Return one reviewed selection."
  [{{:keys [selection-id]} :query :as context}]
  {:query/result (read-models/find-selection context selection-id)})

(defquery :loyly.pi-harness cycle-preparation
  {:authorized? (constantly true)
   :grain.event-model/reads #{:loyly.pi-harness/cycle-preparations}}
  "Return one cycle preparation."
  [{{:keys [preparation-id]} :query :as context}]
  {:query/result (read-models/find-cycle-preparation context preparation-id)})

(defquery :loyly.pi-harness cycle-progress
  {:authorized? (constantly true)
   :grain.event-model/reads #{:loyly.pi-harness/cycle-progress}}
  "Return progress projected for one ORC tick."
  [{{:keys [tick-id]} :query :as context}]
  {:query/result (read-models/find-cycle-progress context tick-id)})

(defquery :loyly.pi-harness cycle-observations
  {:authorized? (constantly true)
   :grain.event-model/reads #{:loyly.pi-harness/cycle-observations}}
  "Return independent observations for one cycle."
  [{{:keys [cycle-id]} :query :as context}]
  {:query/result (read-models/find-cycle context cycle-id)})

(defquery :loyly.pi-harness cycle-measurement
  {:authorized? (constantly true)
   :grain.event-model/reads #{:loyly.pi-harness/cycle-measurements}}
  "Return the terminal measurement for one cycle."
  [{{:keys [cycle-id]} :query :as context}]
  {:query/result (read-models/find-measurement context cycle-id)})

(defquery :loyly.pi-harness component-candidates
  {:authorized? (constantly true)
   :grain.event-model/reads #{:loyly.pi-harness/component-candidates}}
  "Return candidate lineage for one component."
  [{{:keys [project-id container-id component-id]} :query :as context}]
  {:query/result (read-models/find-component-candidates
                  context project-id container-id component-id)})

(defquery :loyly.pi-harness oracle-proposal
  {:authorized? (constantly true)
   :grain.event-model/reads #{:loyly.pi-harness/oracle-proposals}}
  "Return one oracle proposal with approval and publication status."
  [{{:keys [proposal-id]} :query :as context}]
  {:query/result (read-models/find-oracle-proposal context proposal-id)})

(defquery :loyly.pi-harness approved-slice-operation-route
  {:authorized? (constantly true)
   :grain.event-model/reads #{:loyly.pi-harness/approved-slice-operation-routes}}
  "Return the pinned operation route for one request."
  [{{:keys [request-id]} :query :as context}]
  {:query/result (read-models/find-approved-slice-route context request-id)})

(defn- query! [context query message]
  (let [result (query-processor/process-query
                (assoc context :query
                       (merge {:query/id (random-uuid)
                               :query/timestamp (time/now)}
                              query)))]
    (when (anomalies/anomaly? result)
      (throw (ex-info message result)))
    (:query/result result)))

(defn selection [context selection-id]
  (query! context {:query/name :loyly.pi-harness/selection
                   :selection-id selection-id}
          "selection query failed"))

(defn cycle-preparation [context preparation-id]
  (query! context {:query/name :loyly.pi-harness/cycle-preparation
                   :preparation-id preparation-id}
          "cycle preparation query failed"))

(defn cycle-progress [context tick-id]
  (query! context {:query/name :loyly.pi-harness/cycle-progress :tick-id tick-id}
          "cycle progress query failed"))

(defn cycle-observations [context cycle-id]
  (query! context {:query/name :loyly.pi-harness/cycle-observations :cycle-id cycle-id}
          "cycle observations query failed"))

(defn cycle-measurement [context cycle-id]
  (query! context {:query/name :loyly.pi-harness/cycle-measurement :cycle-id cycle-id}
          "cycle measurement query failed"))

(defn component-candidates [context project-id container-id component-id]
  (query! context {:query/name :loyly.pi-harness/component-candidates
                   :project-id project-id :container-id container-id
                   :component-id component-id}
          "component candidates query failed"))

(defn oracle-proposal [context proposal-id]
  (query! context {:query/name :loyly.pi-harness/oracle-proposal
                   :proposal-id proposal-id}
          "oracle proposal query failed"))

(defn approved-slice-operation-route [context request-id]
  (query! context {:query/name :loyly.pi-harness/approved-slice-operation-route
                   :request-id request-id}
          "approved slice operation route query failed"))
