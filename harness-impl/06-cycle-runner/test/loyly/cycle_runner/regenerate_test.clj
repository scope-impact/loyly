(ns loyly.cycle-runner.regenerate-test
  "Rules: A regenerate launches one fresh builder and records what it left in
   its candidate scope; a failed gate attempt is repaired by a fresh builder
   that sees only the previous candidate and the gate's evidence."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.harness :as h]
            [loyly.cycle-runner.standins :as standins]))

(def red {:suite-exit 1})

(deftest item-06-014-each-regenerate-launches-one-builder-and-waits-for-it
  (h/with-product
    (let [w (h/world! "06-014" {:candidates [red {}]})
          _ (h/start-run w)
          run (h/progress "R-1")]
      (is (= 2 (h/launch-count (:builder-dir w))))
      (is (= ["start 1" "end 1" "start 2" "end 2"] (h/launches (:builder-dir w))))
      (is (= ["regenerate" "repair"] (mapv :effect (filter #(#{"regenerate" "repair"} (:effect %)) (:effect-results run)))))
      (is (= "awaiting-verdicts" (:status run))))))

(deftest item-06-015-a-builder-that-exits-non-zero-but-leaves-a-candidate-is-gated
  (h/with-product
    (let [w (h/world! "06-015" {:builder {:exit 1}})
          _ (h/start-run w)
          run (h/progress "R-1")
          regen (first (h/effect-rows run "regenerate"))
          digest (get-in regen [:events 0 :candidate-digest])]
      (is (= "recorded" (:outcome regen)))
      (is (str/starts-with? digest "sha256:"))
      (is (str/includes? (:detail regen) digest))
      (is (= digest (:candidate-digest run)))
      (is (= "awaiting-verdicts" (:status run))))))

(deftest item-06-016-a-builder-that-leaves-nothing-fails-the-cycle-at-regenerate
  (h/with-product
    (let [w (h/world! "06-016" {:candidates [:empty]})
          _ (h/start-run w)
          run (h/progress "R-1")]
      (is (empty? (h/effect-rows run "regenerate")))
      (is (= "recorded" (:outcome (first (h/effect-rows run "fail-cycle")))))
      (is (= "failed" (:status run)))
      (is (= {:stage "regenerate" :reason "candidate-not-regenerated"}
             (select-keys (get-in run [:cycle :failure]) [:stage :reason]))))))

(defn- alive? [pid]
  (let [h (java.lang.ProcessHandle/of (long pid))]
    (and (.isPresent h) (.isAlive (.get h)))))

(deftest item-06-017-a-builder-that-outlives-the-regenerate-timeout-is-stopped
  (h/with-product
    (let [w (h/world! "06-017" {:builder {:sleep 30}})
          t0 (System/currentTimeMillis)
          _ (h/start-run w :budget (assoc-in h/budget [:stage-timeouts-seconds :regenerate] 2))
          run (h/progress "R-1")
          pid (parse-long (str/trim (slurp (str (:builder-dir w) "/pid-1"))))]
      (is (< (- (System/currentTimeMillis) t0) 25000) "the run did not wait for the builder")
      (is (not (alive? pid)) "the stub builder was stopped")
      (is (not (some #{"end 1"} (h/launches (:builder-dir w)))))
      (is (= "failed" (:status run)))
      (is (= {:stage "regenerate" :reason "budget"} (select-keys (get-in run [:cycle :failure]) [:stage :reason]))))))

(deftest item-06-023-a-repair-builder-sees-the-previous-candidate-and-the-gate-evidence-read-only
  (h/with-product
    (let [w (h/world! "06-023" {:candidates [red {}]})
          _ (h/start-run w)
          run (h/progress "R-1")
          gate1 (first (h/effect-rows run "gate"))
          evidence (get-in gate1 [:events 0 :evidence-root])
          repair (first (h/effect-rows run "repair"))
          handed (some #(when (and (= "regeneration-cycle/record-regenerated-candidate" (first %))
                                   (= 2 (:attempt (second %))))
                          (second %))
                       (standins/requests "regeneration-cycle"))
          d (:builder-dir w)
          paths (set (str/split-lines (slurp (str d "/paths-2"))))
          writable (str/split-lines (slurp (str d "/writable-2")))]
      (is (= 2 (:attempt repair)))
      (is (= "recorded" (:outcome repair)))
      (is (= [evidence] (:mounted-evidence handed)))
      (is (= "verification-repair" (:purpose handed)))
      (is (str/includes? (:detail repair) "gate-evidence-attempt-1"))
      (is (contains? paths "./docs/plan/01-booking-core/spec.feature") "the clean room")
      (is (contains? paths "./.repair/previous-candidate/test.sh") "the previous candidate")
      (is (some #(str/starts-with? % "./.repair/gate-evidence-attempt-1/") paths) "the gate evidence of attempt 1")
      (is (= 2 (count writable)))
      (is (every? #(str/starts-with? % "read-only") writable) (pr-str writable)))))

(deftest item-06-024-three-failed-attempts-fail-the-cycle-and-no-fourth-builder-is-launched
  (h/with-product
    (let [w (h/world! "06-024" {:candidates [red]})
          _ (h/start-run w)
          run (h/progress "R-1")]
      (is (= 3 (h/launch-count (:builder-dir w))))
      (is (= "failed" (:status run)))
      (is (= {:stage "gate" :reason "gate-attempts-exhausted"} (select-keys (get-in run [:cycle :failure]) [:stage :reason]))))))
