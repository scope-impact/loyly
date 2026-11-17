(ns loyly.effects
  (:require [loyly.domain :as d] [clojure.string :as str] [clojure.java.io :as io]
            [ai.obney.grain.event-store-v3.interface :as es]
            [clojure.java.shell :as sh] [clojure.data.json :as json]))
(defn environment [] (into {} (filter (fn [[k _]] (or (str/starts-with? k "LOYLY_") (= k "OTEL_EXPORTER_OTLP_ENDPOINT"))) (System/getenv))))
(defn git [env & args]
  (let [r (apply sh/sh "git" "-C" (get env "LOYLY_PROJECT_REPO") args)]
    (when-not (zero? (:exit r)) (throw (ex-info (str/trim (:err r)) {:git args}))) (:out r)))
(defn config [env]
  (into {} (keep (fn [line] (when-let [[_ k v] (re-matches #"\s*([A-Z_]+)=(.*)" line)]
    [k (str/replace (str/trim v) #"^['\"]|['\"]$" "")])) (str/split-lines (slurp (get env "LOYLY_HARNESS_CONFIG"))))))
(defn input [ctx] (into {} (map (fn [[k v]] [(keyword k) v]) (:inputs ctx))))
(defn params [in] (json/read-str (:params in) :key-fn keyword))
(defn output [m] m)
(def blank-halt {:effect "" :reason "" :detail ""})
(defn halt-output [effect reason detail] (output {:run "halting" :halt (json/write-str {:effect effect :reason reason :detail detail})}))
(defn cut-file [env p path] (git env "show" (str (:commit p) ":" path)))
(defn slices-at [env p conf]
  (let [root (get conf "PLAN_DIR") select (set (str/split (or (get conf "PLAN_SLICES") "") #"\s+"))
        entries (str/split-lines (git env "ls-tree" (:commit p) (str root "/")))]
    (vec (sort-by :slice (keep (fn [line]
      (when-let [[_ mode path] (re-matches #"(\d+) \w+ [0-9a-f]+\t(.+)" line)]
        (when-let [[_ slice] (re-find #"(?:^|/)(\d+)-[^/]+$" path)]
          (when (or (= select #{""}) (contains? select slice))
            (let [target (if (= mode "120000")
              (str (.normalize (.resolve (.getParent (.toPath (io/file path))) (str/trim (cut-file env p path))))) path)]
              {:slice (.getName (io/file path)) :path (str target "/spec.feature") :source (str path "/spec.feature")}))))) entries)))))
(defn scenarios [text path]
  (let [lines (vec (str/split-lines text))]
    (loop [i 0 rule nil tags [] out []]
      (if (= i (count lines)) out
        (let [line (nth lines i) trimmed (str/trim line)]
          (cond
            (str/starts-with? trimmed "Rule:") (recur (inc i) (str/trim (subs trimmed 5)) [] out)
            (str/starts-with? trimmed "@") (recur (inc i) rule (into tags (re-seq #"@item-([0-9]+-[0-9]{3,})" trimmed)) out)
            (re-find #"^Scenario(?: Outline)?:" trimmed)
            (let [title (str/trim (str/replace trimmed #"^Scenario(?: Outline)?:" ""))
                  tail (take-while #(not (re-find #"^\s*(?:@|Scenario(?: Outline)?:|Rule:|Feature:)" %)) (subvec lines (inc i)))
                  body (str/join "\n" (reverse (drop-while str/blank? (reverse (cons line tail)))))]
              (recur (inc i) rule [] (conj out {:item-id (second (first tags)) :title title :line (inc i) :source (str path ":" (inc i)) :rule rule :scenario-digest (d/digest body)})))
            :else (recur (inc i) rule tags out)))))))
(defn record-effect [ctx p effect method reply detail]
  (d/issue ctx :cycle-runner/record-effect
    (cond-> {:run-id (:run-id p) :effect effect :attempt (case effect "regenerate" 1 "repair" (inc (:attempt (input ctx))) (or (:attempt (input ctx)) 0)) :context (namespace method) :method (subs (str method) 1)
             :outcome (if (:refusal reply) "refused" "recorded") :events (or (:events reply) []) :detail detail}
      (:refusal reply) (assoc :refusal (:refusal reply)))))
(defn start-leaf [ctx]
  (locking (or (:domain-lock ctx) (:event-store ctx))
  (let [in (input ctx) p (params in) env (environment)
        runs (d/rows ctx :cycle-runner/runs)
        refuse (cond (some #(= (:run-id p) (:run-id %)) runs) "run-already-started"
                     (some #(= "running" (:status %)) runs) "run-in-progress"
                     (some #(= (:cycle-id p) (:cycle-id %)) runs) "cycle-already-run"
                     (not (zero? (:exit (sh/sh "git" "-C" (get env "LOYLY_PROJECT_REPO" "") "cat-file" "-e" (str (:commit p) "^{commit}"))))) "not-a-commit")
        conf (when-not refuse (config env))
        image (get conf "BUILDER_IMAGE")
        refuse (or refuse (when (and (not (str/blank? image)) (not (.isDirectory (io/file (get env "LOYLY_BUILDER_IMAGES" "") image)))) "builder-image-missing"))]
    (if refuse (output {:run "refused" :refusal refuse :handed-in "{}" :config "{}" :run-dir "" :slices []})
      (let [slices (slices-at env p conf) dir (.getAbsolutePath (io/file (get env "LOYLY_RUNS_DIR") (:run-id p)))]
        (.mkdirs (io/file dir))
        (d/issue ctx :cycle-runner/start-run p)
        (output {:run "running" :refusal "" :handed-in (json/write-str env) :config (json/write-str conf) :run-dir dir :slices slices}))))))
(defn untag [text]
  (str/join "\n" (keep (fn [line]
    (if (str/starts-with? (str/trim line) "@")
      (let [line (str/replace line #"@item-[0-9]+-[0-9]{3,}\s*" "")] (when-not (str/blank? line) line)) line)) (str/split text #"\n" -1))))
(defn stamp-leaf [ctx]
  (let [in (input ctx) p (params in) env (json/read-str (:handed-in in)) {:keys [slice path]} (:slice in)
        number (first (str/split slice #"-"))
        out (fn [r] {:stamp (assoc r :slice slice)})]
    (try
      (let [text (cut-file env p path) original (scenarios text path) prior (d/row ctx :specification/stamped-items :slice number)
            target (io/file (:run-dir in) slice "spec.feature") stripped (untag text)
            _ (io/make-parents target) _ (spit target stripped)
            readings (if prior (remove :item-id original) (scenarios stripped (str target)))
            reply (d/issue ctx :specification/stamp-slice {:slice number :source (str target)
              :unstamped (mapv #(select-keys % [:line :title]) (remove #(str/blank? (:title %)) readings))
              :empty-title-lines (mapv :line (filter #(str/blank? (:title %)) readings))})
            _ (record-effect ctx p "stamp" :specification/stamp-slice reply path)
            expected (mapv :item-id (remove #(str/blank? (:title %)) original))
            actual (mapv :item-id (:items (d/row ctx :specification/stamped-items :slice number)))]
        (out (if (= expected actual) {:run "running"} {:run "halting" :halt {:effect "stamp" :reason "refused-before-the-cycle" :detail "Stamped item identities differ from the cut"}})))
      (catch Exception e (out {:run "halting" :halt {:effect "stamp" :reason "effect-could-not-run" :detail (.getMessage e)}})))))
(defn stamped-leaf [ctx]
  (if-let [failed (first (filter #(= "halting" (:run %)) (map :stamp (:stamps (input ctx)))))]
    {:run "halting" :halt (json/write-str (:halt failed))} {:run "running" :halt "{}"}))
(defn pin-leaf [ctx]
  (let [in (input ctx) p (params in) env (json/read-str (:handed-in in))]
    (try (let [reply (d/issue ctx :cycle-preparation/pin-source-cut (assoc (select-keys p [:cut-id :commit]) :tree-digest (str/trim (git env "rev-parse" (str (:commit p) "^{tree}")))))]
      (record-effect ctx p "pin" :cycle-preparation/pin-source-cut reply "")
      (if (:refusal reply) (halt-output "pin" "refused-before-the-cycle" (:refusal reply)) (output {:run "running" :halt "{}"})))
      (catch Exception e (halt-output "pin" "effect-could-not-run" (.getMessage e))))))
(defn grading-item [env p slices item]
  (let [slice (first (filter #(str/starts-with? (:slice %) (str (first (str/split (:item-id item) #"-")) "-")) slices))
        folder (str (.getParent (io/file (:path slice)))) text (cut-file env p (:path slice))
        reading (first (filter #(= (:item-id item) (:item-id %)) (scenarios text (:path slice))))
        lines (drop (dec (:line reading)) (str/split-lines text))
        scenario (str/join "\n" (cons (first lines) (take-while #(not (re-find #"^\s*(?:@|Scenario(?: Outline)?:|Rule:|Feature:)" %)) (rest lines))))
        paths (str/split-lines (git env "ls-tree" "-r" "--name-only" (:commit p) (str folder "/event-model/")))
        seam (try (cut-file env p (str folder "/seam.md")) (catch Exception _ ""))]
    (assoc item :slice (:slice slice) :scenario scenario :seam seam :event-model (str/join "\n" (map #(cut-file env p %) paths)))))
(defn checklist-leaf [ctx]
  (let [in (input ctx) p (params in) env (json/read-str (:handed-in in)) slices (:slices in)]
    (try (let [items (mapcat #(scenarios (cut-file env p (:path %)) (or (:source %) (:path %))) slices)
               reply (d/issue ctx :measurement/derive-checklist
                 (merge (select-keys p [:cut-id :selection-id]) {:items (mapv #(dissoc % :line) (filter :item-id items))
                   :unstamped (mapv #(select-keys % [:source :title]) (remove :item-id items)) :skipped-slices []}))]
      (record-effect ctx p "checklist" :measurement/derive-checklist reply "")
      (if (:refusal reply) (assoc (halt-output "checklist" "refused-before-the-cycle" (:refusal reply)) :items []) (output {:run "running" :halt "{}" :items (mapv #(grading-item env p slices %) (:items (first (:events reply))))})))
      (catch Exception e (assoc (halt-output "checklist" "effect-could-not-run" (.getMessage e)) :items [])))))
(defn grader-leaf [ctx]
  (let [in (input ctx) p (params in) env (json/read-str (:handed-in in))]
    (try (let [digest (d/digest (str "cycle-runner/grade.v1\n" (slurp (get env "LOYLY_GRADER_RUBRIC"))))]
      (if (some #{digest} (:grader-digests p)) (output {:run "running" :halt "{}"}) (halt-output "grader" "refused-before-the-cycle" digest)))
      (catch Exception e (halt-output "grader" "effect-could-not-run" (.getMessage e))))))
(defn halt-leaf [ctx]
  (let [in (input ctx)] (d/issue ctx :cycle-runner/halt-run (assoc (json/read-str (:halt in) :key-fn keyword) :run-id (:run-id (params in)))) (output {:run "halted"})))
(defn settings [ctx]
  (let [in (input ctx)] {:in in :p (params in) :env (json/read-str (:handed-in in)) :conf (json/read-str (:config in))}))
(defn cycle-view [ctx p] (:cycle (:query/result (d/query-handler :regeneration-cycle/cycle-progress (assoc ctx :query {:cycle-id (:cycle-id p)})))))
(defn cycle-output [ctx p]
  (let [cycle (cycle-view ctx p)] {:run (case (:status cycle) "failed" "cycle-failed" "passed" "cycle-passed" "running") :cycle (json/write-str cycle) :halt "{}" :failure "{}"}))
(defn failed-output [stage reason detail] {:run "failing" :failure (json/write-str {:stage stage :reason reason :detail detail}) :halt "{}" :cycle ""})
(defn harness-digest [env p conf]
  (d/digest (str (slurp (get env "LOYLY_HARNESS_CONFIG")) (get env "LOYLY_SELF_CHECK_CMD")
    (cut-file env p (get conf "APP_PROBE"))
    (if (not (str/blank? (get env "LOYLY_GATE_CMD"))) (get env "LOYLY_GATE_CMD") (when (= "stdio" (get conf "APP_SEAM")) (get env "LOYLY_STORE_CHECK_CMD"))))))
(defn self-check-leaf [ctx]
  (let [{:keys [in p env conf]} (settings ctx) attempt (or (:attempt in) 0)]
    (try
      (let [r ((requiring-resolve 'loyly.process/shell!) (get env "LOYLY_PROJECT_REPO") (get env "LOYLY_SELF_CHECK_CMD") [] (get-in p [:budget :stage-timeouts-seconds (if (zero? attempt) :fence :gate)]) {})
            lines (str/split-lines (:out r)) last-line (or (last lines) (:err r))
            reply (d/issue ctx :regeneration-cycle/record-harness-self-check
              (merge (select-keys p [:cycle-id :cut-id :budget :grader-digests])
                {:occasion (if (zero? attempt) "before-fence" "before-gate") :attempt attempt
                 :harness-digest (harness-digest env p conf) :goldens-digest (d/digest (apply str (for [g (sort-by :golden-id (d/rows ctx :regeneration-cycle/instrument-goldens))] (str (:golden-id g) "\t" (:digest g) "\n"))))
                 :identity-probe-digest (d/digest (cut-file env p (get conf "APP_PROBE")))
                 :outcome (if (zero? (:exit r)) "passed" "failed") :failing-check last-line :detail last-line
                 :skipped-checks (vec (keep #(some-> (re-matches #"\s*SKIP\s+(.*)" %) second str/trim) lines))
                 :missing-goldens [] :reworded-goldens []}))]
        (record-effect ctx p "self-check" :regeneration-cycle/record-harness-self-check reply (str/join "\n" lines))
        (if (:refusal reply) (merge (cycle-output ctx p) (halt-output "self-check" "effect-could-not-run" (:refusal reply))) (cycle-output ctx p)))
      (catch Exception e (merge {:cycle "" :failure "{}"} (halt-output "self-check" "effect-could-not-run" (.getMessage e)))))))
(defn local-git [dir & args]
  (let [r (apply sh/sh "git" "-C" (str dir) args)] (when-not (zero? (:exit r)) (throw (ex-info (:err r) r))) (str/trim (:out r))))
(defn fence-leaf [ctx]
  (let [{:keys [in p env conf]} (settings ctx) room (str (:run-dir in) "/clean") paths (str/split (get conf "FENCED_PATHS" "") #"\s+")]
    (try
      (let [clone (sh/sh "git" "clone" "--no-local" "--no-checkout" (get env "LOYLY_PROJECT_REPO") room)
            _ (when-not (zero? (:exit clone)) (throw (ex-info (:err clone) clone)))
            _ (local-git room "checkout" "--detach" (:commit p))
            unmatched (vec (filter #(str/blank? (local-git room "ls-files" "--" %)) paths))
            _ (doseq [path paths] (local-git room "rm" "-r" "--ignore-unmatch" "--" path))
            tree (local-git room "write-tree")
            commit (local-git room "-c" "user.name=Loyly" "-c" "user.email=runner@example.invalid" "commit-tree" tree "-m" "Clean source cut")
            _ (local-git room "checkout" "--orphan" "clean")
            _ (local-git room "reset" "--hard" commit)
            _ (doseq [ref (str/split-lines (local-git room "for-each-ref" "--format=%(refname)")) :when (not= ref "refs/heads/clean")] (local-git room "update-ref" "-d" ref))
            _ (local-git room "remote" "remove" "origin")
            _ (local-git room "reflog" "expire" "--expire=now" "--all")
            _ (local-git room "gc" "--prune=now")
            reachable (str/split-lines (local-git room "rev-list" "--objects" "--all"))
            left (vec (filter #(not (str/blank? (local-git room "ls-files" "--" %))) paths))
            leaked (vec (filter (fn [path] (some #(let [s (second (str/split % #" " 2))] (and s (or (= s path) (str/starts-with? s (str path "/"))))) reachable)) paths))
            reply (d/issue ctx :regeneration-cycle/prove-clean-room
              {:cycle-id (:cycle-id p) :cut-id (:cut-id p) :clean-room-commit commit :fenced-paths (vec paths)
               :root-commits (count (str/split-lines (local-git room "rev-list" "--max-parents=0" "--all")))
               :commits (Long/parseLong (local-git room "rev-list" "--count" "--all")) :unmatched-paths unmatched :paths-left-in-tree left :paths-reachable-in-object-graph leaked})]
        (record-effect ctx p "fence" :regeneration-cycle/prove-clean-room reply room)
        (if (:refusal reply) (failed-output "fence" "fence-refused" (:refusal reply)) (cycle-output ctx p)))
      (catch Exception e (merge {:failure "{}" :cycle ""} (halt-output "fence" "effect-could-not-run" (.getMessage e)))))))
(defn fail-cycle-leaf [ctx]
  (let [in (input ctx) p (params in) reply (d/issue ctx :regeneration-cycle/fail-cycle (assoc (json/read-str (:failure in) :key-fn keyword) :cycle-id (:cycle-id p)))]
    (record-effect ctx p "fail-cycle" :regeneration-cycle/fail-cycle reply "") (cycle-output ctx p)))
(defn conclude-leaf [ctx]
  (let [in (input ctx) p (params in) cycle (cycle-view ctx p)]
    (d/issue ctx :cycle-runner/conclude-run {:run-id (:run-id p) :cycle-status (:status cycle) :cycle cycle}) {:run (:status cycle)}))
(defn file-digest [file]
  (str "sha256:" (format "%064x" (java.math.BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256") (java.nio.file.Files/readAllBytes (.toPath (io/file file))))))))
(defn tree-digest [dir]
  (let [root (.toPath (io/file dir))]
    (d/digest (apply str (for [f (sort-by str (file-seq (io/file dir))) :when (.isFile f)]
      (str (.relativize root (.toPath f)) "\t" (subs (file-digest f) 7) "\n"))))))
(defn render-failed-leaf [_] (halt-output "render" "effect-could-not-run" "The instruction model did not complete"))
(defn regenerate-leaf [ctx]
  (let [{:keys [in p env conf]} (settings ctx) room (str (:run-dir in) "/builder-1")
        instruction (str (:run-dir in) "/instruction-1.txt") session (str (:run-id p) "/" (:tick-id ctx) "/1")]
    (try
      (when-not (.exists (io/file room))
        ((requiring-resolve 'loyly.process/copy!) (str (:run-dir in) "/clean") room))
      (spit instruction (:builder-instruction in))
      (let [image (get conf "BUILDER_IMAGE")
            args (cond-> [room instruction] (not (str/blank? image)) (conj (str (get env "LOYLY_BUILDER_IMAGES") "/" image)))
            r ((requiring-resolve 'loyly.process/shell!) (get env "LOYLY_PROJECT_REPO") (get env "LOYLY_BUILDER_HOST") args (get-in p [:budget :stage-timeouts-seconds :regenerate]) {"LOYLY_BUILDER_SESSION_ID" session})
            candidate (io/file room (get conf "APP_DIR"))]
        (cond
          (:timeout r) (merge {:attempt 0 :builder-room ""} (failed-output "regenerate" "budget" "Builder exceeded its regenerate budget"))
          (not (some #(.isFile %) (file-seq candidate))) (merge {:attempt 0 :builder-room ""} (failed-output "regenerate" "candidate-not-regenerated" "Builder left no candidate"))
          :else (let [digest (tree-digest candidate)
                      reply (d/issue ctx :regeneration-cycle/record-regenerated-candidate {:cycle-id (:cycle-id p) :attempt 1 :purpose "fresh-generation" :candidate-digest digest :mounted-evidence []})]
            (record-effect ctx p "regenerate" :regeneration-cycle/record-regenerated-candidate reply (str session " instruction " (file-digest instruction) (when image (str " image " image " " (tree-digest (io/file (get env "LOYLY_BUILDER_IMAGES") image))))))
            (merge {:attempt 1 :builder-room room} (if (:refusal reply) (halt-output "regenerate" "effect-could-not-run" (:refusal reply)) (cycle-output ctx p))))))
      (catch Exception e (merge {:attempt 0 :builder-room "" :cycle "" :failure "{}"} (halt-output "regenerate" "effect-could-not-run" (.getMessage e)))))))
(defn archive! [env p target]
  (.mkdirs (io/file target))
  (let [archive (str target ".tar") result (sh/sh "git" "-C" (get env "LOYLY_PROJECT_REPO") "archive" "--format=tar" (str "--output=" archive) (:commit p))]
    (when-not (zero? (:exit result)) (throw (ex-info (:err result) result)))
    (let [r (sh/sh "tar" "-xf" archive "-C" target)] (when-not (zero? (:exit r)) (throw (ex-info (:err r) r))))
  (io/delete-file (str target ".tar"))))
(defn install-leaf [ctx]
  (let [{:keys [in p env conf]} (settings ctx) n (:attempt in) gate-room (str (:run-dir in) "/gate-" n) candidate (if (str/blank? (get env "LOYLY_GATE_CMD")) (str gate-room "/" (get conf "APP_DIR")) (str (:run-dir in) "/candidate-" n))]
    (try
      (if (str/blank? (get env "LOYLY_GATE_CMD"))
        (do ((requiring-resolve 'loyly.process/copy!) (str (:run-dir in) "/clean") gate-room)
            ((requiring-resolve 'loyly.process/remove!) (str gate-room "/.git")))
        (archive! env p gate-room))
      ((requiring-resolve 'loyly.process/remove!) (str gate-room "/" (get conf "APP_DIR")))
      ((requiring-resolve 'loyly.process/copy!) (str (:builder-room in) "/" (get conf "APP_DIR")) candidate)
      (let [digest (tree-digest candidate) reply (d/issue ctx :regeneration-cycle/install-candidate-in-gate-room
        {:cycle-id (:cycle-id p) :attempt n :candidate-digest digest :purpose (if (= n 1) "fresh-generation" "verification-repair") :gate-room gate-room})]
        (record-effect ctx p "install" :regeneration-cycle/install-candidate-in-gate-room reply gate-room)
        (merge {:candidate (json/write-str {:path candidate :digest digest}) :gate-room gate-room}
          (if (:refusal reply) (halt-output "install" "effect-could-not-run" (:refusal reply)) (cycle-output ctx p))))
      (catch Exception e (merge {:candidate "{}" :gate-room "" :cycle "" :failure "{}"} (halt-output "install" "effect-could-not-run" (.getMessage e)))))))
(defn command-gate [env p conf in evidence candidate]
  (let [executable (str/starts-with? (git env "ls-tree" (:commit p) (get conf "APP_PROBE")) "100755")]
    (if-not executable
      {:outcome "failed" :reason "identity-probe-failed" :identity-probe-present false :seam-answered-before-boot false :boot-polls 20 :boot-budget-seconds 10 :detail "Probe not executable at cut"}
      (let [r ((requiring-resolve 'loyly.process/shell!) (get env "LOYLY_PROJECT_REPO") (get env "LOYLY_GATE_CMD") [(:gate-room in) (:path candidate) evidence] (get-in p [:budget :stage-timeouts-seconds :gate]) {})
            lines (str/split-lines (:out r)) last-line (or (last lines) "")
            boot (some #(re-matches #"BOOT (\d+) (\d+)" %) lines) answered (some #(re-matches #"ANSWERED (\d+)" %) lines)
            code (first (str/split last-line #"\s+")) passed (zero? (:exit r)) guard (#{"seam-answered-before-boot" "identity-probe-missing"} code)]
        (spit (str evidence "/output.txt") (str (:out r) (:err r)))
        (cond
          (:timeout r) {:timeout true}
          (and passed (or (not boot) (not answered))) (throw (ex-info "Gate success omitted BOOT or ANSWERED" {}))
          (and (not passed) (not guard) (not (#{"contract-missing" "suite-discovery-empty" "suite-red" "boot-budget-exhausted" "identity-probe-failed"} code))) (throw (ex-info last-line {}))
          :else (cond-> {:outcome (if passed "passed" "failed") :identity-probe-present (not= code "identity-probe-missing")
            :seam-answered-before-boot (= code "seam-answered-before-boot") :boot-polls (if boot (Long/parseLong (second boot)) 20)
            :boot-budget-seconds (if boot (Long/parseLong (nth boot 2)) 10) :detail (if passed "" (str/trim (subs last-line (count code))))}
            passed (assoc :answered-on-poll (Long/parseLong (second answered)) :identity-probe-result last-line)
            (not passed) (assoc :reason (case code "seam-answered-before-boot" "boot-budget-exhausted" "identity-probe-missing" "identity-probe-failed" code))))))))
(defn gate-leaf [ctx]
  (let [{:keys [in p env conf]} (settings ctx) n (:attempt in) candidate (json/read-str (:candidate in) :key-fn keyword)
        evidence (str (:run-dir in) "/evidence-" n)]
    (try
      (.mkdirs (io/file evidence))
      (let [result (if (str/blank? (get env "LOYLY_GATE_CMD")) ((requiring-resolve 'loyly.host-gate/perform) env p conf in evidence) (command-gate env p conf in evidence candidate))]
        (if (:timeout result) (merge {:evidence-root evidence :gate-refusal "{}"} (failed-output "gate" "budget" "Gate exceeded its stage budget"))
          (let [reply (d/issue ctx :regeneration-cycle/record-gate-attempt
            (merge result {:cycle-id (:cycle-id p) :attempt n :candidate-digest (:digest candidate) :harness-digest (harness-digest env p conf)
              :identity-probe-digest (d/digest (cut-file env p (get conf "APP_PROBE"))) :evidence-root evidence :grader-digests (:grader-digests p)}))]
            (record-effect ctx p "gate" :regeneration-cycle/record-gate-attempt reply (or (:identity-probe-result result) (:detail result)))
            (merge {:evidence-root evidence :gate-refusal "{}"}
              (if (:refusal reply)
                (if (#{"seam-answered-before-boot" "identity-probe-missing" "identity-probe-not-the-instruments"} (:refusal reply))
                  (assoc (cycle-output ctx p) :run "gate-refused" :gate-refusal (json/write-str {:refusal (:refusal reply) :detail (:detail result)}))
                  (merge {:cycle "" :failure "{}"} (halt-output "gate" "effect-could-not-run" (:refusal reply))))
                (assoc (cycle-output ctx p) :run (if (= "passed" (:outcome result)) "gate-passed" (:run (cycle-output ctx p)))))))))
      (catch Exception e (merge {:evidence-root evidence :gate-refusal "{}" :cycle "" :failure "{}"} (halt-output "gate" "effect-could-not-run" (.getMessage e)))))))
(defn gate-refusal-leaf [ctx]
  (let [in (input ctx) p (params in) refusal (json/read-str (:gate-refusal in) :key-fn keyword)
        reply (d/issue ctx :regeneration-cycle/record-gate-refusal (merge refusal {:cycle-id (:cycle-id p) :attempt (:attempt in)}))]
    (record-effect ctx p "gate-refusal" :regeneration-cycle/record-gate-refusal reply (:detail refusal)) (cycle-output ctx p)))
(defn file-verdicts-leaf [ctx]
  (let [{:keys [in p env]} (settings ctx) candidate (json/read-str (:candidate in) :key-fn keyword)
        scores (keep (fn [x] (let [v (if (map? x) (:score x) x)] (when (and (string? v) (not (str/blank? v))) (json/read-str v :key-fn keyword)))) (:scores in))
        grader (d/digest (str "cycle-runner/grade.v1\n" (slurp (get env "LOYLY_GRADER_RUBRIC"))))]
    (let [reply (d/issue ctx :measurement/file-item-verdicts {:cycle-id (:cycle-id p) :candidate-digest (:digest candidate) :grader-digest grader
      :approved-grader-digests (:grader-digests p) :verdicts (mapv #(-> % (assoc :evidence (:reason %)) (dissoc :reason)) scores)})]
      (record-effect ctx p "file-verdicts" :measurement/file-item-verdicts reply "")
      (if (:refusal reply) (assoc (cycle-output ctx p) :run "scoring-failed" :failure (json/write-str {:refusal (:refusal reply) :detail (:refusal reply)})) (cycle-output ctx p)))))
(defn file-score-leaf [ctx]
  (let [{:keys [p]} (settings ctx) reply (d/issue ctx :measurement/file-score {:cycle-id (:cycle-id p) :approved-grader-digests (:grader-digests p)})]
    (record-effect ctx p "file-score" :measurement/file-score reply "")
    (if (:refusal reply) (assoc (cycle-output ctx p) :run "scoring-failed" :failure (json/write-str {:refusal (:refusal reply) :detail (:refusal reply)})) (cycle-output ctx p))))
(defn fail-score-stage-leaf [ctx]
  (let [in (input ctx) p (params in) reply (d/issue ctx :measurement/fail-score-stage (assoc (json/read-str (:failure in) :key-fn keyword) :cycle-id (:cycle-id p)))]
    (record-effect ctx p "fail-score-stage" :measurement/fail-score-stage reply "") (cycle-output ctx p)))
(defn command-leaf [ctx]
  (let [in (input ctx) reply (d/issue ctx (keyword (:method in)) (params in))]
    {:reply (json/write-str reply)}))
(defn repair-leaf [ctx]
  (let [{:keys [in p env conf]} (settings ctx) n (inc (:attempt in)) room (str (:run-dir in) "/builder-" n)
        previous (str room "/previous-candidate") evidence (str room "/gate-evidence") candidate (json/read-str (:candidate in) :key-fn keyword)
        instruction (str (:run-dir in) "/instruction-" n ".txt") session (str (:run-id p) "/" (or (:parent-tick-id ctx)
          (:parent-tick-id (first (into [] (filter #(= (:tick-id ctx) (:tick-id %))) (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types #{:sheet/tree-tick-started}}))))) "/" n)]
    (try
      (when-not (.exists (io/file room))
        ((requiring-resolve 'loyly.process/copy!) (str (:run-dir in) "/clean") room))
      (when-not (.exists (io/file previous)) ((requiring-resolve 'loyly.process/copy!) (:path candidate) previous))
      (when-not (.exists (io/file evidence)) ((requiring-resolve 'loyly.process/copy!) (:evidence-root in) evidence))
      (doseq [f (concat (file-seq (io/file previous)) (file-seq (io/file evidence)))] (.setWritable f false false))
      (spit instruction (str (:builder-instruction in) "\n\nRepair the candidate using the previous candidate at " previous " and the gate evidence at " evidence ". Both paths are read-only."))
      (let [image (get conf "BUILDER_IMAGE") args (cond-> [room instruction] (not (str/blank? image)) (conj (str (get env "LOYLY_BUILDER_IMAGES") "/" image)))
            result ((requiring-resolve 'loyly.process/shell!) (get env "LOYLY_PROJECT_REPO") (get env "LOYLY_BUILDER_HOST") args (get-in p [:budget :stage-timeouts-seconds :regenerate]) {"LOYLY_BUILDER_SESSION_ID" session})
            app (io/file room (get conf "APP_DIR"))]
        (cond
          (:timeout result) (merge {:attempt (:attempt in) :builder-room (:builder-room in)} (failed-output "regenerate" "budget" "Repair builder exceeded its budget"))
          (not (some #(.isFile %) (file-seq app))) (merge {:attempt (:attempt in) :builder-room room} (failed-output "regenerate" "candidate-not-regenerated" "Repair left no candidate"))
          :else (let [reply (d/issue ctx :regeneration-cycle/record-regenerated-candidate {:cycle-id (:cycle-id p) :attempt n :purpose "verification-repair" :candidate-digest (tree-digest app) :parent-candidate-digest (:digest candidate) :mounted-evidence [(:evidence-root in)]})]
            (record-effect ctx p "repair" :regeneration-cycle/record-regenerated-candidate reply (str (:evidence-root in) " " session " instruction " (file-digest instruction) (when image (str " image " image " " (tree-digest (io/file (get env "LOYLY_BUILDER_IMAGES") image))))))
            (merge {:attempt n :builder-room room} (cycle-output ctx p)))))
      (catch Exception e (merge {:attempt (:attempt in) :builder-room (:builder-room in) :cycle "" :failure "{}"} (halt-output "repair" "effect-could-not-run" (.getMessage e)))))))
