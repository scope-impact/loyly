(ns runner.effects
  (:require [runner.util :as u] [runner.contexts :as c] [runner.model :as model] [runner.runtime :as rt]
            [clojure.java.io :as io] [clojure.string :as str]))
(defn cfg [s k] (get-in s [:config k]))
(defn env [s k] (get-in s [:env k]))
(defn repo [s] (env s "LOYLY_PROJECT_REPO"))
(defn param [s k] (get-in s [:params k]))
(defn local [s & xs] (apply u/path (:dir s) xs))
(defn stop [s] (c/stop! (:clients s)))
(defn cycle-view [s]
  (get-in (c/call (:clients s) "regeneration-cycle" "regeneration-cycle/cycle-progress" {:cycle-id (param s :cycle-id)}) [:result :cycle]))
(defn relay [ctx s]
  (c/relay! (:clients s) (:seen s) #(model/emit-command ctx :cycle-runner/relay-handoff %) (param s :run-id)))
(defn conclude [ctx s]
  (let [cycle (cycle-view s)]
    (when (#{"passed" "failed"} (:status cycle))
      (let [f #(model/emit-command ctx :cycle-runner/conclude-run {:run-id (param s :run-id) :cycle-status (:status cycle) :cycle cycle})]
        (if (= "failed" (:status cycle)) (rt/workflow! ctx "cycle-runner/cycle-failed-ends-run" f) (f)))
      (stop s)
      (throw (ex-info "cycle concluded" {:ended true})))))
(defn settle [ctx s]
  ;; Policies may append handoffs after their command's response. Read the producer,
  ;; not the response, until its policy queries have no pending work.
  (loop [n 0]
    (relay ctx s)
    (let [report (get-in (c/call (:clients s) "measurement" "measurement/measurements-to-report" {}) [:result :cycle-ids])
          due (get-in (c/call (:clients s) "regeneration-cycle" "regeneration-cycle/cycles-to-conclude" {}) [:result :cycle-ids])]
      (if (and (< n 100) (or (seq report) (seq due)))
        (do (Thread/sleep 30) (recur (inc n)))
        (do (Thread/sleep 25) (relay ctx s) (conclude ctx s))))))
(defn effect [ctx s effect attempt context method params detail]
  (reset! (:effect s) effect)
  (let [reply (c/call (:clients s) context method params)
        err (:error reply)
        result (cond-> {:run-id (param s :run-id) :effect effect :attempt attempt :context context :method method
                        :outcome (if err "refused" "recorded") :events (vec (get-in reply [:result :events] [])) :detail (str detail)}
                 err (assoc :refusal (or (get-in err [:data :refusal]) (:message err))))]
    (model/emit-command ctx :cycle-runner/record-effect result)
    (settle ctx s)
    result))
(defn rc [ctx s effect-name attempt method params detail]
  (effect ctx s effect-name attempt "regeneration-cycle" (str "regeneration-cycle/" method) (assoc params :cycle-id (param s :cycle-id)) detail))
(defn fail-cycle [ctx s stage reason detail]
  (rc ctx s "fail-cycle" 0 "fail-cycle" {:stage stage :reason reason :detail detail} detail))
(defn halted-refusal [result]
  (when (= "refused" (:outcome result))
    (throw (ex-info (:refusal result) {:reason "refused-before-the-cycle"}))))
(defn scenario-rows [text source]
  (let [lines (str/split text #"\n" -1)]
    (loop [i 0 rule nil tags [] rows []]
      (if (>= i (count lines)) rows
        (let [line (nth lines i) t (str/trim line)]
          (cond
            (str/starts-with? t "Rule:") (recur (inc i) (str/trim (subs t 5)) [] rows)
            (str/starts-with? t "@") (recur (inc i) rule (into tags (re-seq #"@\S+" t)) rows)
            (re-find #"^Scenario(?: Outline)?:" t)
            (let [title (str/trim (str/replace t #"^Scenario(?: Outline)?:" ""))
                  end (or (first (filter #(re-find #"^\s*(?:@|Rule:|Scenario(?: Outline)?:)" (nth lines %)) (range (inc i) (count lines)))) (count lines))
                  block (->> (subvec (vec lines) i end) reverse (drop-while str/blank?) reverse (str/join "\n"))
                  id (some #(second (re-matches #"@item-(\d+-\d+)" %)) tags)]
              (recur (inc i) rule [] (conj rows {:item-id id :line (inc i) :title title :rule rule :source (str source ":" (inc i)) :scenario-digest (u/digest block)})))
            (or (str/blank? t) (str/starts-with? t "#")) (recur (inc i) rule tags rows)
            :else (recur (inc i) rule [] rows)))))))
(defn plan [s]
  (let [commit (param s :commit) dir (cfg s :PLAN_DIR)
        selection (set (str/split (or (cfg s :PLAN_SLICES) "") #"\s+"))
        selected? #(or (= selection #{""}) (selection (first (str/split % #"-"))))
        entries (str/split-lines (u/git! (repo s) "ls-tree" (str commit ":" dir)))]
    (vec (for [entry entries
               :let [[_ mode name] (re-matches #"(\d+) \S+ \S+\t(.+)" entry)]
               :when (and (re-find #"^\d+-" name) (selected? name))
               :let [slice (first (str/split name #"-")) source (str dir "/" name "/spec.feature")
                     target (if (= mode "120000")
                              (str (.normalize (.toPath (io/file dir (u/git! (repo s) "show" (str commit ":" dir "/" name))))))
                              (str dir "/" name))
                     text (u/git! (repo s) "show" (str commit ":" target "/spec.feature"))]]
           {:slice slice :name name :source source :text text :rows (scenario-rows text source)}))))
(defn stripped [text]
  (->> (str/split text #"\n" -1)
       (keep (fn [line] (if (re-find #"^\s*@" line)
                         (let [s (str/replace line #"@item-\d+-\d+\s*" "")] (when-not (str/blank? s) (str/trimr s))) line)))
       (str/join "\n")))
(defn stamp-pin-checklist [ctx s]
  (reset! (:effect s) "stamp")
  (let [slices (plan s)]
    (u/json-file (:digests s) (into {} (for [slice slices row (:rows slice) :when (:item-id row)] [(:item-id row) (:scenario-digest row)])))
    (doseq [{:keys [slice name source text rows]} slices]
      (let [copy (local s "stamp" name "spec.feature") text (stripped text)
            _ (u/put copy text) offered (scenario-rows text copy)
            result (effect ctx s "stamp" 0 "specification" "specification/stamp-slice"
                           {:slice slice :source copy :unstamped (mapv #(select-keys % [:line :title]) (filter #(and (nil? (:item-id %)) (not (str/blank? (:title %)))) offered))
                            :empty-title-lines (mapv :line (filter #(str/blank? (:title %)) offered))} copy)
            original (vec (keep :item-id rows)) stamped (mapv :item-id (filter #(= "specification/scenario-stamped" (:type %)) (:events result)))]
        (halted-refusal result)
        (when-not (= original stamped)
          (throw (ex-info (str "plan identities " original " differ from stamp " stamped) {:reason "refused-before-the-cycle"})))))
    (halted-refusal (effect ctx s "pin" 0 "cycle-preparation" "cycle-preparation/pin-source-cut"
                            {:cut-id (param s :cut-id) :commit (param s :commit) :tree-digest (:tree s)} (:tree s)))
    (halted-refusal (effect ctx s "checklist" 0 "measurement" "measurement/derive-checklist"
                            {:cut-id (param s :cut-id) :selection-id (param s :selection-id)
                             :items (vec (for [sl slices r (:rows sl) :when (:item-id r)] (dissoc r :line)))
                             :unstamped (vec (for [sl slices r (:rows sl) :when (nil? (:item-id r))] (select-keys r [:source :title]))) :skipped-slices []} "checklist read from the cut"))))
(defn probe-text [s] (:out (u/git (repo s) "show" (str (param s :commit) ":" (cfg s :APP_PROBE)))))
(defn harness-digest [s]
  (u/digest (str (slurp (env s "LOYLY_HARNESS_CONFIG")) (env s "LOYLY_SELF_CHECK_CMD") (probe-text s)
                 (when (= "stdio" (cfg s :APP_SEAM)) (env s "LOYLY_STORE_CHECK_CMD")))))
(defn goldens-digest [s]
  (let [gs (get-in (c/call (:clients s) "grader-lifecycle" "grader-lifecycle/instrument-goldens" {}) [:result :goldens])]
    (u/digest (apply str (for [g (sort-by :golden-id gs)] (str (:golden-id g) "\t" (or (:to-digest (last (:moves g))) (:digest g)) "\n"))))))
(defn self-check [ctx s attempt]
  (reset! (:effect s) "self-check")
  (let [result (u/command (env s "LOYLY_SELF_CHECK_CMD") [] (repo s) {} false (get-in s [:params :budget :stage-timeouts-seconds (if (zero? attempt) :fence :gate)]))
        lines (str/split-lines (:out result)) passed (zero? (:exit result))
        params (cond-> {:cut-id (param s :cut-id) :occasion (if (zero? attempt) "before-fence" "before-gate") :attempt attempt
                        :harness-digest (harness-digest s) :identity-probe-digest (u/digest (probe-text s)) :goldens-digest (goldens-digest s)
                        :outcome (if passed "passed" "failed") :skipped-checks (vec (keep #(second (re-find #"^SKIP\s*(.*)" %)) lines))
                        :missing-goldens [] :reworded-goldens [] :grader-digests (param s :grader-digests) :budget (param s :budget)}
                 (not passed) (assoc :failing-check (or (last lines) "self-check failed") :detail (or (last lines) "self-check failed")))]
    (rc ctx s "self-check" attempt "record-harness-self-check" params (:out result))))
(defn fence [ctx s]
  (reset! (:effect s) "fence")
  (let [clean (local s "clean-room") paths (vec (remove str/blank? (str/split (cfg s :FENCED_PATHS) #"\s+")))
        r (u/run ["git" "-c" "safe.directory=*" "clone" "--no-checkout" "--depth" "1" (str "file://" (repo s)) clean] nil)
        _ (when-not (zero? (:exit r)) (throw (ex-info (:out r) {})))
        _ (u/git! clean "fetch" "--depth" "1" "origin" (param s :commit))
        _ (u/git! clean "checkout" "--detach" "FETCH_HEAD")
        unmatched (vec (filter #(str/blank? (u/git! clean "ls-files" "--" %)) paths))]
    (doseq [p paths] (u/git! clean "rm" "-rf" "--ignore-unmatch" "--" p))
    (u/git! clean "checkout" "--orphan" "fenced-root")
    (u/git! clean "-c" "user.name=Cycle runner" "-c" "user.email=runner@local" "commit" "--allow-empty" "-m" "Fenced source cut")
    (doseq [branch (remove #{"fenced-root"} (str/split-lines (u/git! clean "for-each-ref" "--format=%(refname:short)" "refs/heads")))] (u/git! clean "branch" "-D" branch))
    (u/git! clean "remote" "remove" "origin")
    (u/git! clean "reflog" "expire" "--expire=now" "--all")
    (u/git! clean "gc" "--prune=now")
    (let [tree (str/split-lines (u/git! clean "ls-files")) objects (str/split-lines (u/git! clean "rev-list" "--objects" "--all"))
          under? (fn [p file] (or (= p file) (str/starts-with? file (str (str/replace p #"/$" "") "/"))))
          left (vec (filter (fn [p] (some #(under? p %) tree)) paths))
          reachable (vec (filter (fn [p] (some #(under? p (second (str/split % #" " 2))) (filter #(str/includes? % " ") objects))) paths))
          result (rc ctx s "fence" 0 "prove-clean-room"
                     {:cut-id (param s :cut-id) :clean-room-commit (u/git! clean "rev-parse" "HEAD") :fenced-paths paths
                      :root-commits (count (str/split-lines (u/git! clean "rev-list" "--max-parents=0" "--all")))
                      :commits (Long/parseLong (u/git! clean "rev-list" "--count" "--all"))
                      :unmatched-paths unmatched :paths-left-in-tree left :paths-reachable-in-object-graph reachable} clean)]
      (when (= "refused" (:outcome result)) (fail-cycle ctx s "fence" "fence-refused" (:refusal result)))
      clean)))
(defn executable? [p] (.canExecute (io/file p)))
(defn regenerate [ctx s clean attempt previous]
  (reset! (:effect s) (if (= attempt 1) "regenerate" "repair"))
  (let [room (local s (str "builder-" attempt)) _ (u/copy clean room)
        scope (u/path room (cfg s :APP_DIR)) _ (u/remove-tree scope) _ (u/mkdir scope)
        evidence (when previous (:evidence previous))
        prior (when previous (u/copy (:snapshot previous) (u/path room "previous-candidate")))
        mounted (when evidence (u/copy evidence (u/path room "gate-evidence")))
        _ (u/run ["chmod" "-R" "a-w" room] nil)
        _ (u/run ["chmod" "u+rwx" scope] nil)
        instruction (u/put (local s (str "instruction-" attempt ".md"))
                           (str (slurp (env s "LOYLY_BUILDER_INSTRUCTION"))
                                (when previous (str "\nRepair using the previous candidate at " prior " and gate evidence at " mounted ", read-only.\n"))))
        image (not-empty (cfg s :BUILDER_IMAGE)) image-path (when image (u/path (env s "LOYLY_BUILDER_IMAGES") image))
        result (u/command-file (env s "LOYLY_BUILDER_CMD") (cond-> [room instruction] image (conj image-path)) (repo s) (get-in s [:params :budget :stage-timeouts-seconds :regenerate]) (local s (str "builder-" attempt ".log")))]
    (cond
      (:timeout result) (fail-cycle ctx s "regenerate" "budget" "builder outlived regenerate timeout")
      (not (some #(.isFile %) (file-seq (io/file scope)))) (fail-cycle ctx s "regenerate" "candidate-not-regenerated" "builder left no candidate")
      :else
      (let [snapshot (local s (str "candidate-" attempt))
            _ (u/copy scope snapshot) digest (u/tree-digest snapshot)
            detail (str "builder exit " (:exit result) (when image (str "; builder image " image " " (u/tree-digest image-path))))]
        (rc ctx s (if (= attempt 1) "regenerate" "repair") attempt "record-regenerated-candidate"
            (cond-> {:attempt attempt :purpose (if (= attempt 1) "fresh-generation" "verification-repair") :candidate-digest digest :mounted-evidence (if previous [evidence] [])}
              previous (assoc :parent-candidate-digest (:digest previous))) detail)
        {:snapshot snapshot :digest digest :attempt attempt}))))
(defn install [ctx s clean candidate]
  (reset! (:effect s) "install")
  (let [room (local s (str "gate-" (:attempt candidate))) _ (u/copy clean room)
        scope (u/path room (cfg s :APP_DIR)) _ (u/remove-tree scope) _ (u/copy (:snapshot candidate) scope)
        _ (u/remove-tree (u/path room ".git"))
        evidence (u/mkdir (local s (str "evidence-" (:attempt candidate))))]
    (rc ctx s "install" (:attempt candidate) "install-candidate-in-gate-room"
        {:attempt (:attempt candidate) :candidate-digest (u/tree-digest scope) :purpose (if (= 1 (:attempt candidate)) "fresh-generation" "verification-repair") :gate-room room} (:digest candidate))
    (assoc candidate :room room :scope scope :evidence evidence)))
(defn http-get [url] (u/run ["curl" "-sf" "--max-time" "0.4" url] nil {} false 2))
(defn gate [ctx s candidate]
  (reset! (:effect s) "gate")
  (let [{:keys [room scope evidence attempt digest]} candidate
        stdio? (= "stdio" (cfg s :APP_SEAM)) start (u/path scope "start.sh") suite (u/path scope "test.sh")
        probe (if stdio? (local s "identity-probe.sh") (u/path room (cfg s :APP_PROBE)))
        _ (when stdio? (u/put probe (probe-text s)) (u/run ["chmod" "+x" probe] nil))
        present (if stdio? (str/starts-with? (u/git! (repo s) "ls-tree" (param s :commit) "--" (cfg s :APP_PROBE)) "100755") (executable? probe))
        url (str "http://127.0.0.1:" (cfg s :PORT))
        base {:attempt attempt :candidate-digest digest :harness-digest (harness-digest s)
              :identity-probe-present present :identity-probe-digest (u/digest (probe-text s)) :seam-answered-before-boot false
              :boot-budget-seconds (if stdio? 30 10) :boot-polls (if stdio? 1 20) :evidence-root evidence :detail ""
              :grader-digests (param s :grader-digests)}
        fail (fn [reason detail] (assoc base :outcome "failed" :reason reason :detail detail))
        pass (fn [poll output] (assoc base :outcome "passed" :answered-on-poll poll :identity-probe-result (if (str/blank? output) "passed" (str/trim output)) :detail output))
        result
        (cond
          (not (and (executable? suite) (executable? start))) (fail "contract-missing" "test.sh and start.sh must be executable")
          (not present) (assoc base :outcome "failed" :reason "identity-probe-failed" :detail "identity probe missing")
          (and (not stdio?) (zero? (:exit (http-get url)))) (assoc base :outcome "failed" :reason "identity-probe-failed" :seam-answered-before-boot true :detail "seam answered before boot")
          :else
          (let [suite-result (u/run [suite] room {} stdio? (get-in s [:params :budget :stage-timeouts-seconds :gate]))]
            (if-not (zero? (:exit suite-result)) (fail "suite-red" (:out suite-result))
              (if stdio?
                (let [store (u/path evidence "events.sqlite")
                      p (u/run [probe start "30"] room {"GATE_STORE" store} true 35)]
                  (if-not (zero? (:exit p)) (fail "identity-probe-failed" (:out p))
                    (let [check (u/command (env s "LOYLY_STORE_CHECK_CMD") [store] (repo s) {} true 60)]
                      (if (zero? (:exit check)) (pass 1 (:out p)) (fail "identity-probe-failed" (:out check))))))
                (let [process (u/launch [start (cfg s :PORT)] room {} false)
                      sink (future (slurp (.getInputStream process)))]
                  (try
                    (loop [poll 1]
                      (let [response (http-get url)]
                        (cond
                          (zero? (:exit response))
                          (if-not (str/includes? (str/lower-case (:out response)) (str/lower-case (cfg s :APP_MARKER)))
                            (fail "identity-probe-failed" "page has no configured marker")
                            (let [p (u/run [probe url] room {} false 30)]
                              (if (zero? (:exit p)) (pass poll (:out p)) (fail "identity-probe-failed" (:out p)))))
                          (= poll 20) (fail "boot-budget-exhausted" "no HTTP response within boot budget")
                          :else (do (Thread/sleep 500) (recur (inc poll))))))
                    (finally (u/destroy process) (future-cancel sink))))))))]
    (u/put (u/path evidence "gate.txt") (u/encode result))
    (let [recorded (rc ctx s "gate" attempt "record-gate-attempt" result (:detail result))]
      (if (= "refused" (:outcome recorded))
        (do (rc ctx s "gate-refusal" attempt "record-gate-refusal" {:attempt attempt :refusal (:refusal recorded) :detail (:detail result)} (:detail result)) :retry)
        (if (some #(= "regeneration-cycle/gate-attempt-passed" (:type %)) (:events recorded)) :passed :failed)))))
(defn run-to-gate [ctx s]
  (stamp-pin-checklist ctx s)
  (self-check ctx s 0)
  (let [clean (fence ctx s)]
    (loop [attempt 1 previous nil]
      (let [candidate (install ctx s clean (regenerate ctx s clean attempt previous))]
        (self-check ctx s attempt)
        (let [outcome (loop [n 0] (let [r (gate ctx s candidate)] (if (and (= r :retry) (< n 2)) (recur (inc n)) r)))]
          (when-not (= :passed outcome)
            (conclude ctx s)
            (if (< attempt (get-in s [:params :budget :gate-attempt-limit]))
              (recur (inc attempt) candidate)
              (throw (ex-info "cycle did not conclude at its gate budget" {:reason "context-did-not-respond"})))))))))
(defn verdicts-to-conclusion [ctx s]
  (let [r (get (model/runs ctx) (param s :run-id))
        verdicts (mapv #(-> % (assoc :item-id (:id %)) (dissoc :id)) (:verdicts s))
        file-result (effect ctx s "file-verdicts" (:attempts r) "measurement" "measurement/file-item-verdicts"
                            {:cycle-id (param s :cycle-id) :candidate-digest (:candidate-digest r) :grader-digest (:grader-digest s)
                             :approved-grader-digests (param s :grader-digests) :verdicts verdicts} "handed-in verdicts, unchanged")
        result (if (= "refused" (:outcome file-result)) file-result
                 (effect ctx s "file-score" (:attempts r) "measurement" "measurement/file-score"
                         {:cycle-id (param s :cycle-id) :approved-grader-digests (param s :grader-digests)} "file score"))]
    (when (= "refused" (:outcome result))
      (effect ctx s "fail-score-stage" (:attempts r) "measurement" "measurement/fail-score-stage"
              {:cycle-id (param s :cycle-id) :refusal (:refusal result) :detail (:refusal result)} (:refusal result)))
    (loop [n 0] (settle ctx s) (if (< n 100) (do (Thread/sleep 30) (recur (inc n)))
                                  (throw (ex-info "cycle did not conclude after score" {:reason "context-did-not-respond"}))))))
