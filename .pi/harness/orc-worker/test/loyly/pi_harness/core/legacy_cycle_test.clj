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
        regenerate (legacy-cycle/render-prompt :slice-regeneration (assoc cfg :slice "02-execution-pipeline"))
        repair (legacy-cycle/render-prompt :gate-repair cfg)]
    (is (re-find #"APP_DIR/test.sh" (:prompt regenerate)))
    (is (re-find #"(?i)within thirty seconds" (:prompt regenerate)))
    (is (re-find #"JSON-RPC over stdio" (:prompt regenerate)))
    (is (re-find #"rpc.yaml" (:prompt regenerate)))
    (is (re-find #"APP_MARKER: pi-harness" (:prompt regenerate)))
    (is (re-find #"Target stack: Clojure on ORC" (:prompt regenerate)))
    (is (re-find #"identity is proved by domain behavior" (:prompt regenerate)))
    (is (re-find #"not a facade" (:prompt regenerate)))
    (is (re-find #"Slice: \$HARNESS_CUT_ROOT/.pi/model/plan/02-execution-pipeline/spec.feature" (:prompt regenerate)))
    (is (re-find #"every stamped scenario of your slice has a passing test" (:prompt regenerate)))
    (is (re-find #"Earlier slice builders of this cycle" (:prompt regenerate)))
    (is (re-find #"do not read the whole spec\s+up front" (:prompt regenerate)))
    (is (re-find #"\.pi/model/rpc\.yaml" (:prompt regenerate)))
    (is (not (re-find #"Slice:" (:prompt repair))))
    (is (re-find #"Grain, whose durability you use rather than reinvent" (:prompt regenerate)))
    (is (re-find #"Gate log: \$HARNESS_VERIFICATION_EVIDENCE_ROOT/gate.log" (:prompt repair)))
    (is (not (re-find #"Gate log" (:prompt regenerate))))
    (is (str/starts-with? (:prompt-digest regenerate) "sha256:"))
    (is (not= (:prompt-digest regenerate) (:prompt-digest repair)))))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory "legacy-cycle" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- room-with-slices [& slices]
  (let [room (temp-dir)]
    (doseq [slice slices]
      (io/make-parents (io/file room ".pi/model/plan" slice "spec.feature"))
      (spit (io/file room ".pi/model/plan" slice "spec.feature") "Feature: x\n"))
    (io/make-parents (io/file room ".pi/model/plan/notes.md"))
    room))

(deftest the-plan-slices-are-read-in-plan-order
  (let [room (room-with-slices "02-b" "01-a" "10-c")]
    (.mkdirs (io/file room ".pi/model/plan/03-no-spec"))
    (is (= ["01-a" "02-b" "10-c"] (legacy-cycle/plan-slices room ".pi/model/plan")))))

(deftest the-first-attempt-builds-one-slice-per-fresh-builder-in-plan-order
  (let [room (room-with-slices "02-b" "01-a")
        calls (atom [])]
    (with-redefs [legacy-cycle/config (fn [_] {:app-dir "harness-impl" :plan-dir ".pi/model/plan"})
                  legacy-cycle/build! (fn [_ {:keys [slice purpose attempt initial-workspace-root]}]
                                        (let [out (temp-dir)]
                                          (.mkdirs (io/file out "harness-impl"))
                                          (spit (io/file out "harness-impl" "built-by") slice)
                                          (swap! calls conj {:slice slice :purpose purpose :attempt attempt
                                                             :seed initial-workspace-root :out out})
                                          {:output-root out :subject-digest (str "digest-" slice)
                                           :builder-session-id (str "session-" slice)}))]
      (let [result (legacy-cycle/regenerate "/unused" "cycle-1" room)
            [first-call second-call] @calls]
        (is (= ["01-a" "02-b"] (map :slice @calls)))
        (is (every? #(= :slice-regeneration (:purpose %)) @calls))
        (is (every? #(= 1 (:attempt %)) @calls))
        (is (nil? (:seed first-call)) "the first slice builder starts from an empty workspace")
        (is (= (:out first-call) (:seed second-call)) "a later slice builder sees this cycle's candidate so far")
        (is (= ["01-a" "02-b"] (map :slice (:slices result))))
        (is (= "digest-02-b" (:subject-digest result)))
        (is (= "02-b" (slurp (io/file room "harness-impl" "built-by"))) "the gate sees the last slice's candidate")))))

(deftest a-slice-builder-that-changes-nothing-stops-the-regenerate-stage
  (let [room (room-with-slices "01-a" "02-b")]
    (with-redefs [legacy-cycle/config (fn [_] {:app-dir "harness-impl" :plan-dir ".pi/model/plan"})
                  legacy-cycle/build! (fn [_ _]
                                        (let [out (temp-dir)]
                                          (.mkdirs (io/file out "harness-impl"))
                                          {:output-root out :subject-digest "same"}))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"slice 02-b left the candidate unchanged"
                            (legacy-cycle/regenerate "/unused" "cycle-1" room))))))

(defn- fake-build
  "A build! stand-in. UNCARRIED gives each call's UNCARRIED.md (nil writes none); DIGESTS each
  call's subject digest. Records every call."
  [calls uncarried digests]
  (fn [_ opts]
    (let [n (count @calls)
          out (temp-dir)]
      (.mkdirs (io/file out "harness-impl"))
      (when-let [text (nth uncarried n nil)]
        (spit (io/file out "harness-impl" "UNCARRIED.md") text))
      (swap! calls conj (assoc opts :out out))
      {:output-root out :subject-digest (nth digests n) :session-file (str "session-" (:slice opts))})))

(deftest a-slice-builder-is-continued-while-it-lists-its-own-items-uncarried
  (let [room (room-with-slices "01-a")
        calls (atom [])]
    (with-redefs [legacy-cycle/config (fn [_] {:app-dir "harness-impl" :plan-dir ".pi/model/plan"})
                  legacy-cycle/build! (fake-build calls
                                                  ["# 01-a\n\nTests cover @item-01-001.\n\n- @item-01-002: no adapter\n- @item-01-003: later\n- @item-02-001: another slice's\n"
                                                   "# 01-a\n\n- @item-01-003: later\n"
                                                   "# 01-a\n"]
                                                  ["d1" "d2" "d3"])]
      (let [result (legacy-cycle/regenerate "/unused" "cycle-1" room)
            [first-call c1 c2] @calls]
        (is (= 3 (count @calls)) "two continuations, then the slice's list is empty")
        (is (nil? (:resume-session-file first-call)))
        (is (= "session-01-a" (:resume-session-file c1)) "a continuation resumes the same session")
        (is (= ["@item-01-002" "@item-01-003"] (:uncarried c1))
            "only this slice's listed items; prose mentions and other slices' items are not continued")
        (is (= ["@item-01-003"] (:uncarried c2)))
        (is (= (:out first-call) (:initial-workspace-root c1)) "a continuation works on the candidate it left")
        (is (= [1 2] (map :continuation [c1 c2])))
        (is (= "d3" (:subject-digest result)))
        (is (= [{:slice "01-a" :continuations 2 :uncarried []}]
               (map #(select-keys % [:slice :continuations :uncarried]) (:slices result))))))))

(deftest slice-continuation-stops-at-three-and-on-a-continuation-that-changes-nothing
  (let [left "- @item-01-001: not yet\n"]
    (let [calls (atom [])]
      (with-redefs [legacy-cycle/config (fn [_] {:app-dir "harness-impl" :plan-dir ".pi/model/plan"})
                    legacy-cycle/build! (fake-build calls (repeat 5 left) ["d1" "d2" "d3" "d4" "d5"])]
        (let [result (legacy-cycle/regenerate "/unused" "cycle-1" (room-with-slices "01-a"))]
          (is (= 4 (count @calls)) "the first turn and at most three continuations")
          (is (= "d4" (:subject-digest result)))
          (is (= ["@item-01-001"] (-> result :slices first :uncarried)) "what is still listed is reported"))))
    (let [calls (atom [])]
      (with-redefs [legacy-cycle/config (fn [_] {:app-dir "harness-impl" :plan-dir ".pi/model/plan"})
                    legacy-cycle/build! (fake-build calls (repeat 5 left) ["d1" "d1" "d9"])]
        (let [result (legacy-cycle/regenerate "/unused" "cycle-1" (room-with-slices "01-a"))]
          (is (= 2 (count @calls)) "a continuation that leaves the candidate unchanged ends the slice")
          (is (= "d1" (:subject-digest result)))
          (is (= 1 (-> result :slices first :continuations))))))))

(deftest the-continuation-prompt-names-only-the-listed-items
  (let [p (legacy-cycle/continuation-prompt ["@item-01-002" "@item-01-003"])
        first-turn (legacy-cycle/render-prompt :slice-regeneration {:slice "01-a"})]
    (is (re-find #"@item-01-002, @item-01-003" (:prompt p)))
    (is (re-find #"APP_DIR/UNCARRIED.md" (:prompt p)))
    (is (re-find #"(?i)failing test" (:prompt p)))
    (is (= (:system-prompt first-turn) (:system-prompt p)) "the same session keeps its system prompt")
    (is (str/starts-with? (:prompt-digest p) "sha256:"))
    (is (re-find #"- @item-NN-SSS: " (:prompt first-turn))
        "the first turn tells the builder the line form ORC reads")))

(deftest regenerate-refuses-a-room-without-slices
  (with-redefs [legacy-cycle/config (fn [_] {:app-dir "harness-impl" :plan-dir ".pi/model/plan"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no slice under"
                          (legacy-cycle/regenerate "/unused" "cycle-1" (temp-dir))))))

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
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no sealed grader"
                          (legacy-cycle/grader-approval root (str "sha256:" hex) "operator")))
    (.mkdirs sealed)
    (spit (io/file sealed "manifest.json") "{\"items\": [\"02-002\"], \"entry\": \"grade_seam.py\"}")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has no grade_seam.py"
                          (legacy-cycle/grader-approval root (str "sha256:" hex) "operator")))
    (spit (io/file sealed "grade_seam.py") "")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names its approver"
                          (legacy-cycle/grader-approval root (str "sha256:" hex) " ")))
    (let [approval (legacy-cycle/grader-approval root (str "sha256:" hex) "operator")]
      (is (= (str "sha256:" hex) (:grader-digest approval)))
      (is (= "grade_seam.py" (:entry approval)))
      (is (= ["02-002"] (:items approval))))))

(deftest a-sealed-grader-without-an-entry-defaults-to-grade-py
  (let [root (temp-project)
        hex (apply str (repeat 64 "b"))
        sealed (io/file root ".pi/harness/.state/graders/sealed" hex)]
    (.mkdirs sealed)
    (spit (io/file sealed "manifest.json") "{\"items\": [\"02-002\"]}")
    (spit (io/file sealed "grade.py") "")
    (is (= "grade.py" (:entry (legacy-cycle/grader-approval root (str "sha256:" hex) "operator"))))))

(deftest the-score-refuses-without-an-approved-grader
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no approved sealed grader"
                        (legacy-cycle/score (temp-project) "/tmp/evidence" "/tmp/room" []))))

(deftest the-score-refuses-two-graders-filing-the-same-item
  (let [root (temp-project)
        evidence (str root "/evidence")]
    (.mkdirs (io/file evidence))
    (with-redefs [legacy-cycle/config (fn [_] {:app-dir "harness-impl"})
                  legacy-cycle/run-logged! (fn [& _] {:exit 0 :log ""})
                  legacy-cycle/run-grader! (fn [_ _ _ _] [{"id" "02-002" "verdict" "pass"}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"more than one grader filed 02-002"
                            (legacy-cycle/score root evidence "/tmp/room"
                                                [{:grader-digest "sha256:1"} {:grader-digest "sha256:2"}]))))))

(deftest two-cycles-scoring-at-once-never-share-a-number
  (let [root (temp-dir)]
    (.mkdirs (io/file root "harness" "cycles" "08"))
    (.mkdirs (io/file root "harness" "cycles" "README.md-is-not-a-number"))
    (let [claims (pmap (fn [_] (legacy-cycle/claim-cycle-number (str root))) (range 8))]
      (is (= (map #(format "%02d" %) (range 9 17)) (sort claims)))
      (is (every? #(.isDirectory (io/file root "harness" "cycles" %)) claims)))))
