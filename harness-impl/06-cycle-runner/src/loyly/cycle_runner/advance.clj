(ns loyly.cycle-runner.advance
  "The processor advance-run: whenever a run started, an effect was recorded, a
   handoff relayed or the verdicts handed in, it relays the producers' new
   handoffs; then, if the cycle concluded, concludes the run; else performs the
   run's next effect in the real world and records its result into the context
   that owns it; if the effect could not be performed at all, it halts the run.
   One action per step, each derived from what is recorded: the order of the
   effects is the legacy cycle's (seam.md, \"The effects\")."
  (:require [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [clojure.string :as str]
            [loyly.cycle-runner.blocks :as blocks]
            [loyly.cycle-runner.contexts :as contexts]
            [loyly.cycle-runner.effects :as effects]
            [loyly.cycle-runner.model :as model]
            [loyly.cycle-runner.orc :as orc]
            [loyly.cycle-runner.repo :as repo]
            [loyly.cycle-runner.runtime :as runtime]
            [loyly.cycle-runner.util :as u]))

(def RC "regeneration-cycle")

;; ---------------------------------------------------------------------------
;; Rest: a method responds once its run has nothing left to do.

(defonce rests (atom {}))

(defn- rest! [ctx run-id]
  (swap! rests assoc run-id {:at (System/nanoTime) :run (blocks/run-of ctx run-id)}))

(defn wait-for-rest
  "Block until a step after `t0` found nothing due for the run and `ok?` holds
   for the run it saw."
  [run-id t0 ok?]
  (loop []
    (let [{:keys [at run]} (get @rests run-id)]
      (if (and at (> at t0) (ok? run))
        run
        (do (Thread/sleep 25) (recur))))))

;; ---------------------------------------------------------------------------
;; Asking a context; issuing the runner's own commands

(def ^:dynamic *effect* "advance")

(defmacro ^:private doing [effect & body]
  `(binding [*effect* ~effect] ~@body))

(defn- ask [rt context method params]
  (let [reply (contexts/call (get-in rt [:contexts context]) method params)]
    (when (:no-response reply)
      (throw (ex-info (str context " did not respond when asked " method)
                      {::halt "context-did-not-respond" ::effect *effect*})))
    (when-let [e (:error reply)]
      (swap! (:refusals rt) assoc *effect* e))
    reply))

(defn- could-not-run [effect detail]
  (throw (ex-info detail {::halt "effect-could-not-run" ::effect effect})))

(defn- after-verdicts? [run] (some? (:verdicts-digest run)))

(defn- issue-own! [ctx flow command]
  (let [r (orc/issue! ctx flow command)]
    (when (:cognitect.anomalies/category r)
      (throw (ex-info (str "The runner's own command " (:command/name command) " was refused: "
                           (or (:cycle-runner/refusal r) (:cognitect.anomalies/message r)))
                      {::own-refusal true :result r})))
    r))

(defn heard-refusal [error]
  (or (get-in error [:data :refusal])
      (str "error " (:code error) ": " (:message error))))

(defn- record!
  "Record one effect's result into the runner's own store: the context's reply
   as heard."
  [ctx run {:keys [effect attempt context method reply detail]}]
  (let [error (:error reply)
        events (let [es (get-in reply [:result :events])] (if (sequential? es) (vec (filter map? es)) []))
        params (cond-> {:command/name :cycle-runner/record-effect
                        :run-id (:run-id run) :effect effect :attempt attempt :context context
                        :method method :detail (str detail)}
                 error (assoc :outcome "refused" :refusal (heard-refusal error) :events [])
                 (not error) (assoc :outcome "recorded" :events events))]
    (issue-own! ctx (if (after-verdicts? run) "cycle-runner/verdicts-to-conclusion" "cycle-runner/run-to-gate")
                params)
    :recorded))

(defn- record-through!
  "Ask the context's method with params and record its reply as the effect."
  [ctx rt run effect attempt context method params detail]
  (let [reply (ask rt context method params)
        detail (if-let [e (:error reply)]
                 (str detail " (refused: " (heard-refusal e)
                      (when-let [ps (get-in e [:data :paths])] (str " " (str/join ", " ps)))
                      ")")
                 detail)]
    (record! ctx run {:effect effect :attempt attempt :context context :method method
                      :reply reply :detail detail})))

;; ---------------------------------------------------------------------------
;; Ending a run

(defn- close-run! [rt]
  (try (contexts/stop-all! (:contexts rt)) (catch Exception _ nil))
  (doseq [s (vals @(:stores rt))] (contexts/close-store s)))

(defn- halt! [ctx rt run effect reason detail]
  (issue-own! ctx "cycle-runner/effect-not-performed-halts-run"
              {:command/name :cycle-runner/halt-run :run-id (:run-id run)
               :effect effect :reason reason :detail detail})
  (u/log "run" (:run-id run) "halted at" effect ":" reason "-" detail)
  (close-run! rt)
  :halted)

(defn- conclude! [ctx rt run cycle]
  (let [status (:status cycle)]
    (issue-own! ctx (if (= "passed" status) "cycle-runner/verdicts-to-conclusion" "cycle-runner/cycle-failed-ends-run")
                {:command/name :cycle-runner/conclude-run :run-id (:run-id run)
                 :cycle-status status :cycle cycle})
    (close-run! rt)
    :concluded))

;; ---------------------------------------------------------------------------
;; The relay (E8)

(defn- store-of [rt producer]
  (or (get @(:stores rt) producer)
      (let [s (contexts/open-store (get-in rt [:contexts producer :store]))]
        (when s (swap! (:stores rt) assoc producer s))
        s)))

(defn- relay-one!
  "Hand the first handoff not yet relayed to a consumer in, unchanged, and
   record how the consumer took it."
  [ctx rt run]
  (let [done (set (map (juxt :producer :position :consumer) (blocks/relayed-of ctx (:run-id run))))]
    (some
     (fn [producer]
       (some
        (fn [{:keys [position type fields]}]
          (some
           (fn [consumer]
             (when-not (done [producer position consumer])
               (let [reply (ask rt consumer type fields)]
                 (issue-own! ctx (if (after-verdicts? run) "cycle-runner/verdicts-to-conclusion" "cycle-runner/run-to-gate")
                             {:command/name :cycle-runner/relay-handoff :run-id (:run-id run)
                              :handoff-type type :producer producer :position position
                              :consumer consumer :outcome (if (:error reply) "refused" "accepted")})
                 :relayed)))
           (model/consumers producer (keyword type))))
        (contexts/handoffs (store-of rt producer) producer)))
     model/contexts)))

;; ---------------------------------------------------------------------------
;; Reading what was recorded

(defn- of [rows effect] (filterv #(= effect (:effect %)) rows))
(defn- refused? [row] (= "refused" (:outcome row)))
(defn- recorded? [row] (= "recorded" (:outcome row)))
(defn- event-of [row type] (first (filter #(= type (:type %)) (:events row))))

(defn- cycle-progress [rt run]
  (let [reply (ask rt RC "regeneration-cycle/cycle-progress" {:cycle-id (:cycle-id run)})]
    (get-in reply [:result :cycle])))

(defn- conclude-if-concluded! [ctx rt run rows]
  (when (some recorded? (of rows "self-check"))
    (let [cycle (cycle-progress rt run)]
      (when (and (map? cycle) (#{"passed" "failed"} (:status cycle)))
        (conclude! ctx rt run cycle)))))

;; ---------------------------------------------------------------------------
;; The effects, E1 to E7

(defn- stamp! [ctx rt run i]
  (let [{:keys [name number text]} (nth (:slices rt) i)
        copy (str (:run-dir rt) "/stamp/" name "/spec.feature")
        stripped (repo/strip-item-tags text)
        _ (u/spit-utf8 copy stripped)
        reading (repo/read-spec stripped)
        unstamped (mapv #(select-keys % [:line :title]) (:scenarios reading))]
    (record-through! ctx rt run "stamp" 0 "specification" "specification/stamp-slice"
                     {:slice number :source (u/abs-path copy) :unstamped unstamped
                      :empty-title-lines (:empty-title-lines reading)}
                     (str "slice " number " (" name "): " (count unstamped)
                          " unstamped scenarios offered from " (u/abs-path copy)))))

(defn- stamp-mismatch
  "The identities the stamp handed out, against the plan's own, in file order."
  [rt i row]
  (when (recorded? row)
    (let [slice (nth (:slices rt) i)
          own (vec (keep :item-id (:scenarios (repo/read-spec (:text slice)))))
          handed (vec (keep #(when (= "specification/scenario-stamped" (:type %)) (:item-id %)) (:events row)))]
      (when (not= own handed)
        (str "slice " (:number slice) " (" (:name slice) "): the plan's identities are ["
             (str/join " " own) "], the stamp handed out [" (str/join " " handed) "]")))))

(defn- pin! [ctx rt run]
  (let [tree (repo/tree-of (:repo rt) (:commit rt))]
    (record-through! ctx rt run "pin" 0 "cycle-preparation" "cycle-preparation/pin-source-cut"
                     {:cut-id (:cut-id run) :commit (:commit run) :tree-digest tree}
                     (str "commit " (:commit run) " tree " tree))))

(defn- checklist! [ctx rt run]
  (let [{:keys [items unstamped skipped-slices]} (:checklist rt)]
    (record-through! ctx rt run "checklist" 0 "measurement" "measurement/derive-checklist"
                     {:cut-id (:cut-id run) :selection-id (:selection-id rt)
                      :items items :unstamped unstamped :skipped-slices skipped-slices}
                     (str (count items) " stamped items read from "
                          (count (:slices rt)) " selected slices"
                          (when (seq skipped-slices) (str ", " (count skipped-slices) " skipped"))))))

(defn- goldens-digest [rt]
  (let [reply (ask rt "grader-lifecycle" "grader-lifecycle/instrument-goldens" {})
        goldens (get-in reply [:result :goldens])
        current (for [g (if (sequential? goldens) goldens [])]
                  [(str (:golden-id g))
                   (str (or (some-> (:moves g) last :to-digest) (:digest g)))])]
    (u/sha256-of-text (apply str (for [[id d] (sort-by first current)] (str id "\t" d "\n"))))))

(defn- self-check! [ctx rt run attempt occasion]
  (let [{:keys [self-check-cmd repo env]} rt]
    (when-not (u/program-runnable? self-check-cmd repo env)
      (could-not-run "self-check" (str "the self-check " (pr-str (or self-check-cmd "")) " cannot be started")))
    (let [r (effects/self-check rt)
          params (cond-> {:cycle-id (:cycle-id run) :cut-id (:cut-id run) :occasion occasion
                          :attempt attempt :harness-digest (:harness-digest rt)
                          :goldens-digest (goldens-digest rt)
                          :identity-probe-digest (:probe-digest rt)
                          :outcome (if (:passed? r) "passed" "failed")
                          :skipped-checks (:skipped r) :missing-goldens [] :reworded-goldens []
                          :grader-digests (:grader-digests rt) :budget (:budget rt)}
                   (not (:passed? r)) (assoc :failing-check (:failing-check r) :detail (:failing-check r)))]
      (record-through! ctx rt run "self-check" attempt RC "regeneration-cycle/record-harness-self-check"
                       params
                       (str occasion " attempt " attempt ": "
                            (if (:passed? r) "passed" (str "failed: " (:failing-check r)))
                            (when (seq (:skipped r))
                              (str "; skipped checks: " (str/join "; " (:skipped r)))))))))

(defn- fail-cycle! [ctx rt run attempt stage reason detail]
  (record-through! ctx rt run "fail-cycle" attempt RC "regeneration-cycle/fail-cycle"
                   {:cycle-id (:cycle-id run) :stage stage :reason reason :detail detail}
                   (str stage ": " reason ": " detail)))

(defn- fence! [ctx rt run]
  (let [room (str (:run-dir rt) "/clean-room")
        probe (try (effects/fence! {:repo (:repo rt) :commit (:commit rt)
                                    :fenced-paths (get-in rt [:config :fenced-paths])}
                                   room)
                   (catch Exception e (could-not-run "fence" (str "the fence could not run: " (.getMessage e)))))]
    (record-through! ctx rt run "fence" 0 RC "regeneration-cycle/prove-clean-room"
                     (merge {:cycle-id (:cycle-id run) :cut-id (:cut-id run)
                             :fenced-paths (get-in rt [:config :fenced-paths])}
                            probe)
                     (str "clean room " room " of " (:commit rt) ": " (:root-commits probe) " root commits, "
                          (:commits probe) " commits"
                          (when (seq (:unmatched-paths probe))
                            (str "; matching nothing: " (str/join " " (:unmatched-paths probe))))))))

(defn- regenerate!
  "A fresh builder (attempt 1) or a repair builder (a later attempt), launched
   and waited for; the candidate it left copied when it ended."
  [ctx rt run attempt]
  (let [repair? (> attempt 1)
        {:keys [run-dir config builder-cmd env]} rt
        app-dir (:app-dir config)
        room (effects/builder-room! (str run-dir "/clean-room") (str run-dir "/builder-rooms/attempt-" attempt) app-dir)
        prev (get @(:attempts rt) (dec attempt))
        mounts (when repair?
                 (let [cand (str room "/.repair/previous-candidate")
                       evid (str room "/.repair/gate-evidence-attempt-" (dec attempt))]
                   (u/copy-tree! (:copy prev) cand)
                   (u/copy-tree! (:evidence prev) evid)
                   (u/set-read-only-tree! (str room "/.repair"))
                   {:candidate cand :evidence evid}))
        instruction (str (:builder-instruction rt)
                         (when repair?
                           (str "\n\nThis is a repair of attempt " (dec attempt) ", which the gate failed. "
                                "The previous candidate is at " (:candidate mounts)
                                " and the gate evidence of attempt " (dec attempt) " is at " (:evidence mounts)
                                ", both read-only. Write the repaired candidate in " app-dir ".\n")))
        instruction-file (str run-dir "/instructions/attempt-" attempt ".md")
        _ (u/spit-utf8 instruction-file instruction)
        _ (when-not (u/program-runnable? builder-cmd (System/getProperty "user.dir") env)
            (could-not-run (if repair? "repair" "regenerate")
                           (str "the builder " (pr-str (or builder-cmd "")) " cannot be started")))
        timeout (get-in rt [:budget :stage-timeouts-seconds :regenerate])
        launched (effects/launch-builder! rt room instruction-file timeout
                                                 (str run-dir "/logs/builder-attempt-" attempt ".log"))
        stage (if repair? (or (:stage (cycle-progress rt run)) "gate") "regenerate")]
    (if (:timed-out? launched)
      (fail-cycle! ctx rt run attempt stage "budget"
                   (str "the builder outlived the regenerate timeout of " timeout " seconds and was stopped"))
      (let [copy (str run-dir "/candidates/attempt-" attempt)
            {:keys [files digest]} (effects/take-candidate! room app-dir copy)]
        (if (zero? files)
          (fail-cycle! ctx rt run attempt stage "candidate-not-regenerated"
                       (str "the builder left nothing in " app-dir " (it exited " (:exit launched) ")"))
          (let [gate-room (effects/gate-room! config (str run-dir "/clean-room") copy
                                              (str run-dir "/gate-rooms/attempt-" attempt))]
            (swap! (:attempts rt) assoc attempt
                   {:copy copy :digest digest :gate-room gate-room
                    :purpose (if repair? "verification-repair" "fresh-generation")
                    :evidence (str run-dir "/evidence/attempt-" attempt)})
            (record-through! ctx rt run (if repair? "repair" "regenerate") attempt RC
                             "regeneration-cycle/record-regenerated-candidate"
                             (cond-> {:cycle-id (:cycle-id run) :attempt attempt
                                      :purpose (if repair? "verification-repair" "fresh-generation")
                                      :candidate-digest digest
                                      :mounted-evidence (if repair? [(:evidence prev)] [])}
                               repair? (assoc :parent-candidate-digest (:digest prev)))
                             (str "candidate " digest " (" files " files) taken from " room "/" app-dir
                                  "; the builder exited " (:exit launched)
                                  (when repair? (str "; mounted read-only: " (:candidate mounts) ", " (:evidence mounts)))))))))))

(defn- install! [ctx rt run attempt]
  (let [{:keys [digest purpose gate-room]} (get @(:attempts rt) attempt)]
    (record-through! ctx rt run "install" attempt RC "regeneration-cycle/install-candidate-in-gate-room"
                     {:cycle-id (:cycle-id run) :attempt attempt :candidate-digest digest
                      :purpose purpose :gate-room gate-room}
                     (str "candidate " digest " installed in " gate-room))))

(defn- gate! [ctx rt run attempt]
  (let [{:keys [digest gate-room evidence]} (get @(:attempts rt) attempt)
        config (:config rt)
        opts (merge rt config)
        g (if (= "stdio" (:app-seam config))
            (effects/gate-stdio opts {:room gate-room :probe (:probe rt) :evidence evidence})
            (effects/gate-http opts {:room gate-room :probe (:probe rt) :evidence evidence}))]
    (when-let [why (:could-not-run g)] (could-not-run "gate" why))
    (let [refused (:refused g)
          params (cond-> {:cycle-id (:cycle-id run) :attempt attempt :candidate-digest digest
                          :harness-digest (:harness-digest rt)
                          :outcome (or (:outcome g) "failed")
                          :identity-probe-present (not= "identity-probe-missing" refused)
                          :identity-probe-digest (:probe-digest rt)
                          :seam-answered-before-boot (= "seam-answered-before-boot" refused)
                          :boot-budget-seconds (:boot-budget-seconds g) :boot-polls (:boot-polls g)
                          :evidence-root evidence :detail (:detail g)
                          :grader-digests (:grader-digests rt)}
                   (:reason g) (assoc :reason (:reason g))
                   refused (assoc :reason "identity-probe-failed")
                   (:answered-on-poll g) (assoc :answered-on-poll (:answered-on-poll g))
                   (:identity-probe-result g) (assoc :identity-probe-result (:identity-probe-result g)))]
      (swap! (:attempts rt) assoc-in [attempt :last-gate-detail] (:detail g))
      (record-through! ctx rt run "gate" attempt RC "regeneration-cycle/record-gate-attempt"
                       params (:detail g)))))

(defn- gate-refusal! [ctx rt run attempt refusal]
  (let [detail (or (get-in @(:attempts rt) [attempt :last-gate-detail]) refusal)]
    (record-through! ctx rt run "gate-refusal" attempt RC "regeneration-cycle/record-gate-refusal"
                     {:cycle-id (:cycle-id run) :attempt attempt :refusal refusal :detail detail}
                     (str "the gate refused to grade attempt " attempt ": " refusal))))

(defn- gated-attempt [run] (or (:run/gated-attempt run) (:attempts run)))

(defn- file-verdicts! [ctx rt run]
  (let [{:keys [verdicts]} (blocks/read-verdicts (:run/verdicts-path run))
        verdicts (vec (for [v verdicts]
                        (cond-> {:item-id (str (:id v)) :verdict (if (string? (:verdict v)) (:verdict v) (str (:verdict v)))}
                          (contains? v :evidence) (assoc :evidence (str (:evidence v)))
                          (contains? v :reason) (assoc :reason (str (:reason v))))))
        digest (or (:run/gated-candidate-digest run)
                   (get-in @(:attempts rt) [(gated-attempt run) :digest]))]
    (record-through! ctx rt run "file-verdicts" (gated-attempt run) "measurement" "measurement/file-item-verdicts"
                     {:cycle-id (:cycle-id run) :candidate-digest digest
                      :grader-digest (:run/grader-digest run)
                      :approved-grader-digests (:run/grader-digests run)
                      :verdicts verdicts}
                     (str (count verdicts) " verdicts from " (:run/verdicts-path run)
                          " filed as given, grader " (:run/grader-digest run)))))

(defn- file-score! [ctx rt run]
  (record-through! ctx rt run "file-score" (gated-attempt run) "measurement" "measurement/file-score"
                   {:cycle-id (:cycle-id run) :approved-grader-digests (:run/grader-digests run)}
                   "the score filed from the verdicts filed"))

(defn- fail-score-stage! [ctx rt run refused-row]
  (record-through! ctx rt run "fail-score-stage" (gated-attempt run) "measurement" "measurement/fail-score-stage"
                   {:cycle-id (:cycle-id run) :refusal (:refusal refused-row) :detail (:detail refused-row)}
                   (str "the score was refused: " (:refusal refused-row))))

;; ---------------------------------------------------------------------------
;; The next effect

(defn- stuck [why] (could-not-run "advance" why))

(defn- next-after-verdicts! [ctx rt run rows]
  (let [fv (last (of rows "file-verdicts"))
        fs (last (of rows "file-score"))
        fss (last (of rows "fail-score-stage"))]
    (cond
      (nil? fv) (doing "file-verdicts" (file-verdicts! ctx rt run))
      (refused? fv) (when-not fss (doing "fail-score-stage" (fail-score-stage! ctx rt run fv)))
      (nil? fs) (doing "file-score" (file-score! ctx rt run))
      (refused? fs) (when-not fss (doing "fail-score-stage" (fail-score-stage! ctx rt run fs)))
      :else nil)))

(defn- next-effect! [ctx rt run rows]
  (if (= "grading" (:status run))
    (next-after-verdicts! ctx rt run rows)
    (let [slices (:slices rt)
          stamps (of rows "stamp")
          mismatch (first (keep-indexed #(stamp-mismatch rt %1 %2) stamps))
          pin (last (of rows "pin"))
          checklist (last (of rows "checklist"))
          self-checks (of rows "self-check")
          sc0 (first (filter #(zero? (:attempt %)) self-checks))
          fence (last (of rows "fence"))
          fail-cycles (of rows "fail-cycle")
          builds (filter #(#{"regenerate" "repair"} (:effect %)) rows)
          n (:attempts run)
          last-build (last builds)]
      (cond
        (some refused? stamps)
        (halt! ctx rt run "stamp" "refused-before-the-cycle"
               (str "specification refused the stamp: " (:refusal (first (filter refused? stamps)))))
        mismatch
        (halt! ctx rt run "stamp" "refused-before-the-cycle" mismatch)
        (< (count stamps) (count slices)) (doing "stamp" (stamp! ctx rt run (count stamps)))
        (nil? pin) (doing "pin" (pin! ctx rt run))
        (refused? pin)
        (halt! ctx rt run "pin" "refused-before-the-cycle" (str "cycle-preparation refused the pin: " (:refusal pin)))
        (nil? checklist) (doing "checklist" (checklist! ctx rt run))
        (refused? checklist)
        (halt! ctx rt run "checklist" "refused-before-the-cycle"
               (str "measurement refused the checklist: " (:refusal checklist)))
        (nil? sc0) (doing "self-check" (self-check! ctx rt run 0 "before-fence"))
        (refused? sc0)
        (halt! ctx rt run "self-check" "refused-before-the-cycle"
               (str "regeneration-cycle refused the self-check before the fence: " (:refusal sc0)))
        (seq fail-cycles)
        (if (refused? (last fail-cycles))
          (stuck (str "regeneration-cycle refused fail-cycle: " (:refusal (last fail-cycles))))
          (stuck "the Regeneration cycle did not conclude the cycle after fail-cycle"))
        (nil? fence) (doing "fence" (fence! ctx rt run))
        (refused? fence)
        (doing "fail-cycle"
          (fail-cycle! ctx rt run 0 "fence" "fence-refused"
                       (let [e (get @(:refusals rt) "fence")]
                         (str (:refusal fence)
                              (when-let [ps (seq (get-in e [:data :paths]))] (str ": " (str/join " " ps)))))))
        (nil? last-build) (doing "regenerate" (regenerate! ctx rt run 1))
        (refused? last-build)
        (stuck (str "regeneration-cycle refused the candidate: " (:refusal last-build)))
        :else
        (let [install (last (filter #(= n (:attempt %)) (of rows "install")))
              sc (last (filter #(= n (:attempt %)) self-checks))
              gates (filterv #(= n (:attempt %)) (of rows "gate"))
              refusals (filterv #(= n (:attempt %)) (of rows "gate-refusal"))
              last-gate (last gates)]
          (cond
            (nil? install) (doing "install" (install! ctx rt run n))
            (refused? install) (stuck (str "regeneration-cycle refused the install: " (:refusal install)))
            (nil? sc) (doing "self-check" (self-check! ctx rt run n "before-gate"))
            (refused? sc) (stuck (str "regeneration-cycle refused the self-check before the gate: " (:refusal sc)))
            (nil? last-gate) (doing "gate" (gate! ctx rt run n))
            (refused? last-gate)
            (cond
              (< (count refusals) (count (filter refused? gates))) (doing "gate-refusal" (gate-refusal! ctx rt run n (:refusal last-gate)))
              (refused? (last refusals)) (stuck (str "regeneration-cycle refused the gate refusal: " (:refusal (last refusals))))
              :else (doing "gate" (gate! ctx rt run n)))
            (event-of last-gate "regeneration-cycle/gate-attempt-passed") nil
            :else (doing "repair" (regenerate! ctx rt run (inc n)))))))))

;; ---------------------------------------------------------------------------
;; One step

(defonce ^:private lock (Object.))

(defn step!
  "One action for the run, if one is due: a relay, the conclusion, the next
   effect, or a halt. Records that it found nothing due."
  [ctx run-id]
  (locking lock
    (let [run (blocks/run-of ctx run-id)
          rt (runtime/get-run run-id)]
      (when (and run rt)
        (if-not (blocks/in-progress (:status run))
          (rest! ctx run-id)
          (let [did (try
                      (or (doing "relay" (relay-one! ctx rt run))
                          (when (some #{run-id} (blocks/runs-to-advance ctx))
                            (let [rows (blocks/effect-results-of ctx run-id)]
                              (or (doing "conclude" (conclude-if-concluded! ctx rt run rows))
                                  (next-effect! ctx rt run rows)))))
                      (catch Exception e
                        (let [{reason ::halt effect ::effect} (ex-data e)
                              effect (or effect "advance")]
                          (u/log "run" run-id ":" (.getMessage e))
                          (try
                            (halt! ctx rt (blocks/run-of ctx run-id) effect
                                   (or reason "effect-could-not-run") (.getMessage e))
                            (catch Exception e2
                              (u/log "run" run-id "could not be halted:" (.getMessage e2))
                              (close-run! rt)
                              :halted)))))]
            (when (or (nil? did) (#{:halted :concluded} did))
              (rest! ctx run-id))))))))

(defprocessor :cycle-runner advance-run
  {:topics #{:cycle-runner/run-started :cycle-runner/effect-recorded :cycle-runner/handoff-relayed
             :cycle-runner/verdicts-handed-in}
   :grain.event-model/reads #{:cycle-runner/runs-to-advance}
   :grain.event-model/produces #{:cycle-runner/record-effect :cycle-runner/relay-handoff
                                 :cycle-runner/conclude-run :cycle-runner/halt-run}}
  "Relay the producers' new handoffs; then conclude the run if its cycle concluded; else perform its next effect, or halt it."
  [ctx]
  (when-let [run-id (get-in ctx [:event :run-id])]
    (step! ctx run-id))
  {})
