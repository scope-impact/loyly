(ns loyly.cycle-runner.standins
  "The suite's stand-ins for the five contexts (seam.md, \"How the candidate's own suite drives
   it without the five candidates\"). Each stand-in is a launch entrypoint the test names in
   its own LOYLY_CONTEXTS file: a small bash program that connects to this server and pipes
   its stdio through. The server speaks the context's seam (its methods, results and refusal
   codes, from each context's seam.md) as the test scripts it, keeps what it was handed, and
   appends the handoffs the context produces, with exactly their contract's fields, to a
   Grain store (event-store-v3, SQLite) at the LOYLY_GRAIN_STORE the runner handed it.
   The stand-ins are the suite's, never the product's."
  (:require [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.cycle-runner.util :as u])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.net ServerSocket Socket]
           [java.nio.charset StandardCharsets]))

(def tenant #uuid "5354414e-4449-4e00-8000-000000000001")

(defn register-handoff-schemas!
  "Register each producer's handoff events from the room's contracts, so a stand-in can
   append them to its store as the producer would."
  []
  (doseq [ctx ["specification" "cycle-preparation" "grader-lifecycle" "regeneration-cycle" "measurement"]]
    (let [m (edn/read-string (slurp (str "../../.pi/spec/" ctx "/event-model/handoffs.edn")))]
      (schema-util/register! (into {} (map (fn [[k b]] [k (:schema b)])) (get-in m [(keyword ctx) :events]))))))

;; ---------------------------------------------------------------------------
;; What the test scripts, and what the stand-ins were handed
;; ---------------------------------------------------------------------------

(defonce scripts (atom {}))
(defonce handed (atom []))
(defonce server (atom nil))

(defn reset-scripts! [m] (reset! scripts m) (reset! handed []))

(defn handed-to [context]
  (filterv #(= context (:context %)) @handed))

;; ---------------------------------------------------------------------------
;; Replies
;; ---------------------------------------------------------------------------

(defn- refuse [code & [data]] {:error {:code -32000 :message (str "refused: " code) :data (merge {:refusal code} data)}})
(defn- events [& evs] {:result {:events (vec evs)}})
(defn- now [] (u/now))

(defn- append! [st type body]
  (let [s (or (:store @st)
              (let [s (es/start {:conn {:type :sqlite :database-file (:store-path @st)}})]
                (swap! st assoc :store s)
                s))
        r (es/append s {:tenant-id tenant :events [(es/->event {:type type :body body})]})]
    (when (:cognitect.anomalies/category r)
      (throw (ex-info "stand-in append failed" {:r r})))
    (assoc body :type (str (namespace type) "/" (name type)))))

;; ---------------------------------------------------------------------------
;; Specification
;; ---------------------------------------------------------------------------

(defn- specification [st method p]
  (case method
    "specification/stamped-items" {:result {:slices []}}
    "specification/stamp-slice"
    (let [f (io/file (:source p))]
      (if-not (.isFile f)
        (refuse "no-spec-found")
        (let [lines (vec (str/split-lines (slurp f)))
              start (get-in @st [:ledger (:slice p)] 0)
              stamped (map-indexed
                       (fn [i {:keys [line title]}]
                         {:item-id (format "%s-%03d" (:slice p) (+ start i 1))
                          :slice (:slice p) :source (:source p) :line line :title title :stamped-at (now)})
                       (:unstamped p))
              ;; Write each tag above its heading, with the heading's indentation.
              by-line (into {} (map (juxt :line identity)) stamped)
              out (mapcat (fn [i l] (if-let [s (by-line (inc i))]
                                      [(str (re-find #"^\s*" l) "@item-" (:item-id s)) l]
                                      [l]))
                          (range) lines)]
          (spit f (str (str/join "\n" out) "\n"))
          (swap! st update-in [:ledger (:slice p)] (fnil + 0) (count stamped))
          {:result {:events (vec (concat (map #(append! st :specification/scenario-stamped %) stamped)
                                         (when (seq (:empty-title-lines p))
                                           [{:type "specification/empty-title-headings-reported"
                                             :slice (:slice p) :source (:source p)
                                             :lines (:empty-title-lines p) :reported-at (str (now))}])))}})))
    nil))

;; ---------------------------------------------------------------------------
;; Cycle preparation
;; ---------------------------------------------------------------------------

(defn- cycle-preparation [st method p]
  (case method
    "cycle-preparation/source-cuts" {:result {:cuts (vec (reverse (:cuts @st)))}}
    "cycle-preparation/pin-source-cut"
    (let [commits (try (u/parse-json (slurp (:commits-path @st))) (catch Exception _ {}))
          tree (get commits (keyword (:commit p)))]
      (cond
        (some #(= (:cut-id p) (:cut-id %)) (:cuts @st)) (refuse "cut-already-pinned")
        (nil? tree) (refuse "not-a-commit")
        (not= tree (:tree-digest p)) (refuse "tree-digest-not-the-commits")
        :else (let [e (append! st :cycle-preparation/source-cut-pinned
                               (assoc (select-keys p [:cut-id :commit :tree-digest]) :pinned-at (now)))]
                (swap! st update :cuts (fnil conj []) e)
                (events e))))
    nil))

;; ---------------------------------------------------------------------------
;; Grader lifecycle
;; ---------------------------------------------------------------------------

(defn- grader-lifecycle [_st method _p]
  (case method
    "grader-lifecycle/instrument-goldens" {:result {:goldens []}}
    nil))

;; ---------------------------------------------------------------------------
;; Regeneration cycle
;; ---------------------------------------------------------------------------

(defn- fail-cycle! [st cycle-id stage reason detail]
  (swap! st update-in [:cycles cycle-id] assoc :status "failed"
         :failure {:stage stage :reason reason :detail detail})
  {:type "regeneration-cycle/cycle-failed" :cycle-id cycle-id :stage stage :reason reason
   :detail detail :concluded-at (str (now))})

(defn- cyc [st id] (get-in @st [:cycles id]))
(defn- att [st id n] (get-in @st [:attempts id n]))

(defn- regeneration-cycle [st method p]
  (let [id (:cycle-id p)]
    (case method
      "regeneration-cycle/cycles-to-conclude" {:result {:cycle-ids []}}
      "regeneration-cycle/cycle-progress"
      {:result {:cycle (when-let [c (cyc st id)]
                         (assoc c :attempts (vec (vals (sort-by key (get-in @st [:attempts id]))))))}}

      "cycle-preparation/source-cut-pinned"
      (do (swap! st update :pinned (fnil conj #{}) (:cut-id p)) (events (assoc p :type method)))
      ("grader-lifecycle/instrument-golden-written" "grader-lifecycle/instrument-golden-moved")
      (events (assoc p :type method))
      "measurement/measurement-filed"
      (do (when (and (= "running" (:status (cyc st id))) (= "score" (:stage (cyc st id))))
            (swap! st update-in [:cycles id] assoc :stage "scoreboard" :filed true))
          (events (assoc p :type method)))
      "measurement/score-stage-failed"
      (do (when (and (= "running" (:status (cyc st id))) (= "score" (:stage (cyc st id))))
            (fail-cycle! st id "score" "score-stage-failed" (:detail p)))
          (events (assoc p :type method)))
      "measurement/scoreboard-reported"
      (do (when (and (= "running" (:status (cyc st id))) (:filed (cyc st id)))
            (swap! st update-in [:cycles id] assoc :status "passed" :stage "result"))
          (events (assoc p :type method)))

      "regeneration-cycle/record-harness-self-check"
      (let [k [id (:occasion p) (:attempt p)]]
        (cond
          (not (contains? (:pinned @st) (:cut-id p))) (refuse "cut-not-pinned")
          (contains? (:self-checks @st) k) (refuse "self-check-already-recorded")
          (and (= "failed" (:outcome p)) (nil? (:failing-check p))) (refuse "no-failing-check")
          (and (= "before-gate" (:occasion p)) (nil? (att st id (:attempt p)))) (refuse "no-candidate-for-attempt")
          :else
          (do (swap! st update :self-checks (fnil conj #{}) k)
              (when (= "passed" (:outcome p)) (swap! st update :passed-checks (fnil conj #{}) k))
              (when (and (= "before-fence" (:occasion p)) (nil? (cyc st id)))
                (swap! st assoc-in [:cycles id]
                       {:cycle-id id :cut-id (:cut-id p) :status "running" :stage "fence"
                        :stage-entered-at (str (now)) :consecutive-gate-refusals 0 :budget (:budget p)
                        :harness-digest (:harness-digest p) :gate-attempts 0}))
              (if (= "passed" (:outcome p))
                (events (assoc (select-keys p [:cycle-id :cut-id :occasion :attempt :harness-digest :goldens-digest
                                               :identity-probe-digest :skipped-checks :grader-digests :budget])
                               :type "regeneration-cycle/harness-self-check-passed" :checked-at (str (now))))
                (let [e (assoc (select-keys p [:cycle-id :cut-id :occasion :attempt :budget :harness-digest
                                               :failing-check :detail :reworded-goldens])
                               :type "regeneration-cycle/harness-self-check-failed" :checked-at (str (now)))]
                  (fail-cycle! st id (:stage (cyc st id)) "harness-self-check-failed" (:failing-check p))
                  (events e))))))

      "regeneration-cycle/prove-clean-room"
      (let [c (cyc st id)]
        (cond
          (not (contains? (:passed-checks @st) [id "before-fence" 0])) (refuse "self-check-not-passed")
          (seq (:unmatched-paths p)) (refuse "fenced-path-matched-nothing" {:paths (:unmatched-paths p)})
          (not= 1 (:root-commits p)) (refuse "root-commits-not-one")
          (not= 1 (:commits p)) (refuse "commits-not-one")
          (seq (:paths-left-in-tree p)) (refuse "fenced-path-left-in-tree" {:paths (:paths-left-in-tree p)})
          (seq (:paths-reachable-in-object-graph p)) (refuse "fenced-path-reachable-in-object-graph"
                                                             {:paths (:paths-reachable-in-object-graph p)})
          (:clean-room-commit c) (refuse "clean-room-already-proved")
          (not= "running" (:status c)) (refuse "cycle-concluded")
          :else (do (swap! st update-in [:cycles id] assoc :clean-room-commit (:clean-room-commit p) :stage "regenerate")
                    (events (assoc (select-keys p [:cycle-id :cut-id :clean-room-commit :fenced-paths])
                                   :type "regeneration-cycle/clean-room-proved" :proved-at (str (now)))))))

      "regeneration-cycle/record-regenerated-candidate"
      (let [c (cyc st id) n (:attempt p)]
        (cond
          (nil? c) (refuse "no-cycle")
          (nil? (:clean-room-commit c)) (refuse "no-clean-room")
          (not= n (inc (:gate-attempts c))) (refuse "not-the-next-attempt")
          (> n (get-in c [:budget :gate-attempt-limit])) (refuse "over-gate-attempt-limit")
          (not= "running" (:status c)) (refuse "cycle-concluded")
          :else (do (swap! st update-in [:cycles id] assoc :gate-attempts n :stage "gate"
                           :candidate-digest (:candidate-digest p))
                    (swap! st assoc-in [:attempts id n] {:cycle-id id :attempt n :candidate-digest (:candidate-digest p)
                                                         :installed? false})
                    (events (assoc (select-keys p [:cycle-id :attempt :purpose :candidate-digest :parent-candidate-digest])
                                   :type "regeneration-cycle/candidate-regenerated" :regenerated-at (str (now)))))))

      "regeneration-cycle/install-candidate-in-gate-room"
      (let [a (att st id (:attempt p))]
        (cond
          (nil? a) (refuse "no-candidate-for-attempt")
          (not= (:candidate-digest a) (:candidate-digest p)) (refuse "not-the-regenerated-candidate")
          (:installed? a) (refuse "already-installed")
          (not= "running" (:status (cyc st id))) (refuse "cycle-concluded")
          :else (do (swap! st assoc-in [:attempts id (:attempt p) :installed?] true)
                    (events (assoc (select-keys p [:cycle-id :attempt :candidate-digest :purpose :gate-room])
                                   :type "regeneration-cycle/candidate-installed-in-gate-room"
                                   :installed-at (str (now)))))))

      "regeneration-cycle/record-gate-attempt"
      (let [n (:attempt p) a (att st id n) c (cyc st id)]
        (cond
          (not (:installed? a)) (refuse "not-installed")
          (:verdict a) (refuse "verdict-already-recorded")
          (not= (= "failed" (:outcome p)) (some? (:reason p))) (refuse "reason-does-not-fit-outcome")
          (and (= "passed" (:outcome p)) (not (and (:answered-on-poll p) (:identity-probe-result p))))
          (refuse "pass-without-poll-or-probe-result")
          (not= (:candidate-digest a) (:candidate-digest p)) (refuse "not-the-installed-candidate")
          (not (contains? (:passed-checks @st) [id "before-gate" n])) (refuse "no-passing-self-check")
          (not (:identity-probe-present p)) (refuse "identity-probe-missing")
          (:seam-answered-before-boot p) (refuse "seam-answered-before-boot")
          (not= "running" (:status c)) (refuse "cycle-concluded")
          (= "passed" (:outcome p))
          (do (swap! st assoc-in [:attempts id n :verdict] "passed")
              (swap! st update-in [:cycles id] assoc :stage "score" :consecutive-gate-refusals 0)
              (events (append! st :regeneration-cycle/gate-attempt-passed
                               (assoc (select-keys p [:cycle-id :attempt :candidate-digest :boot-budget-seconds :boot-polls
                                                      :answered-on-poll :identity-probe-result :evidence-root])
                                      :cut-id (:cut-id c) :passed-at (now)))))
          :else
          (do (swap! st update-in [:attempts id n] assoc :verdict "failed" :reason (:reason p)
                     :evidence-root (:evidence-root p))
              (swap! st update-in [:cycles id] assoc :consecutive-gate-refusals 0)
              (when (>= n (get-in c [:budget :gate-attempt-limit]))
                (fail-cycle! st id "gate" "gate-attempts-exhausted" (str "attempt " n " failed: " (:reason p))))
              (events (assoc (select-keys p [:cycle-id :attempt :candidate-digest :reason :boot-budget-seconds
                                             :boot-polls :evidence-root :detail])
                             :type "regeneration-cycle/gate-attempt-failed" :failed-at (str (now)))))))

      "regeneration-cycle/record-gate-refusal"
      (let [n (:attempt p) a (att st id n) c (cyc st id)]
        (cond
          (not (:installed? a)) (refuse "not-installed")
          (:verdict a) (refuse "verdict-already-recorded")
          (not= "running" (:status c)) (refuse "cycle-concluded")
          :else
          (do (swap! st update-in [:cycles id :consecutive-gate-refusals] inc)
              (swap! st assoc-in [:attempts id n :last-refusal] (:refusal p))
              (when (>= (:consecutive-gate-refusals (cyc st id)) 3)
                (fail-cycle! st id "gate" "infrastructure" (str "three gate refusals in a row: " (:refusal p))))
              (events (assoc (select-keys p [:cycle-id :attempt :refusal :detail])
                             :type "regeneration-cycle/gate-attempt-refused" :refused-at (str (now)))))))

      "regeneration-cycle/fail-cycle"
      (let [c (cyc st id)]
        (cond
          (nil? c) (refuse "no-cycle")
          (not= "running" (:status c)) (refuse "cycle-concluded")
          (not= (:stage p) (:stage c)) (refuse "not-the-stage-reached")
          :else (events (fail-cycle! st id (:stage p) (:reason p) (:detail p)))))
      nil)))

;; ---------------------------------------------------------------------------
;; Measurement
;; ---------------------------------------------------------------------------

(defn- measurement [st method p]
  (let [id (:cycle-id p)]
    (case method
      "measurement/measurements-to-report" {:result {:cycle-ids []}}
      "specification/scenario-stamped"
      (do (swap! st update :stamped (fnil conj #{}) (:item-id p)) (events (assoc p :type method)))
      "cycle-preparation/source-cut-pinned"
      (do (swap! st update :pinned (fnil conj #{}) (:cut-id p)) (events (assoc p :type method)))
      "regeneration-cycle/gate-attempt-passed"
      (do (swap! st assoc-in [:gated id] p) (events (assoc p :type method)))

      "measurement/derive-checklist"
      (let [ids (map :item-id (:items p))]
        (cond
          (not (contains? (:pinned @st) (:cut-id p))) (refuse "cut-not-pinned")
          (seq (:unstamped p)) (refuse "unstamped-scenarios" {:unstamped (:unstamped p)})
          (empty? (:items p)) (refuse "empty-checklist")
          (some #(not (contains? (:stamped @st) %)) ids)
          (refuse "item-never-stamped" {:item-ids (vec (remove #(contains? (:stamped @st) %) ids))})
          (not= (count ids) (count (distinct ids))) (refuse "item-twice")
          (get-in @st [:checklists (:cut-id p)]) (refuse "checklist-already-derived")
          :else (let [cl {:cut-id (:cut-id p) :selection-id (:selection-id p)
                          :checklist-digest (u/sha256-of-string (pr-str (:items p)))
                          :items (:items p) :total (count (:items p)) :skipped-slices (:skipped-slices p)}]
                  (swap! st assoc-in [:checklists (:cut-id p)] cl)
                  (events (assoc cl :type "measurement/checklist-derived" :derived-at (str (now)))))))

      "measurement/file-item-verdicts"
      (let [g (get-in @st [:gated id])
            cl (get-in @st [:checklists (:cut-id g)])
            known (set (map :item-id (:items cl)))]
        (cond
          (or (nil? g) (not= (:candidate-digest g) (:candidate-digest p))) (refuse "not-the-gated-candidate")
          (nil? cl) (refuse "no-checklist")
          (empty? (:verdicts p)) (refuse "zero-verdicts")
          (some #(not (#{"pass" "fail"} (:verdict %))) (:verdicts p)) (refuse "bad-verdict-value")
          (some #(not (known (:item-id %))) (:verdicts p))
          (refuse "unknown-item" {:item-ids (vec (remove known (map :item-id (:verdicts p))))})
          (get-in @st [:filed id]) (refuse "measurement-already-filed")
          (get-in @st [:failed id]) (refuse "score-stage-failed")
          (not (some #{(:grader-digest p)} (:approved-grader-digests p))) (refuse "grader-not-approved")
          :else (let [vs (mapv #(assoc (select-keys % [:item-id :verdict :reason]) :evidence (or (:evidence %) ""))
                               (:verdicts p))]
                  (swap! st update-in [:verdicts id] (fnil into []) vs)
                  (swap! st assoc-in [:graders id] (:grader-digest p))
                  (events {:type "measurement/item-verdicts-filed" :cycle-id id
                           :candidate-digest (:candidate-digest p) :grader-digest (:grader-digest p)
                           :verdicts vs :filed-at (str (now))}))))

      "measurement/file-score"
      (let [g (get-in @st [:gated id])
            cl (get-in @st [:checklists (:cut-id g)])
            vs (get-in @st [:verdicts id])]
        (cond
          (nil? g) (refuse "not-the-gated-candidate")
          (empty? vs) (refuse "zero-verdicts")
          (get-in @st [:filed id]) (refuse "measurement-already-filed")
          (get-in @st [:failed id]) (refuse "score-stage-failed")
          :else
          (let [last-v (into {} (map (juxt :item-id identity)) vs)
                items (mapv (fn [{:keys [item-id]}]
                              (or (some-> (last-v item-id) (select-keys [:item-id :verdict :reason :evidence]))
                                  {:item-id item-id :verdict "fail" :reason "unreached" :evidence ""}))
                            (:items cl))
                passes (count (filter #(= "pass" (:verdict %)) items))
                filed (append! st :measurement/measurement-filed
                               {:cycle-id id :cut-id (:cut-id g) :selection-id (:selection-id cl)
                                :candidate-digest (:candidate-digest g) :checklist-digest (:checklist-digest cl)
                                :grader-digests (:approved-grader-digests p) :total (count items)
                                :passes passes :unreached (count (filter #(= "unreached" (:reason %)) items))
                                :items items :superseded [] :filed-at (now)})]
            (swap! st assoc-in [:filed id] true)
            ;; The report-scoreboard-once policy, before the method responds.
            (append! st :measurement/scoreboard-reported
                     {:cycle-id id :selection-id (:selection-id cl) :passes passes :total (count items)
                      :rate (format "%.1f" (* 100.0 (/ passes (count items))))
                      :previous-cycle-id nil :previous-selection-id nil :new-items [] :dropped-items []
                      :reported-at (now)})
            (events filed))))

      "measurement/fail-score-stage"
      (cond
        (nil? (get-in @st [:gated id])) (refuse "not-the-gated-candidate")
        (get-in @st [:filed id]) (refuse "measurement-already-filed")
        (get-in @st [:failed id]) (refuse "score-stage-failed")
        :else (do (swap! st assoc-in [:failed id] true)
                  (events (append! st :measurement/score-stage-failed
                                   (assoc (select-keys p [:cycle-id :refusal :detail]) :failed-at (now))))))
      nil)))

(def ^:private behaviours
  {"specification" specification
   "cycle-preparation" cycle-preparation
   "grader-lifecycle" grader-lifecycle
   "regeneration-cycle" regeneration-cycle
   "measurement" measurement})

;; ---------------------------------------------------------------------------
;; The server
;; ---------------------------------------------------------------------------

(defn- serve [^Socket sock]
  (let [in (BufferedReader. (InputStreamReader. (.getInputStream sock) StandardCharsets/UTF_8))
        out (OutputStreamWriter. (.getOutputStream sock) StandardCharsets/UTF_8)
        header (u/parse-json (.readLine in))
        context (:context header)
        script (get @scripts context {})
        st (atom {:store-path (:store header) :commits-path (:commits header)})]
    (try
      (loop []
        (when-let [line (.readLine in)]
          (let [req (u/parse-json line)
                method (:method req)
                params (:params req)]
            (swap! handed conj {:context context :method method :params params})
            (cond
              (contains? (:exit-on script) method) nil
              (contains? (:silent-on script) method) (recur)
              :else
              (let [r (or (when-let [code (get (:refuse script) method)] (refuse code))
                          ((behaviours context) st method params)
                          {:error {:code -32601 :message (str "Method not found: " method)}})]
                (.write out (str (u/->json (merge {:jsonrpc "2.0" :id (:id req)} r)) "\n"))
                (.flush out)
                (recur))))))
      (catch Exception e (u/log "stand-in " context " failed: " e))
      (finally
        (some-> (:store @st) es/stop)
        (.close sock)))))

(defn start-server! []
  (or @server
      (let [ss (ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
            t (Thread. (fn []
                         (while (not (.isClosed ss))
                           (try
                             (let [s (.accept ss)]
                               (doto (Thread. #(serve s)) (.setDaemon true) .start))
                             (catch Exception _ nil)))))]
        (register-handoff-schemas!)
        (.setDaemon t true)
        (.start t)
        (reset! server {:socket ss :port (.getLocalPort ss)}))))

(defn entrypoint-script
  "A stand-in's launch entrypoint: it pipes its stdio through to the server, telling it which
   context it is and the store and fixtures the runner handed it."
  [context port]
  (str "#!/bin/bash\n"
       "exec 3<>/dev/tcp/127.0.0.1/" port " || exit 1\n"
       "printf '{\"context\":\"" context "\",\"store\":\"%s\",\"commits\":\"%s\",\"digests\":\"%s\"}\\n' "
       "\"$LOYLY_GRAIN_STORE\" \"$LOYLY_PROJECT_COMMITS\" \"$LOYLY_SCENARIO_DIGESTS\" >&3\n"
       "exec 4<&0\n"
       "cat <&4 >&3 &\n"
       "pump=$!\n"
       "cat <&3\n"
       "kill $pump 2>/dev/null\n"))

(defn write-contexts!
  "Write a stand-in entrypoint per context into dir and the LOYLY_CONTEXTS file naming them.
   overrides maps a context to literal script text (an entrypoint that exits at once, say),
   or to :absent to leave it unnamed."
  ([dir] (write-contexts! dir {}))
  ([dir overrides]
   (let [port (:port (start-server!))
         m (into {}
                 (keep (fn [c]
                         (let [o (get overrides c)]
                           (when-not (= :absent o)
                             (let [f (io/file dir (str "standin-" c ".sh"))]
                               (spit f (or o (entrypoint-script c port)))
                               (.setExecutable f true)
                               [c (u/abs-path f)])))))
                 ["specification" "cycle-preparation" "grader-lifecycle" "regeneration-cycle" "measurement"])
         f (io/file dir "contexts.json")]
     (spit f (u/->json m))
     (u/abs-path f))))
