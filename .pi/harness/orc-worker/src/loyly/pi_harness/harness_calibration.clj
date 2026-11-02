(ns loyly.pi-harness.harness-calibration
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.pi-harness.instrument-oracle :as instrument-oracle]))

(def required-components
  #{"accepted-contract-eligibility"
    "allure-evidence-projection"
    "allure-golden-normalizer"
    "application-contract"
    "black-box-grader"
    "builder-room"
    "candidate-subject-seal"
    "compose-verifier"
    "cut-preparer"
    "cycle-measurement"
    "durable-task-boundary"
    "evidence-manifest"
    "execution-room"
    "grader-evidence-interpreter"
    "instrument-oracle-loader"
    "observation-persistence"
    "prompt-optimizer"
    "runtime-conformance-evaluator"
    "selection-contract"
    "workflow-orchestrator"})

(def required-instrument-ids #{"full-pi-harness"})

(defn validate-component-coverage! [pack]
  (let [actual (->> (:instruments pack) (mapcat :components) set)
        missing (sort (remove actual required-components))]
    (when (seq missing)
      (throw (ex-info (str "instrument oracle is missing calibrated harness components: "
                           (str/join ", " missing))
                      {:required required-components :actual actual})))
    pack))

(defn- actual-golden [fixture passed]
  (let [fixture-id (:id fixture)
        instrument-id (:instrumentId fixture)]
    {:schemaVersion 1
     :name fixture-id
     :fullName (str instrument-id "." fixture-id)
     :status (if passed "passed" "failed")
     :stage "finished"
     :labels {:instrument_id instrument-id :fixture_id fixture-id}
     :parameters [{:name "role" :value (:role fixture)}]
     :attachments []}))

(defn evaluate [pack fixture-results]
  (instrument-oracle/validate! pack required-instrument-ids)
  (validate-component-coverage! pack)
  (let [expected-by-fixture (into {}
                                  (map (fn [golden]
                                         [(get-in golden [:labels :fixture_id]) golden]))
                                  (:goldens pack))
        actual-by-fixture (into {}
                                (map (fn [fixture]
                                       [(:id fixture)
                                        (actual-golden fixture
                                                       (true? (get fixture-results (:id fixture))))]))
                                (:fixtures pack))
        mismatches (->> (:fixtures pack)
                        (keep (fn [fixture]
                                (let [fixture-id (:id fixture)]
                                  (when-not (= (get expected-by-fixture fixture-id)
                                               (get actual-by-fixture fixture-id))
                                    fixture-id))))
                        sort
                        vec)]
    {:passed (empty? mismatches)
     :mismatches mismatches
     :expected expected-by-fixture
     :actual actual-by-fixture}))

(defn- run-command [directory command]
  (let [builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory (io/file directory))
                  (.redirectErrorStream true))
        process (.start builder)
        output-future (future (slurp (.getInputStream process)))
        completed (.waitFor process 180 java.util.concurrent.TimeUnit/SECONDS)]
    (when-not completed
      (.destroyForcibly process))
    {:command command
     :exit-code (if completed (.exitValue process) 124)
     :output (deref output-future 5000 "calibration output did not close")
     :timed-out? (not completed)}))

(defn run-suites [project-root fixtures]
  (let [root (.getCanonicalFile (io/file project-root))
        orc-root (io/file root "orc")
        harness-root (io/file root ".pi/harness")
        worker-src "../.pi/harness/orc-worker/src"
        worker-test "../.pi/harness/orc-worker/test"
        clojure-result
        (run-command orc-root
                     ["clojure" "-Sdeps"
                      (str "{:paths [\"" worker-src "\" \"" worker-test "\"]}")
                      "-M:dev" "-m" "loyly.pi-harness.test-runner"])
        node-result (run-command harness-root ["npm" "test"])
        passed (and (zero? (:exit-code clojure-result))
                    (zero? (:exit-code node-result)))]
    {:fixture-results (into {} (map (juxt :id (constantly passed))) fixtures)
     :suites {:clojure-worker clojure-result
              :node-adapters node-result}}))

(defn calibrate! [project-root pack-ref]
  (let [pack (-> (instrument-oracle/resolve-pack pack-ref)
                 (instrument-oracle/validate! required-instrument-ids)
                 validate-component-coverage!
                 (instrument-oracle/verify-instrument-files! project-root))
        suites (run-suites project-root (:fixtures pack))
        evaluation (evaluate pack (:fixture-results suites))]
    (assoc evaluation
           :oracle-digest (:digest pack)
           :suites (:suites suites))))
