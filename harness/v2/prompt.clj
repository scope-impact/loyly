#!/usr/bin/env bb
;; harness/v2/prompt.clj NN OUT-DIR: render the builder prompt of one context's cycle (.pi/spec/
;; decisions.md, "First light") for a person-run `pi` session in the context's clean room.
;; Adapted from harness/regen.clj's prompt text, for one context and its own seam: no other
;; slice, no cycle contract. Writes, outside the room:
;;
;;   OUT-DIR/system.md   appended to pi's system prompt (--append-system-prompt)
;;   OUT-DIR/prompt.md   the task, the session's first message (@OUT-DIR/prompt.md)
;;
;; and prints the sha256 of each, so a session can be tied to the exact text it was given.
;; The engines are pinned as regen.clj pins them: the newest commit the remote holds beneath
;; the orc and grain submodules (their merge-base with origin/HEAD), read from this checkout,
;; or, in a worktree whose submodules are empty, from the main checkout that owns it.
(require '[clojure.java.io :as io] '[clojure.java.shell :as shell] '[clojure.string :as str])

(def root (-> *file* io/file .getCanonicalFile .getParentFile .getParentFile .getParent))
(defn- die [& parts] (binding [*out* *err*] (println (str "prompt: " (apply str parts)))) (System/exit 1))
(defn- git [dir & args] (let [{:keys [exit out]} (apply shell/sh "git" "-C" (str dir) args)] (when (zero? exit) (str/trim out))))

(defn- config
  "APP_DIR, APP_STACK and PLAN_DIR as harness/config.sh declares them for SLICE."
  [slice]
  (let [ks ["APP_DIR" "APP_STACK" "PLAN_DIR" "APP_PROBE"]
        {:keys [exit out err]} (shell/sh "bash" "-c" (str ". harness/config.sh; "
                                                          (str/join "; " (map #(str "printf '%s\\n' \"$" % "\"") ks)))
                                         :dir root :env (assoc (into {} (System/getenv)) "SLICE" slice))]
    (when-not (zero? exit) (die "harness/config.sh could not be read: " err))
    (zipmap [:app-dir :app-stack :plan-dir :app-probe] (str/split-lines out))))

(defn- submodule-root
  "Where the orc and grain submodules are checked out: here, or the main checkout."
  []
  (if (.exists (io/file root "grain" ".git"))
    root
    (some-> (git root "rev-parse" "--git-common-dir") io/file .getCanonicalFile .getParent)))

(defn- pins []
  (let [sub (submodule-root)]
    (for [module ["orc" "grain"]
          :let [sha (some-> sub (io/file module) (git "merge-base" "HEAD" "origin/HEAD"))
                url (git root "config" "-f" ".gitmodules" (str "submodule." module ".url"))]]
      (do (when-not (and sha (re-matches #"[0-9a-f]{40}" sha) url)
            (die "no pin for " module ": its submodule is not checked out here or in the main checkout"))
          {:module module :url url :sha sha}))))

(defn- sha256 [s]
  (format "%064x" (BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))))

(def slice-words
  "What differs between the slices, in the builder's words: :allium, what the context's Allium
  holds beyond its rules, refusals and invariants; :product, the real work it does; :inputs,
  what the candidate is handed besides its store, a sentence of the seam's; :contains, for the
  one candidate that holds the five other contexts (slice 06, spec round 18), how they are in it;
  :identity, what its identity probe (harness/v2/probe-NN.sh) asks. A slice with no entry has
  no probe, and no prompt. There is one event model, every context's event-model/*.edn, and no
  handoff contract (.pi/spec/decisions.md, Spec round 18)."
  {"01" {:allium "the contract `ScenarioReading`"
         :product (str "it reads the spec file it is given with the one "
                       "reading of Gherkin the contract `ScenarioReading` describes, refuses what the Allium refuses, records "
                       "its facts as Grain events, answers its query from its read model, and writes each identity it hands "
                       "out into the spec file.")
         :identity (str "the product must stamp a fresh spec file's unstamped scenario through "
                        "`specification/stamp-slice` and report it through `specification/stamped-items`")}
   "02" {:allium "the contracts `Scoring`, `RateFormat` and `Comparison`"
         :product (str "it folds the other contexts' events it reads from the one store, derives a pinned cut's checklist from the "
                       "reading it is offered, files the approved grader's item verdicts for the gated candidate, files "
                       "the one measurement of every checklist item (an unreached item fails and stays counted), reports "
                       "each measured cycle's scoreboard once through its policy, refuses what the Allium refuses, records "
                       "its facts as Grain events, and answers its queries from its read models.")
         :identity (str "the product must take a stamped scenario and a pinned cut handed in through "
                        "`specification/scenario-stamped` and `cycle-preparation/source-cut-pinned`, derive that cut's "
                        "checklist through `measurement/derive-checklist`, refuse one for a cut never pinned, and report "
                        "the checklist through `measurement/checklist`")}
   "03" {:allium "the contract `ScenarioDigests`, with the enumeration `MoveReason`"
         :product (str "it writes each instrument golden from its scenario, checking the scenario digest it is "
                       "offered against the current scenario digests it is handed (the contract `ScenarioDigests`), "
                       "moves a golden only for one of the three recorded reasons and with a note, refuses what the "
                       "Allium refuses, records its facts as Grain events, and answers its query from its read model.")
         :inputs (str "It is also handed the current scenario digests, a JSON file that `LOYLY_SCENARIO_DIGESTS` names, "
                      "read at every command, never only at boot.")
         :identity (str "the product, handed a scenario's digest through `LOYLY_SCENARIO_DIGESTS`, must write an "
                        "instrument golden from that scenario through `grader-lifecycle/write-instrument-golden`, move "
                        "it for a recorded reason through `grader-lifecycle/move-instrument-golden`, refuse a move "
                        "without a note, and report the golden and its move through `grader-lifecycle/instrument-goldens`")}
   "04" {:allium "the contracts `HarnessSelfCheckRun`, `FenceProbe` and `GateRoom`, the value `Budget` and the config"
         :product (str "it records the stage facts of a regeneration cycle as ORC runtime offers them (the harness "
                       "self-check's result, the fence probe's counts and paths, the regenerated candidate, the gate "
                       "room's result) and guards them: it refuses what the Allium refuses, compares a self-check's "
                       "goldens digest with the one it computes from the instrument goldens it was handed, folds every "
                       "fact into the cycle, concludes each cycle once through its conclude-cycle policy and its stage "
                       "clock, records its facts as Grain events, and answers its queries from its read models. It acts "
                       "on nothing outside its store: it builds no room, runs no git, runs no self-check and boots "
                       "nothing; those results reach it as command params.")
         :inputs (str "It is also handed the repository's scoring tools, the JSON array that `LOYLY_SCORING_TOOL_PATHS` "
                      "holds, read at start.")
         :identity (str "the product must take a pinned cut and an instrument golden handed in through "
                        "`cycle-preparation/source-cut-pinned` and `grader-lifecycle/instrument-golden-written`, open a "
                        "cycle through `regeneration-cycle/record-harness-self-check` at the goldens digest `seam.md` "
                        "defines, refuse a clean room whose fenced path matched nothing, prove one through "
                        "`regeneration-cycle/prove-clean-room`, and report the cycle through "
                        "`regeneration-cycle/cycle-progress`")}
   "05" {:allium "the contract `ProjectRepository`"
         :product (str "it pins each source cut at a commit of the project repository, checking the commit and its "
                       "tree digest against the repository's commits it is handed (the contract `ProjectRepository`), "
                       "never pins a cut identity twice, refuses what the Allium refuses, records each pinned cut as "
                       "the event `cycle-preparation/source-cut-pinned`, which Measurement and the Regeneration cycle "
                       "fold from the one store, and answers its query from its read model. It runs no git and computes no digest.")
         :inputs (str "It is also handed the project repository's commits, a JSON file that `LOYLY_PROJECT_COMMITS` "
                      "names (each commit to its tree digest), read at every command, never only at boot.")
         :identity (str "the product, handed two commits through `LOYLY_PROJECT_COMMITS`, must pin a source cut at "
                        "one of them through `cycle-preparation/pin-source-cut`, refuse to pin that cut again at the "
                        "other, and report the one cut through `cycle-preparation/source-cuts`")}
   "06" {:allium (str "the contracts `OneStore`, `ProjectRepository`, `BuilderSession`, `GateOnTheHost` and "
                      "`TheJudge`, the value `RunBudget` and the config")
         :product (str "it runs one regeneration cycle as one tick of the ORC tree `cycle-runner/cycle.v1`, its "
                       "leaves in the order `seam.md` lists them (\"The tree\"): it reads the project repository at the "
                       "cut with git, stamps each selected slice, pins the cut, derives the checklist, holds the run to "
                       "the sealed grader, runs the harness self-check, builds and probes the clean room, renders the "
                       "builder instruction, runs a builder session through the provided builder host and waits for it, "
                       "installs a copy of the candidate in a gate room and gates it, repairs within the budget, grades "
                       "each checklist item with the sealed grader `cycle-runner/grade.v1`, and files the verdicts and "
                       "the score. Each leaf records its effect's result into the context that owns it, by that "
                       "context's own command on the one store. It concludes the run as its cycle concludes, halts "
                       "loudly when an effect cannot be performed or a refusal comes before the cycle, resumes every "
                       "run in progress on its store when it starts, records its own facts as Grain events, and answers "
                       "its queries from its read models. It owns no rule of the five contexts: a refusal it shows is "
                       "the context's, as heard.")
         :contains (str "The five contexts specification, cycle-preparation, grader-lifecycle, regeneration-cycle and "
                        "measurement are in this one candidate and are built here, on its one Grain store: their specs "
                        "are in the room (each `.pi/spec/<context>/`), their rules, event models and queries are in the "
                        "product, and their methods (each context's `seam.md`) are methods of this same candidate. "
                        "There is no previous candidate of theirs to look for. There is no relay, hand-in method or "
                        "handoff contract: each event is appended once, by its producer's command, and every context "
                        "that folds it reads it from the one store. Your own tests need no stand-in for them: the "
                        "fixture repository, the stub self-check, the stub builder host, the stub model and the fixture "
                        "candidates belong to your suite, never to the product.")
         :inputs (str "It is also handed, and reads at every `cycle-runner/start-run`, never only at boot: "
                      "`LOYLY_RUNS_DIR`, `LOYLY_PROJECT_REPO`, `LOYLY_HARNESS_CONFIG`, `LOYLY_SELF_CHECK_CMD`, "
                      "`LOYLY_STORE_CHECK_CMD`, `LOYLY_BUILDER_HOST`, `LOYLY_GATE_CMD`, `LOYLY_BUILDER_IMAGES`, "
                      "`LOYLY_BUILDER_INSTRUCTION`, `LOYLY_MODEL_API_BASE`, `LOYLY_RENDER_MODEL`, `LOYLY_GRADER_MODEL`, "
                      "`LOYLY_GRADER_RUBRIC`, `LOYLY_SCORING_TOOL_PATHS` and the optional "
                      "`OTEL_EXPORTER_OTLP_ENDPOINT` (`seam.md`, \"Handed in\"). `cycle-runner/start-run` responds once "
                      "the run concluded or halted, and a `cycle-runner/run-progress` sent meanwhile is answered "
                      "without waiting for it.")
         :identity (str "the product, handed a fixture project repository whose commit holds a one-slice plan with one "
                        "stamped scenario, a stub self-check, a stub builder host and a grader rubric, must refuse "
                        "`cycle-runner/start-run` at a hex string that is no commit of the repository (`not-a-commit`), "
                        "refuse a start whose configuration names a builder image `LOYLY_BUILDER_IMAGES` does not hold "
                        "(`builder-image-missing`), and run a start whose approved grader digests do not name the "
                        "rubric's through the stamp, the pin and the checklist, each recorded into its context, to a "
                        "halt at the `grader` effect, `refused-before-the-cycle`, its detail naming that grader digest, "
                        "reported through `cycle-runner/run-progress`, the self-check and the builder never launched")}})

(defn render [slice]
  (let [{:keys [app-dir app-stack plan-dir app-probe]} (config slice)
        _ (when-not (= app-probe (str "harness/v2/probe-" slice ".sh"))
            (die "slice " slice " names no context: no " plan-dir "/" slice "-* link"))
        {:keys [allium product inputs contains identity]} (slice-words slice)
        _ (when-not identity (die "slice " slice " has no identity probe yet"))
        link (.getName (io/file app-dir))
        ctx (.getName (.getCanonicalFile (io/file root plan-dir link)))
        spec (str ".pi/spec/" ctx)
        [orc grain] (pins)
        coord (fn [{:keys [url sha]}] (str url " at " sha))
        system (str "You are a clean-room regeneration builder for one context of the spec, `" ctx "` (slice " slice "). "
                    "Your working directory is the clean room: read only inside it, and never outside it. "
                    "Write only under " app-dir "/. Use the bash tool to build and test.")
        prompt
        (str
         "# Build the " ctx " context (slice " slice ")\n\n"
         "Build the product described by the spec in this clean room. The spec is authoritative. Every stamped "
         "Gherkin scenario (`@item-" slice "-NNN`) in `" plan-dir "/" link "/spec.feature` (the same file as `"
         spec "/spec.feature`) is a requirement. Beside it, `" spec "/allium/` (the rules, their refusals as "
         "`requires`, the invariants and " allium "), `" spec "/event-model/*.edn` (the Grain "
         "Event Model: commands, events, read models, queries and their schemas), `" spec "/domain-design.md`, "
         "`.pi/model/GLOSSARY.md` and `AGENTS.md` explain the domain. `" spec "/drafts.feature` holds scenarios "
         "not yet accepted: they are not requirements. Anything the spec does not say is your choice. There is "
         "no previous implementation in the room and you must not look for one elsewhere: the room is your only "
         "input.\n\n"
         "## The product\n\n"
         "One context, rebuilt from scratch. It does real work: " product " A result returned without doing the work (a canned answer, a fixed id, a "
         "number that is not in the store) is a defect the score will find.\n\n"
         "## The one event model\n\n"
         "There is one event model: the union of every context's `.pi/spec/<context>/event-model/*.edn` in the "
         "room, this context's and every other's. The other contexts' events this context folds are defined in "
         "their producers' fragments, and reach it on the one Grain store, appended by the producer's own command; "
         "there is no hand-in method and no handoff contract.\n\n"
         (when contains
           (str "## The five contexts in it\n\n" contains "\n\n"))
         "## The stack\n\n"
         "Exactly: " app-stack ".\n\n"
         "- ORC: " (coord orc) "\n"
         "- Grain: " (coord grain) ", one `:git/url`/`:git/sha` dependency per project you use, with "
         "`:deps/root`, for example `projects/grain-core-v2` (commands, queries, read models, the event store), "
         "`projects/grain-event-store-sqlite-v3` (the SQLite store) and `projects/grain-code-agent-tools` (the "
         "event-model validator and its boot guard).\n\n"
         "Register the room's one event model as given: copy every context's event model in the room (each `"
         ".pi/spec/<context>/event-model/*.edn`) byte for byte into `event-model/<context>/` in `" app-dir
         "`, read them only there (the build reads no file outside its own folder to run), merge the fragments "
         "into the one event model, and register it with Grain's `register-event-model!`. Never edit them. Implement each "
         "command, query, read model and event of the model as the Grain building block of the same name, the "
         "event's schema being the model's schema, and make start refuse, through Grain's boot guard "
         "(`verify-or-throw!`), when the running blocks and the model are apart.\n\n"
         "Run ORC on the product's own Grain store, the one `LOYLY_GRAIN_STORE` names, with the same tenant: never on a "
         "store of its own. Each command method runs as the ORC workflow named after the flow its command "
         "belongs to in the event model (`:flows`); a query method runs none. `" spec "/seam.md` names them.\n\n"
         "## The seam and the gate\n\n"
         "The public seam is `" spec "/seam.md`: JSON-RPC 2.0 over stdio, one method per command and query of "
         "the event model, named as in the model, and the Grain store the candidate is handed through "
         "`LOYLY_GRAIN_STORE`." (when inputs (str " " inputs)) "\n\n"
         "Done is decided by the gate and the score, not by you. The gate runs `" app-dir "/test.sh`, which must "
         "run the whole suite, exit non-zero on any failure, and discover at least one real test. Then it runs `"
         app-dir "/start.sh` with `LOYLY_GRAIN_STORE` set to a fresh file, which must start the product speaking "
         "the seam (one JSON object per line, one response per request carrying its id, nothing else on stdout; "
         "diagnostics go to stderr). Within thirty seconds of start.sh launching, " identity
         ": identity is proved by domain behaviour, not by a name. Then the gate "
         "reads the Grain store directly, not through your seam: it must hold your events, every event an event "
         "of the one event model with exactly that event's fields, or ORC's own, and each event a command produced written "
         "inside a run of its ORC workflow (or, for another context's command a leaf of this context's flow "
         "issues, of that flow's workflow), in that same store, that completed with success: a store without "
         "ORC's workflow runs fails the gate. Where the event model's flows name the leaf that writes an event "
         "(`:leaf`), that event must lie inside the ORC leaf of that name, in its flow's tick or a child tick that "
         "tick delegated: a one-node wrapper around orchestration of your own fails the gate. Each ORC tick must "
         "complete exactly once (one terminal "
         "`sheet/tree-tick-completed` and one `sheet/execution-traced` per tick), and the store must hold "
         "Grain's `grain/todo-processor-checkpoint` events: start every Grain todo processor, ORC's included, "
         "with its `:processor-name`, or Grain neither checkpoints it nor drops a redelivered event. "
         "Slow builds belong in test.sh, which is untimed, not in "
         "start.sh. Both scripts must be executable and work from any working directory. The gate runs them "
         "on the host, macOS with a BSD userland, not in your Linux VM: use only portable shell and tools in "
         "them (no GNU-only options such as `stat -c`, `sed -i` without a suffix argument, `readlink -f`, "
         "`date -d` or `timeout`).\n\n"
         "## How to work\n\n"
         "For each stamped scenario, write a failing test that drives it through the public seam and names its "
         "`@item` id, make it pass with the smallest real change, and keep the whole suite green. Finish only "
         "when every stamped scenario has a passing test naming its id. Record any you could not carry in `"
         app-dir "/UNCARRIED.md`, one line each, `- @item-" slice "-NNN: reason`; name an item there only while "
         "it is not carried. Before finishing, run `" app-dir "/test.sh`, then start `" app-dir "/start.sh` with "
         "`LOYLY_GRAIN_STORE` set to a new file, play the gate's identity check by hand over stdin, and confirm "
         "the responses and their effects. Never weaken or "
         "delete a test to obtain green.\n")]
    {:system system :prompt prompt}))

(let [[slice out] *command-line-args*]
  (when-not (and slice out (re-matches #"[0-9]+" slice)) (die "usage: bb harness/v2/prompt.clj NN OUT-DIR"))
  (let [{:keys [system prompt]} (render slice)]
    (.mkdirs (io/file out))
    (spit (io/file out "system.md") system)
    (spit (io/file out "prompt.md") prompt)
    (println (str "system.md sha256:" (sha256 system) "  " (io/file out "system.md")))
    (println (str "prompt.md sha256:" (sha256 prompt) "  " (io/file out "prompt.md")))))
