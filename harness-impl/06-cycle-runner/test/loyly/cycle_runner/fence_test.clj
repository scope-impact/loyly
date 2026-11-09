(ns loyly.cycle-runner.fence-test
  "Rules: No clean room is built over a failed harness self-check; the fence
   builds the clean room of the cut and proves it, as legacy fence.sh does."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.harness :as h]))

(deftest item-06-010-a-failing-self-check-fails-the-cycle-at-the-fence-and-the-run-with-it
  (h/with-product
    (let [w (h/world! "06-010" {:self-check {:lines ["checking goldens" "golden checklist differs"] :exit 1}})
          _ (h/start-run w)
          run (h/progress "R-1")]
      (is (= "failed" (:status run)))
      (is (= {:stage "fence" :reason "harness-self-check-failed" :detail "golden checklist differs"}
             (get-in run [:cycle :failure])))
      (is (= "self-check" (:effect (last (:effect-results run)))))
      (is (empty? (h/effect-rows run "fence")))
      (is (empty? (h/effect-rows run "regenerate")))
      (is (= 0 (h/launch-count (:builder-dir w)))))))

(deftest item-06-011-a-check-the-self-check-skips-is-recorded-as-a-listed-skip
  (h/with-product
    (let [w (h/world! "06-011" {:self-check {:lines ["SKIP bb-tools" "ok"] :exit 0}})
          _ (h/start-run w)
          sc (first (h/effect-rows (h/progress "R-1") "self-check"))]
      (is (= ["regeneration-cycle/harness-self-check-passed"] (h/event-types sc)))
      (is (str/includes? (:detail sc) "bb-tools"))
      (is (= ["bb-tools"] (get-in sc [:events 0 :skipped-checks]))))))

(deftest item-06-038-a-skipped-check-is-recorded-with-the-whole-text-after-skip
  (h/with-product
    (let [line "SKIP  the oracle cannot judge this tree: a tool it grades is absent."
          w (h/world! "06-038" {:self-check {:lines [line] :exit 0}})
          _ (h/start-run w)
          sc (first (h/effect-rows (h/progress "R-1") "self-check"))
          whole "the oracle cannot judge this tree: a tool it grades is absent."]
      (is (= ["regeneration-cycle/harness-self-check-passed"] (h/event-types sc)))
      (is (str/includes? (:detail sc) whole))
      (is (= [whole] (get-in sc [:events 0 :skipped-checks]))))))

(deftest item-06-012-a-fenced-path-that-matches-nothing-fails-the-cycle-at-the-fence
  (h/with-product
    (let [w (h/world! "06-012" {:drop-files ["harness/cycles/c1/score.txt"]})
          _ (h/start-run w)
          run (h/progress "R-1")
          fence (first (h/effect-rows run "fence"))
          rows (:effect-results run)
          after (second (drop-while #(not= "fence" (:effect %)) rows))]
      (is (= "refused" (:outcome fence)))
      (is (= "fenced-path-matched-nothing" (:refusal fence)))
      (is (= "regeneration-cycle" (:context fence)))
      (is (= ["fail-cycle" "recorded"] [(:effect after) (:outcome after)]))
      (is (= "failed" (:status run)))
      (is (= {:stage "fence" :reason "fence-refused"} (select-keys (get-in run [:cycle :failure]) [:stage :reason])))
      (is (= 0 (h/launch-count (:builder-dir w)))))))

(deftest item-06-013-the-fresh-builder-sees-the-clean-room-of-one-commit-with-no-fenced-path
  (h/with-product
    (let [w (h/world! "06-013" {})
          _ (h/start-run w)
          d (:builder-dir w)
          paths (set (str/split-lines (slurp (str d "/paths-1"))))
          objects (map #(second (str/split % #" " 2)) (str/split-lines (slurp (str d "/objects-1"))))]
      (is (contains? paths "./docs/plan/01-booking-core/spec.feature"))
      (is (contains? paths "./harness/config.sh"))
      (is (contains? paths "./impl") "the candidate scope is there")
      (is (not-any? #(str/starts-with? % "./impl/") paths) "and empty")
      (is (= "1" (str/trim (slurp (str d "/commits-1")))))
      (is (not-any? #(or (str/starts-with? % "./harness/cycles") ) paths))
      (is (not-any? #(and % (or (= % "impl") (str/starts-with? % "impl/")
                                (str/starts-with? % "harness/cycles"))) objects)))))
