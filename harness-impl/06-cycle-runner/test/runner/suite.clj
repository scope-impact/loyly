(ns runner.suite
  (:require [clojure.test :refer :all] [clojure.java.io :as io] [clojure.string :as str]
            [runner.util :as u] [runner.contexts :as contexts] [runner.model :as model] [runner.mailbox :as mailbox]
            [taoensso.nippy :as nippy])
  (:import [java.nio.file Files]))
(def base (u/abs "."))
(defn q [s] (str "'" (str/replace (str s) "'" "'\\''") "'"))
(defn connection-script [root header]
  (str "dir=$(mktemp -d " root "/peer.XXXXXXXX)\n" header " > \"$dir/hello.tmp\"\nmv \"$dir/hello.tmp\" \"$dir/hello.json\"\nposition=0\nwhile IFS= read -r line; do\n  printf '%s\\n' \"$line\" > \"$dir/request-$position.tmp\"\n  mv \"$dir/request-$position.tmp\" \"$dir/request-$position.json\"\n  while [ ! -f \"$dir/response-$position.json\" ]; do sleep 0.02; done\n  cat \"$dir/response-$position.json\"\n  position=$((position+1))\ndone"))
(defn context-script [context]
  (connection-script "\"${RUNNER_TEST_SCRIPT%/*}/mailboxes\""
    (str "printf '{\"context\":\"" context "\",\"pid\":\"%s\",\"env\":{\"RUNNER_TEST_SCRIPT\":\"%s\",\"LOYLY_GRAIN_STORE\":\"%s\"}}\\n' \"$$\" \"$RUNNER_TEST_SCRIPT\" \"$LOYLY_GRAIN_STORE\"")))
(defn http-script [root]
  (str "dir=$(mktemp -d " (q (str root "/mailboxes/peer.XXXXXXXX")) ")\nprintf '{\"context\":\"fixture-http\",\"port\":%s,\"pid\":\"%s\"}\\n' \"$1\" \"$$\" > \"$dir/hello.tmp\"\nmv \"$dir/hello.tmp\" \"$dir/hello.json\"\nwhile :; do sleep 1; done"))
(defn script [path text] (u/put path (str (if (str/includes? text "< <(") "#!/bin/bash\n" "#!/bin/sh\n") "set -eu\n" text "\n")) (.setExecutable (io/file path) true false) path)
(def feature "Feature: Fixture\n  Rule: Book\n    @other @item-01-001\n    Scenario: First\n      Given one\n\n    @item-01-002\n    Scenario: Second\n      Given two\n")
(def default-budget {:gate-attempt-limit 3 :max-outer-cycles 1 :max-continuations 0 :stage-timeouts-seconds {:fence 60 :regenerate 30 :gate 30 :score 30}})
(defn render [v root] (if (fn? v) (v root) v))
(defn fixture [opts]
  (let [root (u/mkdir (str base "/target/suite/" (random-uuid)))
        _ (mailbox/register! root)
        repo (u/mkdir (str root "/repo"))
        cfg (str root "/config.sh")
        seam (:seam opts "command")
        probe (str repo "/harness/probe.sh")
        config (merge {:APP_DIR "impl" :PLAN_DIR "plan" :APP_PROBE "harness/probe.sh" :APP_SEAM (if (= seam "command") "stdio" seam) :FENCED_PATHS "impl secret" :APP_MARKER "sauna" :PORT "18921"} (when (:image opts) {:BUILDER_IMAGE "builder-clojure"}) (:config opts))
        _ (when (= :present (:image opts)) (u/put (str root "/images/builder-clojure/toolchain") "fixture image bytes"))
        _ (u/put cfg (str/join "\n" (for [[k v] config] (str (name k) "=" (q v)))))
        _ (u/put (str repo "/harness/config.sh") (slurp cfg))
        _ (u/put (str repo "/plan/01-a/spec.feature") (:feature opts feature))
        _ (u/put (str repo "/impl/old.txt") "candidate history")
        _ (u/put (str repo "/secret/hidden.txt") "fenced")
        _ (script probe (or (render (:probe opts) root) (str "printf '%s\\n' \"$@\" > " (q (str root "/probe.args")) "\nenv > " (q (str root "/probe.env")) "\nprintf 'answered as the context\\n'\nexit 0")))
        _ (doseq [[p text] (:files opts)] (u/put (str repo "/" p) text))
        _ (when (:symlink opts)
            (u/mkdir (str repo "/spec"))
            (java.nio.file.Files/move (.toPath (io/file repo "plan/01-a")) (.toPath (io/file repo "spec/a")) (make-array java.nio.file.CopyOption 0))
            (java.nio.file.Files/createSymbolicLink (.toPath (io/file repo "plan/01-a")) (java.nio.file.Paths/get "../spec/a" (make-array String 0)) (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (u/git repo "init")
        _ (u/git repo "add" ".")
        _ (u/git repo "-c" "user.name=Suite" "-c" "user.email=suite@localhost" "commit" "-m" "Fixture")
        commit (str/trim (u/git repo "rev-parse" "HEAD"))
        self (script (str root "/self.sh") (or (:self opts) (str "echo self >> " (q (str root "/self.log")) "\necho passed")))
        storecheck (script (str root "/store-check.sh") (or (:store-check opts) (str "printf '%s\\n' \"$@\" > " (q (str root "/store.args")) "\nenv > " (q (str root "/store.env")) "\necho store-passed")))
        candidate (u/mkdir (str root "/fixture-candidate"))
        _ (script (str candidate "/test.sh") (or (:suite opts) (str "env > " (q (str root "/suite.env")) "\nmkdir impl/suite-tmp\nrmdir impl/suite-tmp\nexit 0")))
        _ (when-not (:no-start opts)
            (script (str candidate "/start.sh")
                    (or (:start opts)
                        (str "echo launched >> " (q (str root "/candidate-start.log")) "\n" (http-script root)))))
        builder (script (str root "/builder.sh")
                        (or (render (:builder opts) root)
                            (str "printf '%s\\n' \"$1\" >> " (q (str root "/builders.log")) "\nprintf '%s\\n' \"$@\" >> " (q (str root "/builder.args"))
                                 "\ngit -c safe.directory='*' -C \"$1\" rev-list --all > \"$1/seen-commits\"\ngit -c safe.directory='*' -C \"$1\" rev-list --objects --all > \"$1/seen-objects\"\ncp -R " (q (str candidate "/.")) " \"$1/impl/\"\n"
                                 (when (:red-first opts) (str "if [ \"$(wc -l < " (q (str root "/builders.log")) ")\" -eq 1 ]; then printf '#!/bin/sh\\nexit 1\\n' > \"$1/impl/test.sh\"; chmod +x \"$1/impl/test.sh\"; fi\n"))
                                 (when (:builder-exit opts) (str "exit " (:builder-exit opts))))))
        gate (script (str root "/gate.sh") (or (render (:gate opts) root) (str "printf '%s\\n' \"$@\" > \"$3/arguments\"\npwd > \"$3/cwd\"\necho BOOT 20 10\necho ANSWERED 3\necho 'ledger answered as itself'")))
        peer-script (u/put (str root "/peers.json") (u/encode {:exit-on (:exit-on opts) :peer-log (str root "/peers.log")}))
        classpath (System/getProperty "java.class.path")
        peers (into {} (for [n contexts/names]
                         [(keyword n) (script (str root "/" n ".sh")
                           (if (contains? (set (:exit-contexts opts)) n) "exit 0"
                             (str "cd " (q base) "\n" (context-script n))))]))
        contexts-file (u/put (str root "/contexts.json") (u/encode peers))
        instruction (u/put (str root "/instruction.md") "Build the fixture product. No grading evidence.")
        env {"LOYLY_PROJECT_REPO" repo "LOYLY_GRAIN_STORE" (str root "/runner.sqlite") "LOYLY_RUNS_DIR" (u/mkdir (str root "/runs"))
             "LOYLY_HARNESS_CONFIG" cfg "LOYLY_CONTEXTS" contexts-file "LOYLY_SELF_CHECK_CMD" self "LOYLY_BUILDER_CMD" builder
             "LOYLY_BUILDER_INSTRUCTION" instruction "LOYLY_STORE_CHECK_CMD" storecheck "RUNNER_TEST_SCRIPT" peer-script
             "GATE_STORE" "inherited-store-must-not-leak" "LOYLY_BUILDER_IMAGES" (str root "/images")}
        env (cond-> (merge env (:env opts)) (= seam "command") (assoc "LOYLY_GATE_CMD" gate))]
    {:root root :repo repo :config config :cfg cfg :commit commit :env env :opts opts}))
(defn launch [f]
  (mailbox/register! (:root f))
  (let [p (u/launch [(str base "/start.sh")] nil (:env f) false)
        err (future (with-open [in (.getErrorStream p) out (io/output-stream (str (:root f) "/runner.stderr"))] (io/copy in out)))]
    (assoc f :process p :peer {:process p :in (io/writer (.getOutputStream p)) :out (io/reader (.getInputStream p)) :lock (Object.)} :stderr err)))
(defn stop [f]
  (when (:process f) (u/stop-process (:process f)))
  (mailbox/stop! (:root f))
  (when (:stderr f) (deref (:stderr f) 5000 nil)))
(defmacro with-runner [[sym opts] & body]
  `(let [~sym (launch (fixture ~opts))] (try ~@body (finally (stop ~sym)))))
(defn rpc [f method params]
  (let [r (contexts/rpc (:peer f) (str "cycle-runner/" method) params 120000)]
    (when-not r (throw (ex-info (str "Runner did not answer; fixture " (:root f) "; " (when (.exists (io/file (:root f) "runner.stderr")) (slurp (str (:root f) "/runner.stderr")))) {}))) r))
(defn start-params [f & [extra]]
  (merge {:run-id "R-1" :cycle-id "C-1" :cut-id "K-1" :commit (:commit f) :selection-id "S" :budget default-budget :grader-digests ["r-1"]} extra))
(defn start! [f & [extra]] (rpc f "start-run" (start-params f extra)))
(defn progress [f & [id]] (get-in (rpc f "run-progress" {:run-id (or id "R-1")}) [:result :run]))
(defn effects [r effect] (filterv #(= effect (:effect %)) (:effect-results r)))
(defn refusal-code [reply] (get-in reply [:error :data :refusal]))
(defn successful! [f & [extra]]
  (let [reply (start! f extra) r (progress f)]
    (is (nil? (:error reply)) (pr-str reply))
    (is (= "awaiting-verdicts" (:status r)) (pr-str r)) r))
(deftest item-06-005
  (testing "@item-06-005 invalid commit is refused before contexts"
    (with-runner [f {:exit-contexts contexts/names}]
      (is (= "not-a-commit" (refusal-code (start! f {:commit "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"}))))
      (is (nil? (progress f)))
      (is (not (.exists (io/file (:root f) "builders.log")))))))
(deftest item-06-006
  (testing "@item-06-006 missing context is named"
    (with-runner [f {:exit-contexts contexts/names}]
      (let [r (start! f)]
        (is (= "contexts-not-responding" (refusal-code r)) (pr-str r))
        (is (= (set contexts/names) (set (get-in r [:error :data :contexts])))))
      (is (nil? (progress f))))))
(deftest item-06-033
  (testing "@item-06-033 context exits at checklist; no checklist fact"
    (with-runner [f {:exit-on "measurement/derive-checklist"}]
      (is (nil? (:error (start! f))))
      (let [r (progress f)]
        (is (= "halted" (:status r)) (pr-str r))
        (is (= {:effect "checklist" :reason "context-did-not-respond"} (select-keys (:halt r) [:effect :reason])) (pr-str r))
        (is (empty? (effects r "checklist")))))))
(deftest happy-pipeline
  (testing "@item-06-001 ordered real effects"
    (with-runner [f {:seam "http"}]
      (let [r (successful! f)]
        (is (= ["stamp" "pin" "checklist" "self-check" "fence" "regenerate" "install" "self-check" "gate"] (mapv :effect (:effect-results r))) (pr-str r))))))
(defmethod clojure.test/report :begin-test-var [m]
  (println "TEST" (-> m :var meta :name)) (flush))
(defn warmup! [] (mailbox/start!))
(defn -main [& selected]
  (load-file "test/runner/cases.clj")
  (warmup!)
  (let [r (if (seq selected)
            (binding [clojure.test/*report-counters* (ref clojure.test/*initial-report-counters*)]
              (clojure.test/test-vars (map #(ns-resolve 'runner.suite (symbol %)) selected))
              @clojure.test/*report-counters*)
            (run-tests 'runner.suite))]
    (mailbox/shutdown!)
    (shutdown-agents)
    (System/exit (if (and (pos? (:test r)) (zero? (+ (:fail r) (:error r)))) 0 1))))
