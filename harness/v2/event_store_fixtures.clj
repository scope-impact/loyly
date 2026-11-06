;; harness/v2/event_store_fixtures.clj DIR: hand-made Grain SQLite stores for the event-store
;; check's own test (event-store-check-test.sh), written with Grain's own appender, one file
;; per case. Each is named for the verdict the check must give. The model's events of a
;; passing store lie inside a completed run of their ORC workflow, as ORC writes one on the
;; candidate's store (orc-run: only the events the check reads, not every event of a run). Grain's appender validates each
;; event against the malli schema registered under its type, so every type here is registered
;; as an open map: the fixtures may hold anything, and only the check judges them.
(ns event-store-fixtures
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.schema-util.interface :as schema]
            [clojure.java.io :as io]))

(def tenant #uuid "0190a1b2-0000-7000-8000-000000000001")

(def stamped
  {:item-id "07-001" :slice "07" :source "/tmp/plan/07-probe/spec.feature" :line 2
   :title "the probe's scenario" :stamped-at (java.util.Date.)})
(def reported
  {:slice "07" :source "/tmp/plan/07-probe/spec.feature" :lines [9] :reported-at (java.time.Instant/now)})

(def sheet-id #uuid "0190a1b2-0000-7000-8000-0000000000a1")

(defn orc-run
  "EVENTS inside one run of the ORC workflow WORKFLOW as ORC writes it on the store: its sheet,
  then the run's tree-tick-started and, unless STATUS is nil, its tree-tick-completed."
  ([events] (orc-run "specification/stamping" :success events))
  ([workflow status events]
   (let [tick (random-uuid)]
     (concat [[:sheet/sheet-created {:sheet-id sheet-id :name workflow}]
              [:sheet/tree-tick-started {:sheet-id sheet-id :tick-id tick}]]
             events
             (when status [[:sheet/tree-tick-completed {:sheet-id sheet-id :tick-id tick :root-status status}]])))))

(def cases
  {"pass-two-events"       (orc-run [[:specification/scenario-stamped stamped]
                                     [:specification/empty-title-headings-reported reported]])
   "fail-empty"            []
   "fail-unknown-type"     (orc-run [[:specification/scenario-stamped stamped]
                                     [:specification/slice-renamed {:slice "07"}]])
   "fail-extra-field"      (orc-run [[:specification/scenario-stamped (assoc stamped :digest "sha256:00")]])
   "fail-missing-field"    (orc-run [[:specification/scenario-stamped (dissoc stamped :title)]])
   "fail-ill-typed-field"  (orc-run [[:specification/scenario-stamped (assoc stamped :line 0)]])
   "fail-no-orc-run"       [[:specification/scenario-stamped stamped]]
   "fail-run-never-completed" (orc-run "specification/stamping" nil [[:specification/scenario-stamped stamped]])
   "fail-run-failed"       (orc-run "specification/stamping" :failure [[:specification/scenario-stamped stamped]])
   "fail-outside-run"      (concat (orc-run []) [[:specification/scenario-stamped stamped]])
   "fail-other-workflow"   (orc-run "specification-stamping" :success [[:specification/scenario-stamped stamped]])})

(schema/register! (into {} (for [[_ events] cases [t _] events] [t :map])))

(let [[dir] *command-line-args*]
  (doseq [[name events] cases
          :let [path (str (io/file dir (str name ".sqlite")))
                store (es/start {:conn {:type :sqlite :database-file path}})]]
    (try
      (when (seq events)
        (let [r (es/append store {:tenant-id tenant
                                  :events (vec (for [[t b] events] (es/->event {:type t :body b})))})]
          (when (:cognitect.anomalies/category r) (throw (ex-info (str "append failed: " r) {})))))
      (finally (es/stop store)))
    (println "fixture" path))
  (shutdown-agents)
  (System/exit 0))
