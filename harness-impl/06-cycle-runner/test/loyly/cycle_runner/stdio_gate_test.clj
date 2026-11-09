(ns loyly.cycle-runner.stdio-gate-test
  "Rule: A stdio candidate is gated as harness/v2/gate.sh gates it: its suite,
   its identity probe, then the store the probe's candidate wrote."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.harness :as h]
            [loyly.cycle-runner.util :as u]))

(defn- gate [run] (first (filterv #(= 1 (:attempt %)) (h/effect-rows run "gate"))))
(def one-attempt (assoc h/budget :gate-attempt-limit 1))
(defn- lines [f] (if (u/exists? f) (str/split-lines (slurp f)) nil))
(defn- env-names [f] (set (map #(first (str/split % #"=" 2)) (lines f))))

(deftest item-06-039-a-stdio-candidate-whose-suite-probe-and-store-check-pass-passes-the-gate
  (h/with-product
    (let [w (h/world! "06-039" {:seam "stdio"})
          _ (h/start-run w)
          run (h/progress "R-1")
          g (gate run)
          e (get-in g [:events 0])]
      (is (= ["regeneration-cycle/gate-attempt-passed"] (h/event-types g)))
      (is (= [30 1 1 "answered as the context"]
             [(:boot-budget-seconds e) (:boot-polls e) (:answered-on-poll e) (:identity-probe-result e)]))
      (is (empty? (h/effect-rows run "gate-refusal")))
      (is (= "awaiting-verdicts" (:status run))))))

(deftest item-06-040-the-probe-is-handed-the-gate-rooms-launch-entrypoint-the-budget-and-the-gates-store
  (h/with-product
    (let [w (h/world! "06-040" {:seam "stdio"})
          _ (h/start-run w)
          run (h/progress "R-1")
          gate-room (get-in (first (h/effect-rows run "install")) [:events 0 :gate-room])
          [a1 a2 :as args] (lines (str (:dir w) "/probe-args"))
          env (lines (str (:dir w) "/probe-env"))
          gate-store (some #(when (str/starts-with? % "GATE_STORE=") (subs % 11)) env)
          runs-dir (u/abs-path (str (:dir w) "/runs"))]
      (is (= 2 (count args)))
      (is (= (u/abs-path (str gate-room "/impl/start.sh")) a1))
      (is (str/starts-with? a1 "/"))
      (is (= "30" a2))
      (is (some? gate-store))
      (is (str/starts-with? gate-store (str runs-dir "/")))
      (is (not (str/starts-with? gate-store (str gate-room "/"))))
      (is (not-any? #(str/starts-with? % "LOYLY_") env))
      (is (= gate-store (last (lines (str (:dir w) "/store-check-args"))))))))

(deftest item-06-041-a-red-stdio-suite-fails-the-attempt-before-the-probe-and-is-handed-no-store
  (h/with-product
    (let [w (h/world! "06-041" {:seam "stdio" :candidates [{:suite-exit 1}]})
          _ (h/start-run w :budget one-attempt)
          g (gate (h/progress "R-1"))
          names (env-names (str (:dir w) "/suite-env-1"))]
      (is (= ["regeneration-cycle/gate-attempt-failed"] (h/event-types g)))
      (is (= "suite-red" (get-in g [:events 0 :reason])))
      (is (seq names) "the suite recorded its environment")
      (is (not (contains? names "GATE_STORE")))
      (is (not-any? #(str/starts-with? % "LOYLY_") names))
      (is (not (u/exists? (str (:dir w) "/probe-args"))))
      (is (not (u/exists? (str (:dir w) "/store-check-args")))))))

(deftest item-06-042-an-identity-probe-that-fails-fails-the-attempt-and-the-store-is-not-checked
  (h/with-product
    (let [w (h/world! "06-042" {:seam "stdio" :probe-line "no response within the 30s boot budget" :probe-exit 1})
          _ (h/start-run w :budget one-attempt)
          g (gate (h/progress "R-1"))]
      (is (= ["regeneration-cycle/gate-attempt-failed"] (h/event-types g)))
      (is (= "identity-probe-failed" (get-in g [:events 0 :reason])))
      (is (str/includes? (get-in g [:events 0 :detail]) "no response within the 30s boot budget"))
      (is (not (u/exists? (str (:dir w) "/store-check-args")))))))

(deftest item-06-043-a-store-check-that-fails-fails-the-attempt-with-its-output-as-the-detail
  (h/with-product
    (let [said "FAIL event 7 was not written inside a run of the ORC workflow"
          w (h/world! "06-043" {:seam "stdio" :store-check {:line said :exit 1}})
          _ (h/start-run w :budget one-attempt)
          run (h/progress "R-1")
          g (gate run)]
      (is (= ["regeneration-cycle/gate-attempt-failed"] (h/event-types g)))
      (is (= "identity-probe-failed" (get-in g [:events 0 :reason])))
      (is (str/includes? (get-in g [:events 0 :detail]) said))
      (is (= "failed" (:status run)))
      (is (= {:stage "gate" :reason "gate-attempts-exhausted"} (select-keys (get-in run [:cycle :failure]) [:stage :reason]))))))

(deftest item-06-044-a-stdio-candidate-reads-the-spec-beside-it-in-the-gate-room
  (h/with-product
    (let [w (h/world! "06-044" {:seam "stdio"
                                :candidates [{:suite-body "[ -f docs/plan/01-booking-core/spec.feature ] || exit 1"}]})
          _ (h/start-run w)
          run (h/progress "R-1")
          g (gate run)]
      (is (= ["regeneration-cycle/gate-attempt-passed"] (h/event-types g)))
      (is (= (get-in (first (h/effect-rows run "regenerate")) [:events 0 :candidate-digest])
             (get-in (first (h/effect-rows run "install")) [:events 0 :candidate-digest]))))))

(deftest item-06-045-a-stdio-run-whose-store-check-cannot-be-started-halts-at-the-gate
  (h/with-product
    (let [w (h/world! "06-045" {:seam "stdio" :store-check-cmd "/no/such/store-check.sh"})
          _ (h/start-run w)
          run (h/progress "R-1")]
      (is (= "halted" (:status run)))
      (is (= {:effect "gate" :reason "effect-could-not-run"} (select-keys (:halt run) [:effect :reason])))
      (is (empty? (h/effect-rows run "gate"))))))

(deftest item-06-046-the-harness-digest-of-a-stdio-run-covers-the-store-check-command
  (h/with-product
    (let [w (h/world! "06-046" {:seam "stdio" :probe-exit 1})
          dir (:dir w)
          _ (h/write! (str dir "/check-store.sh") "#!/bin/sh\nexit 0\n" :exec true)
          _ (h/start-run w :budget one-attempt :env {"LOYLY_STORE_CHECK_CMD" (str dir "/check-store.sh one")})
          _ (is (#{"failed" "halted"} (:status (h/progress "R-1"))))
          _ (h/start-run w :run-id "R-2" :cycle-id "C-2" :cut-id "K-2" :budget one-attempt
                         :env {"LOYLY_STORE_CHECK_CMD" (str dir "/check-store.sh two")})
          sc (fn [id] (first (h/effect-rows (h/progress id) "self-check")))
          [s1 s2] [(sc "R-1") (sc "R-2")]]
      (is (= ["regeneration-cycle/harness-self-check-passed"] (h/event-types s1) (h/event-types s2)))
      (is (some? (get-in s1 [:events 0 :harness-digest])))
      (is (not= (get-in s1 [:events 0 :harness-digest]) (get-in s2 [:events 0 :harness-digest]))))))
