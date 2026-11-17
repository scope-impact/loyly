(ns loyly.test-runner
  (:require [clojure.test :as t] [loyly.http-fixture :as http] [loyly.process :as process] [loyly.store-checks :as store-checks] [clojure.java.io :as io] [clojure.java.shell :as sh]
            [clojure.string :as str] [clojure.data.json :as json] [loyly.main :as main] [loyly.runtime :as runtime]
            [ai.obney.grain.event-store-v3.interface :as es] [loyly.effects :as effects] [loyly.domain :as d]))
(defn write! [p s] (io/make-parents p) (spit p s))
(def model-control (atom {}))
(defn model-server []
  (let [server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/" (reify com.sun.net.httpserver.HttpHandler
      (handle [_ exchange]
        (let [request (slurp (.getRequestBody exchange))
              _ (swap! model-control update :requests (fnil conj []) request)
              content (cond
                (and (str/includes? request "builder-instruction") (not (str/includes? request "[[ ## code ## ]]"))) "[[ ## builder-instruction ## ]]\nBuild the fixture candidate.\n[[ ## completed ## ]]"
                (str/includes? request "[[ ## code ## ]]") "[[ ## reasoning ## ]]\nObserve the candidate via its shell seam.\n[[ ## code ## ]]\n(let [r (shell {\"command\" \"test -f impl/start.sh && sh impl/start.sh\"})] (println (str \"FINAL\" \"_ANSWER: \" (pr-str {:evidence (pr-str r)}))))\n[[ ## completed ## ]]"
                (str/includes? request "01-002") "[[ ## reasoning ## ]]\nno receipt\n[[ ## level ## ]]\n1\n[[ ## completed ## ]]"
                :else "[[ ## reasoning ## ]]\nbooked\n[[ ## level ## ]]\n2\n[[ ## completed ## ]]")
              content (if-let [reply (:reply @model-control)] (reply request content) content)
              bytes (.getBytes (json/write-str {:id "fixture" :object "chat.completion" :choices [{:index 0 :message {:role "assistant" :content content} :finish_reason "stop"}] :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}) "UTF-8")]
          (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
          (.sendResponseHeaders exchange 200 (alength bytes))
          (with-open [out (.getResponseBody exchange)] (.write out bytes))))))
    (.start server) server))
(defn fixture []
  (let [root (.getAbsolutePath (io/file "target" (str "fixture-" (random-uuid)))) repo (str root "/repo")]
    (.mkdirs (io/file repo))
    (write! (io/file repo "plan/01-demo/spec.feature") "Feature: Example\n  @item-01-001\n  Scenario: One real item\n    Given the fixture\n  @item-01-002\n  Scenario: A receipt\n    Then the receipt is visible\n")
    (write! (io/file repo "impl/old.txt") "old")
    (write! (io/file repo "probe.sh") "#!/bin/sh\necho identity\n")
    (write! (io/file root "config.sh") "APP_DIR=impl\nPLAN_DIR=plan\nAPP_PROBE=probe.sh\nFENCED_PATHS=impl\nAPP_SEAM=stdio\n")
    (write! (io/file root "rubric") "Pass means carried; fail means uncarried.\n")
    (.setExecutable (io/file repo "probe.sh") true)
    (doseq [args [["init" "-q"] ["add" "."] ["-c" "user.name=Fixture" "-c" "user.email=fixture@example.invalid" "commit" "-qm" "fixture"]]]
      (let [r (apply sh/sh "git" "-C" repo args)] (assert (zero? (:exit r)) (:err r))))
    (write! (io/file root "self-check.sh") "#!/bin/sh\necho self-check-passed\n")
    (write! (io/file root "builder.sh") "#!/bin/sh\nmkdir -p \"$1/impl\"\nprintf '#!/bin/sh\\necho fixture-identity\\n' > \"$1/impl/start.sh\"\nprintf '#!/bin/sh\\necho 1-test-passed\\n' > \"$1/impl/test.sh\"\nchmod +x \"$1/impl/start.sh\" \"$1/impl/test.sh\"\n")
    (write! (io/file root "gate.sh") "#!/bin/sh\necho 'BOOT 20 10'\necho 'ANSWERED 1'\necho fixture-identity\n")
    (doseq [f ["self-check.sh" "builder.sh" "gate.sh"]] (.setExecutable (io/file root f) true))
    {:root root :commit (str/trim (:out (sh/sh "git" "-C" repo "rev-parse" "HEAD")))
     :env {"LOYLY_PROJECT_REPO" repo "LOYLY_RUNS_DIR" (str root "/runs") "LOYLY_HARNESS_CONFIG" (str root "/config.sh") "LOYLY_RENDER_MODEL" "fixture-render" "LOYLY_GRADER_MODEL" "fixture-grader" "LOYLY_GRADER_RUBRIC" (str root "/rubric")
           "LOYLY_SELF_CHECK_CMD" (str root "/self-check.sh") "LOYLY_BUILDER_HOST" (str root "/builder.sh")
           "LOYLY_GATE_CMD" (str root "/gate.sh") "LOYLY_GRADER_IMAGE" "/Users/sathish.narayanan/Documents/git/loyly-spec-v2/.pi/harness/.state/assets/builder-clojure"}}))
(defn params [fixture id]
  {:run-id (str "R-" id) :cycle-id (str "C-" id) :cut-id (str "K-" id) :commit (:commit fixture) :selection-id "all"
   :grader-digests ["not-approved"] :budget {:gate-attempt-limit 1 :max-outer-cycles 1 :max-continuations 0 :stage-timeouts-seconds {:fence 30 :regenerate 30 :gate 30 :score 30}}})
(defn call [ctx method p]
  (let [input (java.io.BufferedReader. (java.io.StringReader. (str (json/write-str {:jsonrpc "2.0" :id 1 :method method :params p}) "\n")))
        out (java.io.StringWriter.)]
    (main/serve! ctx input out)
    (json/read-str (str out) :key-fn keyword)))
(def ^:dynamic *ctx*)
(def ^:dynamic *fixture*)
(t/use-fixtures :each (fn [f]
  (let [_ (reset! model-control {}) server (model-server) fixture (update (fixture) :env assoc "LOYLY_MODEL_API_BASE" (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1")) ctx (binding [runtime/tenant (random-uuid)] (main/start! (str (:root fixture) "/events.sqlite")))]
    (try (binding [*ctx* ctx *fixture* fixture] (with-redefs [effects/environment (constantly (:env fixture))] (f)))
      (finally (runtime/stop! ctx) (.stop server 0))))))
(t/deftest item-06-005
  (t/testing "@item-06-005 invalid commit refuses with no run"
    (let [r (call *ctx* "cycle-runner/start-run" (assoc (params *fixture* "invalid") :commit (apply str (repeat 40 "d"))))]
      (t/is (= "not-a-commit" (get-in r [:error :data :refusal])) (pr-str r))
      (t/is (nil? (get-in (call *ctx* "cycle-runner/run-progress" {:run-id "R-invalid"}) [:result :run]))))))
(t/deftest item-06-068
  (t/testing "@item-06-068 unapproved rubric halts after the real checklist"
    (let [r (call *ctx* "cycle-runner/start-run" (params *fixture* "rubric"))
          run (get-in (call *ctx* "cycle-runner/run-progress" {:run-id "R-rubric"}) [:result :run])]
      (t/is (:result r) (pr-str r))
      (t/is (= "halted" (:status run)) (pr-str (select-keys run [:status :halt :tick :scores])))
      (t/is (= "grader" (get-in run [:halt :effect])) (pr-str (select-keys run [:status :halt :tick :scores])))
      (t/is (= ["stamp" "pin" "checklist"] (mapv :effect (:effect-results run))))
      (t/is (= "refused-before-the-cycle" (get-in run [:halt :reason]))))))

(t/deftest item-06-064-spine
  (t/testing "@item-06-001 full cycle; @item-06-064 two independently scored items conclude the cycle"
    (let [p (assoc (params *fixture* "spine") :grader-digests [(d/digest (str "cycle-runner/grade.v1\n" (slurp (get-in *fixture* [:env "LOYLY_GRADER_RUBRIC"]))))])
          r (call *ctx* "cycle-runner/start-run" p)
          run (get-in (call *ctx* "cycle-runner/run-progress" {:run-id (:run-id p)}) [:result :run])]
      (t/is (:result r) (pr-str r))
      (t/is (= [{:item-id "01-001" :verdict "pass" :reason "booked"} {:item-id "01-002" :verdict "fail" :reason "no receipt"}] (:scores run)))
      (t/is (= 1 (get-in (call *ctx* "measurement/cycle-measurement" {:cycle-id (:cycle-id p)}) [:result :measurement :passes])))
      (when-not (= "passed" (:status run))
        (spit (str (:root *fixture*) "/model-requests.edn") (pr-str (:requests @model-control)))
        (let [events (into [] (es/read (:event-store *ctx*) {:tenant-id (:tenant-id *ctx*)}))
              names (into {} (keep #(when (= :sheet/node-name-set (:event/type %)) [(:node-id %) (:name %)])) events)]
          (doseq [e events :when (and (= :sheet/node-execution-completed (:event/type e)) (:error e))]
            (println "NODE ERROR" (get names (:node-id e)) (select-keys e [:error :failure-kind :outputs :output-profiles :writes])))))
      (t/is (= "passed" (:status run)) (pr-str (select-keys run [:status :halt :tick :scores])))
      (store-checks/check! *ctx*)
      (t/is (= 2 (count (:scores run))) (pr-str (select-keys run [:status :halt :tick :scores])))
      (t/is (every? (set (map :effect (:effect-results run))) ["self-check" "fence" "regenerate" "file-verdicts" "file-score"]) (pr-str (select-keys run [:status :halt :tick :scores]))))))

(t/deftest item-06-064-structured-research-input
  (t/testing "@item-06-064 researcher reads the structured checklist item through get-input"
    (swap! model-control assoc :reply
      (fn [request default]
        (if (str/includes? request "[[ ## code ## ]]")
          "[[ ## reasoning ## ]]\nRead the documented item input.\n[[ ## code ## ]]\n(let [item (get-input :item)] (when-not (string? (:item-id item)) (/ 1 0)) (println (str \"FINAL\" \"_ANSWER: \" (pr-str {:evidence (:item-id item)}))))\n[[ ## completed ## ]]"
          default)))
    (let [p (assoc (params *fixture* "structured") :grader-digests [(d/digest (str "cycle-runner/grade.v1\n" (slurp (get-in *fixture* [:env "LOYLY_GRADER_RUBRIC"]))))])
          result (call *ctx* "cycle-runner/start-run" p)
          run (get-in (call *ctx* "cycle-runner/run-progress" {:run-id (:run-id p)}) [:result :run])]
      (t/is (:result result))
      (when-not (= "passed" (:status run))
        (doseq [e (into [] (es/read (:event-store *ctx*) {:tenant-id (:tenant-id *ctx*)})) :when (:error e)] (println "STRUCTURED ERROR" (pr-str e)))
        (spit (str (:root *fixture*) "/model-requests.edn") (pr-str (:requests @model-control))))
      (t/is (= "passed" (:status run)))
      (t/is (= 2 (count (:scores run)))))))

(defn approved [id]
  (assoc (params *fixture* id) :grader-digests [(d/digest (str "cycle-runner/grade.v1\n" (slurp (get-in *fixture* [:env "LOYLY_GRADER_RUBRIC"]))))]))
(defn run! [p]
  (let [r (call *ctx* "cycle-runner/start-run" p)] (t/is (:result r) (pr-str r))
    (get-in (call *ctx* "cycle-runner/run-progress" {:run-id (:run-id p)}) [:result :run])))
(defn script! [key text] (spit (get-in *fixture* [:env key]) (str "#!/bin/sh\n" text "\n")))
(defn recommit! []
  (let [repo (get-in *fixture* [:env "LOYLY_PROJECT_REPO"])]
    (sh/sh "git" "-C" repo "add" ".")
    (sh/sh "git" "-C" repo "-c" "user.name=Fixture" "-c" "user.email=fixture@example.invalid" "commit" "-qm" "changed fixture")
    (str/trim (:out (sh/sh "git" "-C" repo "rev-parse" "HEAD")))))
(t/deftest item-06-050
  (t/testing "@item-06-050 missing builder image refuses before any domain event"
    (spit (get-in *fixture* [:env "LOYLY_HARNESS_CONFIG"]) "BUILDER_IMAGE=absent-image\n" :append true)
    (let [r (call *ctx* "cycle-runner/start-run" (approved "missing-image"))]
      (t/is (= "builder-image-missing" (get-in r [:error :data :refusal])))
      (t/is (empty? (get-in (call *ctx* "cycle-runner/run-progress" {}) [:result :runs]))))))
(t/deftest item-06-008
  (t/testing "@item-06-008 empty checklist is Measurement's refusal"
    (spit (str (get-in *fixture* [:env "LOYLY_PROJECT_REPO"]) "/plan/01-demo/spec.feature") "Feature: Empty\n")
    (let [run (run! (assoc (approved "empty") :commit (recommit!)))]
      (t/is (= "halted" (:status run))) (t/is (= "checklist" (get-in run [:halt :effect])))
      (t/is (= "empty-checklist" (:refusal (last (:effect-results run))))))))
(t/deftest item-06-009
  (t/testing "@item-06-009 unstamped scenario halts after stamp"
    (spit (str (get-in *fixture* [:env "LOYLY_PROJECT_REPO"]) "/plan/01-demo/spec.feature") "  Scenario: New unstamped behavior\n    Given a new item\n" :append true)
    (let [run (run! (assoc (approved "unstamped") :commit (recommit!)))]
      (t/is (= "stamp" (get-in run [:halt :effect])))
      (t/is (= ["stamp"] (mapv :effect (:effect-results run)))))))
(t/deftest item-06-010-and-002
  (t/testing "@item-06-010 self-check failure concludes; @item-06-002 identity is used once"
    (script! "LOYLY_SELF_CHECK_CMD" "echo 'golden checklist differs'\nexit 1")
    (let [p (approved "self-check-failure") run (run! p) repeated (call *ctx* "cycle-runner/start-run" (assoc p :cycle-id "another-cycle"))]
      (t/is (= "failed" (:status run)))
      (t/is (= {:stage "fence" :reason "harness-self-check-failed" :detail "golden checklist differs"} (get-in run [:cycle :failure])))
      (t/is (= "self-check" (:effect (last (:effect-results run)))))
      (t/is (= "run-already-started" (get-in repeated [:error :data :refusal])))
      (t/is (= 1 (count (get-in (call *ctx* "cycle-runner/run-progress" {}) [:result :runs])))))))
(t/deftest item-06-012
  (t/testing "@item-06-012 unmatched fenced path fails the fence"
    (spit (get-in *fixture* [:env "LOYLY_HARNESS_CONFIG"]) "FENCED_PATHS=impl missing-path\n" :append true)
    (let [run (run! (approved "unmatched"))]
      (t/is (= "failed" (:status run)))
      (t/is (= "fence-refused" (get-in run [:cycle :failure :reason])))
      (t/is (= "fenced-path-matched-nothing" (get-in run [:cycle :failure :detail]))))))
(t/deftest item-06-016
  (t/testing "@item-06-016 no candidate fails regenerate"
    (script! "LOYLY_BUILDER_HOST" "exit 0")
    (let [run (run! (approved "no-candidate"))]
      (t/is (= "candidate-not-regenerated" (get-in run [:cycle :failure :reason])))
      (t/is (not-any? #(= "gate" (:effect %)) (:effect-results run))))))
(t/deftest item-06-017
  (t/testing "@item-06-017 bounded builder is stopped"
    (script! "LOYLY_BUILDER_HOST" "sleep 20")
    (let [run (run! (assoc-in (approved "slow-builder") [:budget :stage-timeouts-seconds :regenerate] 1))]
      (t/is (= {:stage "regenerate" :reason "budget" :detail "Builder exceeded its regenerate budget"} (get-in run [:cycle :failure]))))))
(t/deftest item-06-056
  (t/testing "@item-06-056 infrastructure is a halt, not a failed candidate"
    (script! "LOYLY_GATE_CMD" "echo 'BOOT 20 10'\necho 'infrastructure the world did not come up'\nexit 2")
    (let [run (run! (approved "gate-infra"))]
      (t/is (= "halted" (:status run)))
      (t/is (= "infrastructure the world did not come up" (get-in run [:halt :detail])))
      (t/is (not-any? #(#{"gate" "fail-cycle"} (:effect %)) (:effect-results run))) (t/is (nil? (:cycle run))))))
(defn native! [f]
  (let [env (-> (:env *fixture*) (dissoc "LOYLY_GATE_CMD") (assoc "LOYLY_STORE_CHECK_CMD" "true"))]
    (with-redefs [effects/environment (constantly env)] (f))))
(defn last-gate [] (last (filter #(#{:regeneration-cycle/gate-attempt-passed :regeneration-cycle/gate-attempt-failed} (:event/type %)) (store-checks/events *ctx*))))
(t/deftest item-06-039-and-040-and-044
  (t/testing "@item-06-039 native stdio; @item-06-040 isolated probe/store; @item-06-044 clean plan beside candidate"
    (let [root (:root *fixture*) repo (get-in *fixture* [:env "LOYLY_PROJECT_REPO"])]
      (spit (str repo "/probe.sh") (str "#!/bin/sh\n[ \"$2\" = 30 ] || exit 1\ncase \"$1\" in /*/gate-1/impl/start.sh) ;; *) exit 2;; esac\n[ -n \"$GATE_STORE\" ] || exit 3\nif env | grep '^LOYLY_'; then exit 4; fi\necho \"$GATE_STORE\" > '" root "/probe-store'\n\"$1\" >/dev/null || exit 5\necho 'answered as the context'\n"))
      (script! "LOYLY_BUILDER_HOST" "mkdir -p \"$1/impl\"\nprintf '#!/bin/sh\\necho fixture-identity\\n' > \"$1/impl/start.sh\"\nprintf '#!/bin/sh\\ntest -f plan/01-demo/spec.feature\\n' > \"$1/impl/test.sh\"\nchmod +x \"$1/impl/start.sh\" \"$1/impl/test.sh\"")
      (let [env (-> (:env *fixture*) (dissoc "LOYLY_GATE_CMD") (assoc "LOYLY_STORE_CHECK_CMD" (str "printf '%s' > '" root "/store-check'")))
            p (assoc (approved "native") :commit (recommit!))
            run (with-redefs [effects/environment (constantly env)] (run! p)) e (last-gate)]
        (t/is (= "passed" (:status run)) (pr-str run))
        (t/is (= [30 1 1 "answered as the context"] ((juxt :boot-budget-seconds :boot-polls :answered-on-poll :identity-probe-result) e)))
        (t/is (= (str/trim (slurp (str root "/probe-store"))) (slurp (str root "/store-check"))))))))
(t/deftest item-06-041
  (t/testing "@item-06-041 red native suite precedes identity and sees no handed-in environment"
    (script! "LOYLY_BUILDER_HOST" "mkdir -p \"$1/impl\"\nprintf '#!/bin/sh\\nexit 0\\n' > \"$1/impl/start.sh\"\nprintf '#!/bin/sh\\nif env | grep -E \"^(LOYLY_|GATE_STORE=)\"; then exit 2; fi\\necho suite-observed\\nexit 1\\n' > \"$1/impl/test.sh\"\nchmod +x \"$1/impl/start.sh\" \"$1/impl/test.sh\"")
    (native! #(let [run (run! (approved "suite-red")) e (last-gate)]
      (t/is (= "failed" (:status run))) (t/is (= "suite-red" (:reason e)))
      (t/is (= "suite-observed\n" (:detail e)))))))
(t/deftest item-06-042
  (t/testing "@item-06-042 native probe failure"
    (spit (str (get-in *fixture* [:env "LOYLY_PROJECT_REPO"]) "/probe.sh") "#!/bin/sh\necho 'no response within the 30s boot budget'\nexit 1\n")
    (let [p (assoc (approved "probe-red") :commit (recommit!))]
      (native! #(let [run (run! p) e (last-gate)] (t/is (= "failed" (:status run)))
        (t/is (= "identity-probe-failed" (:reason e))) (t/is (str/includes? (:detail e) "no response within the 30s boot budget")))))))
(t/deftest item-06-014
  (t/testing "@item-06-014 two fresh sequential builder sessions on a repaired candidate"
    (let [root (:root *fixture*) host (get-in *fixture* [:env "LOYLY_BUILDER_HOST"])]
      (spit host (str (slurp host) "echo \"$LOYLY_BUILDER_SESSION_ID\" >> '" root "/sessions'\n"))
      (script! "LOYLY_GATE_CMD" (str "echo 'BOOT 20 10'\nif [ ! -f '" root "/first-gate' ]; then touch '" root "/first-gate'; echo 'suite-red repair me'; exit 1; fi\necho 'ANSWERED 1'\necho fixture-identity"))
      (let [run (run! (assoc-in (approved "repair") [:budget :gate-attempt-limit] 3))
            sessions (str/split-lines (slurp (str root "/sessions")))]
        (t/is (= "passed" (:status run)) (pr-str (select-keys run [:status :halt :cycle])))
        (store-checks/check-gate-delegation! *ctx*)
        (t/is (= 2 (count sessions))) (t/is (= 2 (count (distinct sessions))))
        (t/is (= ["1" "2"] (mapv #(last (str/split % #"/")) sessions)))
        (t/is (= 1 (count (filter #(= "repair" (:effect %)) (:effect-results run)))))))))
(t/deftest item-06-024
  (t/testing "@item-06-024 three failed attempts conclude without scoring"
    (script! "LOYLY_GATE_CMD" "echo 'BOOT 20 10'\necho 'suite-red always red'\nexit 1")
    (let [run (run! (assoc-in (approved "three-red") [:budget :gate-attempt-limit] 3))]
      (t/is (= "failed" (:status run)) (pr-str (select-keys run [:status :halt :cycle])))
      (t/is (= "gate-attempts-exhausted" (get-in run [:cycle :failure :reason])))
      (t/is (= 3 (count (filter #(= "gate" (:effect %)) (:effect-results run)))))
      (t/is (= 2 (count (filter #(= "repair" (:effect %)) (:effect-results run)))))
      (t/is (empty? (:scores run))))))
(t/deftest item-06-067
  (t/testing "@item-06-026 unreached is Measurement's default; @item-06-067 a pass level with no reasoning is not a score"
    (swap! model-control assoc :reply (fn [request normal] (if (and (str/includes? request "01-002") (not (str/includes? request "[[ ## code ## ]]"))) "[[ ## feedback ## ]]\nunsupported\n[[ ## level ## ]]\n2\n[[ ## completed ## ]]" normal)))
    (let [p (approved "no-reason") run (run! p) measurement (get-in (call *ctx* "measurement/cycle-measurement" {:cycle-id (:cycle-id p)}) [:result :measurement])]
      (t/is (= [{:item-id "01-001" :verdict "pass" :reason "booked"}] (:scores run)))
      (t/is (= [1 2 1] ((juxt :passes :total :unreached) measurement)))
      (t/is (= "unreached" (:reason (second (:items measurement))))))))
(t/deftest item-06-065
  (t/testing "@item-06-065 each grading tick starts with fresh evidence"
    (swap! model-control assoc :reply (fn [request normal]
      (if (str/includes? request "[[ ## code ## ]]")
        "[[ ## reasoning ## ]]\nObserve one\n[[ ## code ## ]]\n(println (str \"FINAL\" \"_ANSWER: \" (pr-str {:evidence (if (= \"01-001\" (:item-id (get-input :item))) \"E-ONE\" \"E-TWO\")})))\n[[ ## completed ## ]]" normal)))
    (let [run (run! (approved "fresh-evidence")) requests (:requests @model-control)]
      (t/is (= "passed" (:status run)))
      (t/is (some #(and (str/includes? % "01-001") (str/includes? % "E-ONE") (not (str/includes? % "[[ ## code ## ]]"))) requests))
      (t/is (not-any? #(and (str/includes? % "01-002") (str/includes? % "E-ONE")) requests)))))
(t/deftest item-06-073
  (t/testing "@item-06-003 another start refuses while running; @item-06-073 progress answers while regenerate runs and records nothing"
    (let [host (get-in *fixture* [:env "LOYLY_BUILDER_HOST"]) marker (str (:root *fixture*) "/builder-running")]
      (spit host (str "#!/bin/sh\ntouch '" marker "'\nsleep 3\n" (slurp host)))
      (let [p (approved "live-progress") work (future (call *ctx* "cycle-runner/start-run" p)) deadline (+ (System/currentTimeMillis) 15000)]
        (loop [] (when (and (not (.exists (io/file marker))) (< (System/currentTimeMillis) deadline)) (Thread/sleep 50) (recur)))
        (let [before (count (store-checks/events *ctx*)) run (get-in (call *ctx* "cycle-runner/run-progress" {:run-id (:run-id p)}) [:result :run]) after (count (store-checks/events *ctx*))]
          (t/is (= "running" (:status run))) (t/is (seq (get-in run [:tick :tick-id])))
          (t/is (= {:name "regenerate" :status "running"} (last (get-in run [:tick :nodes]))))
          (t/is (= before after))
          (t/is (= "run-in-progress" (get-in (call *ctx* "cycle-runner/start-run" (approved "second-live")) [:error :data :refusal]))))
        (t/is (:result (deref work 30000 nil)))))))
(t/deftest item-06-066
  (t/testing "@item-06-066 first researcher request carries the cut contract; shell is isolated Linux"
    (let [repo (get-in *fixture* [:env "LOYLY_PROJECT_REPO"])]
      (write! (str repo "/plan/01-demo/seam.md") "FIXTURE-SEAM-OUTSIDE-CANDIDATE")
      (write! (str repo "/plan/01-demo/event-model/area.edn") "{:fixture-contract :seam-only}")
      (swap! model-control assoc :reply (fn [request normal]
        (if (str/includes? request "[[ ## code ## ]]")
          "[[ ## reasoning ## ]]\nObserve VM\n[[ ## code ## ]]\n(let [r (shell {\"command\" \"uname -s; pwd; ls -a; ls -a ..; test -f plan/01-demo/spec.feature && test -f impl/start.sh && test ! -e ../clean && test ! -e ../gate-1 && test ! -e ../builder-1 && test ! -e ../../../events.sqlite && echo ISOLATED-ROOM-VERIFIED\"})] (println (str \"FINAL\" \"_ANSWER: \" (pr-str {:evidence (str (:out r) (pr-str (get-input :item)))}))))\n[[ ## completed ## ]]" normal)))
      (let [run (run! (assoc (approved "vm-contract") :commit (recommit!))) requests (:requests @model-control)
            first-research (first (filter #(str/includes? % "[[ ## code ## ]]") requests))
            research-evidence (first (filter #(and (str/includes? % "[[ ## level ## ]]") (not (str/includes? % "[[ ## code ## ]]"))) requests))]
        (t/is (= "passed" (:status run)))
        (doseq [text ["Given the fixture" "FIXTURE-SEAM-OUTSIDE-CANDIDATE" "fixture-contract"]] (t/is (str/includes? first-research text)) (t/is (str/includes? research-evidence text)))
        (t/is (str/includes? research-evidence "ISOLATED-ROOM-VERIFIED"))
        (t/is (some #(and (str/includes? % "Linux") (not (str/includes? % "[[ ## code ## ]]"))) requests))))))
(t/deftest item-06-069
  (t/testing "@item-06-069 no scored items fails score without another builder"
    (swap! model-control assoc :reply (fn [request normal] (if (str/includes? request "[[ ## code ## ]]") "[[ ## code ## ]]\n(throw (ex-info \"research unavailable\" {}))\n[[ ## completed ## ]]" normal)))
    (let [run (run! (approved "no-scores")) results (:effect-results run)]
      (t/is (= "failed" (:status run))) (t/is (= "score-stage-failed" (get-in run [:cycle :failure :reason])))
      (t/is (= "zero-verdicts" (:refusal (first (filter #(= "file-verdicts" (:effect %)) results)))))
      (t/is (= 1 (count (filter #(= "regenerate" (:effect %)) results))))
      (t/is (some #(= "fail-score-stage" (:effect %)) results)))))
(t/deftest item-06-011-and-038
  (t/testing "@item-06-011 skips listed; @item-06-038 complete skip text retained"
    (script! "LOYLY_SELF_CHECK_CMD" "echo ' SKIP  the oracle cannot judge this tree: a tool it grades is absent.'\necho self-check-passed")
    (script! "LOYLY_BUILDER_HOST" "exit 0")
    (let [run (run! (approved "skip")) effect (first (filter #(= "self-check" (:effect %)) (:effect-results run)))]
      (t/is (= ["the oracle cannot judge this tree: a tool it grades is absent."] (:skipped-checks (first (:events effect)))))
      (t/is (str/includes? (:detail effect) "the oracle cannot judge this tree: a tool it grades is absent.")))))
(t/deftest item-06-004-and-034-and-035
  (t/testing "@item-06-004 cycle once; @item-06-034 ended run stays ended; @item-06-035 queries write nothing"
    (script! "LOYLY_SELF_CHECK_CMD" "echo red\nexit 1")
    (let [p (approved "cycle-once") run (run! p) before (count (store-checks/events *ctx*))
          again (get-in (call *ctx* "cycle-runner/run-progress" {:run-id (:run-id p)}) [:result :run])
          after (count (store-checks/events *ctx*))
          refusal (call *ctx* "cycle-runner/start-run" (assoc p :run-id "R-reused-cycle"))]
      (t/is (= run again)) (t/is (= before after))
      (t/is (= "cycle-already-run" (get-in refusal [:error :data :refusal]))))))
(t/deftest item-06-015
  (t/testing "@item-06-015 builder exit code does not decide its candidate"
    (spit (get-in *fixture* [:env "LOYLY_BUILDER_HOST"]) "exit 1\n" :append true)
    (let [run (run! (approved "nonzero"))] (t/is (= "passed" (:status run))) (t/is (= "passed" (:verdict (first (get-in run [:cycle :attempts]))))))))
(t/deftest item-06-043
  (t/testing "@item-06-043 store check failure is failed identity with its output"
    (with-redefs [effects/environment (constantly (-> (:env *fixture*) (dissoc "LOYLY_GATE_CMD") (assoc "LOYLY_STORE_CHECK_CMD" "sh -c 'echo FAIL event 7 was not written inside a run of the ORC workflow; exit 1'")))]
      (let [run (run! (approved "store-red")) e (last-gate)]
        (t/is (= "gate-attempts-exhausted" (get-in run [:cycle :failure :reason])))
        (t/is (= "identity-probe-failed" (:reason e)))
        (t/is (str/includes? (:detail e) "FAIL event 7 was not written inside a run of the ORC workflow"))))))
(t/deftest item-06-048
  (t/testing "@item-06-048 only selected plan slices stamped and checklisted"
    (write! (str (get-in *fixture* [:env "LOYLY_PROJECT_REPO"]) "/plan/02-next/spec.feature") "Feature: Other\n  @item-02-001\n  Scenario: Other item\n    Given another thing\n")
    (spit (get-in *fixture* [:env "LOYLY_HARNESS_CONFIG"]) "PLAN_SLICES=02\n" :append true)
    (let [p (assoc (params *fixture* "selected") :commit (recommit!)) run (run! p)
          checklist (get-in (call *ctx* "measurement/checklist" {:cut-id (:cut-id p)}) [:result :checklist])]
      (t/is (= "grader" (get-in run [:halt :effect])))
      (t/is (= ["02-001"] (mapv :item-id (:items checklist))))
      (t/is (nil? (get-in (call *ctx* "specification/stamped-items" {:slice "01"}) [:result :slice]))))))
(t/deftest item-06-036-and-037-and-052-and-060
  (t/testing "@item-06-036 URL probe; @item-06-037 writable gate; @item-06-052 probe siblings; @item-06-060 immediate first HTTP poll"
    (let [candidate (http/prepare! (:root *fixture*)) port (http/port) repo (get-in *fixture* [:env "LOYLY_PROJECT_REPO"])]
      (spit (get-in *fixture* [:env "LOYLY_HARNESS_CONFIG"]) (str "APP_SEAM=http\nPORT=" port "\nAPP_MARKER=PRODUCT\n") :append true)
      (spit (str repo "/probe.sh") (str "#!/bin/sh\n[ \"$1\" = 'http://127.0.0.1:" port "' ] || exit 1\n[ \"$(cat \"$(dirname \"$0\")/probe-data\")\" = sibling ] || exit 2\necho 'answered as HTTP context'\n"))
      (write! (str repo "/probe-data") "sibling")
      (script! "LOYLY_BUILDER_HOST" (str "cp -R '" candidate "' \"$1/impl\""))
      (swap! model-control assoc :reply (fn [request normal] (if (str/includes? request "[[ ## code ## ]]") "[[ ## code ## ]]\n(println (str \"FINAL\" \"_ANSWER: \" (pr-str {:evidence \"observed fixture\"})))\n[[ ## completed ## ]]" normal)))
      (let [p (assoc (approved "http") :commit (recommit!)) run (native! #(run! p)) e (last-gate)]
        (t/is (= "passed" (:status run)) (pr-str (select-keys run [:status :cycle :halt])))
        (t/is (= [10 20 "answered as HTTP context"] ((juxt :boot-budget-seconds :boot-polls :identity-probe-result) e)))
        (t/is (<= 4 (:answered-on-poll e) 20))
        (t/is (= (- 20 (:answered-on-poll e)) (:boot-margin-polls (first (get-in run [:cycle :attempts])))))
        (t/is (.exists (io/file (:root *fixture*) "runs/R-http/gate-1/impl/suite-output")))))))
(t/deftest item-06-045
  (t/testing "@item-06-045 an unlaunchable store check halts instead of judging candidate"
    (with-redefs [effects/environment (constantly (-> (:env *fixture*) (dissoc "LOYLY_GATE_CMD") (assoc "LOYLY_STORE_CHECK_CMD" (str (:root *fixture*) "/missing-program"))))]
      (let [run (run! (approved "missing-store-check"))]
        (t/is (= "halted" (:status run))) (t/is (= "gate" (get-in run [:halt :effect]))) (t/is (nil? (:cycle run)))))))
(t/deftest item-06-049-and-071
  (t/testing "@item-06-049 configured image and digest; @item-06-071 exact render and repair closing paragraph"
    (let [root (:root *fixture*) images (str root "/images") image (str images "/fixture") host (get-in *fixture* [:env "LOYLY_BUILDER_HOST"])]
      (write! (str image "/manifest") "fixture-image")
      (spit (get-in *fixture* [:env "LOYLY_HARNESS_CONFIG"]) "BUILDER_IMAGE=fixture\n" :append true)
      (spit host (str (slurp host) "echo \"$3\" >> '" root "/images-used'\ncp \"$2\" \"$1/instruction-observed\"\n"))
      (script! "LOYLY_GATE_CMD" (str "echo 'BOOT 20 10'\nif [ ! -f '" root "/first-gate' ]; then touch '" root "/first-gate'; echo 'suite-red repair'; exit 1; fi\necho 'ANSWERED 1'\necho identity"))
      (swap! model-control assoc :reply (fn [request normal] (if (and (str/includes? request "builder-instruction") (not (str/includes? request "[[ ## code ## ]]"))) "[[ ## builder-instruction ## ]]\nINSTRUCTION-7\n[[ ## completed ## ]]" normal)))
      (with-redefs [effects/environment (constantly (assoc (:env *fixture*) "LOYLY_BUILDER_IMAGES" images))]
        (let [run (run! (assoc-in (approved "image") [:budget :gate-attempt-limit] 3))
              effects (filter #(#{"regenerate" "repair"} (:effect %)) (:effect-results run))]
          (t/is (= "passed" (:status run)))
          (t/is (= [image image] (str/split-lines (slurp (str root "/images-used")))))
          (t/is (every? #(and (str/includes? (:detail %) "fixture") (str/includes? (:detail %) (effects/tree-digest image))) effects))
          (t/is (= "INSTRUCTION-7" (slurp (str root "/runs/R-image/builder-1/instruction-observed"))))
          (let [instruction (slurp (str root "/runs/R-image/builder-2/instruction-observed"))]
            (t/is (str/starts-with? instruction "INSTRUCTION-7\n\n"))
            (t/is (str/includes? instruction "previous-candidate")) (t/is (str/includes? instruction "gate-evidence"))))))))
(t/deftest item-06-051
  (t/testing "@item-06-051 second run retains previous measured cycle and does not restamp"
    (let [first-run (run! (approved "history-one")) second-run (run! (approved "history-two"))
          board (get-in (call *ctx* "measurement/scoreboard" {:cycle-id "C-history-two"}) [:result :scoreboard])]
      (t/is (= "passed" (:status first-run) (:status second-run)))
      (t/is (= "C-history-one" (:previous-cycle-id board)))
      (t/is (= {:items 2 :previous-passes 1 :passes 1 :previous-rate "50.0" :rate "50.0" :newly-passing [] :newly-failing []} (:shared board)))
      (t/is (empty? (:events (first (:effect-results second-run))))))))
(t/deftest item-06-047
  (t/testing "@item-06-047 a symbolic plan slice is read at its committed target"
    (let [repo (get-in *fixture* [:env "LOYLY_PROJECT_REPO"])]
      (sh/sh "mkdir" "-p" (str repo "/spec"))
      (sh/sh "mv" (str repo "/plan/01-demo") (str repo "/spec/demo"))
      (sh/sh "ln" "-s" "../spec/demo" (str repo "/plan/01-demo"))
      (let [run (run! (assoc (approved "link") :commit (recommit!)))
            stamps (get-in (call *ctx* "specification/stamped-items" {:slice "01"}) [:result :slice])]
        (t/is (= "passed" (:status run)))
        (let [items (:items (first (:events (first (filter #(= "checklist" (:effect %)) (:effect-results run))))))]
          (t/is (= ["01-001" "01-002"] (mapv :item-id items)))
          (t/is (= ["plan/01-demo/spec.feature:3" "plan/01-demo/spec.feature:6"] (mapv :source items))))
        (t/is (= ["01-001" "01-002"] (mapv :item-id (:items stamps))))
        (t/is (every? #(str/includes? (:source %) "/01-demo/spec.feature") (:items stamps)))))))
(t/deftest item-06-055
  (t/testing "@item-06-055 three gate command guard refusals conclude infrastructure"
    (script! "LOYLY_GATE_CMD" "echo 'BOOT 20 10'\necho 'seam-answered-before-boot someone already listening'\nexit 1")
    (let [run (run! (approved "guard")) effects (:effect-results run)]
      (t/is (= "failed" (:status run)))
      (t/is (= "infrastructure" (get-in run [:cycle :failure :reason])))
      (t/is (= 3 (count (filter #(and (= "gate" (:effect %)) (= "seam-answered-before-boot" (:refusal %))) effects))))
      (t/is (= 3 (count (filter #(= "gate-refusal" (:effect %)) effects)))))))
(t/deftest item-06-013
  (t/testing "@item-06-013 builder sees one root and no fenced path or object"
    (let [host (get-in *fixture* [:env "LOYLY_BUILDER_HOST"]) original (slurp host) observed (str (:root *fixture*) "/clean-observed")]
      (spit host (str "#!/bin/sh\ngit -C \"$1\" rev-list --count HEAD > '" observed "'\ngit -C \"$1\" rev-list --max-parents=0 HEAD >> '" observed "'\nif git -C \"$1\" rev-list --objects --all | grep ' impl'; then exit 3; fi\n[ ! -e \"$1/impl\" ] || exit 4\n" original))
      (let [run (run! (approved "clean")) lines (str/split-lines (slurp observed))]
        (t/is (= "passed" (:status run))) (t/is (= 2 (count lines))) (t/is (= "1" (first lines)))))))
(t/deftest item-06-020
  (t/testing "@item-06-020 missing launch entrypoint fails contract before suite"
    (script! "LOYLY_BUILDER_HOST" "mkdir -p \"$1/impl\"\nprintf '#!/bin/sh\\nexit 0\\n' > \"$1/impl/test.sh\"\nchmod +x \"$1/impl/test.sh\"")
    (native! #(let [run (run! (approved "contract")) e (last-gate)] (t/is (= "failed" (:status run))) (t/is (= "contract-missing" (:reason e)))))))
(t/deftest item-06-018
  (t/testing "@item-06-018 an occupied HTTP port refuses three times and fails infrastructure"
    (let [server (model-server) port (.getPort (.getAddress server))]
      (try
        (spit (get-in *fixture* [:env "LOYLY_HARNESS_CONFIG"]) (str "APP_SEAM=http\nPORT=" port "\n") :append true)
        (native! #(let [run (run! (approved "occupied"))] (t/is (= "infrastructure" (get-in run [:cycle :failure :reason])))
          (t/is (= 3 (count (filter (fn [e] (= "gate-refusal" (:effect e))) (:effect-results run)))))))
        (finally (.stop server 0))))))
(t/deftest item-06-046-and-057
  (t/testing "@item-06-046 stdio digest covers store command; @item-06-057 provided gate replaces store command in digest"
    (script! "LOYLY_BUILDER_HOST" "exit 0")
    (let [digest (fn [id env]
          (with-redefs [effects/environment (constantly env)]
            (let [run (run! (approved id)) effect (first (filter #(= "self-check" (:effect %)) (:effect-results run)))]
              (:harness-digest (first (:events effect))))))
          env (:env *fixture*) a (digest "digest-a" (assoc (dissoc env "LOYLY_GATE_CMD") "LOYLY_STORE_CHECK_CMD" "true"))
          b (digest "digest-b" (assoc (dissoc env "LOYLY_GATE_CMD") "LOYLY_STORE_CHECK_CMD" "false"))
          c (digest "digest-c" (assoc env "LOYLY_STORE_CHECK_CMD" "true"))
          e (digest "digest-e" (assoc env "LOYLY_STORE_CHECK_CMD" "false"))
          f (digest "digest-f" (assoc env "LOYLY_GATE_CMD" "other-gate"))]
      (t/is (not= a b)) (t/is (= c e)) (t/is (not= c f)))))
(t/deftest item-06-019
  (t/testing "@item-06-019 native HTTP red suite never launches candidate"
    (let [marker (str (:root *fixture*) "/launched")]
      (spit (get-in *fixture* [:env "LOYLY_HARNESS_CONFIG"]) (str "APP_SEAM=http\nPORT=" (http/port) "\n") :append true)
      (script! "LOYLY_BUILDER_HOST" (str "mkdir -p \"$1/impl\"\nprintf '#!/bin/sh\\ntouch \"" marker "\"\\n' > \"$1/impl/start.sh\"\nprintf '#!/bin/sh\\nexit 1\\n' > \"$1/impl/test.sh\"\nchmod +x \"$1/impl/start.sh\" \"$1/impl/test.sh\""))
      (native! #(let [run (run! (approved "red-http"))] (t/is (= "failed" (:status run))) (t/is (= "suite-red" (:reason (last-gate))))))
      (t/is (not (.exists (io/file marker)))))))
(t/deftest item-06-021
  (t/testing "@item-06-021 HTTP response without marker fails identity"
    (let [candidate (http/prepare! (:root *fixture*)) port (http/port)]
      (spit (get-in *fixture* [:env "LOYLY_HARNESS_CONFIG"]) (str "APP_SEAM=http\nPORT=" port "\nAPP_MARKER=UNSERVED\n") :append true)
      (script! "LOYLY_BUILDER_HOST" (str "cp -R '" candidate "' \"$1/impl\""))
      (native! #(let [run (run! (approved "no-marker"))] (t/is (= "failed" (:status run))) (t/is (= "identity-probe-failed" (:reason (last-gate)))))))))
(t/deftest item-06-022
  (t/testing "@item-06-022 writes after builder exit cannot change installed snapshot"
    (let [host (get-in *fixture* [:env "LOYLY_BUILDER_HOST"])]
      (spit host (str (slurp host) "printf '#!/bin/sh\\nsleep 4\\ntest ! -f impl/bad\\n' > \"$1/impl/test.sh\"\n(sleep 2; echo bad > \"$1/impl/bad\") >/dev/null 2>&1 &\n"))
      (native! #(let [run (run! (approved "snapshot")) effects (:effect-results run)
                      generated (:candidate-digest (first (:events (first (filter (fn [e] (= "regenerate" (:effect e))) effects)))))
                      installed (:candidate-digest (first (:events (first (filter (fn [e] (= "install" (:effect e))) effects)))))]
        (t/is (= "passed" (:status run))) (t/is (= generated installed))
        (t/is (.exists (io/file (:root *fixture*) "runs/R-snapshot/builder-1/impl/bad")))
        (t/is (not (.exists (io/file (:root *fixture*) "runs/R-snapshot/gate-1/impl/bad")))))))))
(t/deftest item-06-023-and-054
  (t/testing "@item-06-023 read-only repair inputs; @item-06-054 failed command evidence is the repair's"
    (let [root (:root *fixture*) host (get-in *fixture* [:env "LOYLY_BUILDER_HOST"]) original (slurp host)]
      (spit host (str "#!/bin/sh\nif [ -d \"$1/previous-candidate\" ]; then\n test -f \"$1/plan/01-demo/spec.feature\" || exit 2\n if touch \"$1/previous-candidate/writable\" 2>/dev/null; then exit 3; fi\n if touch \"$1/gate-evidence/writable\" 2>/dev/null; then exit 4; fi\n grep 'fixture-evidence' \"$1/gate-evidence/detail.txt\" || exit 5\n echo checked > '" root "/repair-checked'\nfi\n" original))
      (script! "LOYLY_GATE_CMD" (str "echo 'BOOT 20 10'\nif [ ! -f '" root "/first-gate' ]; then touch '" root "/first-gate'; echo fixture-evidence > \"$3/detail.txt\"; echo 'suite-red red fixture'; exit 1; fi\necho 'ANSWERED 1'\necho identity"))
      (let [run (run! (assoc-in (approved "repair-mounts") [:budget :gate-attempt-limit] 3)) effects (:effect-results run)
            gate (first (filter #(= "gate" (:effect %)) effects)) repair (first (filter #(= "repair" (:effect %)) effects))
            evidence (:evidence-root (first (:events gate)))]
        (t/is (= "passed" (:status run))) (t/is (.exists (io/file root "repair-checked")))
        (t/is (= "suite-red" (:reason (first (:events gate)))))
        (t/is (str/includes? (:detail repair) evidence))))))
(t/deftest item-06-072
  (t/testing "@item-06-072 failed instruction rendering halts before the builder"
    (swap! model-control assoc :reply (fn [request normal] (if (str/includes? request "builder-instruction") "[[ ## completed ## ]]" normal)))
    (let [run (run! (approved "render-failure"))]
      (t/is (= "halted" (:status run))) (t/is (= "render" (get-in run [:halt :effect])))
      (t/is (nil? (:cycle run))) (t/is (not-any? #(#{"regenerate" "fail-cycle"} (:effect %)) (:effect-results run))))))
(t/deftest item-06-053
  (t/testing "@item-06-053 gate command result budget poll and probe are recorded verbatim"
    (script! "LOYLY_GATE_CMD" "echo 'BOOT 17 9'\necho 'ANSWERED 4'\necho 'context answered exactly'")
    (let [run (run! (approved "gate-output")) e (last-gate)]
      (t/is (= "passed" (:status run)))
      (t/is (= [17 9 4 "context answered exactly"] ((juxt :boot-polls :boot-budget-seconds :answered-on-poll :identity-probe-result) e))))))
(t/deftest item-06-070
  (t/testing "@item-06-070 stopped runtime reopens the same durable tick and builder session"
    (let [root (:root *fixture*) host (get-in *fixture* [:env "LOYLY_BUILDER_HOST"]) original (slurp host)
          sessions (str root "/recovery-sessions") started (str root "/first-launch") p (assoc-in (approved "recovery") [:budget :stage-timeouts-seconds :regenerate] 90)]
      (spit host (str "#!/bin/sh\necho \"$LOYLY_BUILDER_SESSION_ID\" >> '" sessions "'\nif [ ! -f '" started "' ]; then touch '" started "'; sleep 30; fi\n" original))
      (let [work (future (call *ctx* "cycle-runner/start-run" p)) deadline (+ (System/currentTimeMillis) 20000)]
        (loop [] (when (and (not (.exists (io/file started))) (< (System/currentTimeMillis) deadline)) (Thread/sleep 50) (recur)))
        (t/is (.exists (io/file started)))
        (Thread/sleep 5000)
        (runtime/stop! *ctx*)
        (doseq [process @process/live-processes] (process/stop! process))
        (future-cancel work)
        (Thread/sleep 3500)
        (let [restarted (binding [runtime/tenant (:tenant-id *ctx*)] (main/start! (str root "/events.sqlite")))]
          (try
            (let [run (loop [remaining 50]
                        (let [run (get-in (call restarted "cycle-runner/run-progress" {:run-id (:run-id p)}) [:result :run])]
                          (if (or (not= "running" (:status run)) (zero? remaining)) run (do (Thread/sleep 1000) (recur (dec remaining))))))
                  launches (str/split-lines (slurp sessions))]
              (t/is (= "passed" (:status run)) (pr-str (select-keys run [:status :halt :cycle])))
              (t/is (= 2 (count launches))) (t/is (= 1 (count (distinct launches))))
              (store-checks/check! restarted)
              (doseq [effect ["stamp" "pin" "checklist" "fence" "regenerate"]]
                (t/is (= 1 (count (filter #(= effect (:effect %)) (:effect-results run)))) effect)))
            (finally (runtime/stop! restarted))))))))
(t/deftest item-06-066-researcher-iterations-share-vm
  (t/testing "@item-06-066 the same guest survives successive researcher iterations"
    (swap! model-control assoc :reply
      (fn [request normal]
        (if (str/includes? request "[[ ## code ## ]]")
          (if (str/includes? request "loyly-session-state")
            "[[ ## code ## ]]\n(let [r (shell {\"command\" \"cat /tmp/loyly-session-state\"})] (if (= 0 (:exit r)) (final! {:evidence (:out r)}) (/ 1 0)))\n[[ ## completed ## ]]"
            "[[ ## code ## ]]\n(shell {\"command\" \"echo alive > /tmp/loyly-session-state\"})\n[[ ## completed ## ]]")
          normal)))
    (let [run (run! (approved "persistent-vm"))]
      (t/is (= "passed" (:status run)))
      (t/is (= 2 (count (:scores run)))))))

(defn -main [& _]
  (let [start (System/nanoTime) r (t/run-tests 'loyly.test-runner)]
    (println "Suite runtime seconds:" (/ (- (System/nanoTime) start) 1e9))
    (shutdown-agents) (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
