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
    (is (re-find #"not a facade" (:prompt regenerate)))
    (is (re-find #"every stamped scenario of every slice has a passing test" (:prompt regenerate)))
    (is (re-find #"Grain, whose durability you use rather than reinvent" (:prompt regenerate)))
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

(defn- temp-project []
  (.getPath (.toFile (java.nio.file.Files/createTempDirectory
                      "legacy-cycle-project-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest a-grader-is-approved-only-by-the-exact-digest-of-a-sealed-grader
  (let [root (temp-project)
        hex (apply str (repeat 64 "a"))
        sealed (io/file root ".pi/harness/.state/graders/sealed" hex)]
    (is (nil? (legacy-cycle/approved-grader root)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no sealed grader"
                          (legacy-cycle/approve-grader! root (str "sha256:" hex) "operator")))
    (.mkdirs sealed)
    (spit (io/file sealed "manifest.json") "{}")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names its approver"
                          (legacy-cycle/approve-grader! root (str "sha256:" hex) " ")))
    (let [approval (legacy-cycle/approve-grader! root (str "sha256:" hex) "operator")]
      (is (= (str "sha256:" hex) (:grader-digest approval)))
      (is (= approval (legacy-cycle/approved-grader root))))))

(deftest the-score-refuses-without-an-approved-grader
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no approved sealed grader"
                        (legacy-cycle/score (temp-project) "/tmp/evidence" "/tmp/room"))))
