(ns loyly.pi-harness.core.legacy-cycle
  "The regeneration cycle in the legacy shape: fence, regenerate, gate, score, scoreboard.
  ORC owns the order, the retries, and the prompts; each stage calls the legacy tool that
  does that job (harness/fence.sh, a Pi builder session, harness/verify.sh,
  harness/score.clj, harness/scoreboard.clj). The fence and the gate feed the build loop;
  the score and the scoreboard never do."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
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
  {:slice-regeneration
   "You are a clean-room regeneration builder for one slice. Read only the clean room at $HARNESS_CUT_ROOT. Your workspace at $HARNESS_WORKSPACE_ROOT holds the candidate the earlier slice builders of this cycle left, or nothing if you are first; extend it there. Use the bash tool to build and test it."
   :gate-repair
   "You are a clean-room gate-repair builder. Read the clean room at $HARNESS_CUT_ROOT and the failed gate's log at $HARNESS_VERIFICATION_EVIDENCE_ROOT. Repair the candidate under $HARNESS_WORKSPACE_ROOT and use the bash tool to prove the repair."})

(def ^:private spec-is-authoritative
  (str "Rebuild the product described by the spec in the clean room. The spec is authoritative: every "
        "stamped Gherkin scenario (@item-NN-SSS) under the plan directory is a requirement, and the Allium "
        "files, glossary, and AGENTS.md beside it explain the domain. Anything the spec does not say is your "
        "choice; do not look for a previous implementation, there is none in the room: the only candidate you "
        "may see is this cycle's, in your workspace. "))

(def ^:private real-harness
  (str "The product is a real harness, not a facade. Its stages do real work: the fence builds a clean "
        "room with the legacy tools in the room's harness/ directory, the regenerate stage runs a real "
        "builder session, the gate boots the candidate and runs its suite, the score files verdicts, and "
        "the scoreboard compares cycles; each stage runs as an ORC workflow node and its facts are recorded "
        "through Grain, whose durability you use rather than reinvent. A result that is returned without "
        "doing the work (a canned stage list, a fixed id, a status that never ran) is a defect the score "
        "will find, and it fails every scenario that depends on it. "))

(def ^:private gate-contract
  (str "Done is decided by the gate and the score, not by you. The gate runs APP_DIR/test.sh, which must "
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
        "request on stdin, and confirm the response. Never weaken or delete a test to obtain green."))

(def ^:private slice-work
  (str "You carry one slice, named in the runtime context. Read every scenario of that slice, then only the "
       "contracts it meets: the public seam (.pi/model/rpc.yaml), the Grain Event Model "
       "(.pi/model/regeneration_event_model.clj), the glossary, and the Allium file for your slice's domain. "
       "Open another slice's feature only when one of your scenarios depends on it; do not read the whole spec "
       "up front, because everything you read is carried through every later turn. Earlier slice builders of this cycle carried the "
       "slices before yours in plan order and left their candidate in your workspace: extend it, keep every "
       "existing test green, and do not rewrite their work without a failing test that requires it. For each "
       "scenario of your slice, write a failing test that drives it through the public seam and names its "
       "@item id, make it pass with the smallest real change, and keep the whole suite green. Finish only when "
       "every stamped scenario of your slice has a passing test naming its id; record any you could not carry "
       "under a heading for your slice in APP_DIR/UNCARRIED.md, one line each in the form "
       "`- @item-NN-SSS: reason`, keeping earlier slices' entries; ORC reads those lines and asks you to go "
       "on, so name an item there only while it is not carried. "))

(def ^:private seed-instructions
  {:slice-regeneration
   (str spec-is-authoritative real-harness slice-work gate-contract)
   :gate-repair
   (str "The gate failed for the candidate in your workspace. Read the gate log, reproduce the failure, "
        "turn it into a failing test where it is a behavior failure, and make the smallest coherent "
        "correction. Then rerun APP_DIR/test.sh, start APP_DIR/start.sh, and confirm it answers cycle-contract "
        "over stdio within thirty seconds. Do not delete, skip, or weaken tests. The spec in the clean room remains "
        "authoritative; the gate log is diagnostic input only.")})

(defn render-prompt
  "The exact production prompt for one builder purpose, with deterministic runtime context."
  [purpose {:keys [request-id app-dir plan-dir app-marker app-stack dependencies slice]}]
  (let [instruction (seed-instructions purpose)
        context (str/join "\n"
                          (cond-> [(str "Purpose: " (name purpose))
                                   (str "Cycle: " request-id)
                                   (str "Spec: $HARNESS_CUT_ROOT/" plan-dir "/NN-slug/spec.feature (the only spec; build this product)")
                                   (str "Target stack: " app-stack)
                                   (str "APP_DIR: " app-dir " (write everything under $HARNESS_WORKSPACE_ROOT/" app-dir ")")
                                   "Public seam: $HARNESS_CUT_ROOT/.pi/model/rpc.yaml (JSON-RPC over stdio)"
                                   (str "APP_MARKER: " app-marker)]
                            slice
                            (conj (str "Slice: $HARNESS_CUT_ROOT/" plan-dir "/" slice "/spec.feature (the slice you carry)"))
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

(def max-slice-continuations 3)

(defn continuation-prompt
  "The task prompt that continues a slice builder's own session, naming only the items of its
  slice it still lists uncarried. It keeps the session's system prompt."
  [uncarried]
  (let [text (str "Your turn ended with these items of your slice still listed in APP_DIR/UNCARRIED.md: "
                  (str/join ", " uncarried) ". Carry them now. For each, write a failing test that drives "
                  "it through the public seam and names its id, make it pass with the smallest real change, "
                  "keep the whole suite green, and remove its line from APP_DIR/UNCARRIED.md. Leave a line "
                  "only for an item you truly cannot carry, with the reason. Then rerun APP_DIR/test.sh, "
                  "start APP_DIR/start.sh, and confirm it answers cycle-contract over stdio. "
                  "Done is decided by the gate and the score, not by you.")
        system (system-instructions :slice-regeneration)]
    {:system-prompt system
     :system-prompt-digest (prompt/digest system)
     :prompt text
     :prompt-digest (prompt/digest text)}))

(defn slice-uncarried
  "The items of SLICE (NN-slug) that the builder's own APP_DIR/UNCARRIED.md lists as
  `- @item-NN-SSS: reason` lines. Ids mentioned in prose, or another slice's, are not listed."
  [output-root app-dir slice]
  (let [file (io/file output-root app-dir "UNCARRIED.md")
        line (re-pattern (str "(?m)^\\s*[-*]\\s*`?(@item-" (subs slice 0 2) "-\\d{3})"))]
    (if (.isFile file)
      (->> (re-seq line (slurp file)) (map second) distinct sort vec)
      [])))

(defn- dependency-hints
  "Pinned coordinates of the engines the product builds on, taken from this checkout's
  submodules. They are dependencies, not answers, so the builder may fetch them. A local
  commit that never reached the remote cannot be fetched, so the pin is the newest commit
  the remote holds beneath the checkout: its merge-base with origin/HEAD."
  [project-root]
  (keep (fn [module]
          (let [git #(str/trim (:out (apply shell/sh "git" "-C" (str project-root "/" module) %&)))
                sha (git "merge-base" "HEAD" "origin/HEAD")
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
  [project-root {:keys [request-id room cfg attempt purpose initial-workspace-root gate-log-dir slice
                       continuation uncarried resume-session-file builder-session-ref]}]
  (let [rendered (if continuation
                   (continuation-prompt uncarried)
                   (render-prompt purpose (assoc cfg :request-id request-id :slice slice
                                                 :dependencies (dependency-hints project-root))))
        run-id (cond-> (format "%s-regenerate-%02d" request-id attempt)
                 slice (str "-" slice)
                 continuation (str "-c" continuation))
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
                        :builder-session-ref (or builder-session-ref (str (random-uuid)))
                        :resume-session-file resume-session-file
                        :builder-sessions-root (state-dir project-root "builder-sessions")
                        :attempt attempt}))
        candidate (io/file (:output-root result) (:app-dir cfg))]
    (when-not (and (.isDirectory candidate) (seq (.list candidate)))
      (throw (ex-info (str "regenerate attempt " attempt (when slice (str " for slice " slice))
                           " produced no " (:app-dir cfg))
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

(defn plan-slices
  "The slices of the plan, in plan order: every directory under PLAN_DIR with a spec.feature."
  [project-root plan-dir]
  (->> (.listFiles (io/file project-root plan-dir))
       (filter #(.isFile (io/file % "spec.feature")))
       (map #(.getName %))
       sort
       vec))

(defn- carry-slice!
  "One fresh builder for SLICE, continued in its own session while it lists items of its slice
  uncarried: at most max-slice-continuations times, ending early on a continuation that leaves
  the candidate unchanged. The listing is the builder's claim; the gate and the score decide."
  [project-root {:keys [cfg slice] :as opts}]
  (let [first-build (build! project-root (assoc opts :attempt 1 :purpose :slice-regeneration))
        uncarried #(slice-uncarried (:output-root %) (:app-dir cfg) slice)]
    (loop [build first-build
           continuations 0]
      (let [left (uncarried build)]
        (if (or (empty? left) (= continuations max-slice-continuations))
          (assoc build :continuations continuations :uncarried left)
          (let [n (inc continuations)
                next-build (build! project-root
                                   (assoc opts :attempt 1 :purpose :slice-regeneration
                                          :continuation n :uncarried left
                                          :resume-session-file (:session-file build)
                                          :builder-session-ref (:builder-session-id first-build)
                                          :initial-workspace-root (:output-root build)))]
            (if (= (:subject-digest next-build) (:subject-digest build))
              (assoc build :continuations n :uncarried left)
              (recur next-build n))))))))

(defn regenerate
  "The first attempt: one fresh builder per slice, in plan order. Each slice builder reads the
  clean room and the candidate the earlier slice builders of this cycle left, nothing else, and
  must change it. The gate then runs once, on what the last slice builder left."
  [project-root request-id room]
  (let [cfg (config project-root)
        slices (plan-slices room (:plan-dir cfg))]
    (when (empty? slices)
      (throw (ex-info (str "regenerate refused: no slice under " (:plan-dir cfg) " in the room") {})))
    (let [builds (reduce
                  (fn [builds slice]
                    (let [previous (peek builds)
                          build (carry-slice! project-root {:request-id request-id :room room :cfg cfg
                                                            :slice slice
                                                            :initial-workspace-root (:output-root previous)})]
                      (when (= (:subject-digest build) (:subject-digest previous))
                        (throw (ex-info (str "regenerate: the builder for slice " slice
                                             " left the candidate unchanged")
                                        {:slice slice})))
                      (conj builds (assoc build :slice slice))))
                  []
                  slices)
          last-build (peek builds)]
      (install-candidate! room (:app-dir cfg) (:output-root last-build))
      (assoc last-build
             :slices (mapv #(select-keys % [:slice :builder-session-id :subject-digest :continuations :uncarried]) builds)))))

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

(defn grader-approval
  "Validate that a digest names a sealed, calibrated grader and return the approval to record.
  The approval itself is a Grain fact (approve-grader); nothing is written here."
  [project-root grader-digest approved-by]
  (let [hex (str/replace (str grader-digest) #"^sha256:" "")
        dir (io/file (graders-dir project-root "sealed" hex))
        manifest-file (io/file dir "manifest.json")]
    (when-not (and (re-matches #"[0-9a-f]{64}" hex) (.isFile manifest-file))
      (throw (ex-info (str "no sealed grader " grader-digest) {:grader-digest grader-digest})))
    (when (str/blank? approved-by)
      (throw (ex-info "approval names its approver" {})))
    (let [manifest (json/read-str (slurp manifest-file) :key-fn keyword)
          entry (or (:entry manifest) "grade.py")]
      (when-not (.isFile (io/file dir entry))
        (throw (ex-info (str "sealed grader " grader-digest " has no " entry) {})))
      {:grader-digest (str "sha256:" hex)
       :entry entry
       :items (vec (:items manifest))
       :approved-by approved-by
       :approved-at (str (java.time.Instant/now))})))

(defn- run-grader!
  "Run one sealed grader against the candidate's start.sh and return its verdicts."
  [project-root {:keys [grader-digest entry]} start evidence]
  (let [path (graders-dir project-root "sealed" (str/replace grader-digest #"^sha256:" ""))
        tag (subs (str/replace grader-digest #"^sha256:" "") 0 12)
        ;; The candidate inherits the grader's environment through the seam client, so it is told
        ;; which repository it works on (rpc.yaml: environment.PI_HARNESS_PROJECT_ROOT).
        graded (shell/sh "python3" (str path "/" entry) start :dir path
                         :env (assoc (into {} (System/getenv))
                                     "PI_HARNESS_PROJECT_ROOT" project-root
                                     "GRADE_SOURCE_COMMIT"
                                     (str/trim (:out (shell/sh "git" "-C" project-root "rev-parse" "HEAD")))))]
    (spit (str evidence "/grade-" tag ".log") (:err graded))
    (when-not (zero? (:exit graded))
      (throw (ex-info (str "score failed: grader " grader-digest " did not run: "
                           (tail (:err graded) 400)) {})))
    (json/read-str (:out graded))))

(defn- next-cycle-number [project-root]
  (let [dirs (keep #(when (re-matches #"\d+" (.getName %)) (parse-long (.getName %)))
                   (or (seq (.listFiles (io/file project-root "harness" "cycles"))) []))]
    (format "%02d" (inc (reduce max 0 dirs)))))

(defn score
  "File one verdict per checklist item with every approved sealed grader, through the legacy
  tools: the checklist is derived into harness/cycles/NN/, each grader drives the candidate
  through its public seam, and harness/score.clj files the verdicts with an honest
  denominator. Without an approved grader the score is refused, never guessed; two graders
  filing the same item is refused, never merged."
  [project-root evidence room graders]
  (when (empty? graders)
    (throw (ex-info "score refused: no approved sealed grader. A cycle never scores with a grader nobody approved."
                    {})))
  (let [cfg (config project-root)
        cycle-number (next-cycle-number project-root)
        checklist (str "harness/cycles/" cycle-number "/checklist.json")
        derived (run-logged! project-root (str evidence "/checklist.log")
                             "bb" "harness/gen_checklist.clj" "--out" checklist)
        _ (when-not (zero? (:exit derived))
            (throw (ex-info (str "score refused: the checklist could not be derived: " (tail (:log derived) 400)) {})))
        start (str room "/" (:app-dir cfg) "/start.sh")
        verdicts (vec (mapcat #(run-grader! project-root % start evidence) graders))
        twice (keep (fn [[id n]] (when (> n 1) id)) (frequencies (map #(get % "id") verdicts)))
        _ (when (seq twice)
            (throw (ex-info (str "score refused: more than one grader filed " (str/join ", " (sort twice))) {})))
        verdicts-file (str evidence "/verdicts.json")
        _ (spit verdicts-file (json/write-str verdicts))
        filed (run-logged! project-root (str evidence "/score.log")
                           "bb" "harness/score.clj" cycle-number "--verdicts" verdicts-file)]
    (when-not (zero? (:exit filed))
      (throw (ex-info (str "score refused by harness/score.clj: " (tail (:log filed) 400)) {})))
    {:passed true
     :cycle-number cycle-number
     :grader-digests (mapv :grader-digest graders)
     :summary (first (str/split-lines (:log filed)))
     :scores (str "harness/cycles/" cycle-number "/scores.json")}))

(defn scoreboard
  "Report the pass rate and the comparison with the previous cycle through
  harness/scoreboard.clj: shared items, both directions, never a net."
  [project-root evidence cycle-number]
  (let [{:keys [exit log]} (run-logged! project-root (str evidence "/scoreboard.log")
                                        "bb" "harness/scoreboard.clj" cycle-number)]
    (when-not (zero? exit)
      (throw (ex-info (str "scoreboard failed: " (tail log 400)) {})))
    {:passed true :cycle-number cycle-number :report log}))
