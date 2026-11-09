(ns loyly.cycle-runner.verdicts-test
  "Rule: The verdicts of the gated candidate are handed in, filed as given, and
   the run ends as its cycle ends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.harness :as h]))

(defn- awaiting! [name & [opts]]
  (let [w (h/world! name (or opts {}))]
    (h/start-run w)
    (is (= "awaiting-verdicts" (:status (h/progress "R-1"))))
    w))

(defn- filed [run] (get-in (first (h/effect-rows run "file-score")) [:events 0]))

(deftest item-06-025-verdicts-handed-in-are-filed-as-given-and-the-run-passes-as-its-cycle-passes
  (h/with-product
    (let [w (awaiting! "06-025")
          f (h/verdicts-file! w "verdicts.json" [{:id "01-001" :verdict "pass" :evidence "booked"}
                                                 {:id "01-002" :verdict "fail" :evidence "judged by the main session"}])
          r (h/hand-in "R-1" f)
          run (h/progress "R-1")
          m (filed run)]
      (is (= "cycle-runner/verdicts-handed-in" (get-in r [:result :events 0 :type])) (pr-str r))
      (is (= 2 (get-in r [:result :events 0 :verdicts])))
      (is (= "recorded" (:outcome (first (h/effect-rows run "file-verdicts")))))
      (is (= "recorded" (:outcome (first (h/effect-rows run "file-score")))))
      (is (= "measurement/measurement-filed" (:type m)))
      (is (= [1 2] [(:passes m) (:total m)]))
      (is (= [["01-001" "pass" "booked"] ["01-002" "fail" "judged by the main session"]]
             (mapv (juxt :item-id :verdict :evidence) (:items m))))
      (is (= "passed" (:status run)))
      (is (= "passed" (get-in run [:cycle :status])))
      (is (str/starts-with? (:verdicts-digest run) "sha256:")))))

(deftest item-06-026-an-item-the-verdicts-file-does-not-name-is-measurements-to-record-as-unreached
  (h/with-product
    (let [w (awaiting! "06-026")
          _ (h/hand-in "R-1" (h/verdicts-file! w "v.json" [{:id "01-001" :verdict "pass" :evidence "booked"}]))
          run (h/progress "R-1")
          m (filed run)]
      (is (= {:item-id "01-002" :verdict "fail" :reason "unreached"}
             (select-keys (second (:items m)) [:item-id :verdict :reason])))
      (is (= [1 2] [(:passes m) (:total m)]))
      (is (= "passed" (:status run)))
      (is (= ["01-001"] (map :item-id (get-in (first (h/effect-rows run "file-verdicts")) [:events 0 :verdicts])))
          "the runner filed only what it was handed"))))

(deftest item-06-027-verdicts-for-a-run-that-does-not-exist-are-refused
  (h/with-product
    (let [w (h/world! "06-027" {})
          _ (h/use-env! (:env w))
          r (h/hand-in "R-9" (h/verdicts-file! w "v.json" [{:id "01-001" :verdict "pass" :evidence "x"}]))]
      (is (= "no-run" (get-in r [:error :data :refusal])))
      (is (nil? (h/progress "R-9"))))))

(deftest item-06-028-verdicts-for-a-run-whose-gate-never-passed-are-refused
  (h/with-product
    (let [w (h/world! "06-028" {:candidates [{:suite-exit 1}]})
          _ (h/start-run w)
          _ (is (= "gate-attempts-exhausted" (get-in (h/progress "R-1") [:cycle :failure :reason])))
          r (h/hand-in "R-1" (h/verdicts-file! w "v.json" [{:id "01-001" :verdict "pass" :evidence "x"}]))
          run (h/progress "R-1")]
      (is (= "gate-not-passed" (get-in r [:error :data :refusal])))
      (is (empty? (h/effect-rows run "file-verdicts")))
      (is (= "failed" (:status run))))))

(defn- builds-after-verdicts [run]
  (let [n (count (filter #(#{"file-verdicts"} (:effect %)) (:effect-results run)))]
    (->> (:effect-results run)
         (drop-while #(not= "file-verdicts" (:effect %)))
         (filter #(#{"regenerate" "repair"} (:effect %))))))

(deftest item-06-029-a-second-verdicts-file-is-refused-and-no-builder-follows-the-verdicts
  (h/with-product
    (let [w (awaiting! "06-029")
          launched (h/launch-count (:builder-dir w))
          _ (h/hand-in "R-1" (h/verdicts-file! w "v1.json" [{:id "09-001" :verdict "pass" :evidence "x"}]))
          _ (is (= {:stage "score" :reason "score-stage-failed"}
                   (select-keys (get-in (h/progress "R-1") [:cycle :failure]) [:stage :reason])))
          r (h/hand-in "R-1" (h/verdicts-file! w "v2.json" [{:id "01-001" :verdict "pass" :evidence "x"}]))
          run (h/progress "R-1")]
      (is (= "verdicts-already-handed-in" (get-in r [:error :data :refusal])))
      (is (empty? (builds-after-verdicts run)))
      (is (= launched (h/launch-count (:builder-dir w)))))))

(deftest item-06-030-verdicts-under-a-grader-digest-the-run-did-not-approve-are-refused
  (h/with-product
    (let [w (awaiting! "06-030")
          r (h/hand-in "R-1" (h/verdicts-file! w "v.json" [{:id "01-001" :verdict "pass" :evidence "x"}])
                       :grader-digest "r-2")]
      (is (= "grader-not-approved" (get-in r [:error :data :refusal])))
      (is (= "awaiting-verdicts" (:status (h/progress "R-1")))))))

(deftest item-06-031-a-verdicts-file-that-is-blank-is-refused-and-nothing-is-filed
  (h/with-product
    (let [w (awaiting! "06-031")
          r (h/hand-in "R-1" (h/write! (str (:dir w) "/blank.json") "  \n"))
          run (h/progress "R-1")]
      (is (= "verdicts-not-readable" (get-in r [:error :data :refusal])))
      (is (= "awaiting-verdicts" (:status run)))
      (is (empty? (h/effect-rows run "file-verdicts"))))))

(deftest item-06-032-verdicts-measurement-refuses-fail-the-score-stage-and-the-run-fails-with-its-cycle
  (h/with-product
    (let [w (awaiting! "06-032")
          launched (h/launch-count (:builder-dir w))
          _ (h/hand-in "R-1" (h/verdicts-file! w "v.json" [{:id "01-001" :verdict "pass" :evidence "x"}
                                                         {:id "09-001" :verdict "pass" :evidence "x"}]))
          run (h/progress "R-1")
          fv (first (h/effect-rows run "file-verdicts"))]
      (is (= ["refused" "unknown-item" "measurement"] [(:outcome fv) (:refusal fv) (:context fv)]))
      (is (= "recorded" (:outcome (first (h/effect-rows run "fail-score-stage")))))
      (is (= "failed" (:status run)))
      (is (= {:stage "score" :reason "score-stage-failed"} (select-keys (get-in run [:cycle :failure]) [:stage :reason])))
      (is (empty? (builds-after-verdicts run)))
      (is (= launched (h/launch-count (:builder-dir w)))))))
