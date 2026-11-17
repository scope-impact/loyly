(ns loyly.cycle
  (:require [loyly.model :as model] [loyly.domain :as d] [clojure.string :as str]))
(defn emit [type values]
  (let [fields (map first (drop 1 (:schema (get (model/blocks :events) type))))]
    (d/result (d/event type (select-keys values fields)))))
(defn progress [ctx c] (d/row ctx :regeneration-cycle/regeneration-cycles :cycle-id c))
(defn attempt [ctx c n] (first (filter #(and (= c (:cycle-id %)) (= n (:attempt %))) (d/rows ctx :regeneration-cycle/gate-attempts))))
(defn check [ctx c n] (first (filter #(and (= c (:cycle-id %)) (= n (:attempt %))) (d/rows ctx :regeneration-cycle/harness-self-checks))))
(defn command [id ctx]
  (let [p (:command ctx) c (:cycle-id p) n (:attempt p) cycle (progress ctx c) a (when n (attempt ctx c n))
        concluded (and cycle (not= "running" (:status cycle))) date (d/now)]
    (case id
      :regeneration-cycle/record-harness-self-check
      (cond
        (not (d/row ctx :regeneration-cycle/pinned-cuts :cut-id (:cut-id p))) (d/refuse "cut-not-pinned")
        (not= (d/digest (apply str (for [g (sort-by :golden-id (d/rows ctx :regeneration-cycle/instrument-goldens))] (str (:golden-id g) "\t" (:digest g) "\n")))) (:goldens-digest p)) (d/refuse "goldens-not-current")
        (check ctx c n) (d/refuse "self-check-already-recorded")
        (and (= "failed" (:outcome p)) (str/blank? (:failing-check p))) (d/refuse "no-failing-check")
        (and (= "before-fence" (:occasion p)) cycle) (d/refuse "not-the-cycles-first-fact")
        (and (= "before-gate" (:occasion p)) (not a)) (d/refuse "no-candidate-for-attempt")
        (seq (:missing-goldens p)) (d/refuse "missing-golden")
        (seq (:reworded-goldens p)) (d/refuse "reworded-golden")
        :else (emit (if (= "passed" (:outcome p)) :regeneration-cycle/harness-self-check-passed :regeneration-cycle/harness-self-check-failed) (assoc p :checked-at date :detail (or (:detail p) ""))))
      :regeneration-cycle/prove-clean-room
      (cond
        (not= "passed" (:outcome (check ctx c 0))) (d/refuse "self-check-not-passed")
        (seq (:unmatched-paths p)) (d/refuse "fenced-path-matched-nothing")
        (not= 1 (:root-commits p)) (d/refuse "root-commits-not-one")
        (not= 1 (:commits p)) (d/refuse "commits-not-one")
        (seq (:paths-left-in-tree p)) (d/refuse "fenced-path-left-in-tree")
        (seq (:paths-reachable-in-object-graph p)) (d/refuse "fenced-path-reachable-in-object-graph")
        (:clean-room-commit cycle) (d/refuse "clean-room-already-proved")
        (not= (:cut-id cycle) (:cut-id p)) (d/refuse "not-the-self-checked-cut")
        concluded (d/refuse "cycle-concluded")
        :else (emit :regeneration-cycle/clean-room-proved (assoc p :proved-at date)))
      :regeneration-cycle/record-regenerated-candidate
      (cond
        (not cycle) (d/refuse "no-cycle")
        (not (:clean-room-commit cycle)) (d/refuse "no-clean-room")
        (not= n (inc (:gate-attempts cycle))) (d/refuse "not-the-next-attempt")
        (> n (get-in cycle [:budget :gate-attempt-limit])) (d/refuse "over-gate-attempt-limit")
        (not= (:purpose p) (if (= n 1) "fresh-generation" "verification-repair")) (d/refuse "wrong-purpose")
        concluded (d/refuse "cycle-concluded")
        (and (> n 1) (not= "failed" (:verdict (attempt ctx c (dec n))))) (d/refuse "not-after-a-failed-attempt")
        (and (> n 1) (not= (:parent-candidate-digest p) (:candidate-digest (attempt ctx c (dec n))))) (d/refuse "not-the-previous-candidate")
        (if (= n 1) (seq (:mounted-evidence p)) (not= [(:evidence-root (attempt ctx c (dec n)))] (:mounted-evidence p))) (d/refuse "evidence-not-mountable")
        :else (emit :regeneration-cycle/candidate-regenerated (assoc p :regenerated-at date)))
      :regeneration-cycle/install-candidate-in-gate-room
      (cond
        (not a) (d/refuse "no-candidate-for-attempt")
        (not= (:candidate-digest a) (:candidate-digest p)) (d/refuse "not-the-regenerated-candidate")
        (:installed? a) (d/refuse "already-installed")
        concluded (d/refuse "cycle-concluded")
        :else (emit :regeneration-cycle/candidate-installed-in-gate-room (assoc p :installed-at date)))
      :regeneration-cycle/record-gate-attempt
      (let [passed (= "passed" (:outcome p)) sc (check ctx c n)]
        (cond
          (not (:installed? a)) (d/refuse "not-installed")
          (:verdict a) (d/refuse "verdict-already-recorded")
          (if passed (:reason p) (not (:reason p))) (d/refuse "reason-does-not-fit-outcome")
          (and passed (or (not (:answered-on-poll p)) (str/blank? (:identity-probe-result p)))) (d/refuse "pass-without-poll-or-probe-result")
          (and passed (> (:answered-on-poll p) (:boot-polls p))) (d/refuse "answered-after-last-poll")
          (not= (:candidate-digest a) (:candidate-digest p)) (d/refuse "not-the-installed-candidate")
          (or (not= "passed" (:outcome sc)) (not= (:harness-digest sc) (:harness-digest p))) (d/refuse "no-passing-self-check")
          (not (:identity-probe-present p)) (d/refuse "identity-probe-missing")
          (not= (:identity-probe-digest sc) (:identity-probe-digest p)) (d/refuse "identity-probe-not-the-instruments")
          (:seam-answered-before-boot p) (d/refuse "seam-answered-before-boot")
          concluded (d/refuse "cycle-concluded")
          (not= (:grader-digests sc) (:grader-digests p)) (d/refuse "other-grader-digests")
          :else (emit (if passed :regeneration-cycle/gate-attempt-passed :regeneration-cycle/gate-attempt-failed) (assoc p :cut-id (:cut-id cycle) :passed-at date :failed-at date))))
      :regeneration-cycle/record-gate-refusal
      (cond (not (:installed? a)) (d/refuse "not-installed") (:verdict a) (d/refuse "verdict-already-recorded") concluded (d/refuse "cycle-concluded")
            :else (emit :regeneration-cycle/gate-attempt-refused (assoc p :refused-at date)))
      :regeneration-cycle/pass-cycle
      (cond (not cycle) (d/refuse "no-cycle") (not (:scoreboard-reported cycle)) (d/refuse "scoreboard-not-reported") concluded (d/refuse "cycle-concluded")
        :else (emit :regeneration-cycle/cycle-passed {:cycle-id c :candidate-digest (:candidate-digest cycle) :concluded-at date}))
      :regeneration-cycle/fail-cycle
      (cond (not cycle) (d/refuse "no-cycle") concluded (d/refuse "cycle-concluded") (not= (:stage p) (:stage cycle)) (d/refuse "not-the-stage-reached")
        :else (emit :regeneration-cycle/cycle-failed (cond-> (assoc p :concluded-at date) (:candidate-digest cycle) (assoc :candidate-digest (:candidate-digest cycle))))))))
(defn fold [id state e]
  (let [s (or state {}) b (d/body e) c (:cycle-id e) n (:attempt e) t (:event/type e) current (get s c)
        stage (fn [state name at] (-> state (assoc-in [c :stage] name) (assoc-in [c :stage-entered-at] at)))]
    (case id
      :regeneration-cycle/cycles-to-conclude
      (case t
        (:regeneration-cycle/cycle-passed :regeneration-cycle/cycle-failed) (dissoc s c)
        :regeneration-cycle/harness-self-check-passed (if current s (assoc s c {:cycle-id c :budget (:budget e) :ready false :refusals 0}))
        :regeneration-cycle/harness-self-check-failed (assoc s c {:cycle-id c :ready true})
        :regeneration-cycle/gate-attempt-failed (-> s (assoc-in [c :refusals] 0) (assoc-in [c :ready] (>= n (get-in current [:budget :gate-attempt-limit] 1))))
        :regeneration-cycle/gate-attempt-refused (let [r (inc (get current :refusals 0))] (-> s (assoc-in [c :refusals] r) (assoc-in [c :ready] (>= r 3))))
        :regeneration-cycle/gate-attempt-passed (-> s (assoc-in [c :scoring] true) (assoc-in [c :refusals] 0))
        :measurement/score-stage-failed (if (:scoring current) (assoc-in s [c :ready] true) s)
        :measurement/measurement-filed (if (:scoring current) (assoc-in s [c :measurement] true) s)
        :measurement/scoreboard-reported (if (:measurement current) (assoc-in s [c :ready] true) s)
        s)
      :regeneration-cycle/harness-self-checks (assoc s [c n] (assoc b :outcome (if (= t :regeneration-cycle/harness-self-check-passed) "passed" "failed")))
      :regeneration-cycle/gate-attempts
      (case t
        :regeneration-cycle/candidate-regenerated (assoc s [c n] (assoc (select-keys b [:cycle-id :attempt :candidate-digest]) :installed? false))
        :regeneration-cycle/candidate-installed-in-gate-room (assoc-in s [[c n] :installed?] true)
        :regeneration-cycle/gate-attempt-passed (update s [c n] merge {:verdict "passed" :boot-margin-polls (- (:boot-polls e) (:answered-on-poll e)) :evidence-root (:evidence-root e)})
        :regeneration-cycle/gate-attempt-failed (update s [c n] merge (assoc (select-keys b [:reason :evidence-root]) :verdict "failed"))
        :regeneration-cycle/gate-attempt-refused (assoc-in s [[c n] :last-refusal] (:refusal e)) s)
      :regeneration-cycle/regeneration-cycles
      (case t
        (:regeneration-cycle/harness-self-check-passed :regeneration-cycle/harness-self-check-failed)
        (if current s (assoc s c {:cycle-id c :cut-id (:cut-id e) :status "running" :stage "fence" :stage-entered-at (:checked-at e)
          :consecutive-gate-refusals 0 :budget (:budget e) :harness-digest (:harness-digest e) :gate-attempts 0}))
        :regeneration-cycle/clean-room-proved (-> s (assoc-in [c :clean-room-commit] (:clean-room-commit e)) (stage "regenerate" (:proved-at e)))
        :regeneration-cycle/candidate-regenerated (-> s (assoc-in [c :candidate-digest] (:candidate-digest e)) (assoc-in [c :gate-attempts] n) (stage "gate" (:regenerated-at e)))
        :regeneration-cycle/gate-attempt-passed (-> s (assoc-in [c :consecutive-gate-refusals] 0) (stage "score" (:passed-at e)))
        :regeneration-cycle/gate-attempt-failed (cond-> (assoc-in s [c :consecutive-gate-refusals] 0) (< n (get-in current [:budget :gate-attempt-limit])) (stage "regenerate" (:failed-at e)))
        :regeneration-cycle/gate-attempt-refused (update-in s [c :consecutive-gate-refusals] inc)
        :measurement/measurement-filed (if (and (= "running" (:status current)) (= "score" (:stage current))) (-> s (assoc-in [c :measurement-filed] true) (stage "scoreboard" (:filed-at e))) s)
        :measurement/scoreboard-reported (if (:measurement-filed current) (assoc-in s [c :scoreboard-reported] true) s)
        :regeneration-cycle/cycle-passed (-> s (assoc-in [c :status] "passed") (stage "result" (:concluded-at e)))
        :regeneration-cycle/cycle-failed (-> s (assoc-in [c :status] "failed") (assoc-in [c :failure] (select-keys b [:stage :reason :detail]))) s)
      (assoc s c b))))
