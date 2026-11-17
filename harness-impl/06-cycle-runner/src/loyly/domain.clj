(ns loyly.domain
  (:require [loyly.model :as model] [clojure.string :as str] [clojure.java.io :as io]
            [clojure.data.json :as json]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.read-model-processor-v2.interface :as rm]))
(defn now [] (java.util.Date.))
(defn digest [s] (str "sha256:" (format "%064x" (java.math.BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (str s) "UTF-8"))))))
(defn body [e] (into {} (remove (fn [[k _]] (= "event" (namespace k))) e)))
(defn public-event [e] (assoc (body e) :type (subs (str (:event/type e)) 1)))
(defn event [id fields] (es/->event {:type id :body fields}))
(defn refuse [code] {:refusal code})
(defn row [ctx id k v] (first (filter #(= v (get % k)) (vals (rm/project ctx id)))))
(defn rows [ctx id] (vals (rm/project ctx id)))
(defn reduce-row [id state e]
  (let [state (or state {}) b (body e) t (:event/type e) r (:run-id e) c (:cycle-id e)
        key (or r c (:cut-id e) (:golden-id e) (:item-id e) (:slice e))]
    (case id
      :cycle-runner/runs
      (case t
        :cycle-runner/run-started (assoc state r (assoc (select-keys b [:run-id :cycle-id :cut-id :commit]) :status "running" :effects 0 :attempts 0))
        :cycle-runner/effect-recorded (cond-> (-> state (update-in [r :effects] inc) (update-in [r :attempts] max (:attempt e)))
          (:candidate-digest (first (:events e))) (assoc-in [r :candidate-digest] (:candidate-digest (first (:events e)))))
        :cycle-runner/run-halted (-> state (assoc-in [r :status] "halted") (assoc-in [r :halt] (select-keys b [:effect :reason :detail])))
        :cycle-runner/run-concluded (-> state (assoc-in [r :status] (:cycle-status e)) (assoc-in [r :cycle] (:cycle e))) state)
      :cycle-runner/effect-results (assoc state [r (:position e)] (dissoc b :performed-at))
      :specification/stamped-items
      (let [s (:slice e) old (get state s {:slice s :next-number 1 :items []})]
        (assoc state s (case t
          :specification/scenario-stamped (-> old (update :next-number inc) (update :items conj (select-keys b [:item-id :source :line :title])))
          :specification/empty-title-headings-reported (assoc old :empty-title-lines (:lines e)) old)))
      (:grader-lifecycle/instrument-goldens :regeneration-cycle/instrument-goldens) ((requiring-resolve 'loyly.goldens/fold) state e)
      :measurement/checklists (assoc state key (dissoc b :derived-at))
      :measurement/measurements-to-report (if (= t :measurement/measurement-filed) (assoc state c b) (dissoc state c))
      :measurement/item-identities (assoc state (:item-id e) b)
      (cond
        (contains? #{:regeneration-cycle/regeneration-cycles :regeneration-cycle/gate-attempts :regeneration-cycle/harness-self-checks :regeneration-cycle/cycles-to-conclude} id)
        ((requiring-resolve 'loyly.cycle/fold) id state e)
        (contains? #{:measurement/item-verdicts :measurement/cycle-measurements :measurement/scoreboards} id)
        ((requiring-resolve 'loyly.measurement/fold) id state e)
        :else (assoc state key b)))))
(declare command-handler apply-policy!)
(defn issue [ctx id p]
  (locking (or (:domain-lock ctx) (:event-store ctx))
  (let [r (cp/process-command (assoc ctx :command (assoc p :command/name id :command/id (random-uuid) :command/timestamp (java.time.OffsetDateTime/now))))]
    (cond (:refusal r) r
          (:command-result/events r) (do (doseq [e (:command-result/events r)] (apply-policy! ctx e)) {:events (mapv public-event (:command-result/events r))})
          :else (throw (ex-info (str "Command failed " id) {:result r}))))))
(defn result [& events] {:command-result/events (vec events)})
(defn command-handler [id ctx]
  (let [p (:command ctx) p (dissoc p :command/name :command/id :command/timestamp)
        run #(row ctx :cycle-runner/runs :run-id (:run-id p))]
    (case id
      :cycle-runner/start-run (result (event :cycle-runner/run-started (assoc p :started-at (now))))
      :cycle-runner/record-effect (result (event :cycle-runner/effect-recorded (assoc p :position (inc (:effects (run))) :performed-at (now))))
      :cycle-runner/halt-run (result (event :cycle-runner/run-halted (assoc p :halted-at (now))))
      :cycle-runner/conclude-run (result (event :cycle-runner/run-concluded (assoc p :cycle-id (:cycle-id (run)) :concluded-at (now))))
      :cycle-preparation/pin-source-cut
      (if (row ctx :cycle-preparation/source-cuts :cut-id (:cut-id p)) (refuse "cut-already-pinned")
        (result (event :cycle-preparation/source-cut-pinned (assoc p :pinned-at (now)))))
      :specification/stamp-slice
      (let [old (row ctx :specification/stamped-items :slice (:slice p)) n (or (:next-number old) 1)]
        {:command-result/events
         (vec (concat
           (map-indexed (fn [i item] (event :specification/scenario-stamped
             (merge item (select-keys p [:slice :source]) {:item-id (format "%s-%03d" (:slice p) (+ n i)) :stamped-at (now)}))) (:unstamped p))
           (when (seq (:empty-title-lines p)) [(event :specification/empty-title-headings-reported
             {:slice (:slice p) :source (:source p) :lines (:empty-title-lines p) :reported-at (now)})])))})
      :measurement/derive-checklist
      (let [items (:items p) ids (map :item-id items)]
        (cond
          (not (row ctx :measurement/pinned-cuts :cut-id (:cut-id p))) (refuse "cut-not-pinned")
          (seq (:unstamped p)) (refuse "unstamped-scenario")
          (empty? items) (refuse "empty-checklist")
          (some #(not (row ctx :measurement/item-identities :item-id %)) ids) (refuse "unknown-item-identity")
          (not= (count ids) (count (distinct ids))) (refuse "duplicate-item-identity")
          (row ctx :measurement/checklists :cut-id (:cut-id p)) (refuse "checklist-already-derived")
          (some #(str/blank? (:title %)) items) (refuse "empty-title")
          :else (result (event :measurement/checklist-derived (assoc (dissoc p :unstamped) :total (count items) :checklist-digest (digest (pr-str items)) :derived-at (now))))))
      (case (namespace id)
        "regeneration-cycle" ((requiring-resolve 'loyly.cycle/command) id ctx)
        "measurement" ((requiring-resolve 'loyly.measurement/command) id ctx)
        "grader-lifecycle" ((requiring-resolve 'loyly.goldens/command) id ctx)
        (throw (ex-info (str "Command implementation not carried: " id) {:method id}))))))
(defn query-handler [id ctx]
  (let [p (:query ctx)]
    {:query/result
     (case id
       :cycle-runner/run-progress
       (let [augment (fn [r] ((requiring-resolve 'loyly.progress/augment) ctx (assoc r :effect-results (vec (sort-by :position (filter #(= (:run-id r) (:run-id %)) (rows ctx :cycle-runner/effect-results)))) :scores [])))]
         (if-let [r (:run-id p)] {:run (some-> (row ctx :cycle-runner/runs :run-id r) augment)} {:runs (mapv augment (rows ctx :cycle-runner/runs))}))
       :specification/stamped-items (if (:slice p) {:slice (row ctx id :slice (:slice p))} {:slices (vec (rows ctx id))})
       :cycle-preparation/source-cuts {:cuts (vec (reverse (rows ctx id)))}
       :measurement/checklist {:checklist (row ctx :measurement/checklists :cut-id (:cut-id p))}
       :regeneration-cycle/cycle-progress
       (let [augment (fn [c] (assoc c :attempts (vec (sort-by :attempt (filter #(= (:cycle-id c) (:cycle-id %)) (rows ctx :regeneration-cycle/gate-attempts))))))]
         (if (:cycle-id p) {:cycle (some-> (row ctx :regeneration-cycle/regeneration-cycles :cycle-id (:cycle-id p)) augment)} {:cycles (mapv augment (rows ctx :regeneration-cycle/regeneration-cycles))}))
       :measurement/cycle-measurement {:measurement (row ctx :measurement/cycle-measurements :cycle-id (:cycle-id p))}
       :grader-lifecycle/instrument-goldens {:goldens (vec (rows ctx :grader-lifecycle/instrument-goldens))}
       :measurement/measurements-to-report {:cycle-ids (mapv :cycle-id (rows ctx id))}
       :regeneration-cycle/cycles-to-conclude {:cycle-ids (mapv :cycle-id (filter :ready (rows ctx id)))}
       :measurement/scoreboard {:scoreboard (row ctx :measurement/scoreboards :cycle-id (:cycle-id p))}
       (throw (ex-info (str "Query implementation not carried: " id) {:method id})))}))
(defn apply-policy! [ctx e]
  (let [c (:cycle-id e) cycle (when c (row ctx :regeneration-cycle/regeneration-cycles :cycle-id c))
        fail (fn [reason detail] (when (= "running" (:status cycle)) (issue ctx :regeneration-cycle/fail-cycle {:cycle-id c :stage (:stage cycle) :reason reason :detail detail})))]
    (case (:event/type e)
      :measurement/measurement-filed (when-not (row ctx :measurement/scoreboards :cycle-id c) (issue ctx :measurement/report-scoreboard {:cycle-id c}))
      :measurement/scoreboard-reported (when (= "running" (:status cycle)) (issue ctx :regeneration-cycle/pass-cycle {:cycle-id c}))
      :measurement/score-stage-failed (fail "score-stage-failed" (:detail e))
      :regeneration-cycle/harness-self-check-failed (fail "harness-self-check-failed" (:detail e))
      :regeneration-cycle/gate-attempt-failed (when (>= (:attempt e) (get-in cycle [:budget :gate-attempt-limit])) (fail "gate-attempts-exhausted" (:detail e)))
      :regeneration-cycle/gate-attempt-refused (when (>= (:consecutive-gate-refusals cycle) 3) (fail "infrastructure" (:detail e)))
      nil)))
(defn processor-handler [_ ctx] (locking (or (:domain-lock ctx) (:event-store ctx)) (apply-policy! ctx (:event ctx))) {})

(defn periodic-handler [ctx _ tenant time]
  (when (= tenant (:tenant-id ctx))
    (doseq [cycle (rows ctx :regeneration-cycle/regeneration-cycles)
            :let [seconds (get-in cycle [:budget :stage-timeouts-seconds (keyword (:stage cycle))]) entered (:stage-entered-at cycle)]
            :when (and (= "running" (:status cycle)) seconds entered (> (- (System/currentTimeMillis) (.getTime ^java.util.Date entered)) (* 1000 seconds)))]
      ((requiring-resolve 'loyly.main/handle) ctx {:jsonrpc "2.0" :id "stage-clock" :method "regeneration-cycle/fail-cycle"
        :params {:cycle-id (:cycle-id cycle) :stage (:stage cycle) :reason "budget" :detail "Stage exceeded its original budget"}})))
  {})
