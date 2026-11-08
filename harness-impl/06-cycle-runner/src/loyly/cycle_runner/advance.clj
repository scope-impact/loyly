(ns loyly.cycle-runner.advance
  "The processor advance-run. Whenever a run started, an effect was recorded, a handoff
   relayed or the verdicts handed in, one step: relay the producers' next new handoff; else,
   if the cycle concluded, conclude the run; else perform the run's next effect in the real
   world and record its result into its context; if the effect could not be performed at
   all, halt the run. Each step issues one command, whose event triggers the next step, until
   the run awaits its verdicts, concluded or halted.

   The order of the effects lives here, not in a requires (domain-design.md, decision 1).
   Every rule a context owns is the context's: a refusal is recorded as heard."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.cycle-runner.blocks :as blocks]
            [loyly.cycle-runner.contexts :as contexts]
            [loyly.cycle-runner.effects :as fx]
            [loyly.cycle-runner.gherkin :as gherkin]
            [loyly.cycle-runner.repo :as repo]
            [loyly.cycle-runner.system :as system]
            [loyly.cycle-runner.util :as u]))

;; ---------------------------------------------------------------------------
;; The handoffs: who produces each, who consumes it (each context's event-model)
;; ---------------------------------------------------------------------------

(def handoff-consumers
  "Each producer's handoff events (its event-model/handoffs.edn) and, in order, every
   consumer whose event model consumes it."
  {"specification" {:specification/scenario-stamped ["measurement"]}
   "cycle-preparation" {:cycle-preparation/source-cut-pinned ["measurement" "regeneration-cycle"]}
   "grader-lifecycle" {:grader-lifecycle/instrument-golden-written ["regeneration-cycle"]
                       :grader-lifecycle/instrument-golden-moved ["regeneration-cycle"]}
   "regeneration-cycle" {:regeneration-cycle/gate-attempt-passed ["measurement"]}
   "measurement" {:measurement/measurement-filed ["regeneration-cycle"]
                  :measurement/score-stage-failed ["regeneration-cycle"]
                  :measurement/scoreboard-reported ["regeneration-cycle"]}})

(def run-event-types
  #{:cycle-runner/run-started :cycle-runner/effect-recorded :cycle-runner/handoff-relayed
    :cycle-runner/verdicts-handed-in :cycle-runner/run-concluded :cycle-runner/run-halted})

;; ---------------------------------------------------------------------------
;; Sessions: what a run holds while its contexts run
;; ---------------------------------------------------------------------------

(defn- read-text [path] (when (and path (.isFile (io/file path))) (slurp path)))

(defn harness-digest
  "SHA-256 of the configuration file, the self-check command and the identity probe, in
   that order."
  [config-bytes self-check-cmd probe-bytes]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (.update md ^bytes (or config-bytes (byte-array 0)))
    (.update md ^bytes (u/utf8 (or self-check-cmd "")))
    (.update md ^bytes (or probe-bytes (byte-array 0)))
    (str "sha256:" (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md))))))

(defn prepare
  "Everything the run acts on, read now from what was handed in."
  [{:keys [env run-id commit] :as base}]
  (let [repo-path (env "LOYLY_PROJECT_REPO")
        config-path (env "LOYLY_HARNESS_CONFIG")
        config-text (read-text config-path)
        config (repo/parse-config config-text)
        probe-bytes (when (:app-probe config) (repo/show-bytes repo-path commit (:app-probe config)))
        plan (if (:plan-dir config) (repo/plan repo-path commit (:plan-dir config)) [])
        instruction (let [v (or (env "LOYLY_BUILDER_INSTRUCTION") "")]
                      (or (when (and (not (str/blank? v)) (< (count v) 4096)) (read-text v)) v))]
    (merge base
           {:repo repo-path
            :config config
            :config-path config-path
            :self-check-cmd (env "LOYLY_SELF_CHECK_CMD")
            :builder-cmd (env "LOYLY_BUILDER_CMD")
            :instruction instruction
            :plan plan
            :stamp-slices (vec (remove :status plan))
            :probe-present? (some? probe-bytes)
            :identity-probe-digest (str "sha256:" (u/sha256-hex (or probe-bytes (byte-array 0))))
            :harness-digest (harness-digest (when config-text (u/file-bytes config-path)) (env "LOYLY_SELF_CHECK_CMD") probe-bytes)
            :prep-error (cond (nil? config-text) (str "The harness configuration " config-path " cannot be read.")
                              (nil? (:app-dir config)) "The harness configuration names no APP_DIR.")})))

(defn scenario-digests
  "The cut's scenario digests, keyed by item tag and by title (LOYLY_SCENARIO_DIGESTS)."
  [plan]
  (into {}
        (for [s plan sc (get-in s [:reading :scenarios])
              k (cond-> [(:title sc)] (:item-id sc) (conj (str "@item-" (:item-id sc))))]
          [k (:digest sc)])))

(defn store-of
  "The producer's own store, opened for reading once it exists."
  [session producer]
  (or (get @(:stores session) producer)
      (let [path (get-in session [:store-paths producer])]
        (when (and path (.isFile (io/file path)))
          (let [s (es/start {:conn {:type :sqlite :database-file path}})]
            (swap! (:stores session) assoc producer s)
            s)))))

(defn close-session! [session]
  (contexts/stop-all (:clients session))
  (doseq [[_ s] @(:stores session)] (try (es/stop s) (catch Throwable _))))

;; ---------------------------------------------------------------------------
;; Commands, issued as the ORC workflow of their flow
;; ---------------------------------------------------------------------------

(defn- settle! [sys run-id]
  (when-let [p (get @(:settles sys) run-id)] (deliver p true)))

(defn- end-session! [sys run-id]
  (when-let [s (get @(:sessions sys) run-id)]
    (swap! (:sessions sys) dissoc run-id)
    (close-session! s)))

(defn halt!
  "Halt the run, loudly; nothing is recorded for the effect that could not be performed."
  [sys run-id effect reason detail]
  (u/log "run " run-id " halted at " effect ": " reason ": " detail)
  (system/issue! sys :cycle-runner/effect-not-performed-halts-run :cycle-runner/halt-run
                 {:run-id run-id :effect effect :reason reason :detail detail})
  (end-session! sys run-id)
  (settle! sys run-id))

(defn- flow-for [row] (if (= "grading" (:status row)) :cycle-runner/verdicts-to-conclusion :cycle-runner/run-to-gate))

(defn- no-response [context method]
  (ex-info (str context " did not respond to " method) {::no-response context ::method method}))

(defn ask
  "Call a context's method; a context that does not respond throws ::no-response."
  [session context method params]
  (let [client (get-in session [:clients context])
        r (if client
            (contexts/call client method params (:call-timeout-ms session))
            ::contexts/no-response)]
    (if (map? r) r (throw (no-response context method)))))

(defn record!
  "Record the effect's result through the context's method, then the context's reply as
   heard (record-effect)."
  [sys session row {:keys [effect attempt context method params detail]}]
  (let [r (ask session context method params)
        refused? (contains? r :error)
        refusal (when refused?
                  (or (get-in r [:error :data :refusal])
                      (str "error " (get-in r [:error :code]) ": " (get-in r [:error :message]))))
        events (if refused? [] (vec (filter map? (get-in r [:result :events]))))
        detail (str detail (when refused? (str "; " context " refused: " refusal
                                               (when-let [m (get-in r [:error :message])] (str " (" m ")")))))]
    (system/issue! sys (flow-for row) :cycle-runner/record-effect
                   (cond-> {:run-id (:run-id row) :effect effect :attempt attempt :context context
                            :method method :outcome (if refused? "refused" "recorded")
                            :events events :detail detail}
                     refused? (assoc :refusal refusal)))))

;; ---------------------------------------------------------------------------
;; The relay (E8)
;; ---------------------------------------------------------------------------

(defn- handoff-params [ev]
  (into {} (remove (fn [[k _]] (#{"event" "grain"} (namespace k)))) ev))

(defn producer-handoffs
  "The producer's handoff events, in store order, within its tenant."
  [session producer]
  (if-let [s (store-of session producer)]
    (let [types (set (keys (handoff-consumers producer)))]
      (vec (mapcat (fn [tid] (into [] (es/read s {:tenant-id tid :types types})))
                   (sort-by str (keys (es/tenants s))))))
    []))

(defn next-relay [sys session run-id]
  (let [done (set (map (juxt :producer :position :consumer)
                       (blocks/relayed-handoffs (:ctx sys) run-id)))]
    (first
     (for [producer contexts/names
           [pos ev] (map-indexed (fn [i e] [(inc i) e]) (producer-handoffs session producer))
           consumer (get-in handoff-consumers [producer (:event/type ev)])
           :when (not (done [producer pos consumer]))]
       {:producer producer :position pos :consumer consumer :event ev}))))

(defn relay!
  "Hand the producer's handoff in, unchanged, to the consumer; record how it took it."
  [sys session row {:keys [producer position consumer event]}]
  (let [type (let [t (:event/type event)] (str (namespace t) "/" (name t)))
        r (ask session consumer type (handoff-params event))]
    (system/issue! sys (flow-for row) :cycle-runner/relay-handoff
                   {:run-id (:run-id row) :handoff-type type :producer producer
                    :position position :consumer consumer
                    :outcome (if (contains? r :result) "accepted" "refused")})))

;; ---------------------------------------------------------------------------
;; The conclusion
;; ---------------------------------------------------------------------------

(defn cycle-progress [session cycle-id]
  (get-in (ask session "regeneration-cycle" "regeneration-cycle/cycle-progress" {:cycle-id cycle-id})
          [:result :cycle]))

(defn conclude!
  [sys row cycle]
  (let [status (:status cycle)]
    (system/issue! sys (if (= "failed" status) :cycle-runner/cycle-failed-ends-run
                           :cycle-runner/verdicts-to-conclusion)
                   :cycle-runner/conclude-run
                   {:run-id (:run-id row) :cycle-status status :cycle cycle})
    (end-session! sys (:run-id row))
    (settle! sys (:run-id row))))

;; ---------------------------------------------------------------------------
;; The next effect
;; ---------------------------------------------------------------------------

(defn- event-of [result type]
  (some #(when (= type (str (:type %))) %) (:events result)))

(defn- refused? [r] (= "refused" (:outcome r)))

(defn- plan-ids [slice]
  (vec (keep :item-id (remove #(str/blank? (:title %)) (get-in slice [:reading :scenarios])))))

(defn- stamped-ids [result]
  (vec (keep #(when (= "specification/scenario-stamped" (str (:type %))) (:item-id %)) (:events result))))

(def infrastructure-refusals
  #{"seam-answered-before-boot" "identity-probe-missing" "identity-probe-not-the-instruments"})

(defn next-action
  "What the run does next, from its effect results so far."
  [session row results]
  (let [last-r (peek results)
        slices (:stamp-slices session)
        stamps (filterv #(= "stamp" (:effect %)) results)
        n (:attempts row)
        stuck (fn [why] {:do :halt :effect (or (:effect last-r) "stamp") :reason "effect-could-not-run"
                         :detail why})
        before-cycle (fn [effect] {:do :halt :effect effect :reason "refused-before-the-cycle"
                                   :detail (str (:context last-r) " refused " (:method last-r) ": "
                                                (:refusal last-r))})]
    (if (= "grading" (:status row))
      (let [last-of (fn [e] (last (filter #(= e (:effect %)) results)))
            fv (last-of "file-verdicts") fs (last-of "file-score") fss (last-of "fail-score-stage")]
        (cond
          fss (stuck "the score stage was failed, and the cycle did not conclude")
          (nil? fv) {:do :file-verdicts}
          (refused? fv) {:do :fail-score-stage :refusal (:refusal fv) :detail (:detail fv)}
          (nil? fs) {:do :file-score}
          (refused? fs) {:do :fail-score-stage :refusal (:refusal fs) :detail (:detail fs)}
          :else (stuck "the score was filed, and the cycle did not conclude")))
      (case (:effect last-r)
        nil (if (seq slices) {:do :stamp :index 0} {:do :pin})

        "stamp"
        (let [i (dec (count stamps))
              plan (plan-ids (nth slices i))
              stamped (stamped-ids last-r)]
          (cond
            (refused? last-r) (before-cycle "stamp")
            (not= plan stamped)
            {:do :halt :effect "stamp" :reason "refused-before-the-cycle"
             :detail (str "slice " (:slice (nth slices i)) ": the plan's identities "
                          (pr-str plan) " are not the stamp's " (pr-str stamped))}
            (< (count stamps) (count slices)) {:do :stamp :index (count stamps)}
            :else {:do :pin}))

        "pin" (if (refused? last-r) (before-cycle "pin") {:do :checklist})

        "checklist" (if (refused? last-r) (before-cycle "checklist")
                        {:do :self-check :occasion "before-fence" :attempt 0})

        "self-check"
        (if (zero? (:attempt last-r))
          (cond
            (refused? last-r) (before-cycle "self-check")
            (event-of last-r "regeneration-cycle/harness-self-check-passed") {:do :fence}
            :else (stuck "the self-check failed, and the cycle did not conclude"))
          (if (and (not (refused? last-r))
                   (not (event-of last-r "regeneration-cycle/harness-self-check-passed")))
            (stuck "the self-check before the gate failed, and the cycle did not conclude")
            {:do :gate :attempt (:attempt last-r)}))

        "fence" (if (refused? last-r)
                  {:do :fail-cycle :stage "fence" :reason "fence-refused" :attempt 0
                   :detail (:refusal last-r)}
                  {:do :regenerate :attempt 1})

        "fail-cycle" (stuck "fail-cycle was recorded, and the cycle did not conclude")

        ("regenerate" "repair") (if (refused? last-r)
                                  (stuck (str "the regenerated candidate was refused: " (:refusal last-r)))
                                  {:do :install :attempt (:attempt last-r)})

        "install" (if (refused? last-r)
                    (stuck (str "the install was refused: " (:refusal last-r)))
                    {:do :self-check :occasion "before-gate" :attempt (:attempt last-r)})

        "gate" (cond
                 (and (refused? last-r) (infrastructure-refusals (:refusal last-r)))
                 {:do :gate-refusal :attempt (:attempt last-r) :refusal (:refusal last-r)}
                 (refused? last-r) (stuck (str "the gate attempt was refused: " (:refusal last-r)))
                 (event-of last-r "regeneration-cycle/gate-attempt-failed")
                 {:do :repair :attempt (inc (:attempt last-r))}
                 :else (stuck "the gate passed, and the run does not await its verdicts"))

        "gate-refusal" (if (refused? last-r)
                         (stuck (str "the gate refusal was refused: " (:refusal last-r)))
                         {:do :self-check :occasion "before-gate" :attempt (:attempt last-r)})

        (stuck (str "nothing follows " (:effect last-r)))))))

;; ---------------------------------------------------------------------------
;; Performing each effect
;; ---------------------------------------------------------------------------

(defn- dir [session & parts] (apply io/file (:dir session) parts))

(defn- goldens-digest
  "regeneration-cycle/seam.md's formula over grader-lifecycle/instrument-goldens."
  [session]
  (let [gs (get-in (ask session "grader-lifecycle" "grader-lifecycle/instrument-goldens" {}) [:result :goldens])
        current (sort-by :golden-id (map (fn [g] {:golden-id (:golden-id g)
                                                  :digest (or (:to-digest (last (:moves g))) (:digest g))})
                                         gs))]
    (u/sha256-of-string (apply str (map #(str (:golden-id %) "\t" (:digest %) "\n") current)))))

(defn- gated-candidate [results]
  (some #(:candidate-digest (event-of % "regeneration-cycle/gate-attempt-passed")) (reverse results)))

(defn- handed-in [sys run-id]
  (last (filter #(= run-id (:run-id %))
                (into [] (es/read (:store sys) {:tenant-id (get-in sys [:ctx :tenant-id])
                                                :types #{:cycle-runner/verdicts-handed-in}})))))

(defn- builder-run
  "Launch one fresh builder for attempt n and record what it left (regenerate or repair),
   or fail the cycle when it left nothing or outlived the regenerate timeout."
  [sys session row n repair?]
  (let [app-dir (get-in session [:config :app-dir])
        room (str (dir session "builder-rooms" (str "attempt-" n)))
        snapshot (str (dir session "candidates" (str "attempt-" n)))
        instr (dir session "instructions" (str "attempt-" n ".md"))
        prev (str (dir session "candidates" (str "attempt-" (dec n))))
        evidence (str (dir session "evidence" (str "attempt-" (dec n))))
        timeout (get-in session [:budget :stage-timeouts-seconds :regenerate])
        effect (if repair? "repair" "regenerate")]
    (fx/prepare-builder-room (str (dir session "clean-room")) room app-dir)
    (u/mkdirs (.getParentFile instr))
    (when repair?
      ;; The previous candidate and this attempt's gate evidence, read-only, in the room.
      (u/copy-tree! prev (io/file room ".repair" "previous-candidate"))
      (u/copy-tree! evidence (io/file room ".repair" "gate-evidence" (str "attempt-" (dec n))))
      (u/make-read-only! (io/file room ".repair")))
    (spit instr (str (:instruction session)
                     (when repair?
                       (str "\n\nThis is a verification repair of attempt " (dec n) ". The previous candidate is at "
                            ".repair/previous-candidate and the gate evidence of attempt " (dec n)
                            " is at .repair/gate-evidence/attempt-" (dec n)
                            ", both read-only, in this builder room. Write the repaired candidate in "
                            app-dir ".\n"))))
    (let [r (fx/launch-builder session room (str instr) app-dir snapshot timeout
                               (str (dir session "logs" (str "builder-" n ".log"))))
          stage (or (:stage (cycle-progress session (:cycle-id row))) "regenerate")]
      (cond
        (:could-not-run r) {:halt {:effect effect :reason "effect-could-not-run" :detail (:could-not-run r)}}

        (:timed-out? r)
        {:effect "fail-cycle" :attempt n :context "regeneration-cycle" :method "regeneration-cycle/fail-cycle"
         :params {:cycle-id (:cycle-id row) :stage stage :reason "budget"
                  :detail (str "the builder outlived the regenerate timeout of " timeout " s and was stopped")}
         :detail (str "builder for attempt " n " stopped after " timeout " s")}

        (nil? (:candidate-digest r))
        {:effect "fail-cycle" :attempt n :context "regeneration-cycle" :method "regeneration-cycle/fail-cycle"
         :params {:cycle-id (:cycle-id row) :stage stage :reason "candidate-not-regenerated"
                  :detail (str "the builder exited " (:exit r) " and left nothing in " app-dir)}
         :detail (str "builder for attempt " n " exited " (:exit r) " and left nothing in " app-dir)}

        :else
        {:effect effect :attempt n :context "regeneration-cycle"
         :method "regeneration-cycle/record-regenerated-candidate"
         :params (cond-> {:cycle-id (:cycle-id row) :attempt n
                          :purpose (if repair? "verification-repair" "fresh-generation")
                          :candidate-digest (:candidate-digest r)
                          :mounted-evidence (if repair? [evidence] [])}
                   repair? (assoc :parent-candidate-digest (fx/candidate-digest prev)))
         :detail (str "builder for attempt " n " exited " (:exit r) " and left " (:files r) " files in "
                      app-dir ", candidate " (:candidate-digest r) " taken as it ended"
                      (when repair? (str "; mounted, read-only: the previous candidate of attempt " (dec n)
                                         " and the gate evidence of attempt " (dec n) " (" evidence ")")))}))))

(defn effect-recording
  "Perform the action's effect in the real world and say how to record it."
  [sys session row results {:keys [do] :as action}]
  (let [cycle-id (:cycle-id row) cut-id (:cut-id row)
        config (:config session)]
    (case do
      :stamp
      (let [slice (nth (:stamp-slices session) (:index action))
            copy (dir session "stamp" (:folder slice) "spec.feature")
            stripped (gherkin/strip-item-tags (:text slice))
            reading (gherkin/read-feature stripped)
            unstamped (vec (for [s (:scenarios reading) :when (not (str/blank? (:title s)))]
                             {:line (:line s) :title (:title s)}))
            empty-lines (vec (for [s (:scenarios reading) :when (str/blank? (:title s))] (:line s)))]
        (u/mkdirs (.getParentFile copy))
        (spit copy stripped)
        {:effect "stamp" :attempt 0 :context "specification" :method "specification/stamp-slice"
         :params {:slice (:slice slice) :source (u/abs-path copy) :unstamped unstamped
                  :empty-title-lines empty-lines}
         :detail (str "slice " (:slice slice) " (" (:source slice) " at " (:commit row) "), its item tags removed, at "
                      (u/abs-path copy) "; offered " (count unstamped) " unstamped scenarios"
                      (when (seq empty-lines) (str " and empty-title headings at lines " (str/join ", " empty-lines)))
                      "; the plan's identities " (pr-str (plan-ids slice)))})

      :pin
      (let [tree (repo/tree-of (:repo session) (:commit row))]
        {:effect "pin" :attempt 0 :context "cycle-preparation" :method "cycle-preparation/pin-source-cut"
         :params {:cut-id cut-id :commit (:commit row) :tree-digest tree}
         :detail (str "commit " (:commit row) ", tree " tree)})

      :checklist
      (let [plan (:plan session)
            read (remove :status plan)
            items (vec (for [s read sc (get-in s [:reading :scenarios])
                             :when (and (:item-id sc) (not (str/blank? (:title sc))))]
                         {:item-id (:item-id sc) :scenario-digest (:digest sc) :rule (:rule sc)
                          :source (str (:source s) ":" (:line sc)) :title (:title sc)}))
            unstamped (vec (for [s read sc (get-in s [:reading :scenarios])
                                 :when (and (nil? (:item-id sc)) (not (str/blank? (:title sc))))]
                             {:source (str (:source s) ":" (:line sc)) :title (:title sc)}))
            skipped (vec (for [s plan :when (:status s)] {:slice (:slice s) :status (:status s)}))]
        {:effect "checklist" :attempt 0 :context "measurement" :method "measurement/derive-checklist"
         :params {:cut-id cut-id :selection-id (:selection-id session) :items items
                  :unstamped unstamped :skipped-slices skipped}
         :detail (str (count items) " stamped scenarios of " (count read) " slices"
                      (when (seq unstamped) (str ", " (count unstamped) " unstamped"))
                      (when (seq skipped) (str ", skipped " (pr-str skipped))))})

      :self-check
      (let [goldens (goldens-digest session)
            r (fx/self-check session)
            failing (if (str/blank? (:last-line r)) (str "the self-check exited " (:exit r)) (:last-line r))]
        {:effect "self-check" :attempt (:attempt action) :context "regeneration-cycle"
         :method "regeneration-cycle/record-harness-self-check"
         :params (cond-> {:cycle-id cycle-id :cut-id cut-id :occasion (:occasion action)
                          :attempt (:attempt action) :harness-digest (:harness-digest session)
                          :goldens-digest goldens :identity-probe-digest (:identity-probe-digest session)
                          :outcome (if (:passed? r) "passed" "failed")
                          :skipped-checks (:skipped r) :missing-goldens [] :reworded-goldens []
                          :grader-digests (:grader-digests session) :budget (:budget session)}
                   (not (:passed? r)) (assoc :failing-check failing :detail failing))
         :detail (str "self-check " (:occasion action) " exited " (:exit r)
                      (if (:passed? r) ", passed" (str ", failed: " failing))
                      (when (seq (:skipped r))
                        (str "; skipped checks: " (str/join "; " (map #(str "\"" % "\"") (:skipped r))))))})

      :fence
      (let [r (fx/fence {:repo (:repo session) :commit (:commit row) :config config}
                        (str (dir session "clean-room")))]
        {:effect "fence" :attempt 0 :context "regeneration-cycle" :method "regeneration-cycle/prove-clean-room"
         :params (assoc (select-keys r [:clean-room-commit :fenced-paths :root-commits :commits :unmatched-paths
                                        :paths-left-in-tree :paths-reachable-in-object-graph])
                        :cycle-id cycle-id :cut-id cut-id)
         :detail (str "clean room " (:room r) " at " (:clean-room-commit r) "; fenced " (pr-str (:fenced-paths r))
                      ", found " (pr-str (:found r)) ", matching nothing " (pr-str (:unmatched-paths r))
                      "; root commits " (:root-commits r) ", commits " (:commits r)
                      ", left in tree " (pr-str (:paths-left-in-tree r))
                      ", reachable " (pr-str (:paths-reachable-in-object-graph r)))})

      :fail-cycle
      {:effect "fail-cycle" :attempt (:attempt action) :context "regeneration-cycle"
       :method "regeneration-cycle/fail-cycle"
       :params {:cycle-id cycle-id :stage (:stage action) :reason (:reason action) :detail (str (:detail action))}
       :detail (str "the cycle fails at the " (:stage action) " for " (:reason action) ": " (:detail action))}

      :regenerate (builder-run sys session row (:attempt action) false)
      :repair (builder-run sys session row (:attempt action) true)

      :install
      (let [n (:attempt action)
            r (fx/install (str (dir session "clean-room")) (str (dir session "candidates" (str "attempt-" n)))
                          (str (dir session "gate-rooms" (str "attempt-" n))) (:app-dir config))]
        {:effect "install" :attempt n :context "regeneration-cycle"
         :method "regeneration-cycle/install-candidate-in-gate-room"
         :params {:cycle-id cycle-id :attempt n :candidate-digest (:candidate-digest r)
                  :purpose (if (= 1 n) "fresh-generation" "verification-repair") :gate-room (:gate-room r)}
         :detail (str "candidate " (:candidate-digest r) " copied into the gate room " (:gate-room r))})

      :gate
      (let [n (:attempt action)
            gate-room (str (dir session "gate-rooms" (str "attempt-" n)))
            evidence (str (dir session "evidence" (str "attempt-" n)))
            installed (some #(when (and (= "install" (:effect %)) (= n (:attempt %)))
                               (:candidate-digest (event-of % "regeneration-cycle/candidate-installed-in-gate-room")))
                            (reverse results))
            digest (or installed (fx/candidate-digest (io/file gate-room (:app-dir config))))
            r (fx/gate session gate-room evidence (get-in session [:budget :stage-timeouts-seconds :gate]))]
        {:effect "gate" :attempt n :context "regeneration-cycle" :method "regeneration-cycle/record-gate-attempt"
         :params (cond-> {:cycle-id cycle-id :attempt n :candidate-digest digest
                          :harness-digest (:harness-digest session) :outcome (:outcome r)
                          :identity-probe-present (boolean (:identity-probe-present r))
                          :identity-probe-digest (:identity-probe-digest session)
                          :seam-answered-before-boot (boolean (:seam-answered-before-boot r))
                          :boot-budget-seconds (:boot-budget-seconds config) :boot-polls (:boot-polls config)
                          :evidence-root evidence :detail (:detail r)
                          :grader-digests (:grader-digests session)}
                   (:reason r) (assoc :reason (:reason r))
                   (:answered-on-poll r) (assoc :answered-on-poll (:answered-on-poll r))
                   (:identity-probe-result r) (assoc :identity-probe-result (:identity-probe-result r)))
         :detail (str "gate on attempt " n ": " (:outcome r) (when (:reason r) (str " for " (:reason r)))
                      ": " (:detail r) "; evidence " evidence)})

      :gate-refusal
      {:effect "gate-refusal" :attempt (:attempt action) :context "regeneration-cycle"
       :method "regeneration-cycle/record-gate-refusal"
       :params {:cycle-id cycle-id :attempt (:attempt action) :refusal (:refusal action)
                :detail (str "the gate refused to grade attempt " (:attempt action) ": " (:refusal action))}
       :detail (str "the gate refused to grade attempt " (:attempt action) ": " (:refusal action))}

      :file-verdicts
      (let [h (handed-in sys (:run-id row))
            {:keys [verdicts]} (blocks/verdicts-reading (:verdicts-path h))
            filed (mapv (fn [v] (cond-> {:item-id (:id v) :verdict (:verdict v)}
                                  (contains? v :evidence) (assoc :evidence (str (:evidence v)))
                                  (contains? v :reason) (assoc :reason (str (:reason v)))))
                        verdicts)]
        (if (nil? verdicts)
          {:halt {:effect "file-verdicts" :reason "effect-could-not-run"
                  :detail (str "the verdicts file " (:verdicts-path h) " can no longer be read")}}
          {:effect "file-verdicts" :attempt (:attempts row) :context "measurement"
           :method "measurement/file-item-verdicts"
           :params {:cycle-id cycle-id :candidate-digest (gated-candidate results)
                    :grader-digest (:grader-digest h) :approved-grader-digests (:grader-digests session)
                    :verdicts filed}
           :detail (str (count filed) " verdicts from " (:verdicts-path h) " at grader digest " (:grader-digest h)
                        ", for candidate " (gated-candidate results))}))

      :file-score
      {:effect "file-score" :attempt (:attempts row) :context "measurement" :method "measurement/file-score"
       :params {:cycle-id cycle-id :approved-grader-digests (:grader-digests session)}
       :detail (str "the score of cycle " cycle-id)}

      :fail-score-stage
      {:effect "fail-score-stage" :attempt (:attempts row) :context "measurement"
       :method "measurement/fail-score-stage"
       :params {:cycle-id cycle-id :refusal (str (:refusal action)) :detail (str (:detail action))}
       :detail (str "the score stage fails: " (:refusal action))})))

;; ---------------------------------------------------------------------------
;; One step
;; ---------------------------------------------------------------------------

(defn- latest-event-id [sys run-id]
  (:event/id (last (filter #(= run-id (:run-id %))
                           (into [] (es/read (:store sys) {:tenant-id (get-in sys [:ctx :tenant-id])
                                                           :types run-event-types}))))))

(defn step!
  "One step of the run, when the triggering event is the run's latest fact."
  [sys run-id event-id]
  (let [ctx (:ctx sys)
        row (blocks/run ctx run-id)]
    (when (and row (= event-id (latest-event-id sys run-id)))
      (let [session (get @(:sessions sys) run-id)
            results (blocks/effect-results ctx run-id)
            phase (volatile! (or (:effect (peek results)) "stamp"))]
        (try
          (cond
            (not (blocks/in-progress (:status row))) (settle! sys run-id)

            (nil? session)
            (halt! sys run-id (or (:effect (peek results)) "stamp") "context-did-not-respond"
                   "the run's contexts are not running in this runner")

            :else
            (if-let [relay (do (vreset! phase "relay") (next-relay sys session run-id))]
              (relay! sys session row relay)
              (let [cycle (cycle-progress session (:cycle-id row))]
                (cond
                  (#{"passed" "failed"} (:status cycle)) (conclude! sys row cycle)
                  (= "awaiting-verdicts" (:status row)) (settle! sys run-id)
                  (:prep-error session) (halt! sys run-id "stamp" "effect-could-not-run" (:prep-error session))
                  :else
                  (let [action (next-action session row results)]
                    (vreset! phase (name (:do action)))
                    (if (= :halt (:do action))
                      (halt! sys run-id (:effect action) (:reason action) (:detail action))
                      (let [rec (try (effect-recording sys session row results action)
                                     (catch clojure.lang.ExceptionInfo e
                                       (if (::no-response (ex-data e)) (throw e)
                                           {:halt {:effect (name (:do action)) :reason "effect-could-not-run"
                                                   :detail (ex-message e)}})))]
                        (if-let [h (:halt rec)]
                          (halt! sys run-id (:effect h) (:reason h) (:detail h))
                          (try
                            (record! sys session row rec)
                            (catch clojure.lang.ExceptionInfo e
                              (if (::no-response (ex-data e))
                                (halt! sys run-id (:effect rec) "context-did-not-respond" (ex-message e))
                                (throw e))))))))))))
          (catch clojure.lang.ExceptionInfo e
            (if (::no-response (ex-data e))
              (halt! sys run-id @phase "context-did-not-respond" (ex-message e))
              (do (u/log "step failed: " (ex-message e))
                  (try (halt! sys run-id (or (:effect (peek results)) "stamp") "effect-could-not-run" (ex-message e))
                       (catch Throwable _ (settle! sys run-id))))))
          (catch Throwable t
            (u/log "step failed: " t)
            (try (halt! sys run-id (or (:effect (peek results)) "stamp") "effect-could-not-run" (str t))
                 (catch Throwable _ (settle! sys run-id)))))))))

(defn processor-handler
  "Grain todo processor :cycle-runner/advance-run."
  [{:keys [event] :as ctx}]
  (let [sys @(:loyly/system ctx)
        run-id (:run-id event)]
    {:result/effect (fn [] (step! sys run-id (:event/id event)))
     :result/checkpoint :after}))
