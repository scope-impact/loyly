(ns loyly.pi-harness.grain.candidate-lineage-test
  (:require [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.grain.interface :as model]))

(deftest latest-candidate-lineage-survives-an-event-store-restart
  (let [db (.getAbsolutePath (java.io.File/createTempFile "loyly-candidate-" ".sqlite"))
        tenant #uuid "27e205ec-80c4-3e7c-86ca-9a8cc66e51e7"
        generation {:candidate-id "candidate-1"
                    :project-id "loyly" :container-id "loyly"
                    :component-id "booking-core" :operation "generation"
                    :selection-id "selection-1" :cut "cut-1"
                    :output-root "/state/candidates/1"
                    :subject-digest "sha256:candidate-1" :disposition "ungraded"}
        regeneration (assoc generation :candidate-id "candidate-2"
                            :operation "regeneration" :parent-candidate-id "candidate-1"
                            :selection-id "selection-2"
                            :cut "cut-2" :output-root "/state/candidates/2"
                            :subject-digest "sha256:candidate-2" :disposition "measured")
        route {:request-id "request-2" :request-digest "sha256:request-2"
               :project-id "loyly" :container-id "loyly"
               :component-id "booking-core" :selection-id "selection-2"
               :operation "regeneration" :parent-candidate-id "candidate-1"
               :parent-candidate-root "/state/candidates/1"
               :parent-candidate-digest "sha256:candidate-1"}]
    (.delete (java.io.File. db))
    (try
      (let [events (event-store/start {:conn {:type :sqlite :database-file db}})
            context {:event-store events :tenant-id tenant}]
        (try
          (is (= generation (model/record-generated-candidate! context generation)))
          (is (= regeneration (model/record-generated-candidate! context regeneration)))
          (is (= route (model/record-approved-slice-route! context route)))
          (finally (event-store/stop events))))
      (let [events (event-store/start {:conn {:type :sqlite :database-file db}})]
        (try
          (is (= regeneration
                 (model/find-latest-candidate
                  {:event-store events :tenant-id tenant :cache ::untrusted-projection-cache}
                  "loyly" "loyly" "booking-core")))
          (is (= route
                 (model/find-approved-slice-route
                  {:event-store events :tenant-id tenant} "request-2")))
          (finally (event-store/stop events))))
      (finally (.delete (java.io.File. db))))))
