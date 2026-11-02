(ns loyly.pi-harness.core.runtime.adapters-test
  (:require [clojure.test :refer [deftest is testing]]
            [loyly.pi-harness.core.runtime.adapters :as adapters]
            [loyly.pi-harness.core.runtime.json-process :as json-process]))

(deftest cut-adapter-translates-the-process-boundary
  (let [invocation (atom nil)
        result (with-redefs [json-process/invoke!
                             (fn [script request]
                               (reset! invocation {:script script :request request})
                               {:exit-code 0
                                :stdout ""
                                :stderr ""
                                :response {:ok true :result {:cutId "cut-1"}}})]
                 (adapters/run-cut! "/repo" "abc123" "gondolin"))]
    (is (= {:script "/repo/.pi/harness/src/cut-worker.ts"
            :request {:sourceCommit "abc123" :backend "gondolin"}}
           @invocation))
    (is (= {:cutId "cut-1"} result))))

(deftest adapter-failures-retain-process-diagnostics
  (with-redefs [json-process/invoke!
                (constantly {:exit-code 17
                             :stdout "partial output"
                             :stderr "worker exploded"
                             :response nil})]
    (let [error (try
                  (adapters/run-cut! "/repo" "abc123" "gondolin")
                  nil
                  (catch clojure.lang.ExceptionInfo exception exception))]
      (testing "the exception identifies both the boundary and process status"
        (is (= "cut adapter" (:adapter (ex-data error))))
        (is (= 17 (:exit-code (ex-data error))))
        (is (re-find #"worker exploded" (.getMessage error)))))))
