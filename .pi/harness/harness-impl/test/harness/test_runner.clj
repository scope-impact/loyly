(ns harness.test-runner
 (:require [clojure.test :refer :all] [clojure.data.json :as json] [clojure.java.io :as io] [clojure.java.shell :as sh] [harness.core :as h]))
(def ^:dynamic *client* nil)
(defn rpc [m p]
 (let [[writer reader] *client*]
  (.write writer (str (json/write-str {:id 1 :method m :params p}) "\n")) (.flush writer)
  (let [line (.readLine reader)] (assert line "RPC process closed stdout") (json/read-str line :key-fn keyword))))
(defn result [m p] (let [r (rpc m p)] (is (:ok r) (pr-str r)) (:result r)))
(defn git [root & args] (let [r (apply sh/sh "git" "-C" (str root) args)] (assert (zero? (:exit r)) r) (clojure.string/trim (:out r))))
(defn fixture []
 (let [r (.toFile (java.nio.file.Files/createTempDirectory "harness-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
  (doseq [[p s] {"harness/config.sh" "FENCED_PATHS=answer\nPLAN_DIR=model/plan\n" "harness/check.sh" "#!/bin/bash\nset -e\ntest -e \"$(dirname \"$0\")/config.sh\"\n" "answer/secret" "old implementation" "model/plan/01-demo/spec.feature" "Feature: Demo\n Rule: Stable\n @item-01-001\n Scenario: First\n Given one\n Then two\n @item-01-002\n Scenario: Second\n Given two\n Then three\n"}]
   (io/make-parents (io/file r p)) (spit (io/file r p) s))
  (io/copy (io/file "test/fixtures/fence.sh") (io/file r "harness/fence.sh"))
  (doseq [p ["harness/check.sh" "harness/fence.sh"]] (.setExecutable (io/file r p) true))
  (git r "init" "-q") (git r "add" ".") (git r "-c" "user.name=test" "-c" "user.email=test@test" "commit" "-qm" "source")
  r))
(defn setup [f]
 (let [r (fixture) state (str r "/state")
       pb (ProcessBuilder. ^java.util.List [(.getAbsolutePath (io/file "start.sh"))])
       _ (.put (.environment pb) "PI_HARNESS_PROJECT_ROOT" (str r))
       _ (.put (.environment pb) "PI_HARNESS_STATE_ROOT" state)
       _ (.redirectError pb (io/file r "rpc-stderr.log"))
       process (.start pb)
       writer (io/writer (.getOutputStream process))
       reader (io/reader (.getInputStream process))]
  (binding [h/*project-root* (str r) h/*state-root* state *client* [writer reader]]
   (try (f) (finally (.close writer) (when-not (.waitFor process 10 java.util.concurrent.TimeUnit/SECONDS) (.destroyForcibly process)))))))
(use-fixtures :each setup)
(defn cut [] (let [c (git h/*project-root* "rev-parse" "HEAD") p (result "prepare-cut" {:sourceCommit c :executionBackend "host"}) w (result "wait" {:taskId (:taskId p)})] (is (= "completed" (:status w)) (pr-str w)) (get-in w [:result :outputs :cut-result])))
(deftest item-01-001
 "@item-01-001"
 (let [c (cut) room (:root c)]
  (is (= (git h/*project-root* "rev-parse" "HEAD") (:sourceCommit c)))
  (is (= (:treeDigest c) (git room "rev-parse" "HEAD^{tree}")))
  (is (= "1" (git room "rev-list" "--all" "--count")))
  (is (not (clojure.string/includes? (git room "rev-list" "--objects" "--all") "answer")))))
(deftest item-01-005
 "@item-01-005"
 (let [c (cut) p {:cut (:cutId c) :slice "01-demo" :itemIds ["@item-01-002"]} a (result "preview-selection" p) b (result "preview-selection" p)]
  (is (= a b)) (is (= ["@item-01-002"] (:items a))) (is (= "awaiting-approval" (:status a)))))
(deftest item-01-006
 "@item-01-006"
 (let [c (cut) p {:cut (:cutId c) :slice "01-demo"} a (result "preview-selection" p)]
  (is (false? (:ok (rpc "approve-selection" {:selectionId (:selectionId a) :selectionDigest "bad"}))))
  (is (= "awaiting-approval" (:status (result "preview-selection" p))))))
(deftest item-01-007
 "@item-01-007"
 (let [c (cut) a (result "preview-selection" {:cut (:cutId c) :slice "01-demo" :itemIds ["@item-01-001"]})]
  (is (false? (:ok (rpc "submit-approved-slice" {:requestId "no" :selectionId (:selectionId a) :scope ["app"] :targetStack "Clojure" :executionBackend "host"}))))))
(deftest item-01-010
 "@item-01-010"
 (let [w (result "workflow-contract" {})]
  (is (some #{"builderSessionId"} (:builderResultFields w)))
  (is (= ["fence" "regenerate" "gate" "score" "scoreboard"] (:stages w)))))
(deftest item-01-008
 "@item-01-008"
 (let [p {:requestId "same-request"} a (result "submit-cycle" p)
       terminal (result "wait" {:taskId (:taskId a)})]
  (is (#{"completed" "failed"} (:status terminal)))
  (is (= a (result "submit-cycle" p)))
  (is (false? (:ok (rpc "submit-cycle" (assoc p :executionBackend "gondolin")))))))
(deftest item-02-002
 "@item-02-002"
 (let [a (result "cycle-contract" {}) b (result "workflow-contract" {})]
  (is (= ["fence" "regenerate" "gate" "score" "scoreboard"] (:stages a)))
  (is (= (:stages a) (:stages b)))
  (is (= "sequence" (:rootType b)))))
(deftest item-02-010
 "@item-02-010"
 (let [started (System/nanoTime) response (rpc "cycle-contract" {})]
  (is (:ok response)) (is (= 1 (:id response)))
  (is (= "pi-harness" (get-in response [:result :workflowName])))
  (is (= ["fence" "regenerate" "gate" "score" "scoreboard"] (get-in response [:result :stages])))
  (is (< (/ (- (System/nanoTime) started) 1e9) 30))))
(deftest item-02-003
 "@item-02-003"
 ;; Establish a successful check, reuse the identical cut, then change harness content.
 (let [source (git h/*project-root* "rev-parse" "HEAD")
       c (cut)
       receipt (result "prepare-cut" {:sourceCommit source :executionBackend "host"})
       again (result "wait" {:taskId (:taskId receipt)})]
  (is (= (:cutId c) (get-in again [:result :outputs :cut-result :cutId]))))
 (spit (io/file h/*project-root* "harness/check.sh") "#!/bin/bash\necho calibration-broken >&2\nexit 1\n")
 (git h/*project-root* "add" "harness/check.sh")
 (git h/*project-root* "-c" "user.name=test" "-c" "user.email=test@test" "commit" "-qm" "broken self check")
 (let [p (result "prepare-cut" {:sourceCommit (git h/*project-root* "rev-parse" "HEAD") :executionBackend "host"})
       w (result "wait" {:taskId (:taskId p)})]
  (is (= "failed" (:status w)))
  (is (clojure.string/includes? (pr-str w) "calibration-broken"))
  (is (= 1 (count (filter #(and (.isDirectory %) (not (.endsWith (.getName %) "-source")))
                    (.listFiles (io/file h/*state-root* "rooms"))))))))
(defn commit-spec! [source]
 (spit (io/file h/*project-root* "model/plan/01-demo/spec.feature") source)
 (git h/*project-root* "add" "model/plan/01-demo/spec.feature")
 (git h/*project-root* "-c" "user.name=test" "-c" "user.email=test@test" "commit" "-qm" "scenario contract"))
(deftest item-03-001
 "@item-03-001: derive one digest-bound item per selected scenario; refuse missing and empty identities"
 (let [c (cut) p {:cut (:cutId c) :slice "01-demo"}
       selection (result "preview-selection" p)
       checklist (:checklist selection)]
  (is (= ["@item-01-001" "@item-01-002"] (mapv :id checklist)))
  (is (every? #(re-matches #"sha256:[0-9a-f]{64}" (:scenario-digest %)) checklist))
  (is (= 2 (count (set (map :scenario-digest checklist)))))
  (is (= [(first checklist)] (:checklist (result "preview-selection" (assoc p :itemIds ["@item-01-001"])))))
  (is (= selection (result "preview-selection" p))))
 (commit-spec! "Feature: Demo\n Rule: Stable\n @item-01-001\n Scenario: First\n Given one\n Then two\n Scenario: Unstamped\n Then stop\n")
 (let [c (cut) r (rpc "preview-selection" {:cut (:cutId c) :slice "01-demo"})]
  (is (false? (:ok r)))
  (is (re-find #"unstamped" (str (:error r)))))
 (commit-spec! "Feature: Empty\n")
 (let [c (cut)]
  (is (false? (:ok (rpc "preview-selection" {:cut (:cutId c) :slice "01-demo"})))))
 (is (:ok (rpc "cycle-contract" {}))))

(deftest item-03-001-scenario-digests
 "@item-03-001: digest the complete scenario, including Rule, Background, Examples, and arguments"
 (let [source "Feature: Demo\n Rule: Stable\n Background: Setup\n Given ready\n @item-01-001\n Scenario Outline: First\n Given <input>\n Then result\n   | key | value |\n Examples: Cases\n   | input |\n   | one |\n @item-01-002\n Scenario: Second\n Then stable\n"
       read-items (fn [] (let [c (cut)] (:checklist (result "preview-selection" {:cut (:cutId c) :slice "01-demo"}))))]
  (commit-spec! source)
  (let [original (read-items)]
   (commit-spec! (clojure.string/replace source "| one |" "| two |"))
   (let [changed (read-items)]
    (is (= (mapv :id original) (mapv :id changed)))
    (is (not= (:scenario-digest (first original)) (:scenario-digest (first changed))))
    (is (= (:scenario-digest (second original)) (:scenario-digest (second changed)))))
   (commit-spec! (clojure.string/replace source "Rule: Stable" "Rule: Changed"))
   (is (every? true? (map #(not= (:scenario-digest %1) (:scenario-digest %2)) original (read-items)))))
  (commit-spec! (str source " @item-01-001\n Scenario: Duplicate\n Then refuse\n"))
  (let [c (cut)]
   (is (false? (:ok (rpc "preview-selection" {:cut (:cutId c) :slice "01-demo"})))))))

(deftest instrument-refuses-unknown-grader
 (let [r (rpc "approve-grader" {:graderDigest (str "sha256:" (apply str (repeat 64 "0"))) :approvedBy "operator"})]
  (is (false? (:ok r)))
  (is (re-find #"no sealed.*grader" (str (:error r)))))
 (is (nil? (result "measurement" {:cycleId "never-scored"})))
 (is (:ok (rpc "cycle-contract" {}))))

(deftest item-04-006
 "@item-04-006: refuse unresolved denominators without recording a selection"
 (let [c (cut) p {:cut (:cutId c) :slice "01-demo"}]
  (doseq [ids [["@item-01-001" "@item-01-001"] ["@item-99-999"] [nil] "@item-01-001"]]
   (let [r (rpc "preview-selection" (assoc p :itemIds ids))]
    (is (false? (:ok r)) (pr-str r))
    (is (re-find #"denominator|itemIds" (str (:error r))))))
  (is (= ["@item-01-001" "@item-01-002"] (:items (result "preview-selection" p)))))
 (doseq [source ["Feature: Empty\n"
                 "Feature: Bad\n Scenario: Missing\n Then fail\n"
                 "Feature: Bad\n @item-01-001\n Scenario: One\n Then ok\n @item-01-001\n Scenario: Two\n Then ambiguous\n"
                 "Feature: Bad\n @item-01-001 @item-01-002\n Scenario: Ambiguous\n Then fail\n"]]
  (commit-spec! source)
  (let [c (cut) r (rpc "preview-selection" {:cut (:cutId c) :slice "01-demo"})]
   (is (false? (:ok r)))
   (is (re-find #"empty|unstamped|duplicate" (str (:error r)))))))

(deftest item-04-007
 "@item-04-007: refuse unsafe and overlapping scopes before opening a room"
 (let [c (cut) a (result "preview-selection" {:cut (:cutId c) :slice "01-demo"})
       _ (result "approve-selection" (select-keys a [:selectionId :selectionDigest]))
       rooms (set (map str (file-seq (io/file h/*state-root* "rooms"))))]
  (doseq [[n scope] (map-indexed vector [[] [""] ["/tmp/app"] ["../app"] ["app/../escape"] ["app" "app/sub"] ["app/sub" "app"] ["app" "app"]])]
   (let [r (rpc "submit-approved-slice" {:requestId (str "bad-scope-" n) :selectionId (:selectionId a) :scope scope :targetStack "Clojure" :executionBackend "host"})]
    (is (false? (:ok r)) (pr-str r))
    (is (re-find #"scope" (str (:error r))))))
  (is (= rooms (set (map str (file-seq (io/file h/*state-root* "rooms"))))))
  (is (not (.exists (io/file h/*state-root* "sessions"))))))

(deftest item-04-008
 "@item-04-008: repeated waits return the actual ORC terminal outcome"
 (let [p (result "prepare-cut" {:sourceCommit (git h/*project-root* "rev-parse" "HEAD") :executionBackend "host"})
       a (result "wait" {:taskId (:taskId p)})]
  (is (= "completed" (:status a)))
  (is (get-in a [:result :outputs :cut-result :treeDigest]))
  (is (= a (result "wait" {:taskId (:taskId p)}))))
 ;; Exercise a real failed cycle without asking a model to build: the ORC regenerate
 ;; node explicitly rejects the unavailable Gondolin backend after fencing.
 (let [p (result "submit-cycle" {:requestId "failed-cycle" :executionBackend "gondolin"})
       a (result "wait" {:taskId (:taskId p)})]
  (is (= "failed" (:status a)))
  (is (re-find #"Gondolin" (pr-str a)))
  (is (= (:tickId p) (get-in a [:traceSummary :tickId])))
  (is (= a (result "wait" {:taskId (:taskId p)})))) )

(deftest item-04-008-approved-slice
 "@item-04-008: an approved slice task also has one stable ORC outcome"
 (let [c (cut) selection (result "preview-selection" {:cut (:cutId c) :slice "01-demo"})
       _ (result "approve-selection" (select-keys selection [:selectionId :selectionDigest]))
       receipt (result "submit-approved-slice" {:requestId "approved-task" :selectionId (:selectionId selection)
                  :scope ["app"] :targetStack "Clojure" :executionBackend "gondolin"})
       a (result "wait" {:taskId (:taskId receipt)})]
  (is (= "failed" (:status a)))
  (is (re-find #"Gondolin" (pr-str a)))
  (is (= (:tickId receipt) (get-in a [:traceSummary :tickId])))
  (is (= a (result "wait" {:taskId (:taskId receipt)})))))

(deftest item-04-009
 "@item-04-009: unknown task identities never start work"
 (doseq [id [999999 nil "1" -1 1.5]]
  (let [r (rpc "wait" {:taskId id})]
   (is (false? (:ok r)))
   (is (re-find #"taskId" (str (:error r))))))
 (is (not (.exists (io/file h/*state-root* "rooms"))))
 (is (:ok (rpc "cycle-contract" {}))))

(deftest item-05-005
 "@item-05-005: executable observation contract carries artifact provenance"
 (let [w (result "workflow-contract" {})]
  (is (some #{"artifacts"} (:observationFields w))))
 ;; The public handler must describe the actual ORC blackboard schema, not a
 ;; parallel constant. Change the executable contract to detect stale metadata.
 (let [workflow (assoc-in h/cycle-workflow
                  [:blackboard-schema :score-result]
                  [:map [:complete :boolean]
                   [:observations [:vector [:map [:artifacts [:vector :map]] [:probeField :string]]]]])]
  (with-redefs [h/cycle-workflow workflow]
   (let [response (h/respond {:id "schema-probe" :method "workflow-contract" :params {}})]
    (is (:ok response))
    (is (= "schema-probe" (:id response)))
    (is (= ["artifacts" "probeField"] (get-in response [:result :observationFields])))))))

(deftest item-06-001
 "@item-06-001: invalid example sets refuse before any candidate evaluation"
 (let [base {:purpose "fresh-generation" :reflectionProvider "bedrock"
             :reflectionModel "explicit-test-model" :trainset ["training"] :valset ["validation"]}]
  (doseq [purpose ["fresh-generation" "verification-repair"]
          field [:trainset :valset]
          invalid [::missing nil [] "example" 42 true {"example" "not a list"}]]
   (let [p (assoc base :purpose purpose)
         p (if (= ::missing invalid) (dissoc p field) (assoc p field invalid))
         response (rpc "optimize-prompt" p)]
    (is (= 1 (:id response)))
    (is (false? (:ok response)))
    (is (re-find (re-pattern (str (name field) ".*non-empty list")) (str (:error response)))
    ;; Validation must precede all evaluation side effects, including creation
    ;; of the durable runtime, a builder workspace, or reflection evidence.
    (is (not (.exists (io/file h/*state-root*)))))))
  (is (:ok (rpc "cycle-contract" {})))))

(defn runtime-fixture! [stacks]
 (spit (io/file h/*project-root* "harness/config.sh")
       "FENCED_PATHS=answer\nPLAN_DIR=model/plan\nPROJECT_ID=loyly\n")
 (doseq [[path content] (merge {"harness/loyly/gondolin.json" "{}"
                              "harness/other/Dockerfile.rust" "FROM scratch"}
                             (into {} (map (fn [stack] [(str "harness/loyly/Dockerfile." stack) "FROM scratch"]) stacks)))]
  (io/make-parents (io/file h/*project-root* path))
  (spit (io/file h/*project-root* path) content))
 (git h/*project-root* "add" "harness")
 (git h/*project-root* "-c" "user.name=test" "-c" "user.email=test@test" "commit" "-qm" "project runtime inputs"))
(defn runtime-selection []
 (let [c (cut) s (result "preview-selection" {:cut (:cutId c) :slice "01-demo"})]
  (result "approve-selection" (select-keys s [:selectionId :selectionDigest]))
  (:selectionId s)))

(deftest item-08-001
 "@item-08-001: conventional project-local runtime resolution through submission"
 (runtime-fixture! ["typescript" "rust" "java" "rails"])
 (let [selection (runtime-selection)]
  (doseq [stack ["typescript" "rust" "java" "rails"]]
   (let [p {:requestId (str "runtime-" stack) :selectionId selection :scope ["app"]
            :targetStack stack :executionBackend "gondolin"}
         receipt (result "submit-approved-slice" p)
         files (:runtimeFiles receipt)]
    (is (= "loyly" (:projectId files)))
    (is (= "harness/loyly/gondolin.json" (:gondolinVmConfig files)))
    (is (= (str "harness/loyly/Dockerfile." stack) (:dockerfile files)))
    (is (= stack (:targetStack files)))
    (is (= receipt (result "submit-approved-slice" p)))
    ;; Await actual ORC termination; resolution does not pretend a VM ran.
    (is (= "failed" (:status (result "wait" {:taskId (:taskId receipt)}))))))))

(deftest item-08-002
 "@item-08-002: absent/unsupported inputs refuse before task issuance; never use other project's image"
 (runtime-fixture! ["typescript"])
 (let [selection (runtime-selection)]
  (doseq [stack ["rust" "unknown" "../other/Dockerfile.rust"]]
   (let [response (rpc "submit-approved-slice"
                  {:requestId (str "missing-" (if (= stack "rust") "rust" "other"))
                   :selectionId selection :scope ["app"] :targetStack stack :executionBackend "gondolin"})]
    (is (false? (:ok response)))
    (is (re-find #"project runtime.*(Dockerfile|stack)" (str (:error response))))))
  ;; The only issued task was cut preparation. Refusal allocated no cycle task.
  (is (false? (:ok (rpc "wait" {:taskId 2}))))
  (is (:ok (rpc "cycle-contract" {})))))

(deftest item-09-001
 "@item-09-001: pipelined requests each produce exactly one line with their own id"
 (let [[writer reader] *client*]
  (doseq [[id method] [[7 "cycle-contract"] ["sentinel" "cycle-contract"]]]
   (.write writer (str (json/write-str {:id id :method method :params {}}) "\n")))
  (.flush writer)
  (doseq [id [7 "sentinel"]]
   (let [response (json/read-str (.readLine reader) :key-fn keyword)]
    (is (= id (:id response)))
    (is (or (and (true? (:ok response)) (contains? response :result))
            (and (false? (:ok response)) (contains? response :error))))))
  ;; If an extra response was emitted, this request reads it instead.
  (is (= "pi-harness" (:workflowName (result "cycle-contract" {}))))))

(deftest item-09-002
 "@item-09-002"
 (let [r (rpc "not-a-declared-method" {})]
  (is (false? (:ok r)))
  (is (clojure.string/includes? (:error r) "not-a-declared-method")))
 (is (= "pi-harness" (:workflowName (result "cycle-contract" {})))))

(deftest item-09-003
 "@item-09-003: duplicate submissions issue no additional ORC task"
 (let [params {:requestId "rpc-repeat" :executionBackend "gondolin"}
       a (result "submit-cycle" params)
       outcome (result "wait" {:taskId (:taskId a)})]
  (dotimes [_ 3] (is (= a (result "submit-cycle" params))))
  (is (= outcome (result "wait" {:taskId (:taskId a)})))
  (let [next-task (result "prepare-cut" {:sourceCommit (git h/*project-root* "rev-parse" "HEAD") :executionBackend "host"})]
   (is (= (inc (:taskId a)) (:taskId next-task)))
   (is (= "completed" (:status (result "wait" {:taskId (:taskId next-task)})))))))

(deftest item-09-004
 "@item-09-004: an executed failure names its stage and reason and stays terminal"
 (let [receipt (result "submit-cycle" {:requestId "rpc-outcome" :executionBackend "gondolin"})
       outcome (result "wait" {:taskId (:taskId receipt)})]
  (is (= "failed" (:status outcome)))
  (is (= "regenerate" (:stage outcome)))
  (is (re-find #"Gondolin.*not configured" (str (:error outcome))))
  (dotimes [_ 2] (is (= outcome (result "wait" {:taskId (:taskId receipt)}))))))

(defn -main [& _] (let [r (run-tests 'harness.test-runner)] (shutdown-agents) (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
