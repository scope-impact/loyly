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
  (let [keys ["APP_DIR" "PORT" "PLAN_DIR" "APP_MARKER" "FENCED_PATHS"]
        script (str ". harness/config.sh; "
                    (str/join "; " (map #(str "printf '%s\\n' \"$" % "\"") keys)))
        {:keys [exit out err]} (shell/sh "bash" "-c" script :dir project-root)]
    (when-not (zero? exit)
      (throw (ex-info (str "harness/config.sh could not be read: " (str/trim err)) {})))
    (zipmap [:app-dir :port :plan-dir :app-marker :fenced-paths]
            (str/split-lines out))))

(defn- state-dir [project-root & parts]
  (str/join "/" (into [project-root ".pi/harness/.state"] parts)))

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
        "Gherkin scenario under the plan directory is a requirement, and the Allium files, glossary, and "
        "AGENTS.md beside it explain the domain. Anything the spec does not say is your choice; do not look "
        "for a previous implementation, there is none in the room. "
        "Work test-first: write a failing test for a scenario, make it pass with the smallest change, and "
        "keep the whole suite green. "
        "Done is decided by the gate, not by you. The gate runs APP_DIR/test.sh, which must run the whole "
        "suite and exit non-zero on any failure and must discover at least one test; then it runs "
        "APP_DIR/start.sh PORT, which must start the product serving HTTP on that port in the foreground, "
        "and it must answer within ten seconds of start.sh launching, with the served root page carrying "
        "the APP_MARKER text. Slow builds belong in test.sh, which is untimed, not in start.sh. "
        "Both scripts must be executable and must work from any working directory. "
        "Before finishing, run APP_DIR/test.sh, start APP_DIR/start.sh on a free port, fetch the root page, "
        "and confirm it carries the marker. Never weaken or delete a test to obtain green.")
   :gate-repair
   (str "The gate failed for the candidate in your workspace. Read the gate log, reproduce the failure, "
        "turn it into a failing test where it is a behavior failure, and make the smallest coherent "
        "correction. Then rerun APP_DIR/test.sh, start APP_DIR/start.sh, and confirm the marker is served "
        "within ten seconds. Do not delete, skip, or weaken tests. The spec in the clean room remains "
        "authoritative; the gate log is diagnostic input only.")})

(defn render-prompt
  "The exact production prompt for one builder purpose, with deterministic runtime context."
  [purpose {:keys [request-id app-dir port plan-dir app-marker dependencies]}]
  (let [instruction (seed-instructions purpose)
        context (str/join "\n"
                          (cond-> [(str "Purpose: " (name purpose))
                                   (str "Cycle: " request-id)
                                   (str "Spec: $HARNESS_CUT_ROOT/" plan-dir "/NN-slug/spec.feature")
                                   (str "APP_DIR: " app-dir " (write everything under $HARNESS_WORKSPACE_ROOT/" app-dir ")")
                                   (str "Gate port: " port)
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
                 :builder-id #(str "openai-codex/" (or (System/getenv "PI_BUILDER_MODEL") "gpt-5.6-luna"))}
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

(defn- approved-grader-for [project-root checklist-digest]
  (let [file (io/file (state-dir project-root "graders" "approved" (str checklist-digest ".edn")))]
    (when (.isFile file) (read-string (slurp file)))))

(defn score
  "File one verdict per checklist item with the approved sealed grader. Without an approved
  grader for this exact checklist, the score is refused, never guessed."
  [project-root evidence]
  (let [checklist-path (str evidence "/checklist.json")
        {:keys [exit log]} (run-logged! project-root (str evidence "/checklist.log")
                                 "bb" "harness/gen_checklist.clj" "--out" checklist-path)
        _ (when-not (zero? exit)
            (throw (ex-info (str "score refused: the checklist could not be derived: " (tail log 400)) {})))
        checklist-digest (prompt/digest (slurp checklist-path))
        items (count (re-seq #"\"id\"" (slurp checklist-path)))
        grader (approved-grader-for project-root (subs checklist-digest 7))]
    (when-not grader
      (throw (ex-info (str "score refused: no approved sealed grader covers this checklist ("
                           items " items, " checklist-digest "). A cycle never scores with a grader "
                           "nobody approved.")
                      {:checklist checklist-path :checklist-digest checklist-digest})))
    ;; Grader execution is the next increment; an approved grader is required first.
    (throw (ex-info "score refused: grader execution is not implemented yet" {:grader grader}))))
