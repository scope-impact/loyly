(ns loyly.pi-harness.grain.grader-approval-test
  (:require [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.demos.pi-agent-loop.system :as system]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.grain.interface :as model]))

(defn- approval [digest items at]
  {:grader-digest digest :entry "grade.py" :items items
   :approved-by "operator" :approved-at at})

(deftest grader-approval-is-a-grain-fact-that-refuses-overlapping-items
  (let [runtime (system/start!)
        context (:context runtime)
        first-grader (approval "sha256:first" ["02-002" "09-001"] "2026-11-05T10:00:00Z")
        second-grader (approval "sha256:second" ["01-005"] "2026-11-05T11:00:00Z")]
    (try
      (is (= [] (model/approved-graders context)))
      (is (= first-grader (model/approve-grader! context first-grader)))
      (is (= first-grader (model/approve-grader! context (assoc first-grader :approved-at "later")))
          "approving the same digest again returns the first approval")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already covered by an approved grader: 09-001"
                            (model/approve-grader! context (approval "sha256:overlap" ["09-001"] "x"))))
      (is (= second-grader (model/approve-grader! context second-grader)))
      (is (= [first-grader second-grader] (model/approved-graders context)))
      (finally (system/stop! runtime)))))
