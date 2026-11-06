#!/usr/bin/env bb
;; One regeneration cycle in the legacy shape, for the product harness/config.sh names:
;;
;;   bb harness/regen.clj <run-id>
;;
;;   fence → regenerate → gate → score → scoreboard
;;
;; Each step is the legacy tool for its job. This script only does what the legacy README
;; left to a person: it points a fresh Pi builder at the clean room (one per slice, in plan
;; order, continued in its own session while it lists its slice's items uncarried), repairs
;; a failed gate with a fresh builder, and runs the sealed graders the operator approved in
;; harness/graders.approved.json. The builder never sees the score.
(ns regen
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def root (-> *file* io/file .getCanonicalFile .getParentFile .getParent))
(def state (or (not-empty (System/getenv "PI_HARNESS_STATE_ROOT")) (str root "/.pi/harness/.state")))
(def max-gate-attempts 3)
(def max-slice-continuations 3)
(def builder-host
  "The provided durable Pi builder host ORC launches once per builder tick. Not built yet."
  ".pi/harness/builder-host/host.mjs")

(defn- say [& parts] (binding [*out* *err*] (println (str "[" (subs (str (java.time.LocalTime/now)) 0 8) "] " (apply str parts)))))
(defn- die [& parts] (binding [*out* *err*] (println (str "regen: " (apply str parts)))) (System/exit 1))
(defn- tail [text n] (let [t (str/trim (str text))] (if (> (count t) n) (subs t (- (count t) n)) t)))

(defn- run-logged!
  "Run a command in DIR, keep its whole output as evidence in LOG, and return exit and output."
  [dir log & command]
  (let [{:keys [exit out err]} (apply shell/sh (concat command [:dir dir]))]
    (io/make-parents log)
    (spit log (str out err))
    {:exit exit :log (str out err) :log-path log}))

(defn- config
  "The values harness/config.sh declares, read by bash so the file stays the only source."
  []
  (let [ks ["APP_DIR" "PLAN_DIR" "APP_MARKER" "APP_STACK"]
        {:keys [exit out err]} (shell/sh "bash" "-c" (str ". harness/config.sh; "
                                                          (str/join "; " (map #(str "printf '%s\\n' \"$" % "\"") ks)))
                                         :dir root)]
    (when-not (zero? exit) (die "harness/config.sh could not be read: " err))
    (zipmap [:app-dir :plan-dir :app-marker :app-stack] (str/split-lines out))))

(defn- hex [^bytes bs] (format "%064x" (BigInteger. 1 bs)))
(defn digest [value]
  (str "sha256:" (hex (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (str value) "UTF-8")))))

(defn subject-digest
  "Digest of every file under DIR, by relative path and content, in path order."
  [dir]
  (let [base (.toPath (.getCanonicalFile (io/file dir)))
        md (java.security.MessageDigest/getInstance "SHA-256")
        sep (byte-array [(byte 0)])]
    (doseq [f (->> (file-seq (io/file dir)) (filter #(.isFile %))
                   (sort-by #(str (.relativize base (.toPath (.getCanonicalFile %))))))]
      (.update md (.getBytes (str/replace (str (.relativize base (.toPath (.getCanonicalFile f)))) "\\" "/") "UTF-8"))
      (.update md sep)
      (.update md (java.nio.file.Files/readAllBytes (.toPath f)))
      (.update md sep))
    (hex (.digest md))))

;; ------------------------------------------------------------------ prompts

(def system-instructions
  {:slice-regeneration
   "You are a clean-room regeneration builder for one slice. Read only the clean room at $HARNESS_CUT_ROOT. Your workspace at $HARNESS_WORKSPACE_ROOT holds the candidate the earlier slice builders of this cycle left, or nothing if you are first; extend it there. Use the bash tool to build and test it."
   :gate-repair
   "You are a clean-room gate-repair builder. Read the clean room at $HARNESS_CUT_ROOT and the failed gate's log at $HARNESS_VERIFICATION_EVIDENCE_ROOT. Repair the candidate under $HARNESS_WORKSPACE_ROOT and use the bash tool to prove the repair."})

(def spec-is-authoritative
  (str "Build the product described by the spec in the clean room. The spec is authoritative: every "
        "stamped Gherkin scenario (@item-NN-SSS) under the plan directory is a requirement, and the Allium "
        "files, glossary, and AGENTS.md beside it explain the domain. Anything the spec does not say is your "
        "choice; do not look for a previous implementation, there is none in the room: the only candidate you "
        "may see is this cycle's, in your workspace. "))

(def real-harness
  (str "The product is a real harness, not a facade. Its stages do real work: the fence builds a clean "
        "room with the legacy tools in the room's harness/ directory, the regenerate stage runs a real "
        "builder session, the gate boots the candidate and runs its suite, the score files verdicts, and "
        "the scoreboard compares cycles; each stage runs as an ORC workflow node and its facts are recorded "
        "through Grain, whose durability you use rather than reinvent. A result that is returned without "
        "doing the work (a canned stage list, a fixed id, a status that never ran) is a defect the score "
        "will find, and it fails every scenario that depends on it. "))

(def gate-contract
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

(def slice-work
  (str "You carry one slice, named in the runtime context. Read every scenario of that slice, then only the "
       "contracts it meets: the public seam (.pi/model/affordances.yaml), the Grain Event Model "
       "(.pi/model/event-model/), the glossary, and the Allium file for your slice's domain. "
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

(def seed-instructions
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
                                   "Public seam: $HARNESS_CUT_ROOT/.pi/model/affordances.yaml (JSON-RPC over stdio)"
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
     :system-prompt-digest (digest system)
     :prompt rendered
     :prompt-digest (digest rendered)}))

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
     :system-prompt-digest (digest system)
     :prompt text
     :prompt-digest (digest text)}))

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
  "Pinned coordinates of the engines the product builds on, from this checkout's submodules.
  A local commit the remote never received cannot be fetched, so the pin is the newest commit
  the remote holds beneath the checkout: its merge-base with origin/HEAD."
  []
  (keep (fn [module]
          (let [git #(str/trim (:out (apply shell/sh "git" "-C" (str root "/" module) %&)))
                sha (git "merge-base" "HEAD" "origin/HEAD")
                url (str/trim (:out (shell/sh "git" "config" "-f" ".gitmodules" (str "submodule." module ".url") :dir root)))]
            (when (and (re-matches #"[0-9a-f]{40}" sha) (seq url))
              (str module " " url " at " sha))))
        ["orc" "grain"]))

;; ------------------------------------------------------------------ fence

(defn fence
  "Build and prove a fresh clean room with harness/fence.sh, which first refuses unless the
  harness self-check is green."
  [run-id evidence]
  (let [room (str state "/rooms/" run-id)]
    (when (.exists (io/file room)) (die "fence refused: " room " already exists; a room must be fresh"))
    (io/make-parents (io/file room "x"))
    (.delete (io/file room))
    (let [{:keys [exit log log-path]} (run-logged! root (str evidence "/fence.log") "bash" "harness/fence.sh" room)]
      (when-not (and (zero? exit) (str/includes? log "fence OK"))
        (die "fence failed (exit " exit "), log " log-path ": " (tail log 600)))
      room)))

;; ------------------------------------------------------------------ regenerate

(defn- build!
  "One Pi builder session over the clean room, writing only APP_DIR."
  [{:keys [run-id room cfg attempt purpose seed gate-log slice continuation uncarried resume session-ref]}]
  (let [rendered (if continuation
                   (continuation-prompt uncarried)
                   (render-prompt purpose (assoc cfg :request-id run-id :slice slice :dependencies (dependency-hints))))
        id (cond-> (format "%s-regenerate-%02d" run-id attempt) slice (str "-" slice) continuation (str "-c" continuation))
        request (cond-> {:backend "host"
                         :cutsRoot (.getParent (io/file room))
                         :cutId (.getName (io/file room))
                         :scope [(:app-dir cfg)]
                         :targetStack "clojure"
                         :prompt (:prompt rendered) :promptDigest (:prompt-digest rendered)
                         :systemPrompt (:system-prompt rendered) :systemPromptDigest (:system-prompt-digest rendered)
                         :outputRoot (str state "/outputs/" id)
                         :builderSessionRef (or session-ref (str (random-uuid)))
                         :builderSessionsRoot (str state "/builder-sessions")
                         :attempt attempt}
                  seed (assoc :initialWorkspaceRoot seed)
                  gate-log (assoc :verificationEvidenceRoot gate-log)
                  resume (assoc :resumeSessionFile resume))
        _ (say "builder " id " started")
        _ (when-not (.isFile (io/file root builder-host))
            (die "regenerate refused: the durable Pi builder host (" builder-host ") is not built yet;"
                 " ORC launches it for each builder tick (.pi/model 02-018, 02-019)"))
        {:keys [out err]} (shell/sh "node" builder-host :in (json/write-str request) :dir root)
        response (try (json/read-str (or (last (str/split-lines (str out))) "") :key-fn keyword) (catch Exception _ nil))
        result (:result response)]
    (when-not (:ok response)
      (die "builder " id " failed: " (or (:error response) (tail err 600))))
    (let [candidate (io/file (:outputRoot result) (:app-dir cfg))]
      (when-not (and (.isDirectory candidate) (seq (.list candidate)))
        (die "builder " id " produced no " (:app-dir cfg))))
    (say "builder " id " done, " (:toolCalls result) " tool calls")
    {:output-root (:outputRoot result)
     :session-id (:builderSessionId result)
     :session-file (:sessionFile result)
     :subject-digest (subject-digest (:outputRoot result))}))

(defn- plan-slices [dir plan-dir]
  (->> (.listFiles (io/file dir plan-dir)) (filter #(.isFile (io/file % "spec.feature"))) (map #(.getName %)) sort vec))

(defn- carry-slice!
  "One fresh builder for SLICE, continued in its own session while it lists items of its slice
  uncarried: at most max-slice-continuations times, ending early on a continuation that leaves
  the candidate unchanged. The listing is the builder's claim; the gate and the score decide."
  [{:keys [cfg slice] :as opts}]
  (let [first-build (build! (assoc opts :attempt 1 :purpose :slice-regeneration))]
    (loop [build first-build n 0]
      (let [left (slice-uncarried (:output-root build) (:app-dir cfg) slice)]
        (if (or (empty? left) (= n max-slice-continuations))
          (assoc build :continuations n :uncarried left)
          (let [next-build (build! (assoc opts :attempt 1 :purpose :slice-regeneration
                                          :continuation (inc n) :uncarried left
                                          :resume (:session-file build) :session-ref (:session-id first-build)
                                          :seed (:output-root build)))]
            (if (= (:subject-digest next-build) (:subject-digest build))
              (assoc build :continuations (inc n) :uncarried left)
              (recur next-build (inc n)))))))))

(defn- install! [room app-dir output-root]
  (shell/sh "rm" "-rf" (str room "/" app-dir))
  (let [{:keys [exit err]} (shell/sh "cp" "-R" (str output-root "/" app-dir) (str room "/" app-dir))]
    (when-not (zero? exit) (die "could not install the candidate into the room: " err))))

(defn regenerate
  "One fresh builder per slice, in plan order, each seeded with this cycle's candidate so far."
  [run-id room cfg]
  (let [slices (plan-slices room (:plan-dir cfg))]
    (when (empty? slices) (die "regenerate refused: no slice under " (:plan-dir cfg) " in the room"))
    (let [builds (reduce (fn [builds slice]
                           (let [previous (peek builds)
                                 build (carry-slice! {:run-id run-id :room room :cfg cfg :slice slice
                                                      :seed (:output-root previous)})]
                             (when (= (:subject-digest build) (:subject-digest previous))
                               (die "regenerate: the builder for slice " slice " left the candidate unchanged"))
                             (say "slice " slice ": " (:continuations build) " continuations, "
                                  (count (:uncarried build)) " items still listed")
                             (conj builds (assoc build :slice slice))))
                         [] slices)]
      (install! room (:app-dir cfg) (:output-root (peek builds)))
      (peek builds))))

;; ------------------------------------------------------------------ gate

(defn gate
  "harness/verify.sh inside the room. A failed gate starts a fresh gate-repair builder with only
  the previous candidate and the gate log, at most three attempts in total."
  [run-id room cfg evidence first-build]
  (loop [attempt 1 build first-build]
    (let [log-dir (str evidence "/gate-" (format "%02d" attempt))
          {:keys [exit log log-path]} (run-logged! room (str log-dir "/gate.log") "bash" "harness/verify.sh")]
      (cond
        (zero? exit) (do (say "gate passed on attempt " attempt) build)
        (>= attempt max-gate-attempts) (die "gate failed after " attempt " attempts; last log " log-path ": " (tail log 600))
        :else (let [next-build (build! {:run-id run-id :room room :cfg cfg :attempt (inc attempt)
                                        :purpose :gate-repair :seed (:output-root build) :gate-log log-dir})]
                (say "gate failed on attempt " attempt "; repairing")
                (install! room (:app-dir cfg) (:output-root next-build))
                (recur (inc attempt) next-build))))))

;; ------------------------------------------------------------------ score and scoreboard

(defn approved-graders
  "The sealed graders the operator approved, each checked to exist as sealed."
  []
  (let [graders (get (json/read-str (slurp (str root "/harness/graders.approved.json")) :key-fn keyword) :graders)]
    (when (empty? graders) (die "score refused: no approved sealed grader"))
    (doseq [{:keys [digest entry]} graders]
      (let [dir (str state "/graders/sealed/" (str/replace digest #"^sha256:" ""))]
        (when-not (.isFile (io/file dir entry)) (die "score refused: approved grader " digest " is not sealed at " dir))))
    graders))

(defn- grader-runner
  "Graders are Elixir scripts (.exs), in a functional language other than the product's Clojure.
  The three graders sealed before that choice are Python and run as Python until they are
  ported and re-approved."
  [entry]
  (cond (str/ends-with? entry ".exs") "elixir"
        (str/ends-with? entry ".py") "python3"
        :else (die "score refused: no runner for grader entry " entry)))

(defn- claim-cycle-number
  "Claim the next cycle number by creating harness/cycles/NN; mkdir is atomic."
  []
  (let [cycles (io/file root "harness" "cycles")]
    (.mkdirs cycles)
    (loop [n (inc (reduce max 0 (keep #(when (re-matches #"\d+" (.getName %)) (parse-long (.getName %))) (.listFiles cycles))))]
      (let [number (format "%02d" n)]
        (if (.mkdir (io/file cycles number)) number (recur (inc n)))))))

(defn score
  "Every approved grader drives the candidate through its public seam; harness/score.clj files
  the verdicts with an honest denominator. Two graders filing one item is refused."
  [room cfg evidence]
  (let [graders (approved-graders)
        number (claim-cycle-number)
        {:keys [exit log]} (run-logged! root (str evidence "/checklist.log")
                                        "bb" "harness/gen_checklist.clj" "--out" (str "harness/cycles/" number "/checklist.json"))
        _ (when-not (zero? exit) (die "score refused: the checklist could not be derived: " (tail log 400)))
        start (str room "/" (:app-dir cfg) "/start.sh")
        commit (str/trim (:out (shell/sh "git" "-C" root "rev-parse" "HEAD")))
        verdicts (vec (mapcat (fn [{:keys [digest entry]}]
                                (let [dir (str state "/graders/sealed/" (str/replace digest #"^sha256:" ""))
                                      graded (shell/sh (grader-runner entry) (str dir "/" entry) start :dir dir
                                                       :env (assoc (into {} (System/getenv))
                                                                   "PI_HARNESS_PROJECT_ROOT" root
                                                                   "GRADE_SOURCE_COMMIT" commit))]
                                  (spit (str evidence "/grade-" (subs (str/replace digest #"^sha256:" "") 0 12) ".log") (:err graded))
                                  (when-not (zero? (:exit graded)) (die "grader " digest " did not run: " (tail (:err graded) 400)))
                                  (json/read-str (:out graded))))
                              graders))
        twice (keep (fn [[id n]] (when (> n 1) id)) (frequencies (map #(get % "id") verdicts)))
        _ (when (seq twice) (die "score refused: more than one grader filed " (str/join ", " (sort twice))))
        verdicts-file (str evidence "/verdicts.json")]
    (spit verdicts-file (json/write-str verdicts))
    (let [{:keys [exit log]} (run-logged! root (str evidence "/score.log") "bb" "harness/score.clj" number "--verdicts" verdicts-file)]
      (when-not (zero? exit) (die "score refused by harness/score.clj: " (tail log 400)))
      (say (first (str/split-lines log)))
      number)))

(defn -main [& [run-id]]
  (when-not (and run-id (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" run-id))
    (die "usage: bb harness/regen.clj <run-id>"))
  (let [cfg (config)
        evidence (str state "/cycles/" run-id)
        _ (say "fence")
        room (fence run-id evidence)
        _ (say "regenerate " (:app-dir cfg) " from " (:plan-dir cfg))
        build (regenerate run-id room cfg)
        _ (say "gate")
        _ (gate run-id room cfg evidence build)
        _ (say "score")
        number (score room cfg evidence)
        {:keys [log]} (run-logged! root (str evidence "/scoreboard.log") "bb" "harness/scoreboard.clj" number)]
    (println log)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
