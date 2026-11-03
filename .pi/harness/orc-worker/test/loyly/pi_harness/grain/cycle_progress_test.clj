(ns loyly.pi-harness.grain.cycle-progress-test
  (:require [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.orc.orc-service.interface.schemas]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.grain.interface :as model]))

(deftest latest-cycle-progress-survives-an-event-store-restart
  (let [db (.getAbsolutePath (java.io.File/createTempFile "loyly-progress-" ".sqlite"))
        tenant #uuid "27e205ec-80c4-3e7c-86ca-9a8cc66e51e7"
        tick-id #uuid "f36dc9b7-9b92-4e15-84f5-1b9c4b798416"
        sheet-id #uuid "952b704e-c4e6-44aa-859c-b54594f1f06d"
        node-id #uuid "bd7dff38-2cfe-43cc-9712-0d7743ea53c1"
        latest {:cycle-id "cycle-37"
                :tick-id (str tick-id)
                :stage (str node-id)
                :attempt 1
                :status "completed"}
        facts [(event-store/->event
                 {:type :sheet/tree-tick-started
                  :tags #{[:sheet sheet-id] [:tick tick-id]}
                  :body {:sheet-id sheet-id :tick-id tick-id}})
                (event-store/->event
                 {:type :sheet/node-execution-started
                  :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
                  :body {:sheet-id sheet-id :tick-id tick-id :node-id node-id
                         :inputs {:request-id "cycle-37"}}})
                (event-store/->event
                 {:type :sheet/node-execution-completed
                  :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
                  :body {:sheet-id sheet-id :tick-id tick-id :node-id node-id
                         :status :success}})
                (event-store/->event
                 {:type :sheet/tree-tick-completed
                  :tags #{[:sheet sheet-id] [:tick tick-id]}
                  :body {:sheet-id sheet-id :tick-id tick-id :root-status :success}})]]
    (.delete (java.io.File. db))
    (try
      (let [events (event-store/start {:conn {:type :sqlite :database-file db}})]
        (try
          (event-store/append events {:tenant-id tenant :events facts})
          (finally (event-store/stop events))))
      (let [events (event-store/start {:conn {:type :sqlite :database-file db}})]
        (try
          (is (= latest
                 (model/find-cycle-progress
                  {:event-store events :tenant-id tenant} (str tick-id))))
          (finally (event-store/stop events))))
      (finally (.delete (java.io.File. db))))))
