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
  holds beyond its rules, refusals and invariants; :product, the real work it does; :handoffs,
  the producers whose handoff contracts it consumes (each .pi/spec/<producer>/event-model/
  handoffs.edn, the only file of a producer in the room), with how they reach the candidate;
  :inputs, what the candidate is handed besides its store, a sentence of the seam's; :identity,
  what its identity probe (harness/v2/probe-NN.sh) asks. A slice with no entry has no probe,
  and no prompt."
  {"01" {:allium "the contract `ScenarioReading`"
         :product (str "it reads the spec file it is given with the one "
                       "reading of Gherkin the contract `ScenarioReading` describes, refuses what the Allium refuses, records "
                       "its facts as Grain events, answers its query from its read model, and writes each identity it hands "
                       "out into the spec file.")
         :identity (str "the product must stamp a fresh spec file's unstamped scenario through "
                        "`specification/stamp-slice` and report it through `specification/stamped-items`")}
   "02" {:allium "the contracts `Scoring`, `RateFormat` and `Comparison`"
         :product (str "it takes the handoffs other contexts hand it, derives a pinned cut's checklist from the "
                       "reading it is offered, files the approved grader's item verdicts for the gated candidate, files "
                       "the one measurement of every checklist item (an unreached item fails and stays counted), reports "
                       "each measured cycle's scoreboard once through its policy, refuses what the Allium refuses, records "
                       "its facts as Grain events, and answers its queries from its read models.")
         :handoffs ["specification" "cycle-preparation" "regeneration-cycle"]
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
                        "without a note, and report the golden and its move through `grader-lifecycle/instrument-goldens`")}})

(defn render [slice]
  (let [{:keys [app-dir app-stack plan-dir app-probe]} (config slice)
        _ (when-not (= app-probe (str "harness/v2/probe-" slice ".sh"))
            (die "slice " slice " names no context: no " plan-dir "/" slice "-* link"))
        {:keys [allium product handoffs inputs identity]} (slice-words slice)
        _ (when-not identity (die "slice " slice " has no identity probe yet"))
        contracts (str/join ", " (map #(str "`.pi/spec/" % "/event-model/handoffs.edn`") handoffs))
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
         (when (seq handoffs)
           (str "## The handoffs it consumes\n\n"
                "Other contexts are not in the room and are not built here. The events this context consumes from "
                "them are defined by their contracts, " contracts ", the only files of those contexts in the room. "
                "Until the contexts are assembled, their facts reach your product through its seam: one method per "
                "consumed event, named as the event, its params the contract's schema; the product appends exactly "
                "that event to its Grain store, as the producer would, and its read models fold it. `" spec
                "/seam.md` says how.\n\n"))
         "## The stack\n\n"
         "Exactly: " app-stack ".\n\n"
         "- ORC: " (coord orc) "\n"
         "- Grain: " (coord grain) ", one `:git/url`/`:git/sha` dependency per project you use, with "
         "`:deps/root`, for example `projects/grain-core-v2` (commands, queries, read models, the event store), "
         "`projects/grain-event-store-sqlite-v3` (the SQLite store) and `projects/grain-code-agent-tools` (the "
         "event-model validator and its boot guard).\n\n"
         "Register the room's event model as given: load `" spec "/event-model/*.edn` from the room itself (the "
         "candidate runs at `" app-dir "`, so they are at `../../" spec "/event-model/`), merge the fragments"
         (when (seq handoffs) " with the consumed events of the handoff contracts above") ", and "
         "register the area with Grain's `register-event-model!`. Never copy or edit them. Implement each "
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
         "of the model" (when (seq handoffs) " or a handoff it consumes") " with exactly that event's fields, or ORC's own, and each event a command produced written "
         "inside a run of its ORC workflow, in that same store, that completed with success: a store without "
         "ORC's workflow runs fails the gate. Slow builds belong in test.sh, which is untimed, not in "
         "start.sh. Both scripts must be executable and work from any working directory.\n\n"
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
