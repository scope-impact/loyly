(ns harness.core
 (:require [harness.checklist :as checklist] [clojure.data.json :as json] [clojure.java.io :as io] [clojure.java.shell :as sh] [clojure.string :as str]
  [ai.obney.orc.orc-service.interface :as orc]
  [ai.obney.grain.event-store-v3.interface :as es]
  [ai.obney.grain.event-store-sqlite-v3.interface]
  [ai.obney.grain.kv-store.interface :as kv]
  [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
  [ai.obney.grain.todo-processor-v2.interface :as tp])
 (:gen-class))
(def ^:dynamic *project-root* (or (System/getenv "PI_HARNESS_PROJECT_ROOT") "."))
(def ^:dynamic *state-root* (or (System/getenv "PI_HARNESS_STATE_ROOT") ".state"))
(defn tenant [] (java.util.UUID/nameUUIDFromBytes (.getBytes (.getAbsolutePath (io/file *state-root*)) "UTF-8")))
(def systems (atom {}))
(def tasks (atom {}))
(defn refuse [s] (throw (ex-info s {})))
(defn check [x s] (when-not x (refuse s)) x)
(defn digest [x] (str "sha256:" (format "%064x" (java.math.BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (pr-str x) "UTF-8"))))))
(defn shell [& args] (let [r (apply sh/sh args)] (check (zero? (:exit r)) (str "command failed: " (first args) ": " (:err r) (:out r))) (str/trim (:out r))))
(defn git [root & args] (apply shell "git" "-C" (str root) args))
(doseq [t [:loyly.pi-harness/selection-recorded :loyly.pi-harness/selection-approved :loyly.pi-harness/task-receipt-issued :loyly.pi-harness/cut-prepared :loyly.pi-harness/request-submitted :loyly.pi-harness/self-check-passed]]
 (eval `(es/defevent ~t "Harness durable domain fact" {:schema [:map]})))
(defn system []
 (or (get @systems *state-root*)
  (locking systems
   (or (get @systems *state-root*)
    (let [dir (.getAbsolutePath (io/file *state-root*)) _ (.mkdirs (io/file dir))
          store (es/start {:conn {:type :sqlite :database-file (str dir "/events.sqlite")}})
          cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir (str dir "/cache") :db-name "projections"}))
          ctx {:tenant-id (tenant) :event-store store :cache cache}
          poller (tp/start-tenant-poller {:event-store store :tenant-ids #{(tenant)} :context {:cache cache} :poll-interval-ms 20})
          s {:ctx ctx :store store :cache cache :poller poller}]
     (swap! systems assoc *state-root* s) s)))))
(defn stop! []
 (when-let [s (get @systems *state-root*)]
  (tp/stop-tenant-poller (:poller s)) (kv/stop (:cache s)) (es/stop (:store s)) (swap! systems dissoc *state-root*)))
(defn events [t] (into [] (es/read (:store (system)) {:tenant-id (tenant) :types #{t}})))
(defn record! [t body]
 (let [r (es/append (:store (system)) {:tenant-id (tenant) :events [(es/->event {:type t :body body})]})]
  (check (not (:cognitect.anomalies/category r)) (str "Grain refused event " r)) body))
(defn lookup [t k v] (last (filter #(= v (get % k)) (events t))))
(defn cut-by-id [id] (or (:cut (lookup :loyly.pi-harness/cut-prepared :cutId id)) (refuse "unknown prepared cut identity")))
(defn prepare-fence [{:keys [sourceCommit executionBackend]}]
 (check (and (string? sourceCommit) (re-matches #"[0-9a-f]{40}" sourceCommit)) "sourceCommit must be a full 40-hex commit")
 (check (#{"host" "gondolin"} executionBackend) "unknown executionBackend")
 (check (= sourceCommit (git *project-root* "rev-parse" (str sourceCommit "^{commit}"))) "sourceCommit must identify a commit")
 (let [id (str "cut-" (subs (digest [sourceCommit executionBackend]) 7))
       base (.getAbsolutePath (io/file *state-root* "rooms" id))
       pinned (str base "-source") room (str base "/room")]
  (if-let [old (lookup :loyly.pi-harness/cut-prepared :cutId id)] (:cut old)
   (do
    (check (not (.exists (io/file base))) "cut identity collides with existing room")
    (.mkdirs (io/file (.getParent (io/file base))))
    ;; Pin independently of HEAD; legacy fence's clone must see precisely this commit.
    (shell "git" "clone" "--no-hardlinks" "--no-checkout" (.getAbsolutePath (io/file *project-root*)) pinned)
    (git pinned "checkout" "--detach" sourceCommit)
    ;; Self-check is tied to all committed harness assets, not a mutable name or HEAD.
    ;; No clean-room directory is allocated before it passes.
    (let [harness-digest (digest (git pinned "ls-tree" "-r" "HEAD" "harness"))]
     (when-not (lookup :loyly.pi-harness/self-check-passed :harnessDigest harness-digest)
      (try (shell "bash" (str pinned "/harness/check.sh") "--local")
       (catch Throwable e (refuse (str "harness self-check failed: harness/check.sh: " (.getMessage e)))))
      (record! :loyly.pi-harness/self-check-passed {:harnessDigest harness-digest :check "harness/check.sh --local"})))
    (.mkdirs (io/file base))
    (shell "bash" (str pinned "/harness/fence.sh") room)
    (let [c {:cutId id :sourceCommit sourceCommit :treeDigest (git room "rev-parse" "HEAD^{tree}") :root room :executionBackend executionBackend}]
     (record! :loyly.pi-harness/cut-prepared {:cutId id :cut c}) c)))))
(defn fence-node [{:keys [inputs]}]
 (let [r (:request inputs)] (binding [*project-root* (:project-root r) *state-root* (:state-root r)] {:cut-result (prepare-fence r)})))
(def request-schema [:map [:sourceCommit :string] [:executionBackend :string]])
(def cut-schema [:map [:cutId :string] [:sourceCommit :string] [:treeDigest :string] [:root :string] [:executionBackend :string]])
(def prepare-workflow
 (orc/workflow "pi-harness-prepare-cut"
  (orc/blackboard {:request request-schema :cut-result cut-schema})
  (orc/code "fence" :fn "harness.core/fence-node" :reads [:request] :writes [:cut-result])))
(def builder-schema [:map [:builderSessionId :string] [:workspace :string] [:toolCalls :int]])
 ;; Observation shape belongs to the executable score output contract. Reports
;; describe this shape; they do not introduce evidence or author verdicts.
(def observation-schema
 [:map
  [:cycle-id :string] [:cut-digest :string] [:project-id :string]
  [:container-id :string] [:component-id :string] [:item-id :string]
  [:subject-digest :string] [:producer :string]
  [:producer-authority [:enum "candidate" "harness-observer" "independent-validator"]]
  [:evidence-purpose [:enum "assertion" "diagnostic" "calibration"]]
  [:claim-type :string] [:evaluation-id :string] [:evidence-digest :string]
  [:result [:enum "pass" "fail" "inconclusive"]] [:reached? :boolean]
  [:evidence :string]
  [:collection [:map [:complete? :boolean] [:sampled? :boolean] [:dropped :int]
                [:reason {:optional true} :string]]]
  [:correlation [:map [:operation-id {:optional true} :string]
                 [:trace-id {:optional true} :string] [:event-id {:optional true} :string]]]
  [:artifacts [:vector [:map [:digest :string] [:media-type :string] [:location :string]]]]
  [:oracle-digest :string]])
(def score-schema [:map [:complete :boolean] [:observations [:vector observation-schema]]])
(declare regenerate-node gate-node score-node scoreboard-node composition)
(def cycle-workflow
 (orc/workflow "pi-harness"
  (orc/blackboard {:request request-schema :cut-result cut-schema :builder-result builder-schema :gate-result [:map [:passed :boolean]] :score-result score-schema :scoreboard-result [:map [:report :string]]})
  (orc/sequence "cycle"
   (orc/code "fence" :fn "harness.core/fence-node" :reads [:request] :writes [:cut-result])
   (orc/code "regenerate" :fn "harness.core/regenerate-node" :reads [:request :cut-result] :writes [:builder-result])
   (orc/code "gate" :fn "harness.core/gate-node" :reads [:request :cut-result :builder-result] :writes [:gate-result])
   (orc/code "score" :fn "harness.core/score-node" :reads [:request :cut-result :builder-result :gate-result] :writes [:score-result])
   (orc/code "scoreboard" :fn "harness.core/scoreboard-node" :reads [:request :score-result] :writes [:scoreboard-result]))))
(defn stages [] (mapv :name (get-in cycle-workflow [:root-node :children])))
(defn schema-field [schema field]
 (second (some #(when (= field (first %)) %) (rest schema))))
(defn observation-fields []
 (let [score (get-in cycle-workflow [:blackboard-schema :score-result])
       observations (schema-field score :observations)
       observation (second observations)]
  (mapv (comp name first) (rest observation))))
(defn contract [] {:workflowName (:workflow-name cycle-workflow) :routes ["slice-cycle"] :rootType (name (get-in cycle-workflow [:root-node :node-type])) :stages (stages)
 :builderResultFields (mapv (comp name first) (rest builder-schema)) :proposalResultFields [] :observationFields (observation-fields)})
(defn failed-stage [workflow execution]
 ;; The workflow is a sequence: the first unwritten node output identifies
 ;; the stopped stage. Only report it for a real ORC terminal failure.
 (when (and (= :failure (:status execution))
            (some #(= :failure (:status %)) (:node-trace execution)))
  (let [root (:root-node workflow)
        nodes (if (= :sequence (:node-type root)) (:children root) [root])]
   (:name (first (filter (fn [node]
                          (some #(nil? (get (:outputs execution) %)) (:writes node)))
                        nodes))))))
(defn issue-task! [workflow request cycle-id]
 (let [request (assoc request :project-root (.getAbsolutePath (io/file *project-root*)) :state-root (.getAbsolutePath (io/file *state-root*)))
       ctx (:ctx (system)) sheet (orc/build-workflow! ctx workflow)
       task-id (inc (reduce max 0 (map :task-id (events :loyly.pi-harness/task-receipt-issued))))
       tick-id (str (random-uuid))
       receipt {:taskId task-id :tickId tick-id}]
  (record! :loyly.pi-harness/task-receipt-issued {:task-id task-id :tick-id tick-id :cycle-id cycle-id})
  (swap! tasks assoc [*state-root* task-id]
   (future (try (let [r (orc/execute ctx sheet {:request request} :timeout-ms 3600000 :tick-id (java.util.UUID/fromString tick-id) :correlation-id (java.util.UUID/fromString tick-id))]
    (if (= :success (:status r)) {:status "completed" :result r :traceSummary {:tickId tick-id :workflowName (:workflow-name workflow) :status (name (:status r))}}
     {:status "failed" :stage (failed-stage workflow r) :error (or (:error r) (pr-str r)) :result r :traceSummary {:tickId tick-id :workflowName (:workflow-name workflow) :status (name (:status r))}}))
    (catch Throwable e {:status "failed" :error (.getMessage e) :traceSummary {:tickId tick-id :workflowName (:workflow-name workflow) :status "failed"}}))))
  receipt))
(defn composition [cut]
 (let [root (:root (cut-by-id cut)) config (slurp (io/file root "harness/config.sh"))
       plan (second (re-find #"(?m)^PLAN_DIR=[\"']?([^\s\"']+)" config))]
  (check plan "PLAN_DIR missing from harness/config.sh")
  (let [files (sort-by str (filter #(and (.isFile %) (= "spec.feature" (.getName %))) (file-seq (io/file root plan))))
        slices (mapv (fn [f] (let [source (slurp f) entries (mapv #(assoc % :source (.getPath f)) (checklist/scenarios source digest))] {:slice (.getName (.getParentFile f)) :items (mapv :id entries) :checklist entries :source source})) files)
        items (mapcat :items slices)]
   (check (seq items) "empty behavior denominator")
   (check (= (count items) (count (set items))) "duplicate item mappings")
   (check (= (count slices) (count (set (map :slice slices)))) "conflicting slice mappings")
   slices)))
(defn preview [{:keys [cut slice itemIds]}]
 (check (or (nil? itemIds) (vector? itemIds)) "itemIds denominator must be a list")
 (check (every? #(and (string? %) (re-matches #"@item-\d+-\d+" %)) itemIds) "unresolved itemIds denominator: malformed identity")
 (check (= (count itemIds) (count (set itemIds))) "duplicate itemIds denominator")
 (let [s (first (filter #(= slice (:slice %)) (composition cut))) _ (check s "unknown slice")
       wanted (set itemIds) items (filterv #(or (empty? wanted) (wanted %)) (:items s))]
  (check (and (seq items) (or (empty? wanted) (= wanted (set items)))) "unknown or empty item denominator")
  (let [d (digest [cut slice items (:source s)]) id (str "selection-" (subs d 7))
        existing (lookup :loyly.pi-harness/selection-recorded :selectionId id)
        c {:selectionId id :selectionDigest d :status "awaiting-approval" :items items :checklist (filterv #(some #{(:id %)} items) (:checklist s)) :cut cut :slice slice}]
   (when-not existing (record! :loyly.pi-harness/selection-recorded c))
   (assoc (select-keys (or existing c) [:selectionId :selectionDigest :status :items :checklist]) :status
    (if (lookup :loyly.pi-harness/selection-approved :selectionId id) "approved" "awaiting-approval")))))
(defn approve [{:keys [selectionId selectionDigest]}]
 (let [s (lookup :loyly.pi-harness/selection-recorded :selectionId selectionId)]
  (check s "unknown selection") (check (= selectionDigest (:selectionDigest s)) "approval refused: digest differs from reviewed digest")
  (when-not (lookup :loyly.pi-harness/selection-approved :selectionId selectionId)
   (record! :loyly.pi-harness/selection-approved {:selectionId selectionId :selectionDigest selectionDigest}))
  {:selectionId selectionId :selectionDigest selectionDigest :status "approved"}))
(defn safe-id [id] (check (and (string? id) (re-matches #"[A-Za-z0-9][A-Za-z0-9_-]{0,127}" id)) "unsafe requestId"))
(defn safe-scope [scope]
 (check (and (vector? scope) (seq scope)
             (every? #(and (string? %)
                           (re-matches #"[A-Za-z0-9_-]+(?:/[A-Za-z0-9_.-]+)*" %)
                           (not-any? #{".." "." ".git" "harness"} (str/split % #"/"))) scope))
        "unsafe candidate scope")
 (check (not-any? true?
          (for [i (range (count scope)) j (range (inc i) (count scope))
                :let [a (nth scope i) b (nth scope j)]]
            (or (= a b) (str/starts-with? a (str b "/")) (str/starts-with? b (str a "/")))))
        "overlapping candidate scope"))
(def supported-runtime-stacks #{"typescript" "rust" "java" "rails"})
(defn resolve-project-runtime [root stack]
 ;; A single project identity in shared config selects conventional files. No
 ;; project profile, shell evaluation, search path, or cross-project fallback.
 (let [config (slurp (io/file root "harness/config.sh"))
       project (second (re-find #"(?m)^PROJECT_ID=[\"']?([^\s\"']+)" config))]
  (when project
   (check (re-matches #"[A-Za-z0-9][A-Za-z0-9_-]*" project) "project runtime: invalid project identity")
   (check (supported-runtime-stacks stack) "project runtime: unsupported stack")
   (let [dir (str "harness/" project)
         base (.toPath (.getCanonicalFile (io/file root dir)))
         repo (.toPath (.getCanonicalFile (io/file root)))
         vm (str dir "/gondolin.json") docker (str dir "/Dockerfile." stack)]
    (check (.startsWith base repo) "project runtime: project directory escapes repository")
    (doseq [path [vm docker]]
     (let [file (io/file root path) canonical (.toPath (.getCanonicalFile file))]
      (check (and (.isFile file) (.startsWith canonical base))
             (str "project runtime: missing or unsafe " path))))
    {:projectId project :gondolinVmConfig vm :targetStack stack :dockerfile docker}))))

(defn submit! [p slice?]
 (safe-id (:requestId p))
 (let [id (:requestId p) old (lookup :loyly.pi-harness/request-submitted :requestId id)]
  (if old (do (check (= (:payload old) p) "requestId reused with different payload") (:receipt old))
   (let [selection (when slice? (lookup :loyly.pi-harness/selection-recorded :selectionId (:selectionId p)))
         _ (when slice? (check (lookup :loyly.pi-harness/selection-approved :selectionId (:selectionId p)) "selection must be approved before builder starts") (safe-scope (:scope p)))
         source (if slice? (:sourceCommit (cut-by-id (:cut selection))) (git *project-root* "rev-parse" "HEAD"))
         runtime (when slice? (resolve-project-runtime (:root (cut-by-id (:cut selection))) (:targetStack p)))
         request (cond-> (assoc p :sourceCommit source :executionBackend (or (:executionBackend p) "host")) runtime (assoc :runtimeFiles runtime))
         r (issue-task! cycle-workflow request id)
         receipt (cond-> (if slice? (merge r (select-keys p [:selectionId :targetStack :executionBackend]) {:operationId id :correlationId (:tickId r)}) (assoc r :cycleId id)) runtime (assoc :runtimeFiles runtime))]
    (record! :loyly.pi-harness/request-submitted {:requestId id :payload p :receipt receipt}) receipt))))
(defn optimize-prompt! [p]
 ;; JSON arrays decode to vectors. Validate both sets before initializing any
 ;; runtime, issuing a task, or calling a reflection/builder provider.
 (doseq [field [:trainset :valset]]
  (check (and (vector? (get p field)) (seq (get p field)))
         (str "optimize-prompt refused: " (name field) " must be a non-empty list")))
 (check (#{"fresh-generation" "verification-repair"} (:purpose p))
        "optimize-prompt refused: unknown builder purpose")
 (check (= "bedrock" (:reflectionProvider p))
        "optimize-prompt refused: reflectionProvider must be bedrock")
 (check (and (string? (:reflectionModel p)) (not (str/blank? (:reflectionModel p))))
        "optimize-prompt refused: reflectionModel must be explicitly pinned")
 (refuse "optimize-prompt unavailable: independent purpose-specific evaluation and Bedrock reflection are not configured; no instruction was evaluated or activated"))

(defn dispatch [method p]
 (case method
  "optimize-prompt" (optimize-prompt! p)
  "cycle-contract" (select-keys (contract) [:workflowName :stages])
  "workflow-contract" (contract)
  "prepare-cut" (do
   (check (and (string? (:sourceCommit p)) (re-matches #"[0-9a-f]{40}" (:sourceCommit p))) "sourceCommit must be a full 40-hex commit")
   (check (#{"host" "gondolin"} (:executionBackend p)) "unknown executionBackend")
   (git *project-root* "cat-file" "-e" (str (:sourceCommit p) "^{commit}"))
   (merge (issue-task! prepare-workflow p (:sourceCommit p)) {:cutId (:sourceCommit p) :executionBackend (:executionBackend p)}))
  "wait" (if-let [t (get @tasks [*state-root* (:taskId p)])] @t (refuse "unknown taskId or worker restart during task"))
  "slice-composition" {:slices (mapv #(dissoc % :source) (composition (:cut p)))}
  "preview-selection" (preview p)
  "approve-selection" (approve p)
  "submit-cycle" (submit! p false)
  "submit-approved-slice" (submit! p true)
  "approve-grader" (refuse (str "no sealed, calibrated grader names digest " (:graderDigest p) "; approval refused"))
  "measurement" nil
  (refuse (str "unknown-method: " method))))
(defn respond [{:keys [id method params]}]
 (try {:id id :ok true :result (dispatch method (or params {}))}
  (catch Throwable e {:id id :ok false :error (or (.getMessage e) (str e))})))
(defn -main [& _]
 (try (doseq [line (line-seq (java.io.BufferedReader. *in*))]
  (let [response (try (respond (json/read-str line :key-fn keyword)) (catch Throwable e {:id nil :ok false :error (.getMessage e)}))]
   (println (json/write-str response :default-write-fn (fn [x out _] (.write out (json/write-str (str x)))))) (flush)))
  (finally (doseq [root (keys @systems)] (binding [*state-root* root] (stop!))) (shutdown-agents))))

;; The host backend is deliberately developmental; it never claims independent grading.
;; A session is a new process and a new transcript, never a continuation of the caller.
(defn regenerate-node [{:keys [inputs]}]
 (let [r (:request inputs) cut (:cut-result inputs)
       session-id (str (random-uuid))
       workspace (str (:state-root r) "/workspaces/" (:requestId r))
       session-dir (str (:state-root r) "/sessions/" session-id)]
  (binding [*state-root* (:state-root r)]
   (let [slices (composition (:cutId cut))
         selected (when (:selectionId r) (lookup :loyly.pi-harness/selection-recorded :selectionId (:selectionId r)))
         entries (if selected (:checklist selected) (vec (mapcat :checklist slices)))]
    (check (seq entries) "empty cycle checklist")
    (when-not selected
     (record! :loyly.pi-harness/selection-recorded
      {:selectionId (str "whole-plan-" (:requestId r)) :selectionDigest (digest [(:cutId cut) entries])
       :status "approved" :cut (:cutId cut) :slice "whole-plan" :items (mapv :id entries) :checklist entries}))))
  (check (= "host" (:executionBackend r)) "Gondolin isolated builder backend is not configured")
  (check (not (.exists (io/file workspace))) "workspace already exists")
  (.mkdirs (io/file workspace)) (.mkdirs (io/file session-dir))
  (shell "cp" "-R" (str (:root cut) "/.") workspace)
  ;; Do not expose grading material left by the legacy fence in the builder view.
  (doseq [p ["harness" ".git" "archive"]] (shell "rm" "-rf" (str workspace "/" p)))
  (let [scope (or (:scope r) ["harness-impl"])
        prompt (str "Build the application from the specification in this workspace. Read only this workspace. Write only "
                    (pr-str scope) ". Target stack: " (or (:targetStack r) "Clojure CLI on ORC")
                    ". Implement executable start.sh and test.sh in the application directory. Approved selection: "
                    (or (:selectionId r) "all stamped scenarios in the declared plan")
                    ". You must run workspace tools and produce actual output. Do not access host resources or grading material.")
        _ (spit (io/file session-dir "instruction.txt") prompt)
        run (sh/sh "pi" "--print" "--mode" "json" "--session-dir" session-dir "--session-id" session-id
                   "--no-extensions" "--no-mcp" "--no-skills" "--no-prompt-templates" "--" prompt :dir workspace)
        _ (spit (io/file session-dir "transcript.jsonl") (:out run))
        _ (spit (io/file session-dir "stderr.log") (:err run))
        entries (keep #(try (json/read-str % :key-fn keyword) (catch Exception _ nil)) (str/split-lines (:out run)))
        tool-calls (count (filter #(= "tool_execution_start" (:type %)) entries))]
   (check (zero? (:exit run)) (str "builder session failed: " (:err run)))
   (check (pos? tool-calls) "builder executed no workspace tools")
   (check (some #(and (.exists (io/file workspace %)) (seq (file-seq (io/file workspace %)))) scope) "builder generated no workspace output")
   {:builder-result {:builderSessionId session-id :workspace workspace :toolCalls tool-calls}})))
(defn gate-node [{:keys [inputs]}]
 (let [r (:request inputs) c (:cut-result inputs) b (:builder-result inputs)
       gate (str (:state-root r) "/gates/" (:requestId r))
       pinned (str (:state-root r) "/rooms/" (:cutId c) "-source")]
  (.mkdirs (io/file gate))
  ;; Fresh independent gate tree; harness scripts are restored from pinned source,
  ;; never from the builder's writable tree.
  (shell "cp" "-R" (str pinned "/.") gate)
  (doseq [scope (or (:scope r) ["harness-impl"])]
   (shell "rm" "-rf" (str gate "/" scope))
   (io/make-parents (io/file gate scope))
   (shell "cp" "-R" (str (:workspace b) "/" scope) (str gate "/" scope)))
  (let [run (sh/sh "bash" (str gate "/harness/verify.sh") :dir gate)]
   (spit (str gate "/gate.log") (str (:out run) (:err run)))
   (check (zero? (:exit run)) (str "gate failed: " (:out run) (:err run)))
   {:gate-result {:passed true}})))
(defn score-node [{:keys [inputs]}]
 (let [backend (get-in inputs [:request :executionBackend])]
  (when (= "host" backend) (refuse "independent comparison requires Gondolin; host execution cannot pass as an independently graded measurement"))
  (refuse "no approved sealed grader is configured; no score was filed")))
(defn scoreboard-node [_] (refuse "scoreboard requires a filed cycle measurement"))
