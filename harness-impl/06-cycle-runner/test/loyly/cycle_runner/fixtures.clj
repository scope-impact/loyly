(ns loyly.cycle-runner.fixtures
  "The suite's fixtures (seam.md, \"How a grader drives it without a live pi\"): a fixture
   project repository, a stub self-check, a stub builder, fixture candidates, a port, and
   the runner itself driven through its seam, one JSON-RPC line at a time."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.cycle-runner.advance :as advance]
            [loyly.cycle-runner.rpc :as rpc]
            [loyly.cycle-runner.standins :as standins]
            [loyly.cycle-runner.system :as system]
            [loyly.cycle-runner.util :as u])
  (:import [java.net ServerSocket]
           [java.nio.file Files]))

(defn temp-dir [prefix]
  (str (Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn write! [f text & {:keys [exec]}]
  (let [f (io/file f)]
    (.mkdirs (.getParentFile f))
    (spit f text)
    (when exec (.setExecutable f true))
    (u/abs-path f)))

;; ---------------------------------------------------------------------------
;; The project repository
;; ---------------------------------------------------------------------------

(def two-scenarios
  "Feature: Booking core
  The fixture plan: two stamped scenarios.

  Rule: A booking holds one slot

    # a comment line
    @minimum
    @item-01-001
    Scenario: A member books a free slot
      Given a free slot
      When the member books it
      Then the slot is booked

    @balance @item-01-002
    Scenario: A booked slot is not booked twice
      Given a booked slot
      When another member books it
      Then the booking is refused
")

(def three-scenarios-one-unstamped
  (str two-scenarios "
    @balance
    Scenario: A slot with no identity yet
      Given a slot
      Then nothing
"))

(def no-scenario "Feature: Nothing yet\n  A spec file with no scenario.\n")

(defn probe-script
  "The identity probe: exits 0 only when its one argument is http://127.0.0.1:<port> and the
   page there carries the marker; handed the bare port, it exits 1."
  [port]
  (str "#!/bin/bash\n"
       "if [ \"$1\" != \"http://127.0.0.1:" port "\" ]; then echo \"probe handed '$1', not the URL\"; exit 1; fi\n"
       "if curl -s \"$1\" | grep -qi sauna; then echo \"this is the sauna\"; exit 0; fi\n"
       "echo \"the page at $1 is not the sauna\"; exit 1\n"))

(defn config-text [port & {:keys [fenced] :or {fenced "impl harness/cycles"}}]
  (str "# fixture harness configuration (legacy config.sh shape)\n"
       "APP_DIR=impl\n"
       "PORT=" port "\n"
       "PLAN_DIR=docs/plan\n"
       "APP_MARKER=sauna\n"
       "APP_PROBE=harness/probe.sh\n"
       "FENCED_PATHS=\"" fenced "\"\n"))

(defn git! [dir & args]
  (let [r (u/run-process (into ["git" "-c" "user.name=fixture" "-c" "user.email=fixture@loyly.invalid"
                                "-c" "commit.gpgsign=false"] args)
                         {:dir dir :env (into {} (System/getenv)) :timeout-ms 60000})]
    (when-not (zero? (:exit r)) (throw (ex-info (str "git failed: " (:out r)) {:args args})))
    (str/trim (:out r))))

(defn make-repo
  "A fixture git repository; files maps a path to text (or [text :exec]). Returns
   {:dir :commit :config}."
  [files]
  (let [dir (temp-dir "loyly-fixture-repo")]
    (doseq [[p v] files]
      (if (vector? v) (write! (io/file dir p) (first v) :exec true) (write! (io/file dir p) v)))
    (git! dir "init" "-q")
    (git! dir "add" "-A")
    (git! dir "commit" "-q" "-m" "the cut")
    {:dir dir :commit (git! dir "rev-parse" "HEAD") :config (u/abs-path (io/file dir "harness/config.sh"))}))

(defn default-files [port & {:keys [spec fenced extra] :or {spec two-scenarios}}]
  (merge {"README.md" "fixture project\n"
          "docs/plan/01-booking-core/spec.feature" (or spec two-scenarios)
          "harness/config.sh" (config-text port :fenced (or fenced "impl harness/cycles"))
          "harness/probe.sh" [(probe-script port) :exec]
          "impl/old-candidate.txt" "a prior candidate\n"
          "harness/cycles/cycle-1.txt" "a recorded measurement\n"}
         extra))

;; ---------------------------------------------------------------------------
;; Self-checks
;; ---------------------------------------------------------------------------

(defn self-check! [dir text]
  (write! (io/file dir (str "self-check-" (hash text) ".sh")) (str "#!/bin/bash\n" text "\n") :exec true))

(def passing-self-check "echo checking the harness\necho all checks pass\nexit 0")

;; ---------------------------------------------------------------------------
;; Candidates
;; ---------------------------------------------------------------------------

(defn candidate!
  "A fixture candidate in dir: test.sh (exit suite-exit), start.sh serving www/ on its one
   argument, the port, with page text. Options: :no-start, :start-marker-file (start.sh
   records that it ran), :test-text."
  [dir & {:keys [suite-exit page no-start start-marker-file test-text]
          :or {suite-exit 0 page "<html><body>Welcome to the Sauna</body></html>"}}]
  (write! (io/file dir "test.sh") (or test-text (str "#!/bin/bash\necho suite\nexit " suite-exit "\n")) :exec true)
  (write! (io/file dir "www" "index.html") page)
  (when-not no-start
    (write! (io/file dir "start.sh")
            (str "#!/bin/bash\n"
                 (when start-marker-file (str "echo ran >> " start-marker-file "\n"))
                 "cd \"$(dirname \"$0\")/www\"\n"
                 "exec python3 -m http.server \"$1\" --bind 127.0.0.1\n")
            :exec true))
  dir)

;; ---------------------------------------------------------------------------
;; The stub builder
;; ---------------------------------------------------------------------------

(defn builder!
  "A stub builder. Each launch appends 'start N' and 'end N' to log, records the paths and
   the commits its builder room holds, and whether it can write the repair mounts; then
   copies candidate N (the last one for later launches) into the candidate scope, unless
   :leave-empty. Options: :exit, :sleep, :after-exit (shell run in the background after it
   exits)."
  [dir candidates & {:keys [exit sleep leave-empty after-exit] :or {exit 0}}]
  (let [log (u/abs-path (io/file dir "builder.log"))
        script (str "#!/bin/bash\n"
                    "room=\"$1\"; instr=\"$2\"\n"
                    "n=$(( $(grep -c '^start' " log " 2>/dev/null || true) + 1 ))\n"
                    "echo \"start $n\" >> " log "\n"
                    "(cd \"$room\" && find . -path ./.git -prune -o -print | sort) > " dir "/paths-$n.txt\n"
                    "git -C \"$room\" rev-list --all --count > " dir "/commits-$n.txt 2>/dev/null\n"
                    "git -C \"$room\" rev-list --objects --all > " dir "/objects-$n.txt 2>/dev/null\n"
                    "cp \"$instr\" " dir "/instruction-$n.md\n"
                    "if [ -d \"$room/.repair\" ]; then\n"
                    "  for d in \"$room/.repair/previous-candidate\" \"$room/.repair/gate-evidence\"; do\n"
                    "    if touch \"$d/probe-write\" 2>/dev/null; then echo \"writable $d\"; else echo \"read-only $d\"; fi\n"
                    "  done > " dir "/mounts-$n.txt\n"
                    "fi\n"
                    (when sleep (str "sleep " sleep "\n"))
                    (when-not leave-empty
                      (str "cands=(" (str/join " " (map #(str "\"" % "\"") candidates)) ")\n"
                           "i=$(( n - 1 )); if [ $i -ge ${#cands[@]} ]; then i=$(( ${#cands[@]} - 1 )); fi\n"
                           "mkdir -p \"$room/impl\" && cp -R \"${cands[$i]}/.\" \"$room/impl/\"\n"))
                    (when after-exit (str "( " after-exit " ) >/dev/null 2>&1 &\n"))
                    "echo \"end $n\" >> " log "\n"
                    "exit " exit "\n")]
    {:cmd (write! (io/file dir "builder.sh") script :exec true) :log log :dir dir}))

(defn builder-log [b] (let [f (io/file (:log b))] (if (.exists f) (str/split-lines (slurp f)) [])))

;; ---------------------------------------------------------------------------
;; The runner, through its seam
;; ---------------------------------------------------------------------------

(defonce registered (delay (system/register-all! advance/processor-handler) (system/verify!)))

(defn start-runner
  "Start the runner on a fresh store with env (a map of LOYLY_* values)."
  [env]
  @registered
  (let [store (str (io/file (temp-dir "loyly-store") "runner.sqlite"))
        sys (system/start! {:store-path store :env-fn #(get env %)})]
    (assoc sys :test/store store :test/next-id (atom 0))))

(defn stop-runner [sys]
  (doseq [[_ s] @(:sessions sys)] (try (advance/close-session! s) (catch Throwable _)))
  (system/stop! sys))

(defn call
  "One JSON-RPC request through the seam; returns the parsed response."
  [sys method params]
  (let [id (swap! (:test/next-id sys) inc)
        line (u/->json {:jsonrpc "2.0" :id id :method method :params params})]
    (u/parse-json (rpc/handle-line sys line))))

(def budget {:gate-attempt-limit 3 :max-outer-cycles 1 :max-continuations 0
             :stage-timeouts-seconds {:fence 600 :regenerate 600 :gate 600 :score 600}})

(defn start-params [commit & {:as over}]
  (merge {:run-id "R-1" :cycle-id "C-1" :cut-id "K-1" :commit commit :selection-id "whole-plan"
          :budget budget :grader-digests ["r-1"]}
         over))

(defn progress [sys run-id]
  (get-in (call sys "cycle-runner/run-progress" {:run-id run-id}) [:result :run]))

(defn effects [run] (mapv :effect (:effect-results run)))

(defn results-of [run effect] (filterv #(= effect (:effect %)) (:effect-results run)))

(defn event-types [result] (mapv :type (:events result)))

;; ---------------------------------------------------------------------------
;; A world: repository, stand-ins, self-check, builder, runner
;; ---------------------------------------------------------------------------

(defn world
  "Everything one scenario needs. Options: :spec :fenced :candidates (dirs; default one good
   candidate) :builder-opts :self-check (text) :scripts (stand-in scripts) :contexts
   (entrypoint overrides) :extra (repo files)."
  [& {:keys [spec fenced candidates builder-opts self-check scripts contexts extra]}]
  (let [port (free-port)
        base (temp-dir "loyly-world")
        repo (make-repo (default-files port :spec spec :fenced fenced :extra extra))
        cands (or candidates [(candidate! (str base "/cand-good"))])
        b (apply builder! base cands (mapcat identity builder-opts))
        _ (standins/reset-scripts! (or scripts {}))
        env {"LOYLY_RUNS_DIR" (str base "/runs")
             "LOYLY_PROJECT_REPO" (:dir repo)
             "LOYLY_HARNESS_CONFIG" (:config repo)
             "LOYLY_CONTEXTS" (standins/write-contexts! base (or contexts {}))
             "LOYLY_SELF_CHECK_CMD" (self-check! base (or self-check passing-self-check))
             "LOYLY_BUILDER_CMD" (:cmd b)
             "LOYLY_BUILDER_INSTRUCTION" "Build the fixture product from the clean room."
             "LOYLY_CONTEXT_TIMEOUT_SECONDS" "20"
             "LOYLY_CALL_TIMEOUT_SECONDS" "20"}]
    {:port port :base base :repo repo :builder b :env env}))

(defmacro with-runner
  "Run body with sys bound to a runner started on w's env."
  [[sys w] & body]
  `(let [~sys (start-runner (:env ~w))]
     (try ~@body (finally (stop-runner ~sys)))))

(defn start!
  "Start a run through the seam; returns the response."
  [sys w & {:as over}]
  (call sys "cycle-runner/start-run"
        (merge (start-params (get-in w [:repo :commit])) over)))

(defn verdicts-file! [w name text]
  (write! (io/file (:base w) name) text))

(defn hand-in! [sys run-id path digest]
  (call sys "cycle-runner/hand-in-verdicts" {:run-id run-id :verdicts-path path :grader-digest digest}))

(defn refusal [resp] (get-in resp [:error :data :refusal]))

(defn budget-with [& {:keys [limit regenerate]}]
  (cond-> budget
    limit (assoc :gate-attempt-limit limit)
    regenerate (assoc-in [:stage-timeouts-seconds :regenerate] regenerate)))
