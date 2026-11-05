(ns loyly.pi-harness.core.legacy-cycle-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.core.legacy-cycle :as legacy-cycle]
            [loyly.pi-harness.workflows.cycle :as cycle-workflow]))

(def project-root
  (.getCanonicalPath (io/file (System/getProperty "user.dir") "..")))

(deftest the-cycle-has-the-legacy-stage-order
  (let [contract (cycle-workflow/contract)]
    (is (= "pi-harness-cycle" (:workflowName contract)))
    (is (= ["fence" "regenerate" "gate" "score" "scoreboard"] (:stages contract)))))

(deftest config-comes-from-harness-config-sh
  (let [cfg (legacy-cycle/config project-root)]
    (is (= ".pi/model/plan" (:plan-dir cfg)))
    (is (= "harness-impl" (:app-dir cfg)))
    (is (str/includes? (:fenced-paths cfg) ".pi/harness"))
    (is (str/includes? (:fenced-paths cfg) "docs/plan"))
    (is (str/includes? (:app-stack cfg) "Clojure"))))

(deftest prompts-carry-the-gate-contract-and-are-digested
  (let [cfg {:request-id "cycle-1" :app-dir "harness-impl"
             :plan-dir ".pi/model/plan" :app-marker "pi-harness" :app-stack "Clojure on ORC"}
        regenerate (legacy-cycle/render-prompt :regeneration cfg)
        repair (legacy-cycle/render-prompt :gate-repair cfg)]
    (is (re-find #"APP_DIR/test.sh" (:prompt regenerate)))
    (is (re-find #"(?i)within thirty seconds" (:prompt regenerate)))
    (is (re-find #"JSON-RPC over stdio" (:prompt regenerate)))
    (is (re-find #"rpc.yaml" (:prompt regenerate)))
    (is (re-find #"APP_MARKER: pi-harness" (:prompt regenerate)))
    (is (re-find #"Target stack: Clojure on ORC" (:prompt regenerate)))
    (is (re-find #"identity is proved by domain behavior" (:prompt regenerate)))
    (is (re-find #"Gate log: \$HARNESS_VERIFICATION_EVIDENCE_ROOT/gate.log" (:prompt repair)))
    (is (not (re-find #"Gate log" (:prompt regenerate))))
    (is (str/starts-with? (:prompt-digest regenerate) "sha256:"))
    (is (not= (:prompt-digest regenerate) (:prompt-digest repair)))))

(deftest the-fence-refuses-a-room-that-already-exists
  (let [request-id (str "fence-test-" (random-uuid))
        room (io/file project-root ".pi/harness/.state/rooms" request-id)]
    (.mkdirs room)
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a room must be fresh"
                            (legacy-cycle/fence project-root request-id)))
      (finally (.delete room)))))
