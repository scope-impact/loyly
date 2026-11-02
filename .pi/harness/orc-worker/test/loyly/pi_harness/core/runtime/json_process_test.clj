(ns loyly.pi-harness.core.runtime.json-process-test
  (:require [clojure.test :refer [deftest is]]
            [loyly.pi-harness.core.runtime.json-process :as json-process]))

(deftest invokes-a-newline-delimited-json-worker
  (let [script (.getCanonicalPath
                (java.io.File. "../.pi/harness/fixtures/fake-worker.mjs"))
        result (json-process/invoke! script
                                     {:id 1
                                      :method "submit"
                                      :params {:requestId "request-1"}})]
    (is (zero? (:exit-code result)))
    (is (= "" (:stderr result)))
    (is (= {:id 1
            :ok true
            :result {:cycleId "request-1" :taskId 7}}
           (:response result)))))
