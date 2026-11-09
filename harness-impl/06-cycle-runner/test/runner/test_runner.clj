(ns runner.test-runner
  (:require [clojure.test :refer :all] [runner.main :as app] [runner.runtime :as rt] [runner.util :as u]
            [runner.model :as model] [runner.fixtures :as f] [runner.contexts :as c]
            [clojure.string :as str] [clojure.java.io :as io]
            [ai.obney.grain.read-model-processor-v2.interface :as rm]
            [ai.obney.grain.event-store-v3.interface :as es]))
(def ^:dynamic *ctx*)
(def ^:dynamic *fixture*)
(defn rpc [method params] (app/request *ctx* (u/decode (u/encode {:jsonrpc "2.0" :id 42 :method (str "cycle-runner/" method) :params params}))))
(defn start-run ([] (start-run {})) ([changes] (rpc "start-run" (merge (f/params *fixture*) changes))))
(defn progress [] (get-in (rpc "run-progress" {:run-id "R-1"}) [:result :run]))
(defn effects [name] (filterv #(= name (:effect %)) (:effect-results (progress))))
(defn run-case [opts test-fn]
  (let [fixture (f/fixture! opts)]
    (rm/l1-clear!)
    (reset! app/sessions {})
    (let [ctx (rt/start! (u/path (:dir fixture) "runner.sqlite") app/processor)]
      (try (binding [*ctx* ctx *fixture* fixture] (with-redefs [app/read-env (fn [] (:env fixture))] (test-fn)))
           (finally (doseq [s (vals @app/sessions)] (c/stop! (:clients s))) (Thread/sleep 100) (rt/stop! ctx) (f/close! fixture))))))
(deftest ^{:item "@item-06-027"} item-06-027
  (testing "@item-06-027" (run-case {} #(is (= "no-run" (get-in (rpc "hand-in-verdicts" {:run-id "absent" :verdicts-path "absent" :grader-digest "r"}) [:error :data :refusal]))))))
(declare http-start)
(deftest ^{:item "@item-06-001"} item-06-001
  (testing "@item-06-001"
    (run-case {:stdio false :start http-start} #(do
                   (is (nil? (:error (start-run))))
                   (when-not (= "awaiting-verdicts" (:status (progress))) (println "PROGRESS" (progress)))
                   (is (= "awaiting-verdicts" (:status (progress))))
                   (is (= ["stamp" "pin" "checklist" "self-check" "fence" "regenerate" "install" "self-check" "gate"] (mapv :effect (:effect-results (progress))))) ))))
(defn -main [& _]
  (let [r (run-tests 'runner.test-runner)] (shutdown-agents) (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))

(defn fail-at [stage reason] (is (= ["failed" stage reason] ((juxt :status #(get-in % [:cycle :failure :stage]) #(get-in % [:cycle :failure :reason])) (progress)))))
(defn calls [] (:calls @(get-in *fixture* [:server :world])))
(defn values [effect type] (first (filter #(= type (:type %)) (mapcat :events (effects effect)))))
(defn verdicts [entries]
  (rpc "hand-in-verdicts" {:run-id "R-1" :grader-digest "r-1" :verdicts-path (u/json-file (u/path (:dir *fixture*) "verdicts.json") entries)}))
(defn two-verdicts [] [{:id "01-001" :verdict "pass" :evidence "booked"} {:id "01-002" :verdict "fail" :evidence "judged by the main session"}])
(defn set-config! [text] (spit (:config *fixture*) (str (slurp (:config *fixture*)) "\n" text "\n")))
(defn re-commit! [] (u/git! (:repo *fixture*) "add" ".") (u/git! (:repo *fixture*) "-c" "user.name=Fixture" "-c" "user.email=fixture@local" "commit" "-qm" "another fixture") (u/git! (:repo *fixture*) "rev-parse" "HEAD"))
(defn budget-one [] (assoc (:budget (f/params *fixture*)) :gate-attempt-limit 1))
(defn append-mode! [k v] (swap! (get-in *fixture* [:server :world]) assoc-in [:script k] v))
(defn builder! [body] (f/script! (get-in *fixture* [:env "LOYLY_BUILDER_CMD"]) body))
(defn repair-builder! []
  (builder! (str "n=$(cat '" (:dir *fixture*) "/count' 2>/dev/null || echo 0)\nn=$((n+1))\necho $n > '" (:dir *fixture*) "/count'\n"
                 "echo begin >> '" (:dir *fixture*) "/launches'\nprintf '%s\\n' \"$@\" >> '" (:dir *fixture*) "/builder-args'\n"
                 "cp -a '" (:candidate *fixture*) "/.' \"$1/out/\"\n"
                 "if [ $n -eq 1 ]; then printf '#!/bin/sh\\nexit 1\\n' > \"$1/out/test.sh\"; fi\n"
                 "if [ $n -gt 1 ]; then test -f \"$1/previous-candidate/test.sh\"; test -f \"$1/gate-evidence/gate.txt\"; ls -ld \"$1/previous-candidate/test.sh\" | awk '{print $1}' > '" (:dir *fixture*) "/previous-mode'; fi\n"
                 "echo end >> '" (:dir *fixture*) "/launches'")))
(def http-start "exec bb -e '(require (quote [org.httpkit.server :as http])) (http/run-server (fn [_] {:status 200 :body \"sauna\"}) {:port (Integer/parseInt (first *command-line-args*))}) @(promise)' \"$1\"")
(defmacro item-test [n opts & body]
  `(deftest ~(with-meta (symbol (str "item-06-" n)) {:item (str "@item-06-" n)})
     (testing ~(str "@item-06-" n) (println ~(str "Testing @item-06-" n)) (run-case ~opts (fn [] ~@body)))))
(item-test "002" {:config "FENCED_PATHS=\"impl absent\""}
  (start-run) (is (= "failed" (:status (progress))))
  (is (= "run-already-started" (get-in (start-run {:cycle-id "C-2"}) [:error :data :refusal])))
  (is (= 1 (count (get-in (rpc "run-progress" {}) [:result :runs])))))
(item-test "003" {} (start-run)
  (is (= "run-in-progress" (get-in (start-run {:run-id "R-2" :cycle-id "C-2"}) [:error :data :refusal])))
  (is (nil? (get-in (rpc "run-progress" {:run-id "R-2"}) [:result :run]))))
(item-test "004" {} (start-run) (verdicts (two-verdicts))
  (is (= "passed" (:status (progress))))
  (is (= "cycle-already-run" (get-in (start-run {:run-id "R-2"}) [:error :data :refusal]))))
(item-test "005" {}
  (is (= "not-a-commit" (get-in (start-run {:commit (apply str (repeat 40 "f"))}) [:error :data :refusal])))
  (is (empty? (calls))) (is (nil? (progress))))
(item-test "006" {:exit-contexts ["measurement"]}
  (is (= ["measurement"] (get-in (start-run) [:error :data :contexts])))
  (is (nil? (progress))))
(item-test "007" {} (start-run)
  (let [rows (:relayed-handoffs (progress))]
    (is (= [["specification/scenario-stamped" 1 "measurement"] ["specification/scenario-stamped" 2 "measurement"]
            ["cycle-preparation/source-cut-pinned" 1 "regeneration-cycle"] ["cycle-preparation/source-cut-pinned" 1 "measurement"]]
           (mapv (juxt :handoff-type :position :consumer) (take 4 rows))))
    (is (= 1 (count (filter #(= "regeneration-cycle/gate-attempt-passed" (:handoff-type %)) rows))))
    (is (every? #(= "accepted" (:outcome %)) rows))
    (is (= (count rows) (count (set (map (juxt :producer :position :consumer) rows)))))))
(item-test "008" {:spec "Feature: empty\n"} (start-run)
  (is (= "empty-checklist" (:refusal (first (effects "checklist")))))
  (is (= {:effect "checklist" :reason "refused-before-the-cycle" :detail "empty-checklist"} (:halt (progress))))
  (is (empty? (effects "fence"))))
(item-test "009" {:spec "Feature: fixture\n  @item-01-001\n  Scenario: first\n    Given first\n  Scenario: missing tag\n    Given missing\n"} (start-run)
  (is (= "stamp" (get-in (progress) [:halt :effect]))) (is (= "refused-before-the-cycle" (get-in (progress) [:halt :reason])))
  (is (= 1 (count (effects "stamp")))) (is (empty? (effects "pin"))))
(item-test "010" {:self-check "echo golden checklist differs\nexit 1"} (start-run)
  (fail-at "fence" "harness-self-check-failed")
  (is (= "golden checklist differs" (get-in (progress) [:cycle :failure :detail]))) (is (empty? (effects "fence"))))
(item-test "011" {:self-check "echo SKIP bb-tools"} (start-run)
  (is (= ["bb-tools"] (:skipped-checks (values "self-check" "regeneration-cycle/harness-self-check-passed")))))
(item-test "012" {:config "FENCED_PATHS=\"impl absent\""} (start-run)
  (is (= "fenced-path-matched-nothing" (:refusal (first (effects "fence"))))) (fail-at "fence" "fence-refused")
  (is (= 1 (count (effects "fail-cycle")))) (is (empty? (effects "regenerate"))))
(item-test "013" {} (start-run)
  (is (= "1" (str/trim (slurp (u/path (:dir *fixture*) "builder-commits")))))
  (let [room (u/path (:dir *fixture*) "runs/R-1/builder-1")]
    (is (.isFile (io/file room "plan/01-a/spec.feature")))
    (is (not (.exists (io/file room "impl")))) (is (not (.exists (io/file room "harness/cycles"))))
    (is (not (re-find #"(?: impl| harness/cycles)(?:/|$)" (u/git! room "rev-list" "--objects" "--all"))))))
(item-test "014" {} (repair-builder!) (start-run)
  (is (= ["begin" "end" "begin" "end"] (str/split-lines (slurp (u/path (:dir *fixture*) "launches")))))
  (is (= "awaiting-verdicts" (:status (progress)))))
(item-test "015" {:builder-exit 1} (start-run)
  (is (= "awaiting-verdicts" (:status (progress))))
  (is (str/starts-with? (:candidate-digest (values "regenerate" "regeneration-cycle/candidate-regenerated")) "sha256:")))
(item-test "016" {:builder "exit 0"} (start-run) (fail-at "regenerate" "candidate-not-regenerated")
  (is (empty? (effects "regenerate"))) (is (= 1 (count (effects "fail-cycle")))))
(item-test "017" {:builder "sleep 30"}
  (start-run {:budget (assoc-in (:budget (f/params *fixture*)) [:stage-timeouts-seconds :regenerate] 2)}) (fail-at "regenerate" "budget"))
(item-test "018" {:stdio false :start http-start}
  (let [p (u/launch ["sh" "-c" http-start "fixture-server" "18977"] (:repo *fixture*) {} false)]
    (try (Thread/sleep 250) (start-run)
         (is (= 3 (count (effects "gate")))) (is (= 3 (count (effects "gate-refusal")))) (fail-at "gate" "infrastructure")
         (finally (u/destroy p)))))
(item-test "019" {:stdio false :suite "exit 1" :start "echo launched > start-ran"}
  (start-run {:budget (budget-one)}) (fail-at "gate" "gate-attempts-exhausted")
  (is (= "suite-red" (:reason (values "gate" "regeneration-cycle/gate-attempt-failed")))))
(item-test "020" {:no-start true} (start-run {:budget (budget-one)})
  (is (= "contract-missing" (:reason (values "gate" "regeneration-cycle/gate-attempt-failed")))))
(item-test "021" {:stdio false :start (str/replace http-start "sauna" "other-page")}
  (start-run {:budget (budget-one)}) (is (= "identity-probe-failed" (:reason (values "gate" "regeneration-cycle/gate-attempt-failed")))))
(item-test "022" {}
  (builder! (str "cp -a '" (:candidate *fixture*) "/.' \"$1/out/\"\n"
                 "(sleep 0.25; printf '#!/bin/sh\\nexit 1\\n' > \"$1/out/test.sh\") >/dev/null 2>&1 &\nexit 0"))
  (start-run)
  (is (= "awaiting-verdicts" (:status (progress))))
  (let [regen (values "regenerate" "regeneration-cycle/candidate-regenerated") install (values "install" "regeneration-cycle/candidate-installed-in-gate-room")]
    (is (= (:candidate-digest regen) (:candidate-digest install)))
    (u/put (u/path (:dir *fixture*) "runs/R-1/builder-1/out/test.sh") "red")
    (is (not= "red" (slurp (u/path (:gate-room install) "out/test.sh"))))))
(item-test "023" {} (repair-builder!) (start-run)
  (is (= 1 (count (effects "repair"))))
  (is (re-find #"Repair using the previous candidate" (slurp (u/path (:dir *fixture*) "runs/R-1/instruction-2.md"))))
  (is (re-matches #"^-[r-]-[x-][r-]-[x-][r-]-[x-][+@.]?$"
                  (str/trim (slurp (u/path (:dir *fixture*) "previous-mode"))))))
(item-test "024" {:suite "exit 1"} (start-run) (fail-at "gate" "gate-attempts-exhausted")
  (is (= 3 (count (filter #{"begin"} (str/split-lines (slurp (u/path (:dir *fixture*) "launches"))))))))
(item-test "025" {} (start-run) (is (nil? (:error (verdicts (two-verdicts)))))
  (is (= "passed" (:status (progress))))
  (let [measurement (values "file-score" "measurement/measurement-filed")]
    (is (= [1 2] [(:passes measurement) (:total measurement)]))
    (is (= (mapv :evidence (two-verdicts)) (mapv :evidence (:items measurement))))))
(item-test "026" {} (start-run) (verdicts [{:id "01-001" :verdict "pass"}])
  (is (= "passed" (:status (progress))))
  (is (= "unreached" (get-in (values "file-score" "measurement/measurement-filed") [:items 1 :reason]))))
(item-test "028" {:suite "exit 1"} (start-run {:budget (budget-one)})
  (is (= "gate-not-passed" (get-in (verdicts (two-verdicts)) [:error :data :refusal]))))
(item-test "029" {} (start-run) (verdicts [{:id "09-001" :verdict "pass"}])
  (is (= "verdicts-already-handed-in" (get-in (verdicts (two-verdicts)) [:error :data :refusal])))
  (is (= 1 (count (effects "regenerate")))) (is (empty? (effects "repair"))))
(item-test "030" {} (start-run)
  (is (= "grader-not-approved" (get-in (rpc "hand-in-verdicts" {:run-id "R-1" :grader-digest "r-2" :verdicts-path "absent"}) [:error :data :refusal])))
  (is (= "awaiting-verdicts" (:status (progress)))))
(item-test "031" {} (start-run)
  (is (= "verdicts-not-readable" (get-in (rpc "hand-in-verdicts" {:run-id "R-1" :grader-digest "r-1" :verdicts-path (u/put (u/path (:dir *fixture*) "blank") "")}) [:error :data :refusal])))
  (is (empty? (effects "file-verdicts"))))
(item-test "032" {} (start-run) (verdicts [{:id "01-001" :verdict "pass"} {:id "09-001" :verdict "pass"}])
  (is (= "unknown-item" (:refusal (first (effects "file-verdicts"))))) (fail-at "score" "score-stage-failed")
  (is (= 1 (count (effects "fail-score-stage")))))
(item-test "033" {:exit-at "measurement/derive-checklist"} (start-run)
  (is (= "context-did-not-respond" (get-in (progress) [:halt :reason]))) (is (= "checklist" (get-in (progress) [:halt :effect])))
  (is (empty? (effects "checklist"))))
(item-test "034" {:config "FENCED_PATHS=\"impl absent\""} (start-run)
  (let [before (progress)] (Thread/sleep 5000) (is (= before (progress))))
  (append-mode! :exit-at "measurement/derive-checklist")
  (start-run {:run-id "R-2" :cycle-id "C-2" :cut-id "K-2"})
  (let [before (rpc "run-progress" {:run-id "R-2"})] (Thread/sleep 5000) (is (= before (rpc "run-progress" {:run-id "R-2"})))))
(item-test "035" {} (start-run)
  (let [before (rpc "run-progress" {}) n (count (into [] (es/read (:event-store *ctx*) {:tenant-id (:tenant-id *ctx*)}))) ]
    (is (= before (rpc "run-progress" {})))
    (is (= n (count (into [] (es/read (:event-store *ctx*) {:tenant-id (:tenant-id *ctx*)})))))))
(item-test "036" {:stdio false :start http-start :probe "case \"$1\" in http://127.0.0.1:*) curl -sf \"$1\" | grep -qi sauna;; *) exit 1;; esac"}
  (start-run) (is (= "awaiting-verdicts" (:status (progress)))))
(item-test "037" {:suite "mkdir own-test-temp\nrmdir own-test-temp"} (start-run)
  (is (= "awaiting-verdicts" (:status (progress)))))
(item-test "038" {:self-check "echo 'SKIP  the oracle cannot judge this tree: a tool it grades is absent.'"} (start-run)
  (is (= ["the oracle cannot judge this tree: a tool it grades is absent."] (:skipped-checks (values "self-check" "regeneration-cycle/harness-self-check-passed")))))
(item-test "039" {} (start-run)
  (is (= [30 1 1 "answered as the context"] ((juxt :boot-budget-seconds :boot-polls :answered-on-poll :identity-probe-result) (values "gate" "regeneration-cycle/gate-attempt-passed")))))
(item-test "040" {:probe "printf '%s\\n' \"$@\" > args\nenv > probe-env\nprintf '%s' \"$GATE_STORE\" > probe-store" :store-check "printf '%s\\n' \"$@\" > checked-store"}
  (start-run)
  (let [room (u/path (:dir *fixture*) "runs/R-1/gate-1") args (str/split-lines (slurp (u/path room "args")))
        store (slurp (u/path room "probe-store"))]
    (is (= [(u/path room "out/start.sh") "30"] args))
    (is (not (re-find #"(?m)^LOYLY_" (slurp (u/path room "probe-env")))))
    (is (= store (str/trim (slurp (u/path (:repo *fixture*) "checked-store")))))
    (is (not (str/starts-with? store room)))))
(item-test "041" {:suite "env > suite-env\nexit 1" :probe "touch probe-ran" :store-check "touch store-check-ran"}
  (start-run {:budget (budget-one)})
  (let [room (u/path (:dir *fixture*) "runs/R-1/gate-1")]
    (is (not (re-find #"(?m)^(?:LOYLY_|GATE_STORE=)" (slurp (u/path room "suite-env")))))
    (is (not (.exists (io/file room "probe-ran"))))
    (is (not (.exists (io/file (:repo *fixture*) "store-check-ran"))))))
(item-test "042" {:probe "echo no response within the 30s boot budget\nexit 1" :store-check "touch store-check-ran"}
  (start-run {:budget (budget-one)})
  (is (= "identity-probe-failed" (:reason (values "gate" "regeneration-cycle/gate-attempt-failed"))))
  (is (str/includes? (:detail (first (effects "gate"))) "no response within the 30s boot budget"))
  (is (not (.exists (io/file (:repo *fixture*) "store-check-ran")))))
(item-test "043" {:store-check "echo 'FAIL event 7 was not written inside a run of the ORC workflow'\nexit 1"}
  (start-run {:budget (budget-one)})
  (is (= "identity-probe-failed" (:reason (values "gate" "regeneration-cycle/gate-attempt-failed"))))
  (is (str/includes? (:detail (first (effects "gate"))) "FAIL event 7")))
(item-test "044" {:suite "test -f plan/01-a/spec.feature"} (start-run)
  (is (= "awaiting-verdicts" (:status (progress)))))
(item-test "045" {}
  (with-redefs [app/read-env (fn [] (assoc (:env *fixture*) "LOYLY_STORE_CHECK_CMD" "no-program-like-this"))] (start-run))
  (is (= ["halted" "gate" "effect-could-not-run"] ((juxt :status #(get-in % [:halt :effect]) #(get-in % [:halt :reason])) (progress))))
  (is (empty? (effects "gate"))))
(item-test "046" {:probe "exit 1"}
  (start-run {:budget (budget-one)})
  (let [first-digest (:harness-digest (values "self-check" "regeneration-cycle/harness-self-check-passed"))]
    (with-redefs [app/read-env (fn [] (assoc (:env *fixture*) "LOYLY_STORE_CHECK_CMD" (str (get-in *fixture* [:env "LOYLY_STORE_CHECK_CMD"]) " two")))]
      (start-run {:run-id "R-2" :cycle-id "C-2" :cut-id "K-2" :budget (budget-one)}))
    (let [second-run (get-in (rpc "run-progress" {:run-id "R-2"}) [:result :run])]
      (is (not= first-digest (:harness-digest (first (:events (first (filter #(= "self-check" (:effect %)) (:effect-results second-run)))))))))))
(item-test "047" {}
  (u/mkdir (u/path (:repo *fixture*) "spec"))
  (u/run ["mv" (u/path (:repo *fixture*) "plan/01-a") (u/path (:repo *fixture*) "spec/a")] nil)
  (u/run ["ln" "-s" "../spec/a" (u/path (:repo *fixture*) "plan/01-a")] nil)
  (start-run {:commit (re-commit!)})
  (is (= "awaiting-verdicts" (:status (progress))))
  (is (every? #(str/includes? (:source %) "plan/01-a/") (:items (values "checklist" "measurement/checklist-derived")))))
(item-test "048" {:config "PLAN_SLICES=02"}
  (u/put (u/path (:repo *fixture*) "plan/02-b/spec.feature") "Feature: selected\n  @item-02-001\n  Scenario: only this\n    Given selected\n")
  (start-run {:commit (re-commit!)})
  (is (= ["02"] (mapv :slice (mapcat :events (effects "stamp")))))
  (is (= ["02-001"] (mapv :item-id (:items (values "checklist" "measurement/checklist-derived"))))))
(item-test "049" {:config "BUILDER_IMAGE=builder-clojure"}
  (repair-builder!)
  (let [images (u/mkdir (u/path (:dir *fixture*) "images")) _ (u/put (u/path images "builder-clojure/toolchain") "provided")]
    (with-redefs [app/read-env (fn [] (assoc (:env *fixture*) "LOYLY_BUILDER_IMAGES" images))] (start-run))
    (is (= 6 (count (str/split-lines (slurp (u/path (:dir *fixture*) "builder-args"))))))
    (doseq [r (concat (effects "regenerate") (effects "repair"))]
      (is (str/includes? (:detail r) "builder-clojure")) (is (str/includes? (:detail r) (u/tree-digest (u/path images "builder-clojure")))))))
(item-test "050" {:config "BUILDER_IMAGE=builder-clojure"}
  (is (= "builder-image-missing" (get-in (start-run) [:error :data :refusal])))
  (is (nil? (progress))) (is (not (.exists (io/file (:dir *fixture*) "launches")))))
(item-test "051" {}
  (let [env (assoc (:env *fixture*) "LOYLY_MEASUREMENT_STORE" (u/path (:dir *fixture*) "history.sqlite"))]
    (with-redefs [app/read-env (fn [] env)]
      (start-run) (verdicts (two-verdicts))
      (start-run {:run-id "R-2" :cycle-id "C-2" :cut-id "K-2"})
      (rpc "hand-in-verdicts" {:run-id "R-2" :grader-digest "r-1" :verdicts-path (u/json-file (u/path (:dir *fixture*) "v2.json") (two-verdicts))}))
    (let [r (get-in (rpc "run-progress" {:run-id "R-2"}) [:result :run]) measurement (filter #(= "measurement" (:producer %)) (:relayed-handoffs r))]
      (is (= "passed" (:status r))) (is (= 2 (count measurement)))
      (is (= [1 2] (mapv :position measurement))))
    (let [launchers (u/read-json (get-in *fixture* [:env "LOYLY_CONTEXTS"]))
          clients (c/launch! {:measurement (:measurement launchers)} (u/path (:dir *fixture*) "history-observer") env)]
      (try (is (= "C-1" (get-in (c/call clients "measurement" "measurement/scoreboard" {:cycle-id "C-2"}) [:result :scoreboard :previous-cycle-id])))
           (finally (c/stop! clients))))))
(item-test "052" {:stdio false :start http-start :probe ". \"$(dirname \"$0\")/config.sh\"\ncurl -sf \"$1\" | grep -qi \"$APP_MARKER\""}
  (start-run) (is (= "awaiting-verdicts" (:status (progress)))))

(deftest grain-workflow-evidence
  (run-case {}
    (fn []
      (start-run) (verdicts (two-verdicts))
      (Thread/sleep 200)
      (let [events (into [] (es/read (:event-store *ctx*) {:tenant-id (:tenant-id *ctx*)}))
            starts (filter #(= :sheet/tree-tick-started (:event/type %)) events)
            ends (filter #(= :sheet/tree-tick-completed (:event/type %)) events)
            traces (filter #(= :sheet/execution-traced (:event/type %)) events)]
        (is (seq starts)) (is (= (count starts) (count ends) (count traces)))
        (is (every? #(= :success (:root-status %)) ends))
        (is (every? #(= 1 %) (vals (frequencies (map :tick-id ends)))))
        (is (some #(= :grain/todo-processor-checkpoint (:event/type %)) events))
        (doseq [e events :when (= "cycle-runner" (namespace (:event/type e)))]
          (let [body (into {} (remove (fn [[k _]] (= "event" (namespace k))) e))
                schema (get-in model/area [:events (:event/type e) :schema])]
            (is (malli.core/validate schema body))
            (is (= (set (keys body)) (set (keys (model/body e)))))))))))

(deftest actual-stdio-identity
  (let [fixture (f/fixture! {:self-check "touch self-check-ran"})
        store (u/path (:dir fixture) "identity.sqlite")
        pb (ProcessBuilder. ^java.util.List [(u/path "start.sh")])
        _ (doseq [[k v] (assoc (:env fixture) "LOYLY_GRAIN_STORE" store)] (.put (.environment pb) k v))
        _ (.redirectError pb (java.lang.ProcessBuilder$Redirect/to (io/file (u/path (:dir fixture) "identity.stderr"))))
        began (System/nanoTime) process (.start pb)
        writer (io/writer (.getOutputStream process)) reader (io/reader (.getInputStream process))
        exchange (fn [method params]
                   (.write writer (str (u/encode {:jsonrpc "2.0" :id 99 :method (str "cycle-runner/" method) :params params}) "\n")) (.flush writer)
                   (let [line (deref (future (.readLine reader)) 30000 ::timeout)]
                     (when (= line ::timeout) (u/destroy process) (throw (ex-info "identity timed out" {})))
                     (u/decode line)))]
    (try
      (is (= "not-a-commit" (get-in (exchange "start-run" (assoc (f/params fixture) :commit (apply str (repeat 40 "d")))) [:error :data :refusal])))
      (swap! (get-in fixture [:server :world]) assoc-in [:script :exit-contexts] c/names)
      (is (= (set c/names) (set (get-in (exchange "start-run" (f/params fixture)) [:error :data :contexts]))))
      (swap! (get-in fixture [:server :world]) assoc :script {:exit-at "specification/stamp-slice"})
      (is (seq (get-in (exchange "start-run" (f/params fixture)) [:result :events])))
      (let [r (get-in (exchange "run-progress" {:run-id "R-1"}) [:result :run])]
        (is (= ["halted" "stamp" "context-did-not-respond"] ((juxt :status #(get-in % [:halt :effect]) #(get-in % [:halt :reason])) r)))
        (is (empty? (:effect-results r))))
      (is (< (/ (- (System/nanoTime) began) 1e9) 30))
      (is (not (.exists (io/file (:dir fixture) "launches"))))
      (is (not (.exists (io/file (:repo fixture) "self-check-ran"))))
      (.close writer) (.waitFor process 10000 java.util.concurrent.TimeUnit/MILLISECONDS)
      (let [events (c/foreign-events store)]
        (is (some #(= :cycle-runner/run-started (:event/type %)) events))
        (is (some #(= :cycle-runner/run-halted (:event/type %)) events))
        (is (= (count (filter #(= :sheet/tree-tick-started (:event/type %)) events))
               (count (filter #(= :sheet/tree-tick-completed (:event/type %)) events))
               (count (filter #(= :sheet/execution-traced (:event/type %)) events)))))
      (finally (u/destroy process) (f/close! fixture)))))

(deftest stamped-selection-is-covered
  (let [accepted (set (re-seq #"@item-06-\d{3}" (slurp "../../.pi/plan/06-cycle-runner/spec.feature")))
        tested (set (keep (comp :item meta val) (ns-publics 'runner.test-runner)))]
    (is (= 52 (count accepted)))
    (is (= accepted tested))))

(deftest repair-builder-does-not-require-gnu-stat
  (testing "@item-06-014 @item-06-023: repair fixture also completes without GNU stat"
    (run-case {}
      (fn []
        (repair-builder!)
        (let [bin (u/mkdir (u/path (:dir *fixture*) "non-gnu-tools"))
              marker (u/path (:dir *fixture*) "stat-called")
              builder (get-in *fixture* [:env "LOYLY_BUILDER_CMD"])]
          (f/script! (u/path bin "stat") (str "touch '" marker "'\necho 'stat: illegal option -- c' >&2\nexit 1"))
          (spit builder (str "#!/bin/sh\nPATH='" bin "':\"$PATH\"\nexport PATH\n" (slurp builder)))
          (start-run)
          (is (= ["begin" "end" "begin" "end"]
                 (str/split-lines (slurp (u/path (:dir *fixture*) "launches")))))
          (is (= "awaiting-verdicts" (:status (progress))))
          (is (not (.exists (io/file marker)))))))))

(deftest command-response-waits-for-grain-checkpoints
  (testing "@item-06-035: a completed command leaves no delayed processor writes for a query"
    (run-case {}
      (fn []
        (let [append es/append
              checkpoint-entered (promise)
              release-checkpoint (promise)
              deliveries (atom 0)]
          (with-redefs [es/append
                        (fn [store args]
                          (when (and (some #(and (= :grain/todo-processor-checkpoint (:event/type %))
                                                 (= :sheet/deliver-execution-result (:processor/name %)))
                                           (:events args))
                                     (= 2 (swap! deliveries inc)))
                            (deliver checkpoint-entered true)
                            @release-checkpoint)
                          (append store args))]
            (let [response (future (start-run))]
              (try
                (is (= true (deref checkpoint-entered 30000 ::timeout)))
                (is (= ::pending (deref response 1000 ::pending))
                    "The method must not respond while its processor's checkpoint is blocked")
                (finally (deliver release-checkpoint true)))
              (is (nil? (:error (deref response 30000 {:error :timeout}))))
              (let [events #(into [] (es/read (:event-store *ctx*) {:tenant-id (:tenant-id *ctx*)}))
                    before (events)
                    reading (rpc "run-progress" {})]
                (is (= reading (rpc "run-progress" {})))
                (Thread/sleep 100)
                (is (= (mapv :event/id before) (mapv :event/id (events)))
                    "Queries must inherit no pending event writes")))))))))

(deftest verdict-response-waits-for-orc-trace
  (testing "@item-06-035: checkpoint-before is not proof that ORC's trace effect finished"
    (run-case {}
      (fn []
        (start-run)
        (let [append es/append
              trace-entered (promise)
              release-trace (promise)
              traces (atom 0)]
          (with-redefs [es/append
                        (fn [store args]
                          (when (and (some #(= :sheet/execution-traced (:event/type %)) (:events args))
                                     (= 2 (swap! traces inc)))
                            (deliver trace-entered true)
                            @release-trace)
                          (append store args))]
            (let [response (future (verdicts (two-verdicts)))]
              (try
                (is (= true (deref trace-entered 30000 ::timeout)))
                (is (= ::pending (deref response 1000 ::pending))
                    "Even after the checkpoint, a pending trace must hold the response")
                (finally (deliver release-trace true)))
              (is (nil? (:error (deref response 30000 {:error :timeout}))))
              (is (= "passed" (:status (progress))))
              (let [n (count (into [] (es/read (:event-store *ctx*) {:tenant-id (:tenant-id *ctx*)})))
                    before (rpc "run-progress" {})]
                (Thread/sleep 100)
                (is (= before (rpc "run-progress" {})))
                (is (= n (count (into [] (es/read (:event-store *ctx*) {:tenant-id (:tenant-id *ctx*)})))))))))))))
