(ns loyly.pi-harness.core.legacy-cycle
  "The regeneration cycle in the legacy shape: fence, regenerate, gate, score, scoreboard.
  ORC owns the order, the retries, and the prompts; each stage calls the legacy tool that
  does that job (harness/fence.sh, a Pi builder session, harness/verify.sh,
  harness/score.clj, harness/scoreboard.clj). The fence and the gate feed the build loop;
  the score and the scoreboard never do."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [loyly.pi-harness.core.build.prompt :as prompt]
            [loyly.pi-harness.core.build.subject :as subject]
            [loyly.pi-harness.core.runtime.adapters :as adapters]))

(def max-gate-attempts 3)

;; ------------------------------------------------------------------ configuration

(defn config
  "The values harness/config.sh declares, read by bash so the file stays the only source."
  [project-root]
  (let [keys ["APP_DIR" "PLAN_DIR" "APP_MARKER" "FENCED_PATHS" "APP_STACK"]
        script (str ". harness/config.sh; "
                    (str/join "; " (map #(str "printf '%s\\n' \"$" % "\"") keys)))
        {:keys [exit out err]} (shell/sh "bash" "-c" script :dir project-root)]
    (when-not (zero? exit)
      (throw (ex-info (str "harness/config.sh could not be read: " (str/trim err)) {})))
    (zipmap [:app-dir :plan-dir :app-marker :fenced-paths :app-stack]
            (str/split-lines out))))

(defn- state-dir
  "Cycle state (rooms, evidence, graders) lives under PI_HARNESS_STATE_ROOT like the rest of the
  harness state, so a worker run with its own state root never touches another's."
  [project-root & parts]
  (str/join "/" (into [(or (not-empty (System/getenv "PI_HARNESS_STATE_ROOT"))
                           (str project-root "/.pi/harness/.state"))]
                      parts)))

(defn- tail [text n]
  (let [s (str text)] (subs s (max 0 (- (count s) n)))))

(defn- run-logged!
  "Run a command, keep its full output as evidence, and return exit code and the log path."
  [dir log-file & command]
  (let [{:keys [exit out err]} (apply shell/sh (concat command [:dir dir]))
        log (str out err)]
    (io/make-parents log-file)
    (spit log-file log)
    {:exit exit :log log :log-path log-file}))

;; ------------------------------------------------------------------ prompts (ORC data)

(def ^:private system-instructions
  {:regeneration
   "You are a clean-room regeneration builder. Read only the clean room at $HARNESS_CUT_ROOT. Write the candidate only under $HARNESS_WORKSPACE_ROOT. Use the bash tool to build and test it."
   :gate-repair
   "You are a clean-room gate-repair builder. Read the clean room at $HARNESS_CUT_ROOT and the failed gate's log at $HARNESS_VERIFICATION_EVIDENCE_ROOT. Repair the candidate under $HARNESS_WORKSPACE_ROOT and use the bash tool to prove the repair."})

(def ^:private seed-instructions
  {:regeneration
   (str "Rebuild the product described by the spec in the clean room. The spec is authoritative: every "
        "stamped Gherkin scenario (@item-NN-SSS) under the plan directory is a requirement, and the Allium "
        "files, glossary, and AGENTS.md beside it explain the domain. Anything the spec does not say is your "
        "choice; do not look for a previous implementation, there is none in the room. "
        "The product is a real harness, not a facade. Its stages do real work: the fence builds a clean "
        "room with the legacy tools in the room's harness/ directory, the regenerate stage runs a real "
        "builder session, the gate boots the candidate and runs its suite, the score files verdicts, and "
        "the scoreboard compares cycles; each stage runs as an ORC workflow node and its facts are recorded "
        "through Grain, whose durability you use rather than reinvent. A result that is returned without "
        "doing the work (a canned stage list, a fixed id, a status that never ran) is a defect the score "
        "will find, and it fails every scenario that depends on it. "
        "Work slice by slice in plan order. For each slice, read every scenario, write a failing test that "
        "drives it through the public seam and names its @item id, make it pass with the smallest real "
        "change, and keep the whole suite green. Do not stop after the first slice and do not stop when "
        "the gate would pass: finish only when every stamped scenario of every slice has a passing test "
        "naming its id, and list any scenario you could not carry, with the reason, in APP_DIR/UNCARRIED.md. "
        "Done is decided by the gate and the score, not by you. The gate runs APP_DIR/test.sh, which must "
        "run the whole suite and exit non-zero on any failure and must discover at least one real test; "
        "then it runs APP_DIR/start.sh, which must start the product speaking JSON-RPC over stdio exactly as "
        "the public seam declares (one JSON object per line, one response per request carrying its id, "
        "nothing else on stdout; logs go to stderr). Within thirty seconds of start.sh launching, the "
        "product must answer the cycle-contract request, naming APP_MARKER and listing its stages in the "
        "order the spec declares: identity is proved by domain behavior, not by a name. "
        "Slow builds belong in test.sh, which is untimed, not in start.sh. "
        "Use exactly the target stack in the runtime context. "
        "Both scripts must be executable and must work from any working directory. "
        "Before finishing, run APP_DIR/test.sh, then start APP_DIR/start.sh, send it a cycle-contract "
        "request on stdin, and confirm the response. Never weaken or delete a test to obtain green.")
   :gate-repair
   (str "The gate failed for the candidate in your workspace. Read the gate log, reproduce the failure, "
        "turn it into a failing test where it is a behavior failure, and make the smallest coherent "
        "correction. Then rerun APP_DIR/test.sh, start APP_DIR/start.sh, and confirm it answers cycle-contract "
        "over stdio within thirty seconds. Do not delete, skip, or weaken tests. The spec in the clean room remains "
        "authoritative; the gate log is diagnostic input only.")})

(defn render-prompt
  "The exact production prompt for one builder purpose, with deterministic runtime context."
  [purpose {:keys [request-id app-dir plan-dir app-marker app-stack dependencies]}]
  (let [instruction (seed-instructions purpose)
        context (str/join "\n"
                          (cond-> [(str "Purpose: " (name purpose))
                                   (str "Cycle: " request-id)
                                   (str "Spec: $HARNESS_CUT_ROOT/" plan-dir "/NN-slug/spec.feature (the only spec; build this product)")
                                   (str "Target stack: " app-stack)
                                   (str "APP_DIR: " app-dir " (write everything under $HARNESS_WORKSPACE_ROOT/" app-dir ")")
                                   "Public seam: $HARNESS_CUT_ROOT/.pi/model/rpc.yaml (JSON-RPC over stdio)"
                                   (str "APP_MARKER: " app-marker)]
                            (seq dependencies)
                            (conj (str "Dependencies available to fetch: " (str/join "; " dependencies)))
                            (= purpose :gate-repair)
                            (conj "Gate log: $HARNESS_VERIFICATION_EVIDENCE_ROOT/gate.log")))
        rendered (str instruction "\n\nDeterministic runtime context:\n" context)
        system (system-instructions purpose)]
    {:system-prompt system
     :system-prompt-digest (prompt/digest system)
     :prompt rendered
     :prompt-digest (prompt/digest rendered)}))

(defn- dependency-hints
  "Pinned coordinates of the engines the product builds on, taken from this checkout's
  submodules. They are dependencies, not answers, so the builder may fetch them."
  [project-root]
  (keep (fn [module]
          (let [sha (str/trim (:out (shell/sh "git" "-C" (str project-root "/" module) "rev-parse" "HEAD")))
                url (str/trim (:out (shell/sh "git" "config" "-f" ".gitmodules"
                                              (str "submodule." module ".url") :dir project-root)))]
            (when (and (re-matches #"[0-9a-f]{40}" sha) (seq url))
              (str module " " url " at " sha))))
        ["orc" "grain"]))

;; ------------------------------------------------------------------ stages

(defn fence
  "Build and prove the clean room with harness/fence.sh, which first refuses unless the
  harness self-check (harness/check.sh --local) is green."
  [project-root request-id]
  (let [rooms (state-dir project-root "rooms")
        room (str rooms "/" request-id)
        evidence (state-dir project-root "cycles" request-id)]
    (.mkdirs (io/file rooms))
    (when (.exists (io/file room))
      (throw (ex-info (str "fence refused: the clean room " room " already exists; a room must be fresh")
                      {:room room})))
    (let [{:keys [exit log log-path]} (run-logged! project-root (str evidence "/fence.log")
                                            "bash" "harness/fence.sh" room)]
      (when-not (and (zero? exit) (str/includes? log "fence OK"))
        (throw (ex-info (str "fence failed (exit " exit "); log " log-path ": " (tail log 600))
                        {:log-path log-path})))
      {:passed true
       :room room
       :room-commit (str/trim (:out (shell/sh "git" "-C" room "rev-parse" "HEAD")))
       :evidence-root evidence
       :log-path log-path})))

(defn- build!
  "One Pi builder session over the clean room, writing only APP_DIR."
  [project-root {:keys [request-id room cfg attempt purpose initial-workspace-root gate-log-dir]}]
  (let [rendered (render-prompt purpose (assoc cfg :request-id request-id
                                               :dependencies (dependency-hints project-root)))
        run-id (format "%s-regenerate-%02d" request-id attempt)
        result (adapters/run-builder!
                {:project-root project-root
                 :cuts-root (.getParent (io/file room))
                 :builder-id #(str "openai-codex/" (or (System/getenv "PI_BUILDER_MODEL") "gpt-6.1-sol"))}
                (merge rendered
                       {:request-id run-id
                        :cut (.getName (io/file room))
                        :scope [(:app-dir cfg)]
                        :execution-backend "host"
                        :target-stack "clojure"
                        :initial-workspace-root initial-workspace-root
                        :verification-evidence-root gate-log-dir
                        :builder-session-ref (str (random-uuid))
                        :builder-sessions-root (state-dir project-root "builder-sessions")
                        :attempt attempt}))
        candidate (io/file (:output-root result) (:app-dir cfg))]
    (when-not (and (.isDirectory candidate) (seq (.list candidate)))
      (throw (ex-info (str "regenerate attempt " attempt " produced no " (:app-dir cfg))
                      {:output-root (:output-root result)})))
    (assoc result
           :attempt attempt
           :subject-digest (subject/digest (:output-root result))
           :prompt-digest (:prompt-digest rendered))))

(defn- install-candidate!
  "Place the builder's APP_DIR inside the clean room, where harness/verify.sh looks for it."
  [room app-dir output-root]
  (let [target (str room "/" app-dir)]
    (shell/sh "rm" "-rf" target)
    (let [{:keys [exit err]} (shell/sh "cp" "-R" (str output-root "/" app-dir) target)]
      (when-not (zero? exit)
        (throw (ex-info (str "could not install the candidate into the room: " err) {}))))
    target))

(defn regenerate [project-root request-id room]
  (let [cfg (config project-root)
        result (build! project-root {:request-id request-id :room room :cfg cfg
                                     :attempt 1 :purpose :regeneration})]
    (install-candidate! room (:app-dir cfg) (:output-root result))
    result))

(defn- gate-once [room evidence attempt]
  (let [log-dir (str evidence "/gate-" (format "%02d" attempt))
        outcome (run-logged! room (str log-dir "/gate.log") "bash" "harness/verify.sh")]
    (assoc outcome :attempt attempt :log-dir log-dir :passed (zero? (:exit outcome)))))

(defn gate
  "Ratify the candidate with harness/verify.sh inside the clean room. A failed gate starts a
  fresh gate-repair builder with only the previous candidate and the gate log, at most
  three regenerate attempts in total."
  [project-root request-id room evidence first-build]
  (let [cfg (config project-root)]
    (loop [attempt 1
           build first-build
           attempts []]
      (let [outcome (gate-once room evidence attempt)
            attempts (conj attempts {:attempt attempt
                                     :subject-digest (:subject-digest build)
                                     :passed (:passed outcome)
                                     :log-path (:log-path outcome)})]
        (cond
          (:passed outcome)
          {:passed true :attempts attempts :output-root (:output-root build)
           :subject-digest (:subject-digest build)}

          (>= attempt max-gate-attempts)
          (throw (ex-info (str "gate failed after " attempt " regenerate attempts; last log "
                               (:log-path outcome) ": " (tail (:log outcome) 600))
                          {:attempts attempts}))

          :else
          (let [next-build (build! project-root {:request-id request-id :room room :cfg cfg
                                                 :attempt (inc attempt) :purpose :gate-repair
                                                 :initial-workspace-root (:output-root build)
                                                 :gate-log-dir (:log-dir outcome)})]
            (install-candidate! room (:app-dir cfg) (:output-root next-build))
            (recur (inc attempt) next-build attempts)))))))

;; ------------------------------------------------------------------ the instrument

(defn- graders-dir [project-root & parts]
  (apply state-dir project-root "graders" parts))

(defn approved-grader
  "The grader an operator approved by exact digest, or nil. A cycle never scores without one."
  [project-root]
  (let [file (io/file (graders-dir project-root "approved.edn"))]
    (when (.isFile file) (read-string (slurp file)))))

(defn approve-grader!
  "Record an operator's approval of a sealed, calibrated grader's exact digest."
  [project-root grader-digest approved-by]
  (let [hex (str/replace (str grader-digest) #"^sha256:" "")
        dir (io/file (graders-dir project-root "sealed" hex))
        manifest-file (io/file dir "manifest.json")]
    (when-not (and (re-matches #"[0-9a-f]{64}" hex) (.isFile manifest-file))
      (throw (ex-info (str "no sealed grader " grader-digest) {:grader-digest grader-digest})))
    (when (str/blank? approved-by)
      (throw (ex-info "approval names its approver" {})))
    (let [approval {:grader-digest (str "sha256:" hex) :path (.getPath dir)
                    :approved-by approved-by :approved-at (str (java.time.Instant/now))}]
      (spit (graders-dir project-root "approved.edn") (pr-str approval))
      approval)))

(defn- next-cycle-number [project-root]
  (let [dirs (keep #(when (re-matches #"\d+" (.getName %)) (parse-long (.getName %)))
                   (or (seq (.listFiles (io/file project-root "harness" "cycles"))) []))]
    (format "%02d" (inc (reduce max 0 dirs)))))

(defn score
  "File one verdict per checklist item with the approved sealed grader, through the legacy
  tools: the checklist is derived into harness/cycles/NN/, the grader drives the candidate
  through its public seam, and harness/score.clj files the verdicts with an honest
  denominator. Without an approved grader the score is refused, never guessed."
  [project-root evidence room]
  (let [{:keys [grader-digest path] :as grader} (approved-grader project-root)]
    (when-not grader
      (throw (ex-info "score refused: no approved sealed grader. A cycle never scores with a grader nobody approved."
                      {})))
    (let [cfg (config project-root)
          cycle-number (next-cycle-number project-root)
          checklist (str "harness/cycles/" cycle-number "/checklist.json")
          derived (run-logged! project-root (str evidence "/checklist.log")
                               "bb" "harness/gen_checklist.clj" "--out" checklist)
          _ (when-not (zero? (:exit derived))
              (throw (ex-info (str "score refused: the checklist could not be derived: " (tail (:log derived) 400)) {})))
          verdicts-file (str evidence "/verdicts.json")
          graded (shell/sh "python3" (str path "/grade.py") (str room "/" (:app-dir cfg) "/start.sh")
                           :dir path)
          _ (spit verdicts-file (:out graded))
          _ (spit (str evidence "/grade.log") (:err graded))
          _ (when-not (zero? (:exit graded))
              (throw (ex-info (str "score failed: the grader did not run: " (tail (:err graded) 400)) {})))
          filed (run-logged! project-root (str evidence "/score.log")
                             "bb" "harness/score.clj" cycle-number "--verdicts" verdicts-file)]
      (when-not (zero? (:exit filed))
        (throw (ex-info (str "score refused by harness/score.clj: " (tail (:log filed) 400)) {})))
      {:passed true
       :cycle-number cycle-number
       :grader-digest grader-digest
       :summary (first (str/split-lines (:log filed)))
       :scores (str "harness/cycles/" cycle-number "/scores.json")})))

(defn scoreboard
  "Report the pass rate and the comparison with the previous cycle through
  harness/scoreboard.clj: shared items, both directions, never a net."
  [project-root evidence cycle-number]
  (let [{:keys [exit log]} (run-logged! project-root (str evidence "/scoreboard.log")
                                        "bb" "harness/scoreboard.clj" cycle-number)]
    (when-not (zero? exit)
      (throw (ex-info (str "scoreboard failed: " (tail log 400)) {})))
    {:passed true :cycle-number cycle-number :report log}))
