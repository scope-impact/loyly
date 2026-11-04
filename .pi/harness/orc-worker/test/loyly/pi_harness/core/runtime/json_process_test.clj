(ns loyly.pi-harness.core.runtime.json-process-test
  (:require [clojure.test :refer [deftest is]]
            [loyly.pi-harness.core.runtime.json-process :as json-process]))

(deftest supports-a-worker-specific-working-directory
  (let [script (.getCanonicalPath
                (java.io.File. "../.pi/harness/fixtures/fake-worker.mjs"))
        directory (.getCanonicalPath (java.io.File. (System/getProperty "java.io.tmpdir")))
        result (json-process/invoke! script {:method "cwd"} {:working-directory directory})]
    (is (zero? (:exit-code result)))
    (is (= {:ok true :result {:cwd directory}} (:response result)))))

(deftest invokes-a-newline-delimited-json-worker
  (let [script (.getCanonicalPath
                (java.io.File. "../.pi/harness/fixtures/fake-worker.mjs"))
        result (json-process/invoke! script
                                     {:id 1
                                      :method "submit-approved-slice"
                                      :params {:requestId "request-1"}})]
    (is (zero? (:exit-code result)))
    (is (= "" (:stderr result)))
    (is (= {:id 1
            :ok true
            :result {:operation "fresh-generation" :operationId "request-1" :taskId 7}}
           (:response result)))))
