;; harness/v2/event_store_fixtures.clj DIR: hand-made Grain SQLite stores for the event-store
;; check's own test (event-store-check-test.sh), written with Grain's own appender, one file
;; per case. Each is named for the verdict the check must give. Grain's appender validates each
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

(def cases
  {"pass-two-events"       [[:specification/scenario-stamped stamped]
                            [:specification/empty-title-headings-reported reported]]
   "fail-empty"            []
   "fail-unknown-type"     [[:specification/scenario-stamped stamped]
                            [:specification/slice-renamed {:slice "07"}]]
   "fail-extra-field"      [[:specification/scenario-stamped (assoc stamped :digest "sha256:00")]]
   "fail-missing-field"    [[:specification/scenario-stamped (dissoc stamped :title)]]
   "fail-ill-typed-field"  [[:specification/scenario-stamped (assoc stamped :line 0)]]})

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
