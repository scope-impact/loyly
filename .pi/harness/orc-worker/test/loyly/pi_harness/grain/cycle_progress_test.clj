(ns loyly.pi-harness.grain.cycle-progress-test
  (:require [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.grain.interface :as model]))

(deftest latest-cycle-progress-survives-an-event-store-restart
  (let [db (.getAbsolutePath (java.io.File/createTempFile "loyly-progress-" ".sqlite"))
        tenant #uuid "27e205ec-80c4-3e7c-86ca-9a8cc66e51e7"
        initial {:cycle-id "cycle-37"
                 :tick-id "tick-37"
                 :stage "builder"
                 :attempt 1
                 :builder-session-ref "builder-session-01"
                 :status "running"}
        latest (assoc initial :stage "verify" :status "completed")]
    (.delete (java.io.File. db))
    (try
      (let [events (event-store/start {:conn {:type :sqlite :database-file db}})
            context {:event-store events :tenant-id tenant}]
        (try
          (is (= initial (model/record-cycle-progress! context initial)))
          (is (= latest (model/record-cycle-progress! context latest)))
          (finally (event-store/stop events))))
      (let [events (event-store/start {:conn {:type :sqlite :database-file db}})]
        (try
          (is (= latest
                 (model/find-cycle-progress
                  {:event-store events :tenant-id tenant} "tick-37")))
          (finally (event-store/stop events))))
      (finally (.delete (java.io.File. db))))))
