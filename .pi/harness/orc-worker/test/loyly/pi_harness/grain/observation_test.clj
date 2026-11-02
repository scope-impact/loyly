(ns loyly.pi-harness.grain.observation-test
  (:require [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.grain.core.read-models :as read-models]
            [loyly.pi-harness.grain.observation :as observation]))

(def tool-observation
  {:cycle-id "cycle-02"
   :cut-digest "cut-abc"
   :project-id "loyly"
   :container-id "loyly"
   :component-id "booking-core"
   :item-id "@item-01-007"
   :subject-digest "subject-def"
   :producer "candidate-suite"
   :evidence-kind "tool"
   :result "pass"
   :reached? true
   :evidence "candidate suite exercised all three derived views"
   :oracle-digest "oracle-123"})

(deftest common-observations-survive-an-event-store-restart
  (let [database-file (.getAbsolutePath
                       (java.io.File/createTempFile "loyly-observations-" ".sqlite"))
        tenant #uuid "27e205ec-80c4-3e7c-86ca-9a8cc66e51e7"]
    (.delete (java.io.File. database-file))
    (try
      (let [events (event-store/start {:conn {:type :sqlite :database-file database-file}})
            context {:event-store events :tenant-id tenant}]
        (try
          (is (= tool-observation (observation/record! context tool-observation)))
          (is (= tool-observation (observation/record! context tool-observation)))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"identity already names different evidence"
               (observation/record! context (assoc tool-observation :result "fail"))))
          (finally (event-store/stop events))))
      (let [events (event-store/start {:conn {:type :sqlite :database-file database-file}})]
        (try
          (is (= [tool-observation]
                 (observation/find-cycle
                  {:event-store events :tenant-id tenant} "cycle-02")))
          (finally (event-store/stop events))))
      (finally (.delete (java.io.File. database-file))))))

(deftest record-waits-for-the-durable-projection
  (let [database-file (.getAbsolutePath
                       (java.io.File/createTempFile "loyly-observation-lag-" ".sqlite"))
        tenant #uuid "27e205ec-80c4-3e7c-86ca-9a8cc66e51e7"
        reads (atom 0)]
    (.delete (java.io.File. database-file))
    (try
      (let [events (event-store/start {:conn {:type :sqlite :database-file database-file}})
            context {:event-store events :tenant-id tenant :cache ::stale-cache}]
        (try
          (with-redefs [read-models/find-cycle
                        (fn [read-context _]
                          (swap! reads inc)
                          (if (:cache read-context) [] [tool-observation]))]
            (is (= tool-observation
                   (observation/record! context tool-observation)))
            (is (= 2 @reads)))
          (finally (event-store/stop events))))
      (finally (.delete (java.io.File. database-file))))))
