(ns loyly.paths
  (:require [clojure.test :refer [deftest is testing]] [clojure.string :as str] [clojure.java.io :as io]
            [loyly.spine :as sp] [loyly.main :as main] [loyly.host :as host] [loyly.domain :as d]))
(defn with-case ([f] (with-case identity f)) ([transform f]
  (let [root (.getCanonicalFile (.toFile (java.nio.file.Files/createTempDirectory (.toPath (io/file ".")) "case-" (make-array java.nio.file.attribute.FileAttribute 0))))
        server (sp/model transform)]
    (try
      (let [{:keys [env params]} (sp/fixture root server) e (atom env)
            sys (main/open! (str (io/file root "store")) #(deref e))]
        (try (f {:root root :env e :sys sys :p params :repo (io/file (env "LOYLY_PROJECT_REPO"))})
             (finally (main/close! sys))))
      (finally (.stop server 0) (host/checked "." ["chmod" "-R" "u+w" root]) (host/checked "." ["rm" "-rf" root]))))))
(defn commit [repo]
  (host/checked repo ["git" "add" "-A"])
  (host/checked repo ["git" "-c" "user.name=Fixture" "-c" "user.email=fixture@example.invalid" "commit" "--allow-empty" "-qm" "scenario"])
  (host/checked repo ["git" "rev-parse" "HEAD"]))
(defn start [c p]
  (let [r (sp/call (:sys c) "start-run" p)]
    (is (nil? (:error r)) (str (:error r)))
    (get-in (sp/call (:sys c) "run-progress" {:run-id (:run-id p)}) [:result :run])))
(defn effect [r name] (first (filter #(= name (:effect %)) (:effect-results r))))
(defn first-event [r name] (first (:events (effect r name))))
(defn names [r] (mapv :effect (:effect-results r)))
(defn script [c key body] (let [p (@(:env c) key)] (spit p (str "#!/bin/sh\n" body "\n"))))
(defn patch-config [c text] (spit (@(:env c) "LOYLY_HARNESS_CONFIG") text :append true))
(defn new-id [p id] (assoc p :run-id id :cycle-id id :cut-id id))
(defn query [sys method p] (:result (main/respond sys {:jsonrpc "2.0" :id 1 :method method :params p})))
(deftest preparation-and-refusal
  (with-case
    (fn [{:keys [p sys repo env] :as c}]
      (testing "@item-06-068"
        (let [r (start c (assoc p :grader-digests ["not-approved"]))]
          (is (= ["stamp" "pin" "checklist"] (names r)))
          (is (= ["grader" "refused-before-the-cycle"] ((juxt :effect :reason) (:halt r))))
          (is (str/includes? (get-in r [:halt :detail]) (first (:grader-digests p))))
          (is (not (.exists (io/file (@env "LOYLY_RUNS_DIR") (:run-id p) "builder-1"))))))
      (testing "@item-06-008"
        (patch-config c "PLAN_SLICES=42\n")
        (let [r (start c (new-id p "empty"))]
          (is (= "empty-checklist" (:refusal (effect r "checklist"))))
          (is (= ["halted" "refused-before-the-cycle"] [(:status r) (get-in r [:halt :reason])]))
          (is (nil? (effect r "self-check")))))
      (testing "@item-06-009"
        (patch-config c "PLAN_SLICES=02\n")
        (sp/file! repo "plan/02-extra/spec.feature" "Feature: X\nScenario: unstamped\n" false)
        (let [r (start c (assoc (new-id p "mismatch") :commit (commit repo)))]
          (is (= ["stamp"] (names r)))
          (is (= "recorded" (:outcome (effect r "stamp"))))
          (is (= ["stamp" "refused-before-the-cycle"] ((juxt :effect :reason) (:halt r)))))))))
(deftest fence-and-budget
  (with-case
    (fn [{:keys [p sys env] :as c}]
      (script c "LOYLY_SELF_CHECK_CMD" "echo 'golden checklist differs'; exit 1")
      (let [r (start c p)]
        (testing "@item-06-010"
          (is (= {:stage "fence" :reason "harness-self-check-failed" :detail "golden checklist differs"} (get-in r [:cycle :failure])))
          (is (= "self-check" (last (names r)))))
        (testing "@item-06-002"
          (is (= "run-already-started" (get-in (sp/call sys "start-run" (assoc p :cycle-id "other")) [:error :data :refusal])))
          (is (= [(:cycle-id p)] (mapv :cycle-id (get-in (sp/call sys "run-progress" {}) [:result :runs])))))
        (testing "@item-06-034"
          (patch-config c "PLAN_SLICES=90\n")
          (let [r2 (start c (new-id p "empty"))]
            (Thread/sleep 5000)
            (is (= "failed" (:status r)))
            (is (= "halted" (:status r2)))
            (doseq [row [r r2]] (is (= row (get-in (sp/call sys "run-progress" {:run-id (:run-id row)}) [:result :run])))))))
      (script c "LOYLY_SELF_CHECK_CMD" "echo 'SKIP bb-tools'; echo '  SKIP  the oracle cannot judge this tree: a tool it grades is absent.'")
      (patch-config c "PLAN_SLICES=\nFENCED_PATHS=\"impl absent\"\n")
      (let [r (start c (new-id p "fence")) self (effect r "self-check")]
        (testing "@item-06-011 @item-06-038"
          (is (= ["bb-tools" "the oracle cannot judge this tree: a tool it grades is absent."] (:skipped-checks (first (:events self)))))
          (doseq [skip (:skipped-checks (first (:events self)))] (is (str/includes? (:detail self) skip))))
        (testing "@item-06-012"
          (is (= "fenced-path-matched-nothing" (:refusal (effect r "fence"))))
          (is (= ["fence" "fail-cycle"] (take-last 2 (names r))))
          (is (= "fence-refused" (get-in r [:cycle :failure :reason])))
          (is (not (.exists (io/file (@env "LOYLY_RUNS_DIR") "fence/builder-1"))))))
      (patch-config c "FENCED_PATHS=impl\n")
      (doseq [[id cmd reason] [["@item-06-016" "exit 0" "candidate-not-regenerated"] ["@item-06-017" "sleep 10" "budget"]]]
        (testing id (script c "LOYLY_BUILDER_HOST" cmd)
          (let [r (start c (assoc-in (new-id p id) [:budget :stage-timeouts-seconds :regenerate] 1))]
            (is (= "failed" (:status r))) (is (= ["regenerate" reason] ((juxt :stage :reason) (get-in r [:cycle :failure]))))
            (is (nil? (effect r "gate")))))))))
(deftest failed-command-gates
  (with-case
    (fn [{:keys [p] :as c}]
      (script c "LOYLY_GATE_CMD" "echo 'BOOT 20 10'; echo 'infrastructure the world did not come up'; exit 2")
      (testing "@item-06-056"
        (let [r (start c p)]
          (is (= {:effect "gate" :reason "effect-could-not-run" :detail "infrastructure the world did not come up"} (:halt r)))
          (is (not-any? #{"gate" "fail-cycle"} (names r))) (is (nil? (:cycle r)))))
      (script c "LOYLY_GATE_CMD" "echo 'BOOT 20 10'; echo 'seam-answered-before-boot port answered'; exit 1")
      (testing "@item-06-055"
        (let [r (start c (new-id p "guard"))]
          (is (= (vec (take 6 (cycle ["gate" "gate-refusal"]))) (take-last 6 (names r))))
          (is (= ["seam-answered-before-boot" "seam-answered-before-boot" "seam-answered-before-boot"]
                 (mapv :refusal (filter #(= "gate" (:effect %)) (:effect-results r)))))
          (is (= "infrastructure" (get-in r [:cycle :failure :reason]))))))))
(defn write-program [dir path body] (sp/file! dir path (str "#!/bin/sh\n" body "\n") true))
(defn prepare-builder [c]
  (let [root (:root c) src (io/file root "source") b (@(:env c) "LOYLY_BUILDER_HOST")]
    (write-program root "source/test.sh" "echo green")
    (write-program root "source/start.sh" "echo candidate")
    (write-program root "builder"
      (str "set -eu\nprintf '%s\\n' \"$LOYLY_BUILDER_SESSION_ID $1 $2 ${3-}\" >> '" root "/launches'\n"
           "cp -R '" src "' \"$1/impl\"\n"))
    src))
(defn commit-probe [c path script]
  (write-program (:repo c) path script)
  (commit (:repo c)))
(defn rpc-refusal [c p] (get-in (sp/call (:sys c) "start-run" p) [:error :data :refusal]))
(deftest successful-repair
  (with-case
    (fn [{:keys [p sys root env repo] :as c}]
      (let [_ (prepare-builder c)
            image (io/file root "images" "clojure") _ (.mkdirs image)
            _ (sp/file! image "tool" "toolchain" false)
            _ (swap! env assoc "LOYLY_BUILDER_IMAGES" (str (.getParentFile image)))
            _ (patch-config c "BUILDER_IMAGE=clojure\n")
            _ (script c "LOYLY_GATE_CMD" (str "set -eu\ntest ! -e \"$1/impl\"; test -x \"$2/start.sh\"; test -z \"$(ls -A \"$3\")\"\n"
                     "printf '%s\\n' \"$PWD $1 $2 $3\" >> '" root "/gate-log'\necho 'BOOT 20 10'\n"
                     "case \"$1\" in *gate-1) echo diagnostic > \"$3/detail\"; echo 'suite-red 3 of 65 tests failed'; exit 1;; esac\n"
                     "echo 'ANSWERED 3'; echo 'ledger answered as itself'"))
            r (start c (assoc-in p [:budget :gate-attempt-limit] 2))
            effects (:effect-results r) launches (str/split-lines (slurp (io/file root "launches")))
            [[session1 builder1 instruction1 img1] [session2 builder2 instruction2 img2]] (map #(str/split % #" ") launches)
            gates (filter #(= "gate" (:effect %)) effects) gate-events (mapv #(first (:events %)) gates)
            attempt1 (first gate-events) attempt2 (second gate-events)
            event2 (first (:events (effect r "repair")))]
        (is (= "passed" (:status r)) (str (:halt r) (:cycle r)))
        (testing "@item-06-014"
          (is (= 2 (count launches))) (is (not= session1 session2))
          (is (str/ends-with? session1 "/1")) (is (str/ends-with? session2 "/2"))
          (is (= ["regenerate" "install" "self-check" "gate" "repair"] (subvec (names r) 5 10))))
        (testing "@item-06-049"
          (is (= img1 img2 (str image)))
          (doseq [n ["regenerate" "repair"]]
            (is (str/includes? (:detail (effect r n)) "clojure"))
            (is (str/includes? (:detail (effect r n)) (host/tree-digest image)))))
        (testing "@item-06-023 @item-06-054"
          (is (= ["suite-red" "3 of 65 tests failed"] ((juxt :reason :detail) attempt1)))
          (is (= ["verification-repair" 2] ((juxt :purpose :attempt) event2)))
          (is (str/includes? (:detail (effect r "repair")) (:evidence-root attempt1)))
          (is (= "diagnostic\n" (slurp (io/file builder2 "gate-evidence/detail"))))
          (is (.isFile (io/file builder2 "previous-candidate/start.sh")))
          (doseq [path ["gate-evidence/detail" "previous-candidate/start.sh"]] (is (not (.canWrite (io/file builder2 path)))))
          (is (= "regeneration-cycle/gate-attempt-passed" (:type attempt2))))
        (testing "@item-06-071"
          (is (= "Build the fixture" (slurp instruction1)))
          (let [text (slurp instruction2)] (is (str/starts-with? text (str (slurp instruction1) "\n\n")))
            (is (str/includes? text (str builder2 "/previous-candidate"))) (is (str/includes? text (str builder2 "/gate-evidence")))))
        (testing "@item-06-053"
          (is (= [10 20 3 "ledger answered as itself"] ((juxt :boot-budget-seconds :boot-polls :answered-on-poll :identity-probe-result) attempt2)))
          (let [[cwd gate candidate evidence] (str/split (last (str/split-lines (slurp (io/file root "gate-log")))) #" ")]
            (is (= (str repo) cwd)) (is (= (:evidence-root attempt2) evidence))
            (is (= (:candidate-digest event2) (host/tree-digest (io/file candidate)))) (is (.isFile (io/file gate "probe")))))
        (testing "@item-06-061"
          (let [root-id (get-in r [:tick :tick-id])
                ticks (filter #(and (= root-id (str (:parent-tick-id %))) (= "cycle-runner/gate-attempt.v1" (get-in % [:execution-snapshot :sheet-name])))
                              (d/rows sys :sheet/tree-tick-started))
                stops (into {} (map (juxt :tick-id :event/id) (d/rows sys :sheet/tree-tick-completed)))
                rows (d/rows sys :cycle-runner/effect-recorded)]
            (is (= 2 (count ticks)))
            (is (= [["install" "self-check" "gate" "repair"] ["install" "self-check" "gate"]]
                   (mapv (fn [tick] (mapv :effect (filter #(and (neg? (compare (:event/id tick) (:event/id %)))
                                                          (neg? (compare (:event/id %) (stops (:tick-id tick))))) rows))) ticks)))))))))
(deftest stdio-gate
  (with-case
    (fn [{:keys [p sys root repo env] :as c}]
      (let [src (prepare-builder c)
            store-check (write-program root "storecheck" (str "env > '" root "/store-env'; echo \"$1\" > '" root "/store-argument'; test -f \"$1\""))
            _ (swap! env dissoc "LOYLY_GATE_CMD")
            _ (swap! env assoc "LOYLY_STORE_CHECK_CMD" store-check)
            _ (patch-config c "APP_SEAM=stdio\n")
            probe (str "env > '" root "/probe-env'; echo \"$@\" > '" root "/probe-args'; touch \"$GATE_STORE\"; echo 'answered as the context'")
            commit-id (commit-probe c "probe" probe)
            _ (write-program src "test.sh" (str "env > '" root "/suite-env'; test -f plan/01-fixture/spec.feature; touch impl/suite-write"))
            r (start c (assoc p :commit commit-id))
            gate (first-event r "gate")]
        (testing "@item-06-039 @item-06-044"
          (is (= [30 1 1 "answered as the context"] ((juxt :boot-budget-seconds :boot-polls :answered-on-poll :identity-probe-result) gate)))
          (is (= "passed" (:status r))) (is (nil? (effect r "gate-refusal")))
          (is (.exists (io/file (@env "LOYLY_RUNS_DIR") "R1/gate-1/impl/suite-write")))
          (is (= (:candidate-digest (first-event r "install")) (:candidate-digest (first-event r "regenerate")))))
        (testing "@item-06-040"
          (is (= (str (@env "LOYLY_RUNS_DIR") "/R1/gate-1/impl/start.sh 30\n") (slurp (io/file root "probe-args"))))
          (let [gate-store (str/trim (slurp (io/file root "store-argument")))]
            (is (str/starts-with? gate-store (str (@env "LOYLY_RUNS_DIR") "/R1/evidence-1-1/")))
            (is (str/includes? (slurp (io/file root "probe-env")) (str "GATE_STORE=" gate-store))))
          (doseq [path ["probe-env" "store-env" "suite-env"]] (is (not (re-find #"(?m)^LOYLY_" (slurp (io/file root path)))))))
        (testing "@item-06-041"
          (doseq [path ["probe-args" "store-argument"]] (.delete (io/file root path)))
          (write-program src "test.sh" (str "env > '" root "/suite-env'; exit 1"))
          (let [r (start c (assoc (new-id p "red") :commit commit-id))]
            (is (= "suite-red" (:reason (first-event r "gate"))))
            (is (not (re-find #"(?m)^(?:GATE_STORE|LOYLY_)" (slurp (io/file root "suite-env")))))
            (doseq [path ["probe-args" "store-argument"]] (is (not (.exists (io/file root path)))))))
        (write-program src "test.sh" "exit 0")
        (testing "@item-06-042 @item-06-046"
          (let [commit-id (commit-probe c "probe" "echo 'no response within the 30s boot budget'; exit 1")
                rs (mapv (fn [arg] (swap! env assoc "LOYLY_STORE_CHECK_CMD" (str store-check " " arg))
                           (start c (assoc (new-id p arg) :commit commit-id))) ["one" "two"])]
            (doseq [r rs]
              (is (= "identity-probe-failed" (:reason (first-event r "gate"))))
              (is (str/includes? (:detail (effect r "gate")) "no response within the 30s boot budget")))
            (is (not= (:harness-digest (first-event (first rs) "self-check")) (:harness-digest (first-event (second rs) "self-check"))))
            (is (not (.exists (io/file root "store-argument"))))))
        (testing "@item-06-043"
          (swap! env assoc "LOYLY_STORE_CHECK_CMD" store-check)
          (spit store-check "#!/bin/sh\necho 'FAIL event 7 outside the workflow'; exit 1\n")
          (let [r (start c (assoc (new-id p "store-red") :commit commit-id))]
            (is (= "identity-probe-failed" (:reason (first-event r "gate"))))
            (is (str/includes? (:detail (effect r "gate")) "FAIL event 7 outside the workflow"))
            (is (= "gate-attempts-exhausted" (get-in r [:cycle :failure :reason])))))
        (testing "@item-06-045"
          (swap! env assoc "LOYLY_STORE_CHECK_CMD" (str root "/missing"))
          (let [r (start c (assoc (new-id p "missing") :commit commit-id))]
            (is (= ["gate" "effect-could-not-run"] ((juxt :effect :reason) (:halt r)))) (is (nil? (effect r "gate")))))))))
(defn free-port [] (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))
(defn http-fixture [c src marker]
  (let [port (free-port)]
    (swap! (:env c) dissoc "LOYLY_GATE_CMD")
    (patch-config c (str "PORT=" port "\nAPP_MARKER=sauna\nAPP_PROBE=harness/probe.sh\n"))
    (sp/file! src "service.py"
      (str "from http.server import BaseHTTPRequestHandler, HTTPServer\nimport sys\n"
           "class Handler(BaseHTTPRequestHandler):\n    calls=0\n    def do_GET(self):\n"
           "        Handler.calls+=1\n        self.send_response(503 if Handler.calls<=3 else 200)\n"
           "        self.end_headers()\n        self.wfile.write(b'" marker "')\n"
           "HTTPServer(('127.0.0.1',int(sys.argv[1])),Handler).serve_forever()\n") false)
    (write-program src "start.sh" "exec python3 impl/service.py \"$1\"")
    (sp/file! (:repo c) "harness/config.sh" "MARKER=sauna\n" false)
    (let [commit (commit-probe c "harness/probe.sh" (str "set -eu\n. \"$(dirname \"$0\")/config.sh\"\necho \"$1\" > '" (:root c) "/url'\n"
                                                          "curl -fsS \"$1\" | grep -qi \"$MARKER\"; echo 'marker seen'"))]
      [commit port])))
(deftest http-gate-and-builder
  (with-case (fn [{:keys [repo root env p sys] :as c}]
    (let [src (prepare-builder c) [cut port] (http-fixture c src "sauna")
          target (host/checked repo ["git" "rev-parse" "HEAD"])
          _ (sp/file! repo "harness/cycles/old" "old" false) _ (host/checked repo ["git" "add" "-A"])
          _ (host/checked repo ["git" "update-index" "--add" "--cacheinfo" (str "160000," target ",provided-runtime")])
          _ (host/checked repo ["git" "-c" "user.name=Fixture" "-c" "user.email=fixture@example.invalid" "commit" "-qm" "Runtime pointer"])
          cut (host/checked repo ["git" "rev-parse" "HEAD"])
          _ (patch-config c "FENCED_PATHS=\"impl harness/cycles\"\n")
          _ (write-program src "test.sh" "set -eu; mkdir impl/work; rmdir impl/work")
          b (@env "LOYLY_BUILDER_HOST") original (slurp b)
          _ (spit b (str "#!/bin/sh\nset -eu\ncd \"$1\"; test ! -e impl; test -f plan/01-fixture/spec.feature; test -f harness/config.sh\n"
                         "git rev-list --count --all > '" root "/commits'; git rev-list --objects --all > '" root "/objects'; git ls-tree HEAD provided-runtime > '" root "/gitlink'\n"
                         "touch '" root "/builder-ready'; sleep 1\n" original "\n(sleep 2; echo 'exit 1' >> \"$1/impl/test.sh\") </dev/null >/dev/null 2>&1 &\nexit 1\n"))
          run-f (future (start c (assoc p :commit cut)))
          _ (loop [n 0] (when (and (< n 200) (not (.isFile (io/file root "builder-ready")))) (Thread/sleep 50) (recur (inc n))))
          live (get-in (sp/call sys "run-progress" {:run-id (:run-id p)}) [:result :run])]
      (testing "@item-06-003 @item-06-073"
        (is (= "running" (:status live)))
        (is (= "run-in-progress" (rpc-refusal c (new-id p "other"))))
        (is (nil? (:result (sp/call sys "run-progress" {:run-id "other"}))))
        (is (uuid? (parse-uuid (get-in live [:tick :tick-id]))))
        (is (some #(= ["regenerate" "running"] ((juxt :name :status) %)) (get-in live [:tick :nodes])))
        (is (= ["fence" "render"] (filterv #{"fence" "render"} (map :name (get-in live [:tick :nodes]))))))
      (let [r @run-f gate (first-event r "gate")]
        (testing "@item-06-013"
          (is (= "1\n" (slurp (io/file root "commits"))))
          (is (not (re-find #"(?m) (impl|harness/cycles)(/|$)" (slurp (io/file root "objects")))))
          (is (str/includes? (slurp (io/file root "gitlink")) (str "160000 commit " target))))
        (testing "@item-06-015 @item-06-022 @item-06-036 @item-06-037 @item-06-052"
          (is (= "passed" (:status r)) (str (:halt r) (:cycle r)))
          (is (= "regeneration-cycle/gate-attempt-passed" (:type gate)))
          (is (= "marker seen" (:identity-probe-result gate)))
          (is (= (str "http://127.0.0.1:" port "\n") (slurp (io/file root "url"))))
          (is (= (:candidate-digest (first-event r "install")) (:candidate-digest (first-event r "regenerate"))))
          (is (not (str/includes? (slurp (io/file (@env "LOYLY_RUNS_DIR") "R1/gate-1/impl/test.sh")) "exit 1"))))
        (testing "@item-06-060"
          (is (= [10 20] ((juxt :boot-budget-seconds :boot-polls) gate)))
          (is (<= 4 (:answered-on-poll gate) 20))
          (is (= (- 20 (:answered-on-poll gate)) (get-in r [:cycle :attempts 0 :boot-margin-polls]))))
        (testing "@item-06-004 @item-06-035"
          (is (= r (get-in (sp/call sys "run-progress" {:run-id (:run-id p)}) [:result :run])))
          (is (= "cycle-already-run" (rpc-refusal c (assoc p :run-id "second"))))
          (is (nil? (:result (sp/call sys "run-progress" {:run-id "second"}))))))
      (spit b original)
      (testing "@item-06-020" (.delete (io/file src "start.sh"))
        (is (= "contract-missing" (:reason (first-event (start c (assoc (new-id p "contract") :commit cut)) "gate")))))
      (write-program src "start.sh" (str "touch '" root "/launched'; exec python3 impl/service.py \"$1\""))
      (write-program src "test.sh" "exit 1")
      (testing "@item-06-019 @item-06-024"
        (let [r (start c (assoc-in (assoc (new-id p "red") :commit cut) [:budget :gate-attempt-limit] 3))]
          (is (= ["suite-red" "suite-red" "suite-red"] (mapv #(get-in % [:events 0 :reason]) (filter #(= "gate" (:effect %)) (:effect-results r)))))
          (is (= 3 (:attempts r))) (is (= "gate-attempts-exhausted" (get-in r [:cycle :failure :reason])))
          (is (not (.exists (io/file root "launched")))) (is (nil? (effect r "file-verdicts"))) (is (empty? (:scores r)))))
      (testing "@item-06-021" (write-program src "test.sh" "exit 0")
        (spit (io/file src "service.py") (str/replace (slurp (io/file src "service.py")) "sauna" "elsewhere"))
        (is (= "identity-probe-failed" (:reason (first-event (start c (assoc (new-id p "marker") :commit cut)) "gate")))))
      (testing "@item-06-018"
        (let [srv (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" port) 0)]
          (.createContext srv "/" (reify com.sun.net.httpserver.HttpHandler (handle [_ ex] (.sendResponseHeaders ex 200 -1) (.close ex))))
          (.start srv)
          (try (.delete (io/file root "launched"))
               (let [r (start c (assoc (new-id p "busy") :commit cut))]
                 (is (= 3 (count (filter #(= "gate-refusal" (:effect %)) (:effect-results r)))))
                 (is (= "infrastructure" (get-in r [:cycle :failure :reason])))
                 (is (not (.exists (io/file root "launched")))))
               (finally (.stop srv 0)))))))))
(deftest cut-selection-and-history
  (with-case (fn [{:keys [p repo root env sys] :as c}]
    (let [old (io/file repo "plan/01-fixture") target (io/file repo "spec/a")]
      (.mkdirs (.getParentFile target))
      (host/checked repo ["mv" old target])
      (host/checked repo ["ln" "-s" "../spec/a" (io/file repo "plan/01-a")])
      (spit (io/file target "spec.feature") "  @item-01-002\n  Scenario: Other\n    Then also observed\n" :append true)
      (sp/file! repo "plan/02-b/spec.feature" "Feature: B\n  @item-02-001\n  Scenario: Selected\n    Then selected\n" false)
      (let [cut (commit repo) _ (patch-config c "PLAN_SLICES=01\n")
            r (start c (assoc p :commit cut))
            rows (:items (first-event r "checklist"))]
        (testing "@item-06-047"
          (is (= "passed" (:status r)) (str (:halt r))))
        (is (= ["01-001" "01-002"] (mapv :item-id rows)))
        (is (every? #(str/starts-with? (:source %) "plan/01-a/") rows))
        (testing "@item-06-051"
          (let [second-run (start c (assoc (new-id p "second") :commit cut))
                board (:scoreboard (query sys "measurement/scoreboard" {:cycle-id "second"}))]
            (is (= "passed" (:status second-run))) (is (empty? (:events (effect second-run "stamp"))))
            (is (= (:cycle-id p) (:previous-cycle-id board)))))
        (patch-config c "PLAN_SLICES=02\n")
        (testing "@item-06-048"
          (let [r (start c (assoc (new-id p "selected") :commit cut :grader-digests ["other"]))]
            (is (= ["02"] (mapv :slice (:events (effect r "stamp")))))
            (is (= ["02-001"] (mapv :item-id (:items (first-event r "checklist"))))))))))))
(deftest gate-command-digest
  (with-case (fn [{:keys [p env root repo] :as c}]
    (let [src (prepare-builder c) [cut port] (http-fixture c src "sauna")
          _ (spit (io/file src "service.py") (str/replace (slurp (io/file src "service.py")) "Handler.calls<=3" "False"))
          cmd (fn [name] (write-program root name "echo 'BOOT 1 10'; echo 'ANSWERED 1'; echo passed"))
          cfg (slurp (@env "LOYLY_HARNESS_CONFIG")) check (@env "LOYLY_SELF_CHECK_CMD") probe (slurp (io/file repo "harness/probe.sh"))
          runs (mapv (fn [id command]
                       (if command (swap! env assoc "LOYLY_GATE_CMD" command) (swap! env dissoc "LOYLY_GATE_CMD"))
                       (start c (assoc (new-id p id) :commit cut)))
                     ["local" "a" "b"] [nil (cmd "gate-a") (cmd "gate-b")])]
      (testing "@item-06-057"
        (is (every? #(= "passed" (:status %)) runs))
        (let [digests (mapv #(:harness-digest (first-event % "self-check")) runs)]
          (is (= 3 (count (set digests))))
          (is (= (d/digest (str cfg check probe)) (first digests)))))))))
(deftest researcher-and-unavailable-models
  (let [mode (atom :observe) requests (atom [])
        transform (fn [{:keys [messages marker] :as packet}]
                    (swap! requests conj messages)
                    (let [research? (str/includes? marker "## code ##")
                          code (case @mode
                                 :observe (if (str/includes? messages "Checklist item 01-001")
                                            "(let [data (shell {:command \"uname -s; pwd; ls -a; ls ..; test -x impl/start.sh && echo CANDIDATE-SEEN\"})] (final! {:evidence (str \"E-ONE \" (:stdout data))}))"
                                            "(final! {:evidence \"E-TWO\"})")
                                 :partial "(if (= \"01-002\" (:item-id (get-input :item))) (throw (ex-info \"fixture researcher failed\" {})) (final! {:evidence \"observed\"}))"
                                 nil)]
                      (cond (and research? code) (assoc packet :marker (str "[[ ## reasoning ## ]]\nInspect the seam.\n[[ ## code ## ]]\n" code))
                            (and research? (= :no-scores @mode)) (assoc packet :marker "")
                            (and (= :no-render @mode) (str/includes? marker "builder-instruction")) (assoc packet :marker "")
                            :else packet)))]
    (with-case transform
      (fn [{:keys [p repo root env] :as c}]
        (sp/file! repo "plan/01-fixture/event-model/fixture.edn" "{:fixture/event-observed true}" false)
        (spit (io/file repo "plan/01-fixture/spec.feature") "  @item-01-002\n  Scenario: A second fixture answers\n    Then the second response is observed\n" :append true)
        (let [p (assoc p :commit (commit repo)) r (start c p)
              r1 (filter #(and (str/includes? % "Checklist item 01-001") (str/includes? % "[[ ## code ## ]]")) @requests)
              j1 (some #(when (and (str/includes? % "01-001") (str/includes? % "[[ ## level ## ]]")) %) @requests)
              two (filter #(and (str/includes? % "01-002") (or (str/includes? % "[[ ## level ## ]]") (str/includes? % "Checklist item 01-002"))) @requests)]
          (testing "@item-06-065 @item-06-066"
            (is (= "passed" (:status r)))
            (is (seq r1)) (is (seq two))
            (doseq [snippet ["Scenario: A fixture answers" "Run impl/start.sh" ":fixture/event-observed"]] (is (str/includes? (or (first r1) "") snippet)))
            (doseq [snippet ["E-ONE" "Linux" "grade-01-001" "CANDIDATE-SEEN"]] (is (str/includes? (or j1 "") snippet)))
            (is (not (str/includes? (or j1 "") "builder-1")))
            (is (not-any? #(str/includes? % "E-ONE") two)))
          (reset! mode :partial)
          (testing "@item-06-026"
            (let [r (start c (new-id p "partial")) row (first-event r "file-score")]
              (is (= "passed" (:status r))) (is (= [1 2] ((juxt :passes :total) row)))
              (is (= ["01-001"] (mapv :item-id (:verdicts (first-event r "file-verdicts")))))
              (is (= ["01-002" "fail" "unreached"] ((juxt :item-id :verdict :reason) (second (:items row)))))))
          (reset! mode :no-scores)
          (testing "@item-06-069"
            (let [r (start c (new-id p "none"))]
              (is (= "zero-verdicts" (:refusal (effect r "file-verdicts"))))
              (is (= "recorded" (:outcome (effect r "fail-score-stage"))))
              (is (= "score-stage-failed" (get-in r [:cycle :failure :reason])))
              (is (nil? (effect r "file-score")))))
          (reset! mode :no-render)
          (testing "@item-06-072"
            (let [r (start c (new-id p "no-render"))]
              (is (= ["render" "effect-could-not-run"] ((juxt :effect :reason) (:halt r))))
              (is (not-any? #{"regenerate" "fail-cycle"} (names r))) (is (nil? (:cycle r)))
              (is (not (.exists (io/file (@env "LOYLY_RUNS_DIR") "no-render/builder-1")))))))))))
