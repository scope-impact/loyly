(ns measurement.queries
  "The queries of the event model, each a Grain defquery of the same name,
   answered from its read model."
  (:require [ai.obney.grain.query-processor.interface :refer [defquery]]
            [measurement.read-models :as rm]))

(defquery :measurement checklist
  {:grain.event-model/reads #{:measurement/checklists}}
  "One cut's checklist, or nil when none was derived."
  [{:keys [query] :as ctx}]
  {:query/result {:checklist (get (rm/project ctx :measurement/checklists) (:cut-id query))}})

(defquery :measurement cycle-measurement
  {:grain.event-model/reads #{:measurement/cycle-measurements}}
  "One cycle's measurement receipt, or nil while its score is awaited or when it was never gated."
  [{:keys [query] :as ctx}]
  {:query/result {:measurement (get-in (rm/project ctx :measurement/cycle-measurements)
                                       [:rows (:cycle-id query)])}})

(defquery :measurement scoreboard
  {:grain.event-model/reads #{:measurement/scoreboards}}
  "One cycle's scoreboard with its restatement, or nil when none was reported."
  [{:keys [query] :as ctx}]
  {:query/result
   {:scoreboard (when-let [{:keys [scoreboard restatement]}
                           (get (rm/project ctx :measurement/scoreboards) (:cycle-id query))]
                  (when scoreboard
                    (assoc scoreboard :restatement restatement)))}})

(defquery :measurement measurements-to-report
  {:grain.event-model/reads #{:measurement/measurements-to-report}}
  "The report-scoreboard-once policy's to-do list: filed measurements with no scoreboard yet."
  [ctx]
  {:query/result {:cycle-ids (vec (:todo (rm/project ctx :measurement/measurements-to-report)))}})
