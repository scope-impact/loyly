(ns loyly.cycle-runner.self-check-test
  "Rule: No clean room is built over a failed harness self-check."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.fixtures :as f]))

(deftest item-06-010-a-failing-self-check-fails-the-cycle-at-the-fence-and-the-run-with-it
  (let [w (f/world :self-check "echo checking goldens\necho \"golden checklist differs\"\nexit 3")]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [run (f/progress sys "R-1")]
        (is (= "failed" (:status run)))
        (is (= {:stage "fence" :reason "harness-self-check-failed" :detail "golden checklist differs"}
               (get-in run [:cycle :failure])))
        (is (= "self-check" (:effect (last (:effect-results run)))))
        (is (empty? (f/results-of run "fence")))
        (is (empty? (f/results-of run "regenerate")))
        (is (empty? (f/builder-log (:builder w))))))))

(deftest item-06-011-a-check-the-self-check-skips-is-recorded-as-a-listed-skip
  (let [w (f/world :self-check "echo \"SKIP bb-tools\"\nexit 0")]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [[sc] (f/results-of (f/progress sys "R-1") "self-check")]
        (is (= "recorded" (:outcome sc)))
        (is (= ["regeneration-cycle/harness-self-check-passed"] (f/event-types sc)))
        (is (str/includes? (:detail sc) "bb-tools"))
        (is (= ["bb-tools"] (get-in sc [:events 0 :skipped-checks])))))))

(deftest item-06-038-a-skipped-check-is-recorded-with-the-whole-text-after-skip
  (let [line "SKIP  the oracle cannot judge this tree: a tool it grades is absent."
        text "the oracle cannot judge this tree: a tool it grades is absent."
        w (f/world :self-check (str "echo checking\necho '" line "'\nexit 0"))]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [[sc] (f/results-of (f/progress sys "R-1") "self-check")]
        (is (= ["regeneration-cycle/harness-self-check-passed"] (f/event-types sc)))
        (is (str/includes? (:detail sc) text))
        (is (= [text] (get-in sc [:events 0 :skipped-checks])))))))
