(ns loyly.regen.domain
  "The Allium of the regeneration cycle as pure functions: the folds of the read
   models, the `requires` of every rule (first failing one wins, in Allium order),
   the conclude-cycle policy and the stage clock. No I/O."
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]
           [java.time Instant OffsetDateTime Duration]))

;; ---------------------------------------------------------------------------
;; Goldens digest (contract HarnessSelfCheckRun, goldens_digest_of)
;; ---------------------------------------------------------------------------

(defn- code-point-compare [^String a ^String b]
  (let [ca (.toArray (.codePoints a))
        cb (.toArray (.codePoints b))
        n (min (alength ca) (alength cb))]
    (loop [i 0]
      (if (< i n)
        (let [c (Integer/compare (aget ca i) (aget cb i))]
          (if (zero? c) (recur (inc i)) c))
        (Integer/compare (alength ca) (alength cb))))))

(defn sha256-hex [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn goldens-digest
  "`goldens` is {golden-id {:digest d ...}}: one line per current golden,
   `<golden-id> TAB <digest> LF`, ordered by golden-id by code point."
  [goldens]
  (let [ids (sort code-point-compare (keys goldens))
        text (apply str (for [id ids] (str id "\t" (get-in goldens [id :digest]) "\n")))]
    (str "sha256:" (sha256-hex text))))

;; ---------------------------------------------------------------------------
;; Time
;; ---------------------------------------------------------------------------

(defn ->instant ^Instant [t]
  (cond
    (instance? Instant t) t
    (instance? OffsetDateTime t) (.toInstant ^OffsetDateTime t)
    (instance? java.util.Date t) (.toInstant ^java.util.Date t)
    :else nil))

(defn event-time [event] (->instant (:event/timestamp event)))

;; ---------------------------------------------------------------------------
;; Folds: pinned cuts, instrument goldens, harness self-checks
;; ---------------------------------------------------------------------------

(defn fold-pinned-cuts [state {:keys [cut-id commit tree-digest]}]
  ;; NotePinnedCut requires 1: one already held is ignored
  (if (contains? state cut-id)
    state
    (assoc state cut-id {:cut-id cut-id :commit commit :tree-digest tree-digest})))

(defn fold-instrument-goldens [state {:event/keys [type] :as e}]
  (case type
    :grader-lifecycle/instrument-golden-written
    (if (contains? state (:golden-id e))
      state
      (assoc state (:golden-id e) {:golden-id (:golden-id e)
                                   :digest (:digest e)
                                   :scenario-digest (:scenario-digest e)}))
    :grader-lifecycle/instrument-golden-moved
    (if (contains? state (:golden-id e))
      (update state (:golden-id e) assoc
              :digest (:to-digest e)
              :scenario-digest (:scenario-digest e))
      state)
    state))

(defn self-check-key [cycle-id occasion attempt] (str cycle-id "|" occasion "|" attempt))

(defn fold-harness-self-checks [state {:event/keys [type] :as e}]
  (let [k (self-check-key (:cycle-id e) (:occasion e) (:attempt e))]
    (if (contains? state k)
      state
      (case type
        :regeneration-cycle/harness-self-check-passed
        (assoc state k {:cycle-id (:cycle-id e) :cut-id (:cut-id e)
                        :occasion (:occasion e) :attempt (:attempt e)
                        :outcome "passed"
                        :harness-digest (:harness-digest e)
                        :goldens-digest (:goldens-digest e)
                        :identity-probe-digest (:identity-probe-digest e)
                        :grader-digests (vec (:grader-digests e))
                        :skipped-checks (vec (:skipped-checks e))})
        :regeneration-cycle/harness-self-check-failed
        (assoc state k {:cycle-id (:cycle-id e) :cut-id (:cut-id e)
                        :occasion (:occasion e) :attempt (:attempt e)
                        :outcome "failed"
                        :harness-digest (:harness-digest e)
                        :failing-check (:failing-check e)})
        state))))

;; ---------------------------------------------------------------------------
;; Fold: the regeneration cycle (entity RegenerationCycle)
;; ---------------------------------------------------------------------------
;; A cycle is the read model row (its schema fields) plus the entity's other
;; attributes, kept under ::keys so that the view can drop them.

(defn- new-cycle [e at self-check-failure]
  {:cycle-id (:cycle-id e)
   :cut-id (:cut-id e)
   :status "running"
   :stage "fence"
   :stage-entered-at at
   :consecutive-gate-refusals 0
   :budget (:budget e)
   :harness-digest (:harness-digest e)
   :gate-attempts 0
   ::clean-room-proved false
   ::failed-attempts 0
   ::gate-evidence-roots []
   ::self-check-failed (some? self-check-failure)
   ::self-check-failure self-check-failure
   ::measurement-filed false
   ::score-stage-failed false
   ::scoreboard-reported false})

(defn running? [c] (= "running" (:status c)))

(defn fold-cycle
  "Folds one event into the cycles map {cycle-id cycle}. Returns the new map."
  [cycles {:event/keys [type] :as e}]
  (let [id (:cycle-id e)
        c (get cycles id)
        at (event-time e)
        put (fn [c'] (assoc cycles id c'))]
    (case type
      ;; OpenCycle
      :regeneration-cycle/harness-self-check-passed
      (if (and (= "before-fence" (:occasion e)) (nil? c))
        (put (new-cycle e at nil))
        cycles)

      ;; NoteFailedSelfCheck
      :regeneration-cycle/harness-self-check-failed
      (cond
        (and (= "before-fence" (:occasion e)) (nil? c))
        (put (new-cycle e at (:failing-check e)))
        (and (= "before-gate" (:occasion e)) c)
        (put (assoc c ::self-check-failed true ::self-check-failure (:failing-check e)))
        :else cycles)

      ;; NoteCleanRoomProved
      :regeneration-cycle/clean-room-proved
      (if c
        (put (assoc c ::clean-room-proved true
                    :clean-room-commit (:clean-room-commit e)
                    :stage "regenerate" :stage-entered-at at))
        cycles)

      ;; RecordRegeneratedCandidate's ensures
      :regeneration-cycle/candidate-regenerated
      (if c
        (put (assoc c :gate-attempts (:attempt e)
                    :candidate-digest (:candidate-digest e)
                    :stage "gate" :stage-entered-at at))
        cycles)

      :regeneration-cycle/candidate-installed-in-gate-room cycles

      ;; NoteGateAttemptPassed
      :regeneration-cycle/gate-attempt-passed
      (if c
        (put (assoc c :stage "score" :stage-entered-at at :consecutive-gate-refusals 0))
        cycles)

      ;; NoteGateAttemptFailed
      :regeneration-cycle/gate-attempt-failed
      (if c
        (let [failed (inc (::failed-attempts c))
              c' (-> c
                     (assoc ::failed-attempts failed :consecutive-gate-refusals 0)
                     (update ::gate-evidence-roots (fnil conj []) (:evidence-root e)))]
          (put (if (< failed (get-in c [:budget :gate-attempt-limit]))
                 (assoc c' :stage "regenerate" :stage-entered-at at)
                 c')))
        cycles)

      ;; NoteGateAttemptRefused
      :regeneration-cycle/gate-attempt-refused
      (if c (put (update c :consecutive-gate-refusals inc)) cycles)

      ;; NoteMeasurementFiled
      :measurement/measurement-filed
      (if (and c (running? c) (= "score" (:stage c)))
        (put (assoc c ::measurement-filed true :stage "scoreboard" :stage-entered-at at))
        cycles)

      ;; NoteScoreStageFailed
      :measurement/score-stage-failed
      (if (and c (running? c) (= "score" (:stage c)))
        (put (assoc c ::score-stage-failed true))
        cycles)

      ;; NoteScoreboardReported
      :measurement/scoreboard-reported
      (if (and c (running? c) (::measurement-filed c))
        (put (assoc c ::scoreboard-reported true))
        cycles)

      ;; PassCycle's ensures
      :regeneration-cycle/cycle-passed
      (if c (put (assoc c :status "passed" :stage "result")) cycles)

      ;; FailCycle's ensures
      :regeneration-cycle/cycle-failed
      (if c
        (put (assoc c :status "failed"
                    :failure {:stage (:stage e) :reason (:reason e) :detail (:detail e)}))
        cycles)

      cycles)))

(defn fold-cycles-state
  "State of the regeneration-cycles read model: {:cycles {id cycle} :order [id ...]}."
  [state e]
  (let [cycles (:cycles state {})
        cycles' (fold-cycle cycles e)
        new-id (first (remove (set (keys cycles)) (keys cycles')))]
    (cond-> (assoc state :cycles cycles')
      new-id (update :order (fnil conj []) new-id))))

(defn cycle-row
  "The read model row of a cycle: its schema fields, optional ones left out."
  [c]
  (into {} (remove (fn [[k v]] (or (namespace k) (nil? v)))) c))

;; ---------------------------------------------------------------------------
;; Fold: gate attempts (entity GateAttempt)
;; ---------------------------------------------------------------------------

(defn fold-gate-attempts
  "{cycle-id {attempt row}}"
  [state {:event/keys [type] :as e}]
  (let [path [(:cycle-id e) (:attempt e)]
        row (get-in state path)]
    (case type
      :regeneration-cycle/candidate-regenerated
      (if row
        state
        (assoc-in state path {:cycle-id (:cycle-id e) :attempt (:attempt e)
                              :candidate-digest (:candidate-digest e)
                              :installed? false}))
      :regeneration-cycle/candidate-installed-in-gate-room
      (if row (assoc-in state (conj path :installed?) true) state)
      :regeneration-cycle/gate-attempt-passed
      (if row
        (update-in state path assoc :verdict "passed"
                   :boot-margin-polls (- (:boot-polls e) (:answered-on-poll e))
                   :evidence-root (:evidence-root e))
        state)
      :regeneration-cycle/gate-attempt-failed
      (if row
        (update-in state path assoc :verdict "failed" :reason (:reason e)
                   :evidence-root (:evidence-root e))
        state)
      :regeneration-cycle/gate-attempt-refused
      (if row (assoc-in state (conj path :last-refusal) (:refusal e)) state)
      state)))

(defn attempts-of [gate-attempts cycle-id]
  (->> (get gate-attempts cycle-id) vals (sort-by :attempt) vec))

;; ---------------------------------------------------------------------------
;; The conclude-cycle policy's to-do list
;; ---------------------------------------------------------------------------

(def max-consecutive-gate-refusals 3)

(defn awaits
  "The first to-do condition a cycle meets, or nil: :self-check, :gate,
   :infrastructure, :score or :passing."
  [c]
  (when (and c (running? c))
    (cond
      (::self-check-failed c) :self-check
      (>= (::failed-attempts c 0) (get-in c [:budget :gate-attempt-limit] 3)) :gate
      (>= (:consecutive-gate-refusals c 0) max-consecutive-gate-refusals) :infrastructure
      (::score-stage-failed c) :score
      (and (::measurement-filed c) (::scoreboard-reported c)) :passing
      :else nil)))

(defn fold-cycles-to-conclude
  "State: {:cycles {...} :due {cycle-id seq} :seq n}. A cycle enters the list when it
   becomes due and leaves it when it is concluded."
  [state e]
  (let [cycles' (fold-cycle (:cycles state {}) e)
        id (:cycle-id e)
        due? (some? (awaits (get cycles' id)))
        state' (assoc state :cycles cycles')]
    (cond
      (and due? (not (contains? (:due state) id)))
      (-> state'
          (assoc-in [:due id] (inc (:seq state 0)))
          (assoc :seq (inc (:seq state 0))))
      (and (not due?) (contains? (:due state) id))
      (update state' :due dissoc id)
      :else state')))

(defn due-cycle-ids [state]
  (->> (:due state) (sort-by val) (mapv key)))

(defn conclusion
  "The command the conclude-cycle policy issues for a due cycle, with the ORC
   workflow (flow) it runs in. `c` is the cycle as regeneration-cycles holds it."
  [c]
  (case (awaits c)
    :self-check {:command :regeneration-cycle/fail-cycle
                 :params {:cycle-id (:cycle-id c) :stage (:stage c)
                          :reason "harness-self-check-failed"
                          :detail (or (::self-check-failure c) "harness self-check failed")}
                 :workflow "regeneration-cycle/self-check-failed-fails-cycle"}
    :gate {:command :regeneration-cycle/fail-cycle
           :params {:cycle-id (:cycle-id c) :stage "gate" :reason "gate-attempts-exhausted"
                    :detail "gate attempts exhausted"}
           :workflow "regeneration-cycle/gate-attempts-exhausted"}
    :infrastructure {:command :regeneration-cycle/fail-cycle
                     :params {:cycle-id (:cycle-id c) :stage "gate" :reason "infrastructure"
                              :detail "three consecutive gate refusals"}
                     :workflow "regeneration-cycle/gate-refusals-fail-cycle"}
    :score {:command :regeneration-cycle/fail-cycle
            :params {:cycle-id (:cycle-id c) :stage "score" :reason "score-stage-failed"
                     :detail "score refused"}
            :workflow "regeneration-cycle/refused-score-fails-cycle"}
    :passing {:command :regeneration-cycle/pass-cycle
              :params {:cycle-id (:cycle-id c)}
              :workflow "regeneration-cycle/fence-to-scoreboard"}
    nil))

;; ---------------------------------------------------------------------------
;; The stage clock (ConcludeOnStageTimeout)
;; ---------------------------------------------------------------------------

(def timed-stages #{"fence" "regenerate" "gate" "score"})

(defn timed-out?
  [c ^Instant now]
  (and (running? c)
       (contains? timed-stages (:stage c))
       (when-let [secs (get-in c [:budget :stage-timeouts-seconds (keyword (:stage c))])]
         (when-let [entered (->instant (:stage-entered-at c))]
           (not (.isAfter (.plus entered (Duration/ofSeconds secs)) now))))))

(defn stage-timeout-command [c]
  {:command :regeneration-cycle/fail-cycle
   :params {:cycle-id (:cycle-id c) :stage (:stage c) :reason "budget" :detail "stage timeout"}
   :workflow "regeneration-cycle/stage-timeout-fails-cycle"})

;; ---------------------------------------------------------------------------
;; Requires (refusals). Each returns nil when every requires holds, or
;; {:refusal code ...} for the first that fails, in Allium order.
;; ---------------------------------------------------------------------------

(defn- refuse [code & {:as more}] (merge {:refusal code} more))

(defn record-harness-self-check-refusal
  [{:keys [pinned-cuts goldens self-checks cycles]} cmd]
  (let [{:keys [cycle-id cut-id occasion attempt outcome failing-check
                budget missing-goldens reworded-goldens]} cmd
        c (get cycles cycle-id)]
    (cond
      (not (contains? pinned-cuts cut-id)) (refuse "cut-not-pinned")
      (not= (:goldens-digest cmd) (goldens-digest goldens)) (refuse "goldens-not-current")
      (contains? self-checks (self-check-key cycle-id occasion attempt))
      (refuse "self-check-already-recorded")
      (and (= "failed" outcome) (nil? failing-check)) (refuse "no-failing-check")
      (and (= "before-fence" occasion) (not (and (nil? c) (= 0 attempt))))
      (refuse "not-the-cycles-first-fact")
      (and (= "before-gate" occasion)
           (not (and c (running? c) (= cut-id (:cut-id c)) (= attempt (:gate-attempts c)))))
      (refuse "no-candidate-for-attempt")
      (not (and (<= 1 (:gate-attempt-limit budget) 3) (<= (:max-continuations budget) 3)))
      (refuse "budget-out-of-bounds")
      (and (= "passed" outcome) (seq missing-goldens))
      (refuse "missing-golden" :goldens (vec missing-goldens))
      (and (= "passed" outcome) (seq reworded-goldens))
      (refuse "reworded-golden" :goldens (vec reworded-goldens))
      (seq (remove #(contains? goldens %) reworded-goldens))
      (refuse "reworded-golden-not-current"
              :goldens (vec (remove #(contains? goldens %) reworded-goldens)))
      :else nil)))

(defn prove-clean-room-refusal
  [{:keys [cycles scoring-tools]} cmd]
  (let [{:keys [cycle-id cut-id fenced-paths root-commits commits unmatched-paths
                paths-left-in-tree paths-reachable-in-object-graph]} cmd
        c (get cycles cycle-id)
        unfenced (vec (remove (set fenced-paths) scoring-tools))]
    (cond
      (or (nil? c) (::self-check-failed c)) (refuse "self-check-not-passed")
      (seq unmatched-paths) (refuse "fenced-path-matched-nothing" :paths (vec unmatched-paths))
      (not= 1 root-commits) (refuse "root-commits-not-one")
      (not= 1 commits) (refuse "commits-not-one")
      (seq paths-left-in-tree) (refuse "fenced-path-left-in-tree" :paths (vec paths-left-in-tree))
      (seq paths-reachable-in-object-graph)
      (refuse "fenced-path-reachable-in-object-graph" :paths (vec paths-reachable-in-object-graph))
      (::clean-room-proved c) (refuse "clean-room-already-proved")
      (not= cut-id (:cut-id c)) (refuse "not-the-self-checked-cut")
      (seq unfenced) (refuse "scoring-tool-not-fenced" :paths unfenced)
      (not (running? c)) (refuse "cycle-concluded")
      :else nil)))

(defn record-regenerated-candidate-refusal
  [{:keys [cycles]} cmd]
  (let [{:keys [cycle-id attempt purpose parent-candidate-digest mounted-evidence]} cmd
        c (get cycles cycle-id)]
    (cond
      (nil? c) (refuse "no-cycle")
      (not (::clean-room-proved c)) (refuse "no-clean-room")
      (not= attempt (inc (:gate-attempts c))) (refuse "not-the-next-attempt")
      (> attempt (get-in c [:budget :gate-attempt-limit])) (refuse "over-gate-attempt-limit")
      (not (and (or (not= 1 attempt) (= "fresh-generation" purpose))
                (or (<= attempt 1) (= "verification-repair" purpose))))
      (refuse "wrong-purpose")
      (not (running? c)) (refuse "cycle-concluded")
      (not (and (= "regenerate" (:stage c)) (= (::failed-attempts c) (dec attempt))))
      (refuse "not-after-a-failed-attempt")
      (and (= "verification-repair" purpose)
           (not= parent-candidate-digest (:candidate-digest c)))
      (refuse "not-the-previous-candidate")
      (or (and (= "fresh-generation" purpose) (seq mounted-evidence))
          (seq (remove (set (::gate-evidence-roots c)) mounted-evidence)))
      (refuse "evidence-not-mountable"
              :paths (vec (if (= "fresh-generation" purpose)
                            mounted-evidence
                            (remove (set (::gate-evidence-roots c)) mounted-evidence))))
      :else nil)))

(defn install-candidate-refusal
  [{:keys [cycles gate-attempts]} cmd]
  (let [{:keys [cycle-id attempt candidate-digest]} cmd
        c (get cycles cycle-id)
        row (get-in gate-attempts [cycle-id attempt])]
    (cond
      (not (and c (= attempt (:gate-attempts c)))) (refuse "no-candidate-for-attempt")
      (not= candidate-digest (:candidate-digest c)) (refuse "not-the-regenerated-candidate")
      (:installed? row) (refuse "already-installed")
      (not (running? c)) (refuse "cycle-concluded")
      :else nil)))

(defn record-gate-attempt-refusal
  [{:keys [cycles gate-attempts self-checks goldens]} cmd]
  (let [{:keys [cycle-id attempt candidate-digest harness-digest outcome reason
                answered-on-poll identity-probe-result identity-probe-present
                identity-probe-digest seam-answered-before-boot boot-polls
                grader-digests]} cmd
        c (get cycles cycle-id)
        gate (get-in gate-attempts [cycle-id attempt])
        installed? (:installed? gate)
        check (get self-checks (self-check-key cycle-id "before-gate" attempt))]
    (cond
      (not installed?) (refuse "not-installed")
      (some? (:verdict gate)) (refuse "verdict-already-recorded")
      (not (and (or (not= "failed" outcome) (some? reason))
                (or (not= "passed" outcome) (nil? reason))))
      (refuse "reason-does-not-fit-outcome")
      (and (= "passed" outcome) (not (and (some? answered-on-poll) (some? identity-probe-result))))
      (refuse "pass-without-poll-or-probe-result")
      (and (some? answered-on-poll) (> answered-on-poll boot-polls))
      (refuse "answered-after-last-poll")
      (not= candidate-digest (:candidate-digest gate)) (refuse "not-the-installed-candidate")
      (not (and check (= "passed" (:outcome check))
                (= harness-digest (:harness-digest check))
                (= (:goldens-digest check) (goldens-digest goldens))))
      (refuse "no-passing-self-check")
      (not identity-probe-present) (refuse "identity-probe-missing")
      (not= identity-probe-digest (:identity-probe-digest check))
      (refuse "identity-probe-not-the-instruments")
      seam-answered-before-boot (refuse "seam-answered-before-boot")
      (not (and c (running? c))) (refuse "cycle-concluded")
      (not= (set (:grader-digests check)) (set grader-digests)) (refuse "other-grader-digests")
      :else nil)))

(defn record-gate-refusal-refusal
  [{:keys [cycles gate-attempts]} cmd]
  (let [{:keys [cycle-id attempt]} cmd
        c (get cycles cycle-id)
        gate (get-in gate-attempts [cycle-id attempt])]
    (cond
      (not (:installed? gate)) (refuse "not-installed")
      (some? (:verdict gate)) (refuse "verdict-already-recorded")
      (not (and c (running? c))) (refuse "cycle-concluded")
      :else nil)))

(defn pass-cycle-refusal
  [{:keys [cycles]} {:keys [cycle-id]}]
  (let [c (get cycles cycle-id)]
    (cond
      (nil? c) (refuse "no-cycle")
      (not (and (::measurement-filed c) (::scoreboard-reported c))) (refuse "scoreboard-not-reported")
      (not (running? c)) (refuse "cycle-concluded")
      :else nil)))

(def ^:private reason-stage
  {"fence-refused" "fence"
   "candidate-not-regenerated" "regenerate"
   "gate-attempts-exhausted" "gate"
   "score-stage-failed" "score"})

(defn fail-cycle-refusal
  [{:keys [cycles]} {:keys [cycle-id stage reason]}]
  (let [c (get cycles cycle-id)]
    (cond
      (nil? c) (refuse "no-cycle")
      (not (running? c)) (refuse "cycle-concluded")
      (not= stage (:stage c)) (refuse "not-the-stage-reached")
      (when-let [s (reason-stage reason)] (not= s stage)) (refuse "reason-not-of-the-stage")
      (and (= "infrastructure" reason) (not= "gate" stage)) (refuse "infrastructure-not-at-the-gate")
      :else nil)))
