(in-ns 'runner.suite)
(defn event-of [r effect] (first (:events (last (effects r effect)))))
(defn failed! [f stage reason & [extra]]
  (is (nil? (:error (start! f extra))))
  (let [r (progress f)]
    (is (= "failed" (:status r)) (pr-str r))
    (is (= [stage reason] ((juxt :stage :reason) (get-in r [:cycle :failure]))) (pr-str r)) r))
(defn halted! [f effect reason]
  (is (nil? (:error (start! f))))
  (let [r (progress f)]
    (is (= "halted" (:status r)) (pr-str r))
    (is (= [effect reason] ((juxt :effect :reason) (:halt r))) (pr-str r)) r))
(defn file! [f values & [extra]]
  (let [path (u/put (str (:root f) "/verdicts.json") (u/encode values))]
    (rpc f "hand-in-verdicts" (merge {:run-id "R-1" :verdicts-path path :grader-digest "r-1"} extra))))
(defn peers-log [f] (mapv u/decode (str/split-lines (slurp (str (:root f) "/peers.log")))))
(defn store-events [f]
  (let [output (str (:root f) "/audit-" (random-uuid) ".nippy")
        result (u/exec (into (mailbox/java-command) ["-m" "runner.store-reader" (get-in f [:env "LOYLY_GRAIN_STORE"]) output]) base)]
    (when-not (zero? (:exit result)) (throw (ex-info (:err result) result)))
    (nippy/thaw-from-file output)))
(deftest durable-workflows-and-observation
  (with-runner [f {}]
    (let [r (successful! f)]
      (testing "@item-06-007 every handoff, in producer order, once, with both Date and Instant transport"
        (let [rows (:relayed-handoffs r)]
          (is (= [["specification" 1 "measurement"] ["specification" 2 "measurement"] ["cycle-preparation" 1 "regeneration-cycle"] ["cycle-preparation" 1 "measurement"]]
                 (mapv (juxt :producer :position :consumer) (take 4 rows))))
          (is (= 1 (count (filter #(= "regeneration-cycle/gate-attempt-passed" (:handoff-type %)) rows))))
          (is (every? #(= "accepted" (:outcome %)) rows))
          (is (= (count rows) (count (set (map (juxt :producer :position :consumer) rows)))))))
      (testing "@item-06-003 one active run"
        (is (= "run-in-progress" (refusal-code (start! f {:run-id "R-2" :cycle-id "C-2"})))))
      (testing "@item-06-035 queries are pure and stable"
        (Thread/sleep 500)
        (let [events (store-events f)]
          (is (= r (progress f) (progress f)))
          (is (= events (store-events f)))
          (is (= [] (get-in (rpc f "runs-to-advance" {}) [:result :run-ids])))
          (doseq [e (filter #(= "cycle-runner" (namespace (:event/type %))) events)]
            (let [schema (get-in model/model [:events (:event/type e) :schema])
                  fields (set (map first (filter vector? (rest schema))))
                  p (model/payload e)]
              (is (clojure.set/subset? (set (keys p)) fields))
              (is (malli.core/validate schema p))))
          (is (some #(= :grain/todo-processor-checkpoint (:event/type %)) events))
          (let [indexed (map-indexed vector events)
                starts (filter #(= :sheet/tree-tick-started (:event/type (second %))) indexed)
                names (into {} (for [e events :when (= :sheet/sheet-created (:event/type e))] [(:sheet-id e) (:name e)]))]
            (doseq [[i e] indexed :when (= "cycle-runner" (namespace (:event/type e)))]
              (let [flow (case (:event/type e)
                           :cycle-runner/run-halted "cycle-runner/effect-not-performed-halts-run"
                           :cycle-runner/run-concluded (if (= "failed" (:cycle-status e)) "cycle-runner/cycle-failed-ends-run" "cycle-runner/verdicts-to-conclusion")
                           :cycle-runner/verdicts-handed-in "cycle-runner/verdicts-to-conclusion"
                           "cycle-runner/run-to-gate")]
                (is (some (fn [[j tick]]
                            (and (< j i) (= flow (get names (:sheet-id tick)))
                                 (some (fn [[k end]] (and (> k i) (= :sheet/tree-tick-completed (:event/type end))
                                                         (= (:tick-id tick) (:tick-id end)) (= :success (:root-status end)))) indexed))) starts)))))
          (let [ticks (filter #(= :sheet/tree-tick-started (:event/type %)) events)]
            (is (seq ticks))
            (doseq [tick ticks]
              (is (= 1 (count (filter #(and (= (:tick-id tick) (:tick-id %)) (= :sheet/tree-tick-completed (:event/type %))) events))))
              (is (= 1 (count (filter #(and (= (:tick-id tick) (:trace-id %)) (= :sheet/execution-traced (:event/type %))) events))))))))
      (testing "@item-06-030 unapproved grader"
        (is (= "grader-not-approved" (refusal-code (file! f [{:id "01-001" :verdict "pass"}] {:grader-digest "r-2"})))))
      (testing "@item-06-031 blank verdicts are refused without filing"
        (let [path (u/put (str (:root f) "/blank") "")]
          (is (= "verdicts-not-readable" (refusal-code (rpc f "hand-in-verdicts" {:run-id "R-1" :verdicts-path path :grader-digest "r-1"})))))
        (is (= "awaiting-verdicts" (:status (progress f)))))
      (testing "@item-06-025 handed-in verdicts are filed verbatim; cycle decides conclusion"
        (is (nil? (:error (file! f [{:id "01-001" :verdict "pass" :evidence "booked"} {:id "01-002" :verdict "fail" :evidence "judged by the main session"}]))))
        (let [done (progress f) event (event-of done "file-score")]
          (is (= "passed" (:status done)) (pr-str done))
          (is (= [1 2] ((juxt :passes :total) event)))
          (is (= ["booked" "judged by the main session"] (mapv :evidence (:items event))))))
      (testing "@item-06-004 a concluded cycle cannot run again"
        (is (= "cycle-already-run" (refusal-code (start! f {:run-id "R-2"})))))
      (testing "@item-06-053 the command gate receives cut, candidate and empty evidence outside both"
        (let [gate (event-of r "gate") paths (str/split-lines (slurp (str (:evidence-root gate) "/arguments"))) [room candidate evidence] paths]
          (is (= [20 10 3 "ledger answered as itself"] ((juxt :boot-polls :boot-budget-seconds :answered-on-poll :identity-probe-result) gate)))
          (is (= (:repo f) (str/trim (slurp (str evidence "/cwd")))))
          (is (.exists (io/file room "secret/hidden.txt")))
          (is (not (.exists (io/file room "impl"))))
          (is (= (:candidate-digest (event-of r "install")) (u/digest candidate)))
          (is (not (str/starts-with? candidate (str room "/"))))
          (is (not (.exists (io/file (:root f) "candidate-start.log"))))))
      (testing "@item-06-013 clean room has one root and no fenced history"
        (let [builder (first (str/split-lines (slurp (str (:root f) "/builders.log"))))]
          (is (= 1 (count (str/split-lines (slurp (str builder "/seen-commits"))))))
          (is (.exists (io/file builder "plan/01-a/spec.feature")))
          (is (.exists (io/file builder "harness/config.sh")))
          (is (not (re-find #" (impl|secret)/" (slurp (str builder "/seen-objects"))))))))))
(deftest refusal-before-cycle
  (testing "@item-06-008 empty checklist shows Measurement's refusal"
    (with-runner [f {:feature "Feature: Empty\n"}]
      (let [r (halted! f "checklist" "refused-before-the-cycle")]
        (is (= "empty-checklist" (:refusal (last (effects r "checklist")))))
        (is (empty? (effects r "self-check"))))))
  (testing "@item-06-009 unstamped extra scenario changes stamp identities"
    (with-runner [f {:feature (str feature "\n    Scenario: Unstamped extra\n      Given three\n")}]
      (let [r (halted! f "stamp" "refused-before-the-cycle")]
        (is (= "recorded" (:outcome (first (effects r "stamp")))))
        (is (empty? (effects r "pin")))))))
(deftest failed-self-and-terminal
  (testing "@item-06-010 failed self-check prevents fencing"
    (with-runner [f {:self "echo 'golden checklist differs'\nexit 1"}]
      (let [r (failed! f "fence" "harness-self-check-failed")]
        (is (= "golden checklist differs" (get-in r [:cycle :failure :detail])))
        (is (empty? (effects r "fence")))
        (testing "@item-06-002 identity is never reused"
          (is (= "run-already-started" (refusal-code (start! f {:cycle-id "C-2"})))))
        (testing "@item-06-034 concluded runs stay concluded after five seconds"
          (Thread/sleep 5000)
          (is (= r (progress f)))))))
  (testing "@item-06-034 halted runs stay halted after five seconds"
    (with-runner [f {:exit-on "measurement/derive-checklist"}]
      (let [r (halted! f "checklist" "context-did-not-respond")]
        (Thread/sleep 5000) (is (= r (progress f)))))))
(deftest self-check-skips
  (doseq [[id skip] [["@item-06-011" "bb-tools"] ["@item-06-038" "the oracle cannot judge this tree: a tool it grades is absent."]]]
    (testing id
      (with-runner [f {:self (str "echo 'SKIP  " skip "'")}]
        (let [r (successful! f) result (first (effects r "self-check"))]
          (is (= [skip] (:skipped-checks (first (:events result)))))
          (is (str/includes? (:detail result) skip)))))))
(deftest fence-missing
  (testing "@item-06-012 unmatched path is offered to context, refused and failed"
    (with-runner [f {:config {:FENCED_PATHS "impl harness/cycles"}}]
      (let [r (failed! f "fence" "fence-refused")]
        (is (= "fenced-path-matched-nothing" (:refusal (first (effects r "fence")))))
        (is (= "fail-cycle" (:effect (last (:effect-results r)))))
        (is (not (.exists (io/file (:root f) "builders.log"))))))))
(deftest regeneration-outcomes
  (testing "@item-06-015 nonzero builder exit is not a verdict"
    (with-runner [f {:builder-exit 1}]
      (let [r (successful! f)]
        (is (str/starts-with? (:candidate-digest (event-of r "regenerate")) "sha256:")))))
  (testing "@item-06-016 empty candidate is not regenerated"
    (with-runner [f {:builder "exit 0"}]
      (let [r (failed! f "regenerate" "candidate-not-regenerated")]
        (is (empty? (effects r "regenerate"))))))
  (testing "@item-06-017 regenerate timeout kills builder and fails budget"
    (with-runner [f {:builder (fn [root] (str "date +%s > " (q (str root "/builder-start")) "\nsleep 30\necho survived > " (q (str root "/builder-finished"))))}]
      (let [r (failed! f "regenerate" "budget" {:budget (assoc-in default-budget [:stage-timeouts-seconds :regenerate] 2)})
            launched (* 1000 (Long/parseLong (str/trim (slurp (str (:root f) "/builder-start")))))]
        ;; The regenerate budget starts at the builder, not at process boot,
        ;; stamping, the self-check, and fencing, which precede it.
        (is (< (- (System/currentTimeMillis) launched) 10000))
        (is (not (.exists (io/file (:root f) "builder-finished"))))
        (is (empty? (effects r "regenerate")))))))
(deftest repair-budget
  (testing "@item-06-014 fresh builders are sequential; @item-06-023 repair evidence is read-only"
    (with-runner [f {:seam "stdio" :red-first true}]
      (let [r (successful! f) builders (str/split-lines (slurp (str (:root f) "/builders.log")))
            evidence (:evidence-root (first (:events (first (effects r "gate")))))
            repair (second builders)]
        (is (= 2 (count builders)))
        (is (= 2 (:attempt (first (effects r "repair")))))
        (is (.exists (io/file repair "gate-evidence/suite.log")))
        (is (.exists (io/file repair "previous-candidate/test.sh")))
        (doseq [path [(str repair "/previous-candidate/test.sh") (str repair "/gate-evidence/suite.log")]]
          (let [permissions (java.nio.file.Files/getPosixFilePermissions (.toPath (io/file path)) (make-array java.nio.file.LinkOption 0))]
            (is (not (contains? permissions java.nio.file.attribute.PosixFilePermission/OWNER_WRITE))))
          (is (thrown? java.io.IOException (spit path "must not reach repair input" :append true)))
          (is (thrown? java.io.IOException (spit (str (.getParent (io/file path)) "/builder-must-not-create") "forbidden")))))))
  (testing "@item-06-024 three red candidates exhaust, never a fourth"
    (with-runner [f {:seam "stdio" :suite "exit 1"}]
      (let [r (failed! f "gate" "gate-attempts-exhausted")]
        (is (= 3 (count (str/split-lines (slurp (str (:root f) "/builders.log"))))))
        (is (= 3 (count (effects r "gate"))))))))
(deftest stdio-gate-contract
  (testing "@item-06-039 stdio suite, probe, store-check; @item-06-040 isolated store and exact argv; @item-06-044 room includes spec"
    (with-runner [f {:seam "stdio" :suite "test -f plan/01-a/spec.feature\nmkdir impl/tmp\nrmdir impl/tmp\nenv > impl/suite-environment"}]
      (let [r (successful! f) e (event-of r "gate")
            args (str/split-lines (slurp (str (:root f) "/probe.args")))
            environment (slurp (str (:root f) "/probe.env"))
            store (second (re-find #"(?m)^GATE_STORE=(.*)$" environment))
            room (:gate-room (event-of r "install"))]
        (is (= [30 1 1 "answered as the context"] ((juxt :boot-budget-seconds :boot-polls :answered-on-poll :identity-probe-result) e)))
        (is (= [(str room "/impl/start.sh") "30"] args))
        (is (str/starts-with? store (get-in f [:env "LOYLY_RUNS_DIR"])))
        (is (not (str/starts-with? store (str room "/"))))
        (is (not (re-find #"(?m)^LOYLY_" environment)))
        (is (= [store] (str/split-lines (slurp (str (:root f) "/store.args")))))
        (is (not (re-find #"(?m)^(LOYLY_|GATE_STORE=)" (slurp (str (:root f) "/store.env")))))
        (is (not (re-find #"(?m)^(LOYLY_|GATE_STORE=)" (slurp (str room "/impl/suite-environment")))))
        (is (= (:candidate-digest (event-of r "regenerate")) (:candidate-digest (event-of r "install")))))))
  (doseq [[id opts reason] [["@item-06-041" {:suite "env > impl/environment\nexit 1"} "suite-red"]
                           ["@item-06-042" {:probe "echo 'not a context'\nexit 1"} "identity-probe-failed"]
                           ["@item-06-043" {:store-check "echo 'FAIL event 7 was not written inside a run of the ORC workflow'\nexit 1"} "identity-probe-failed"]
                           ["@item-06-020" {:no-start true} "contract-missing"]]]
    (testing id
      (with-runner [f (assoc opts :seam "stdio")]
        (let [r (failed! f "gate" "gate-attempts-exhausted" {:budget (assoc default-budget :gate-attempt-limit 1)})
              e (event-of r "gate")]
          (is (= reason (:reason e)))
          (when (= id "@item-06-041")
            (is (not (.exists (io/file (:root f) "probe.args"))))
            (let [room (:gate-room (event-of r "install"))]
              (is (not (re-find #"(?m)^(LOYLY_|GATE_STORE=)" (slurp (str room "/impl/environment")))))))
          (when (= id "@item-06-043") (is (str/includes? (:detail e) "FAIL event 7")))))))
  (testing "@item-06-045 missing store check is a halt, not a failed attempt"
    (with-runner [f {:seam "stdio" :env {"LOYLY_STORE_CHECK_CMD" "/no-such-program"}}]
      (let [r (halted! f "gate" "effect-could-not-run")]
        (is (empty? (effects r "gate")))))))
(deftest score-hand-in-refusals
  (testing "@item-06-027 no run"
    (with-runner [f {}]
      (is (= "no-run" (refusal-code (file! f [] {:run-id "R-9"}))))))
  (testing "@item-06-028 gate never passed"
    (with-runner [f {:seam "stdio" :suite "exit 1"}]
      (failed! f "gate" "gate-attempts-exhausted" {:budget (assoc default-budget :gate-attempt-limit 1)})
      (is (= "gate-not-passed" (refusal-code (file! f []))))))
  (doseq [[id values] [["@item-06-029" []] ["@item-06-032" [{:id "01-001" :verdict "pass"} {:id "09-001" :verdict "pass"}]]]]
    (testing id
      (with-runner [f {}]
        (successful! f)
        (is (nil? (:error (file! f values))))
        (let [r (progress f)]
          (is (= "failed" (:status r)) (pr-str r))
          (is (= "score-stage-failed" (get-in r [:cycle :failure :reason])))
          (is (= 1 (count (effects r "fail-score-stage"))))
          (is (= "verdicts-already-handed-in" (refusal-code (file! f [{:id "01-001" :verdict "pass"}]))))
          (is (= 1 (count (str/split-lines (slurp (str (:root f) "/builders.log"))))))))))
  (testing "@item-06-026 unreached is Measurement's, not filled in by runner"
    (with-runner [f {}]
      (successful! f) (is (nil? (:error (file! f [{:id "01-001" :verdict "pass"}]))))
      (let [r (progress f) e (event-of r "file-score")]
        (is (= "passed" (:status r)))
        (is (= [1 2] ((juxt :passes :total) e)))
        (is (= "unreached" (:reason (second (:items e)))))
        (let [offered (last (filter #(= "measurement/file-item-verdicts" (:method %)) (peers-log f)))]
          (is (= ["01-001"] (mapv :item-id (get-in offered [:params :verdicts])))))))))
(deftest http-host-contract
  (testing "@item-06-036 served URL, not port; @item-06-037 writable gate room; @item-06-052 cut's probe reads its sibling"
    (with-runner [f {:seam "http"
                    :probe "ROOT=$(CDPATH= cd -- \"$(dirname -- \"$0\")/..\" && pwd)\n. \"$ROOT/harness/config.sh\"\ncase \"$1\" in http://127.0.0.1:*) ;; *) exit 1;; esac\ncurl -fsS \"$1\" | grep -qi \"$APP_MARKER\"\necho 'served marker'"}]
      (let [r (successful! f)]
        (is (= "served marker" (:identity-probe-result (event-of r "gate"))))
        (is (= (:candidate-digest (event-of r "regenerate")) (:candidate-digest (event-of r "install")))))))
  (testing "@item-06-019 red HTTP suite never starts candidate"
    (with-runner [f {:seam "http" :suite "exit 1"}]
      (let [r (failed! f "gate" "gate-attempts-exhausted" {:budget (assoc default-budget :gate-attempt-limit 1)})]
        (is (= "suite-red" (:reason (event-of r "gate"))))
        (is (not (.exists (io/file (:root f) "candidate-start.log")))))))
  (testing "@item-06-021 page without marker fails identity"
    (with-runner [f {:seam "http" :config {:APP_MARKER "not-on-the-page"}}]
      (let [r (failed! f "gate" "gate-attempts-exhausted" {:budget (assoc default-budget :gate-attempt-limit 1)})]
        (is (= "identity-probe-failed" (:reason (event-of r "gate")))))))
  (testing "@item-06-018 existing listener refuses three times, never boots candidate"
    (let [listener (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
          responder (future
                      (try
                        (while (not (.isClosed listener))
                          (with-open [client (.accept listener) out (io/writer (.getOutputStream client))]
                            (.write out "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nsauna") (.flush out)))
                        (catch Throwable _)))]
      (try
        (with-runner [f {:seam "http" :config {:PORT (str (.getLocalPort listener))}}]
          (let [r (failed! f "gate" "infrastructure")]
            (is (= ["seam-answered-before-boot" "seam-answered-before-boot" "seam-answered-before-boot"] (mapv :refusal (effects r "gate"))))
            (is (= 3 (count (effects r "gate-refusal"))))
            (is (= 2 (count (effects r "self-check"))))
            (is (not (.exists (io/file (:root f) "candidate-start.log"))))))
        (finally (.close listener) (future-cancel responder))))))
(deftest candidate-snapshot
  (testing "@item-06-022 writes to builder candidate after exit cannot reach the gated copy"
    (with-runner [f {:seam "stdio"
                    :builder (fn [root]
                               (str "cp -R " (q (str root "/fixture-candidate/.")) " \"$1/impl/\"\n"
                                    "(sleep 1; printf '#!/bin/sh\\nexit 1\\n' > \"$1/impl/test.sh\") >/dev/null 2>&1 &\nexit 0"))}]
      (let [r (successful! f)]
        (is (= (:candidate-digest (event-of r "regenerate")) (:candidate-digest (event-of r "install"))))))))
(deftest selected-plan
  (testing "@item-06-047 linked slice reads target, retains own path"
    (with-runner [f {:symlink true}]
      (let [r (successful! f)]
        (is (= ["01-001" "01-002"] (mapv :item-id (:items (event-of r "checklist")))))
        (is (every? #(str/starts-with? (:source %) "plan/01-a/") (:items (event-of r "checklist")))))))
  (testing "@item-06-048 PLAN_SLICES selects both stamping and checklist"
    (with-runner [f {:config {:PLAN_SLICES "02"}
                    :files {"plan/02-b/spec.feature" "Feature: Other\n  @item-02-001\n  Scenario: Selected\n    Given two\n"}}]
      (let [r (successful! f)]
        (is (= ["02-001"] (mapv :item-id (:items (event-of r "checklist")))))
        (is (= 1 (count (effects r "stamp"))))
        (is (= "02" (:slice (event-of r "stamp"))))))))
(deftest configured-builder-images
  (testing "@item-06-050 missing image is refused before run event"
    (with-runner [f {:image :missing}]
      (is (= "builder-image-missing" (refusal-code (start! f))))
      (is (nil? (progress f)))
      (is (not (.exists (io/file (:root f) "builders.log"))))))
  (testing "@item-06-049 every builder gets same image and records its actual digest"
    (with-runner [f {:image :present :seam "stdio" :red-first true}]
      (let [r (successful! f)
            image (str (:root f) "/images/builder-clojure")
            arguments (partition 3 (str/split-lines (slurp (str (:root f) "/builder.args"))))]
        (is (= 2 (count arguments)))
        (is (every? #(= image (nth % 2)) arguments))
        (doseq [e (concat (effects r "regenerate") (effects r "repair"))]
          (is (str/includes? (:detail e) "builder-clojure"))
          (is (str/includes? (:detail e) (u/digest image))))))))
(deftest gate-command-results
  (testing "@item-06-054 failed command gives reason and evidence to fresh repair"
    (with-runner [f {:gate (fn [root]
                            (str "echo proof > \"$3/proof\"\necho BOOT 20 10\nif [ ! -f " (q (str root "/gate-tried")) " ]; then touch " (q (str root "/gate-tried")) "; echo 'suite-red 3 of 65 tests failed'; exit 1; fi\necho ANSWERED 1\necho passed"))}]
      (let [r (successful! f) gates (effects r "gate")
            second-room (second (str/split-lines (slurp (str (:root f) "/builders.log"))))]
        (is (= "suite-red" (:reason (first (:events (first gates))))))
        (is (= "3 of 65 tests failed" (:detail (first (:events (first gates))))))
        (is (= "proof\n" (slurp (str second-room "/gate-evidence/proof"))))
        (is (= "regeneration-cycle/gate-attempt-passed" (:type (first (:events (second gates)))))))))
  (testing "@item-06-055 gate command refusal is not a verdict; three refusals conclude by context"
    (with-runner [f {:gate "echo BOOT 20 10\necho 'seam-answered-before-boot 127.0.0.1:8920 answered before start.sh ran'\nexit 1"}]
      (let [r (failed! f "gate" "infrastructure")]
        (is (= 3 (count (effects r "gate"))))
        (is (every? #(= "seam-answered-before-boot" (:refusal %)) (effects r "gate")))
        (is (= 3 (count (effects r "gate-refusal")))))))
  (testing "@item-06-056 infrastructure command result halts without gate fact"
    (with-runner [f {:gate "echo BOOT 20 10\necho 'infrastructure the world did not come up'\nexit 2"}]
      (let [r (halted! f "gate" "effect-could-not-run")]
        (is (= "infrastructure the world did not come up" (get-in r [:halt :detail])))
        (is (empty? (effects r "gate")))))))
(deftest harness-digests
  (testing "@item-06-046 stdio harness digest covers store-check command text"
    (let [f (fixture {:seam "stdio" :probe "exit 1"})
          run-one (fn [fixture id]
                    (let [running (launch fixture)]
                      (try
                        (start! running {:run-id (str "R-" id) :cycle-id (str "C-" id) :cut-id (str "K-" id) :budget (assoc default-budget :gate-attempt-limit 1)})
                        (:harness-digest (first (:events (first (effects (progress running (str "R-" id)) "self-check")))))
                        (finally (stop running)))))
          d1 (run-one (update-in f [:env "LOYLY_STORE_CHECK_CMD"] str " one") 1)
          d2 (run-one (update-in f [:env "LOYLY_STORE_CHECK_CMD"] str " two") 2)]
      (is (not= d1 d2))))
  (testing "@item-06-057 absent gate command preserves formula; command text is covered"
    (let [f (fixture {:seam "http"})
          expected (u/sha (str (slurp (:cfg f)) (get-in f [:env "LOYLY_SELF_CHECK_CMD"]) (slurp (str (:repo f) "/harness/probe.sh"))))
          run-one (fn [fixture id]
                    (let [running (launch fixture)]
                      (try
                        (successful! running {:run-id "R-1" :cycle-id "C-1"})
                        (:harness-digest (first (:events (first (effects (progress running) "self-check")))))
                        (finally (stop running)))))
          fresh-store (fn [fixture id] (assoc-in fixture [:env "LOYLY_GRAIN_STORE"] (str (:root f) "/digest-" id ".sqlite")))
          d1 (run-one (fresh-store f 1) 1)
          d2 (run-one (assoc-in (fresh-store f 2) [:env "LOYLY_GATE_CMD"] (str (:root f) "/gate.sh one")) 2)
          d3 (run-one (assoc-in (fresh-store f 3) [:env "LOYLY_GATE_CMD"] (str (:root f) "/gate.sh two")) 3)]
      (is (= expected d1))
      (is (= 3 (count (set [d1 d2 d3])))))))
(deftest measurement-history
  (testing "@item-06-051 history retained; only this run's handoffs relay"
    (let [f (fixture {}) f (assoc-in f [:env "LOYLY_MEASUREMENT_STORE"] (str (:root f) "/history.sqlite"))]
      (doseq [id [1 2]]
        (let [running (launch f)]
          (try
            (start! running {:run-id (str "R-" id) :cycle-id (str "C-" id) :cut-id (str "K-" id)})
            (is (nil? (:error (file! running [{:id "01-001" :verdict "pass"}] {:run-id (str "R-" id)}))))
            (let [r (progress running (str "R-" id)) rows (filter #(= "measurement" (:producer %)) (:relayed-handoffs r))]
              (is (= "passed" (:status r)))
              (is (= ["measurement/measurement-filed" "measurement/scoreboard-reported"] (mapv :handoff-type rows)))
              (is (every? #(= "regeneration-cycle" (:consumer %)) rows)))
            (finally (stop running)))))
      (let [_ (mailbox/register! (:root f))
            entry (get (u/decode (slurp (get-in f [:env "LOYLY_CONTEXTS"]))) :measurement)
            p (u/launch [entry] base (assoc (:env f) "LOYLY_GRAIN_STORE" (get-in f [:env "LOYLY_MEASUREMENT_STORE"])) false)
            peer {:process p :in (io/writer (.getOutputStream p)) :out (io/reader (.getInputStream p)) :lock (Object.)}]
        (try
          (is (= "C-1" (get-in (contexts/rpc peer "measurement/scoreboard" {:cycle-id "C-2"} 30000) [:result :scoreboard :previous-cycle-id])))
          (finally (u/stop-process p) (mailbox/stop! (:root f))))))))
(deftest gate-identity-sequence
  (let [f (fixture {:exit-contexts contexts/names}) launched (System/currentTimeMillis) f (launch f)]
    (try
      (is (= "not-a-commit" (refusal-code (start! f {:commit "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"}))))
      (println "IDENTITY invalid commit elapsed-ms" (- (System/currentTimeMillis) launched))
      (let [reply (start! f)]
        (is (= "contexts-not-responding" (refusal-code reply)))
        (is (= (set contexts/names) (set (get-in reply [:error :data :contexts])))))
      (println "IDENTITY exited contexts elapsed-ms" (- (System/currentTimeMillis) launched))
      (doseq [name contexts/names]
        (script (str (:root f) "/" name ".sh")
                (str "cd " (q base) "\n" (context-script name))))
      (u/put (get-in f [:env "RUNNER_TEST_SCRIPT"]) (u/encode {:exit-on "specification/stamp-slice" :peer-log (str (:root f) "/peers.log")}))
      (is (nil? (:error (start! f))))
      (let [r (progress f)]
        (is (= ["halted" {:effect "stamp" :reason "context-did-not-respond"}] [(:status r) (select-keys (:halt r) [:effect :reason])]))
        (is (empty? (:effect-results r)))
        (is (not (.exists (io/file (:root f) "self.log"))))
        (is (not (.exists (io/file (:root f) "builders.log"))))
        (println "IDENTITY halted stamp elapsed-ms" (- (System/currentTimeMillis) launched))
        (is (< (- (System/currentTimeMillis) launched) 30000)))
      (finally (stop f)))))

(deftest builder-status-127-is-not-an-exec-error
  (testing "@item-06-015 a launched builder's status is no verdict, including shell-reserved statuses"
    (with-runner [f {:builder-exit 127}]
      (let [r (successful! f)]
        (is (= "regeneration-cycle/gate-attempt-passed" (:type (event-of r "gate"))))))))
