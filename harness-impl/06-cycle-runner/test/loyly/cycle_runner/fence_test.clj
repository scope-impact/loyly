(ns loyly.cycle-runner.fence-test
  "Rule: The fence builds the clean room of the cut and proves it, as legacy fence.sh does."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.fixtures :as f]))

(deftest item-06-012-a-fenced-path-that-matches-nothing-fails-the-cycle-at-the-fence
  (let [w (f/world :extra {"harness/cycles/cycle-1.txt" nil})]
    ;; the commit holds impl but no harness/cycles
    (f/git! (get-in w [:repo :dir]) "rm" "-r" "-q" "harness/cycles")
    (f/git! (get-in w [:repo :dir]) "commit" "-q" "-m" "no cycles")
    (let [commit (f/git! (get-in w [:repo :dir]) "rev-parse" "HEAD")]
      (f/with-runner [sys w]
        (f/start! sys w :commit commit)
        (let [run (f/progress sys "R-1")
              rs (:effect-results run)
              i (first (keep-indexed #(when (= "fence" (:effect %2)) %1) rs))
              fence (nth rs i)
              next-r (nth rs (inc i))]
          (is (= "refused" (:outcome fence)))
          (is (= "fenced-path-matched-nothing" (:refusal fence)))
          (is (= "regeneration-cycle" (:context fence)))
          (is (= "fail-cycle" (:effect next-r)))
          (is (= "recorded" (:outcome next-r)))
          (is (= "failed" (:status run)))
          (is (= ["fence" "fence-refused"] ((juxt :stage :reason) (get-in run [:cycle :failure]))))
          (is (empty? (f/builder-log (:builder w))) "no builder was launched"))))))

(deftest item-06-013-the-fresh-builder-sees-the-clean-room-of-one-commit-with-no-fenced-path
  (let [w (f/world)]
    (f/with-runner [sys w]
      (f/start! sys w)
      (is (= "awaiting-verdicts" (:status (f/progress sys "R-1"))))
      (let [d (get-in w [:builder :dir])
            paths (set (str/split-lines (slurp (io/file d "paths-1.txt"))))
            objects (slurp (io/file d "objects-1.txt"))]
        (is (contains? paths "./docs/plan/01-booking-core/spec.feature"))
        (is (contains? paths "./harness/config.sh"))
        (is (contains? paths "./impl") "an empty candidate scope")
        (is (not-any? #(str/starts-with? % "./impl/") paths))
        (is (not-any? #(str/starts-with? % "./harness/cycles") paths))
        (is (= "1" (str/trim (slurp (io/file d "commits-1.txt")))))
        (is (not (re-find #" impl(/|$)" objects)))
        (is (not (re-find #"(?m) harness/cycles" objects)))
        (is (re-find #"(?m) harness/config.sh$" objects))))))
