(ns loyly.cycle-runner.harness
  "The suite's fixtures: a fixture project repository, the configuration, a
   stub self-check, a stub builder and its fixture candidates, the identity
   probe and store check stubs, the stand-in contexts, and the product run in
   the suite's own process, driven through its seam (JSON-RPC lines)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.cycle-runner.contexts :as contexts]
            [loyly.cycle-runner.model :as model]
            [loyly.cycle-runner.runtime :as runtime]
            [loyly.cycle-runner.seam :as seam]
            [loyly.cycle-runner.standins :as standins]
            [loyly.cycle-runner.system :as system]
            [loyly.cycle-runner.util :as u])
  (:import [java.net ServerSocket]))

(def home (model/home))

(defn tmp-root [] (str home "/tmp/tests"))

(defn free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn write! [p text & {:keys [exec]}]
  (u/spit-utf8 p text)
  (when exec (u/chmod-exec! p))
  (u/abs-path p))

(defn sh-quote [s] (str "'" (str/replace (str s) "'" "'\\''") "'"))

;; ---------------------------------------------------------------------------
;; The fixture project repository

(def spec-two
  "Feature: Booking core
  The guest books a sauna slot.

  Rule: A guest books a slot

    # a comment above the scenario
    @minimum @item-01-001
    Scenario: A guest books a free slot
      Given a free slot at six
      When the guest books it
      Then the slot is booked

    @balance
    @item-01-002
    Scenario: A guest cannot book a booked slot
      Given a booked slot at seven
      When another guest books it
      Then the booking is refused
")

(def spec-extra-unstamped
  (str spec-two "
    Scenario: A guest books a slot nobody numbered
      Given a free slot at eight
      Then nothing
"))

(def spec-empty
  "Feature: Nothing yet
  No scenario is written.
")

(defn spec-of [slice n-items]
  (str "Feature: Slice " slice "\n\n"
       (apply str (for [i (range 1 (inc n-items))]
                    (format "  @item-%s-%03d\n  Scenario: Scenario %d of slice %s\n    Given step %d\n\n" slice i i slice i)))))

(defn git! [dir & args]
  (let [{:keys [exit out]} (u/run! (into ["git" "-C" (str dir)] args) {:timeout-ms 60000})]
    (when-not (zero? exit) (throw (ex-info (str "git " (str/join " " args) ": " out) {})))
    (str/trim out)))

(defn make-repo!
  "A git repository with the files (path -> text), the executable ones and
   the symbolic links (path -> target), committed once. Returns the commit."
  [dir {:keys [files exec links]}]
  (u/delete-tree! dir)
  (u/mkdirs! dir)
  (git! dir "init" "-q")
  (doseq [[p text] files] (write! (str dir "/" p) text :exec (contains? (set exec) p)))
  (doseq [[p target] links]
    (io/make-parents (io/file dir p))
    (java.nio.file.Files/createSymbolicLink (u/path dir p) (u/path target)
                                            (make-array java.nio.file.attribute.FileAttribute 0)))
  (git! dir "add" "-A")
  (git! dir "-c" "user.name=t" "-c" "user.email=t@t" "-c" "commit.gpgsign=false" "commit" "-q" "-m" "fixture")
  (git! dir "rev-parse" "HEAD"))

(defn http-probe
  "The cut's identity probe for the http seam: exits 0 only for the served URL
   whose page carries the marker."
  [log]
  (str "#!/bin/sh\n"
       "echo \"$@\" >> " (sh-quote log) "\n"
       "case \"$1\" in http://127.0.0.1:*) ;; *) echo \"not the served URL: $1\"; exit 1;; esac\n"
       "if curl -sf \"$1\" | grep -qi sauna; then echo \"the page at $1 is the sauna\"; exit 0; fi\n"
       "echo \"no sauna at $1\"; exit 1\n"))

(defn stdio-probe
  "The cut's identity probe for the stdio seam: records its arguments and
   environment, prints a line, exits as told."
  [dir line exit]
  (str "#!/bin/sh\n"
       "printf '%s\\n' \"$@\" > " (sh-quote (str dir "/probe-args")) "\n"
       "env > " (sh-quote (str dir "/probe-env")) "\n"
       "echo " (sh-quote line) "\n"
       "exit " exit "\n"))

;; ---------------------------------------------------------------------------
;; Fixture candidates and the stub builder

(defn http-candidate!
  "A candidate whose test.sh exits as told and whose start.sh records that it
   ran and serves a page (with the marker unless told otherwise) on its port."
  [dir {:keys [suite-exit page start? suite-body ran-file]
        :or {suite-exit 0 page "<html><body>Welcome to the sauna</body></html>" start? true}}]
  (u/delete-tree! dir)
  (write! (str dir "/test.sh") (str "#!/bin/sh\n" (or suite-body "") "\nexit " suite-exit "\n") :exec true)
  (when start?
    (write! (str dir "/start.sh")
            (str "#!/bin/sh\n"
                 "echo ran >> " (sh-quote ran-file) "\n"
                 "cd \"$(dirname \"$0\")/www\" && exec python3 -m http.server \"$1\" --bind 127.0.0.1 >/dev/null 2>&1\n")
            :exec true))
  (write! (str dir "/www/index.html") page)
  dir)

(defn stdio-candidate!
  [dir {:keys [suite-exit suite-body env-file] :or {suite-exit 0}}]
  (u/delete-tree! dir)
  (write! (str dir "/test.sh")
          (str "#!/bin/sh\n"
               (when env-file (str "env > " (sh-quote env-file) "\n"))
               (or suite-body "") "\nexit " suite-exit "\n")
          :exec true)
  (write! (str dir "/start.sh") "#!/bin/sh\nexec cat\n" :exec true)
  dir)

(defn builder!
  "The stub builder: on launch n it records what its builder room holds, then
   copies the nth candidate (the last one again after that) into the
   candidate scope, or leaves it empty; it may sleep, exit non-zero, or keep
   writing after it exited."
  [dir {:keys [candidates exit sleep after-exit app-dir] :or {exit 0 app-dir "impl"}}]
  (u/mkdirs! dir)
  (let [q sh-quote
        cases (str "case $n in\n"
                   (apply str (map-indexed (fn [i c] (str "  " (inc i) ") cand=" (q c) ";;\n")) candidates))
                   (when (seq candidates) (str "  *) cand=" (q (last candidates)) ";;\n"))
                   "esac\n")]
    (write! (str dir "/builder.sh")
            (str "#!/bin/sh\n"
                 "room=\"$1\"; instr=\"$2\"; d=" (q dir) "\n"
                 "n=$(( $(cat \"$d/count\" 2>/dev/null || echo 0) + 1 )); echo \"$n\" > \"$d/count\"\n"
                 "echo \"start $n\" >> \"$d/launches\"\n"
                 "echo \"$$\" > \"$d/pid-$n\"\n"
                 "echo \"$room\" > \"$d/room-$n\"; cp \"$instr\" \"$d/instruction-$n\"\n"
                 "(cd \"$room\" && find . -path ./.git -prune -o -print | sort) > \"$d/paths-$n\"\n"
                 "git -C \"$room\" rev-list --all --count > \"$d/commits-$n\" 2>/dev/null\n"
                 "git -C \"$room\" rev-list --objects --all > \"$d/objects-$n\" 2>/dev/null\n"
                 "for m in \"$room\"/.repair/*; do [ -e \"$m\" ] || continue; "
                 "if touch \"$m/.write-probe\" 2>/dev/null; then echo \"writable $m\"; else echo \"read-only $m\"; fi; "
                 "done > \"$d/writable-$n\"\n"
                 (when sleep (str "sleep " sleep "\n"))
                 "cand=\n" cases
                 "if [ -n \"$cand\" ]; then cp -R \"$cand/.\" \"$room/" app-dir "/\"; fi\n"
                 (when after-exit
                   (str "( sleep 2; " after-exit " ) >/dev/null 2>&1 &\n"))
                 "echo \"end $n\" >> \"$d/launches\"\n"
                 "exit " exit "\n")
            :exec true)))

(defn launches [dir] (vec (remove str/blank? (str/split-lines (try (slurp (str dir "/launches")) (catch Exception _ ""))))))
(defn launch-count [dir] (count (filter #(str/starts-with? % "start") (launches dir))))

(defn self-check! [p {:keys [lines exit] :or {exit 0}}]
  (write! p (str "#!/bin/sh\n" (apply str (for [l lines] (str "echo " (sh-quote l) "\n"))) "exit " exit "\n") :exec true))

;; ---------------------------------------------------------------------------
;; The stand-in contexts

(defonce script (atom {}))

(defn contexts-file!
  "LOYLY_CONTEXTS naming a stand-in for each context; `exiting` contexts get
   an entrypoint that exits at once."
  [dir {:keys [exiting]}]
  (let [port (standins/start! script)
        bb (u/abs-path (str home "/test/standin.bb"))
        entries (into {}
                      (for [c model/contexts]
                        [c (write! (str dir "/contexts/" c ".sh")
                                   (if (contains? (set exiting) c)
                                     "#!/bin/sh\nexit 0\n"
                                     (str "#!/bin/sh\nexec bb " (sh-quote bb) " " port " " c "\n"))
                                   :exec true)]))]
    (write! (str dir "/contexts.json") (u/->json entries))))

;; ---------------------------------------------------------------------------
;; A world: everything a run acts on

(defn world!
  "Build the fixture world of a scenario in its own directory. Options:
   :seam (http|stdio), :spec (text of slice 01), :slices {name text},
   :links {name target}, :fenced, :plan-slices, :self-check {:lines :exit},
   :candidates [opts...], :builder {...}, :probe-line, :probe-exit,
   :store-check {:line :exit} or :store-check-cmd, :exiting [contexts]."
  [name opts]
  (let [dir (u/abs-path (str (tmp-root) "/" name))
        _ (u/delete-tree! dir)
        _ (u/mkdirs! dir)
        port (free-port)
        stdio? (= "stdio" (:seam opts))
        config (str "APP_DIR=impl\n"
                    (when-not stdio? (str "PORT=" port "\n"))
                    "PLAN_DIR=docs/plan\n"
                    (when-not stdio? "APP_MARKER=sauna\n")
                    "APP_PROBE=harness/probe.sh\n"
                    "FENCED_PATHS=\"" (or (:fenced opts) "impl harness/cycles") "\"\n"
                    (when stdio? "APP_SEAM=stdio\n")
                    (when (:plan-slices opts) (str "PLAN_SLICES=\"" (:plan-slices opts) "\"\n")))
        probe (if stdio?
                (stdio-probe dir (or (:probe-line opts) "answered as the context") (or (:probe-exit opts) 0))
                (or (:probe opts) (http-probe (str dir "/probe-log"))))
        slices (or (:slices opts) {"01-booking-core" (or (:spec opts) spec-two)})
        files (merge {"README.md" "the project\n"
                      "harness/config.sh" config
                      "harness/probe.sh" probe
                      "impl/README.md" "a prior candidate\n"
                      "harness/cycles/c1/score.txt" "a prior score\n"}
                     (into {} (for [[n t] slices] [(str (if (:links opts) "docs/spec/" "docs/plan/") n "/spec.feature") t]))
                     (:files opts))
        files (apply dissoc files (:drop-files opts))
        commit (make-repo! (str dir "/repo") {:files files :exec ["harness/probe.sh"]
                                               :links (into {} (for [[n target] (:links opts)]
                                                                 [(str "docs/plan/" n) target]))})
        config-file (write! (str dir "/config.sh") config)
        self-check (self-check! (str dir "/self-check.sh") (or (:self-check opts) {:lines ["all checks passed"]}))
        ran-file (str dir "/start-ran")
        candidates (vec (map-indexed
                         (fn [i c]
                           (if (= :empty c)
                             nil
                             (if stdio?
                               (stdio-candidate! (str dir "/candidates/c" (inc i))
                                                 (merge {:env-file (str dir "/suite-env-" (inc i))} c))
                               (http-candidate! (str dir "/candidates/c" (inc i)) (merge {:ran-file ran-file} c)))))
                         (or (:candidates opts) [{}])))
        builder (builder! (str dir "/builder")
                          (merge {:candidates (vec (remove nil? candidates))} (:builder opts)))
        store-check (when stdio?
                      (or (:store-check-cmd opts)
                          (let [{:keys [line exit] :or {line "the store holds the context's events" exit 0}} (:store-check opts)]
                            (write! (str dir "/store-check.sh")
                                    (str "#!/bin/sh\nprintf '%s\\n' \"$@\" > " (sh-quote (str dir "/store-check-args")) "\n"
                                         "echo " (sh-quote line) "\nexit " exit "\n")
                                    :exec true))))
        contexts (contexts-file! dir {:exiting (:exiting opts)})
        instruction (write! (str dir "/instruction.md") "Build the product described by the spec.\n")]
    {:dir dir :port port :commit commit :repo (str dir "/repo") :ran-file ran-file
     :builder-dir (str dir "/builder")
     :env (cond-> {"LOYLY_RUNS_DIR" (str dir "/runs")
                   "LOYLY_PROJECT_REPO" (str dir "/repo")
                   "LOYLY_HARNESS_CONFIG" config-file
                   "LOYLY_CONTEXTS" contexts
                   "LOYLY_SELF_CHECK_CMD" self-check
                   "LOYLY_BUILDER_CMD" builder
                   "LOYLY_BUILDER_INSTRUCTION" instruction}
            store-check (assoc "LOYLY_STORE_CHECK_CMD" store-check))}))

;; ---------------------------------------------------------------------------
;; The product, in the suite's own process, driven through its seam

(defonce product (atom nil))

(defn use-env! [env]
  (reset! seam/env-source (fn [] (merge (into {} (System/getenv)) env))))

(defn start-product! []
  (let [store (str (tmp-root) "/store-" (random-uuid) ".sqlite")]
    (u/mkdirs! (tmp-root))
    (reset! product {:sys (system/start store) :store store :ids (atom 0)})))

(defn stop-product! []
  (when-let [{:keys [sys store]} @product]
    (doseq [[id rt] @runtime/runs]
      (when-let [cs (:contexts rt)] (try (contexts/stop-all! cs) (catch Exception _ nil)))
      (runtime/forget-run! id))
    (system/stop sys)
    (doseq [suffix ["" "-wal" "-shm"]] (u/delete-tree! (str store suffix)))
    (reset! product nil)))

(defn rpc
  "Send one request line to the product; return the parsed response."
  [method params]
  (let [{:keys [sys ids]} @product
        line (u/->json {:jsonrpc "2.0" :id (swap! ids inc) :method method :params params})]
    (u/<-json (seam/handle sys line))))

(def budget
  {:gate-attempt-limit 3 :max-outer-cycles 1 :max-continuations 0
   :stage-timeouts-seconds {:fence 600 :regenerate 600 :gate 600 :score 600}})

(defn start-run
  "cycle-runner/start-run with the feature's defaults, in `world`."
  [world & {:as overrides}]
  (use-env! (merge (:env world) (:env overrides)))
  (rpc "cycle-runner/start-run"
       (merge {:run-id "R-1" :cycle-id "C-1" :cut-id "K-1" :commit (:commit world)
               :selection-id "whole-plan" :budget budget :grader-digests ["r-1"]}
              (dissoc overrides :env))))

(defn progress [run-id]
  (get-in (rpc "cycle-runner/run-progress" {:run-id run-id}) [:result :run]))

(defn effects-of [run] (mapv :effect (:effect-results run)))

(defn effect-rows [run effect] (filterv #(= effect (:effect %)) (:effect-results run)))

(defn hand-in [run-id verdicts-path & {:keys [grader-digest] :or {grader-digest "r-1"}}]
  (rpc "cycle-runner/hand-in-verdicts" {:run-id run-id :verdicts-path verdicts-path :grader-digest grader-digest}))

(defn verdicts-file! [world name verdicts]
  (write! (str (:dir world) "/" name) (u/->json verdicts)))

(defn event-types [row] (mapv :type (:events row)))

(defn with-product*
  "Run f with a fresh product and fresh stand-in scripting."
  [f]
  (reset! script {})
  (standins/start! script)
  (standins/reset-standins!)
  (start-product!)
  (try (f) (finally (stop-product!))))

(defmacro with-product [& body] `(with-product* (fn [] ~@body)))
