(ns loyly.cycle-runner.gate-test
  "Rule: The gate grades a copy of the candidate on the host, in legacy verify.sh's order."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.fixtures :as f])
  (:import [java.net ServerSocket InetAddress]))

(defn- gate-results [run n] (filterv #(= n (:attempt %)) (f/results-of run "gate")))

(deftest item-06-018-something-answering-on-the-port-is-a-gate-refusal-three-fail-the-cycle
  (let [base (f/temp-dir "cands")
        marker (str base "/started")
        w (f/world :candidates [(f/candidate! (str base "/good") :start-marker-file marker)])]
    (with-open [_held (ServerSocket. (:port w) 50 (InetAddress/getByName "127.0.0.1"))]
      (f/with-runner [sys w]
        (f/start! sys w)
        (let [run (f/progress sys "R-1")
              gates (gate-results run 1)
              rs (:effect-results run)]
          (is (= 3 (count gates)))
          (is (every? #(and (= "refused" (:outcome %)) (= "seam-answered-before-boot" (:refusal %))) gates))
          (doseq [g gates]
            (let [nxt (nth rs (:position g))]
              (is (= ["gate-refusal" "recorded"] ((juxt :effect :outcome) nxt)))))
          (is (= "failed" (:status run)))
          (is (= ["gate" "infrastructure"] ((juxt :stage :reason) (get-in run [:cycle :failure]))))
          (is (not (.exists (io/file marker))) "the candidate's launch entrypoint was never started"))))))

(deftest item-06-019-a-red-suite-fails-the-attempt-before-the-candidate-is-booted
  (let [base (f/temp-dir "cands")
        marker (str base "/started")
        w (f/world :candidates [(f/candidate! (str base "/red") :suite-exit 1 :start-marker-file marker)])]
    (f/with-runner [sys w]
      (f/start! sys w :budget (f/budget-with :limit 1))
      (let [run (f/progress sys "R-1")
            [g] (gate-results run 1)]
        (is (= ["regeneration-cycle/gate-attempt-failed"] (f/event-types g)))
        (is (= "suite-red" (get-in g [:events 0 :reason])))
        (is (not (.exists (io/file marker))) "the launch entrypoint never ran")
        (is (= "failed" (:status run)))
        (is (= ["gate" "gate-attempts-exhausted"] ((juxt :stage :reason) (get-in run [:cycle :failure]))))))))

(deftest item-06-020-a-candidate-with-no-launch-entrypoint-fails-for-a-missing-contract
  (let [base (f/temp-dir "cands")
        w (f/world :candidates [(f/candidate! (str base "/nostart") :no-start true)])]
    (f/with-runner [sys w]
      (f/start! sys w :budget (f/budget-with :limit 1))
      (let [[g] (gate-results (f/progress sys "R-1") 1)]
        (is (= ["regeneration-cycle/gate-attempt-failed"] (f/event-types g)))
        (is (= "contract-missing" (get-in g [:events 0 :reason])))))))

(deftest item-06-021-a-served-page-without-the-marker-fails-the-attempt-for-its-identity
  (let [base (f/temp-dir "cands")
        w (f/world :candidates [(f/candidate! (str base "/nomarker") :page "<html><body>A gym</body></html>")])]
    (f/with-runner [sys w]
      (f/start! sys w :budget (f/budget-with :limit 1))
      (let [[g] (gate-results (f/progress sys "R-1") 1)]
        (is (= ["regeneration-cycle/gate-attempt-failed"] (f/event-types g)))
        (is (= "identity-probe-failed" (get-in g [:events 0 :reason])))))))

(deftest item-06-022-what-a-builder-writes-after-it-ended-does-not-reach-the-gate
  (let [w (f/world :builder-opts {:after-exit "sleep 1; printf '#!/bin/bash\\nexit 1\\n' > \"$room/impl/test.sh\""})]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [run (f/progress sys "R-1")
            [g] (gate-results run 1)
            [rg] (f/results-of run "regenerate")
            [in] (f/results-of run "install")]
        (is (= ["regeneration-cycle/gate-attempt-passed"] (f/event-types g)))
        (is (= (get-in rg [:events 0 :candidate-digest]) (get-in in [:events 0 :candidate-digest])))
        (Thread/sleep 1500)
        (let [room (io/file (get-in in [:events 0 :gate-room]) ".." ".." "builder-rooms" "attempt-1")]
          (is (str/includes? (slurp (io/file room "impl" "test.sh")) "exit 1")
              "the builder did keep writing after it ended"))))))

(deftest item-06-036-the-identity-probe-is-handed-the-served-url
  (let [w (f/world)]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [run (f/progress sys "R-1")
            [g] (gate-results run 1)]
        (is (= ["regeneration-cycle/gate-attempt-passed"] (f/event-types g)))
        (is (= "this is the sauna" (get-in g [:events 0 :identity-probe-result])))
        (is (= "awaiting-verdicts" (:status run)))))))

(deftest item-06-037-a-candidate-whose-suite-writes-inside-its-own-directory-passes-the-gate
  (let [base (f/temp-dir "cands")
        w (f/world :candidates [(f/candidate! (str base "/writer")
                                              :test-text (str "#!/bin/bash\nset -e\nd=$(mktemp -d \"$(dirname \"$0\")/tmp.XXXXXX\")\n"
                                                              "echo scratch > \"$d/f\"\nrm -rf \"$d\"\nexit 0\n"))])]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [run (f/progress sys "R-1")
            [g] (gate-results run 1)
            [rg] (f/results-of run "regenerate")
            [in] (f/results-of run "install")]
        (is (= ["regeneration-cycle/gate-attempt-passed"] (f/event-types g)) (:detail g))
        (is (= (get-in rg [:events 0 :candidate-digest]) (get-in in [:events 0 :candidate-digest])))))))
