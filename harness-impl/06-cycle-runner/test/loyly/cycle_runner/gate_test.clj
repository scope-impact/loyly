(ns loyly.cycle-runner.gate-test
  "Rule: The gate grades a copy of the candidate on the host, in legacy
   verify.sh's order."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.harness :as h]
            [loyly.cycle-runner.util :as u])
  (:import [java.net InetAddress ServerSocket]))

(defn- gate-rows [run attempt] (filterv #(= attempt (:attempt %)) (h/effect-rows run "gate")))

(deftest item-06-018-something-answering-on-the-port-is-a-gate-refusal-and-three-in-a-row-fail-the-cycle
  (h/with-product
    (let [w (h/world! "06-018" {})]
      (with-open [_ (ServerSocket. (int (:port w)) 50 (InetAddress/getByName "127.0.0.1"))]
        (h/start-run w))
      (let [run (h/progress "R-1")
            gates (gate-rows run 1)
            tail (drop-while #(not= "gate" (:effect %)) (:effect-results run))]
        (is (= 3 (count gates)))
        (is (every? #(= ["refused" "seam-answered-before-boot"] [(:outcome %) (:refusal %)]) gates))
        (is (= (take 6 (map :effect tail)) ["gate" "gate-refusal" "gate" "gate-refusal" "gate" "gate-refusal"]))
        (is (every? #(= "recorded" (:outcome %)) (h/effect-rows run "gate-refusal")))
        (is (= "failed" (:status run)))
        (is (= {:stage "gate" :reason "infrastructure"} (select-keys (get-in run [:cycle :failure]) [:stage :reason])))
        (is (not (u/exists? (:ran-file w))) "the launch entrypoint was never started")))))

(deftest item-06-019-a-red-suite-fails-the-attempt-before-the-candidate-is-booted
  (h/with-product
    (let [w (h/world! "06-019" {:candidates [{:suite-exit 1}]})
          _ (h/start-run w :budget (assoc h/budget :gate-attempt-limit 1))
          run (h/progress "R-1")
          gate (first (gate-rows run 1))]
      (is (= ["regeneration-cycle/gate-attempt-failed"] (h/event-types gate)))
      (is (= "suite-red" (get-in gate [:events 0 :reason])))
      (is (not (u/exists? (:ran-file w))))
      (is (= "failed" (:status run)))
      (is (= {:stage "gate" :reason "gate-attempts-exhausted"} (select-keys (get-in run [:cycle :failure]) [:stage :reason]))))))

(deftest item-06-020-a-candidate-with-no-launch-entrypoint-fails-the-attempt-for-a-missing-contract
  (h/with-product
    (let [w (h/world! "06-020" {:candidates [{:start? false}]})
          _ (h/start-run w :budget (assoc h/budget :gate-attempt-limit 1))
          gate (first (gate-rows (h/progress "R-1") 1))]
      (is (= ["regeneration-cycle/gate-attempt-failed"] (h/event-types gate)))
      (is (= "contract-missing" (get-in gate [:events 0 :reason]))))))

(deftest item-06-021-a-served-page-without-the-marker-fails-the-attempt-for-its-identity
  (h/with-product
    (let [w (h/world! "06-021" {:candidates [{:page "<html>a gym</html>"}]})
          _ (h/start-run w :budget (assoc h/budget :gate-attempt-limit 1))
          gate (first (gate-rows (h/progress "R-1") 1))]
      (is (= ["regeneration-cycle/gate-attempt-failed"] (h/event-types gate)))
      (is (= "identity-probe-failed" (get-in gate [:events 0 :reason])))
      (is (u/exists? (:ran-file w)) "the candidate was booted"))))

(deftest item-06-022-what-a-builder-writes-after-it-ended-does-not-reach-the-gate
  (h/with-product
    (let [w (h/world! "06-022" {:builder {:after-exit "printf '#!/bin/sh\\nexit 1\\n' > \"$room/impl/test.sh\""}})
          _ (h/start-run w)
          run (h/progress "R-1")
          gate (first (gate-rows run 1))
          regen (first (h/effect-rows run "regenerate"))
          install (first (h/effect-rows run "install"))]
      (Thread/sleep 2500)
      (is (str/includes? (slurp (str (str/trim (slurp (str (:builder-dir w) "/room-1"))) "/impl/test.sh")) "exit 1")
          "the builder did keep writing after it ended")
      (is (= ["regeneration-cycle/gate-attempt-passed"] (h/event-types gate)))
      (is (= (get-in regen [:events 0 :candidate-digest]) (get-in install [:events 0 :candidate-digest]))))))

(deftest item-06-036-the-identity-probe-is-handed-the-served-url
  (h/with-product
    (let [w (h/world! "06-036" {})
          _ (h/start-run w)
          run (h/progress "R-1")
          gate (first (gate-rows run 1))]
      (is (= ["regeneration-cycle/gate-attempt-passed"] (h/event-types gate)))
      (is (= [(str "http://127.0.0.1:" (:port w))] (str/split-lines (slurp (str (:dir w) "/probe-log")))))
      (is (= "awaiting-verdicts" (:status run))))))

(deftest item-06-037-a-candidate-whose-suite-writes-inside-its-own-directory-passes-the-gate
  (h/with-product
    (let [w (h/world! "06-037" {:candidates [{:suite-body "d=\"$(dirname \"$0\")/suite-tmp\"; mkdir \"$d\" && touch \"$d/x\" && rm -r \"$d\" || exit 1"}]})
          _ (h/start-run w)
          run (h/progress "R-1")
          gate (first (gate-rows run 1))]
      (is (= ["regeneration-cycle/gate-attempt-passed"] (h/event-types gate)))
      (is (= (get-in (first (h/effect-rows run "regenerate")) [:events 0 :candidate-digest])
             (get-in (first (h/effect-rows run "install")) [:events 0 :candidate-digest]))))))
