(ns loyly.pi-harness.core.oracle.harness-calibration
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.pi-harness.core.oracle.harness-snapshot :as harness-snapshot]))

(def required-components
  #{"accepted-contract-eligibility"
    "allure-evidence-projection"
    "allure-golden-normalizer"
    "production-prompt-renderer"
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
    "harness-snapshot-builder"
    "observation-persistence"
    "prompt-optimizer"
    "runtime-conformance-evaluator"
    "selection-contract"
    "workflow-orchestrator"})

(defn validate-component-coverage! [pack]
  (let [actual (set (or (:components pack)
                        (->> (:instruments pack) (mapcat :components))))
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
  (when (:manifest-digest pack) (harness-snapshot/validate! pack))
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
                      ;; Keep in step with orc-worker/check.sh and run.sh.
                      (str "{:paths [\"" worker-src "\" \"" worker-test "\"]"
                           " :deps {tech.unravel/litellm-clj-bedrock"
                           " {:git/url \"https://github.com/ObneyAI/litellm-clj.git\""
                           " :git/sha \"0570e9459b8751d4a0c3dc1cd48595804426a84d\""
                           " :deps/root \"contrib/bedrock\"}}}")
                      "-M:dev" "-m" "loyly.pi-harness.test-runner"])
        node-result (run-command harness-root ["npm" "test"])
        passed (and (zero? (:exit-code clojure-result))
                    (zero? (:exit-code node-result)))]
    {:fixture-results (into {} (map (juxt :id (constantly passed))) fixtures)
     :suites {:clojure-worker clojure-result
              :node-adapters node-result}}))

;; A calibration certifies one harness snapshot, which is content-addressed by its manifest
;; digest. A passing result is recorded under that digest and reused by later cycles, so
;; concurrent or repeated cycles do not rerun the suites for an unchanged harness. A failure
;; is recorded with each suite's output tail, because ORC keeps only the exception message.
(defn- calibration-record [project-root digest suffix]
  (io/file project-root ".pi/harness/.state/harness-calibrations"
           (str (str/replace digest #"^sha256:" "") suffix)))

(defn- write-record! [file value]
  (.mkdirs (.getParentFile file))
  (let [tmp (io/file (.getParentFile file) (str "." (.getName file) "." (random-uuid) ".tmp"))]
    (spit tmp (pr-str value))
    (when-not (.renameTo tmp file)
      (.delete tmp))))

(defn- read-record [file]
  (when (.isFile file)
    (try (edn/read-string (slurp file)) (catch Exception _ nil))))

(defn- output-tail [output]
  (let [text (str output)]
    (subs text (max 0 (- (count text) 4000)))))

(defn- summarize-suites [suites keep-output?]
  (into {}
        (map (fn [[suite value]]
               [suite (cond-> (select-keys value [:exit-code :timed-out?])
                        keep-output? (assoc :output-tail (output-tail (:output value))))]))
        suites))

(defn calibrate! [project-root]
  (let [pack (-> (harness-snapshot/build! project-root)
                 harness-snapshot/validate!
                 validate-component-coverage!)
        digest (:manifest-digest pack)
        passed-file (calibration-record project-root digest ".edn")
        cached (read-record passed-file)]
    (if (and (:passed cached) (= digest (:harness-snapshot-digest cached)))
      (assoc cached :harness-revision (:harness-revision pack) :cached true)
      (let [suites (run-suites project-root (:fixtures pack))
            evaluation (assoc (evaluate pack (:fixture-results suites))
                              :harness-revision (:harness-revision pack)
                              :harness-snapshot-digest digest)]
        (if (:passed evaluation)
          (let [result (assoc evaluation :suites (summarize-suites (:suites suites) false))]
            (write-record! passed-file result)
            (assoc result :cached false))
          (let [failed-file (calibration-record project-root digest ".failed.edn")
                result (assoc evaluation
                              :suites (summarize-suites (:suites suites) true)
                              :failure-record (.getPath failed-file))]
            (write-record! failed-file result)
            result))))))
