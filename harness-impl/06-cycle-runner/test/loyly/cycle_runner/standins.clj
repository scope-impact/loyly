(ns loyly.cycle-runner.standins
  "The suite's stand-ins for the five contexts (seam.md, \"How the candidate's
   own suite drives it without the five candidates\"). Each stand-in speaks its
   context's seam as the test scripts it, keeps what it was handed, and appends
   the handoffs it produces, with exactly their contract's fields, to a Grain
   store at the LOYLY_GRAIN_STORE the runner handed it. A stand-in's launch
   entrypoint is a tiny babashka program (test/standin.bb) that forwards its
   stdio to this server."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.cycle-runner.model :as model]
            [loyly.cycle-runner.util :as u])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.net ServerSocket Socket]))

(def tenant #uuid "51a2d1e5-0000-4000-8000-00000000c0de")

;; The handoff contracts, from each producer's handoffs.edn in the room.
(def handoff-schemas
  (delay
    (into {}
          (for [c model/contexts
                :let [f (io/file (model/spec-root) c "event-model" "handoffs.edn")]
                :when (.exists f)
                [k {:keys [schema]}] (mapcat (comp :events val) (edn/read-string (slurp f)))]
            [k schema]))))

(defn register-handoffs! [] (schema-util/register! @handoff-schemas))

(defn- fields-of [type] (model/event-fields (get @handoff-schemas type)))

(defn- now [] (u/now))

(defn- ev
  "An event as a context replies it: its type and exactly its fields."
  [type body]
  (into {:type (str (namespace type) "/" (name type))} body))

(defn- append-handoff! [standin type body]
  (let [extra (remove (set (fields-of type)) (keys body))]
    (assert (empty? extra) (str "handoff " type " has fields outside its contract: " (vec extra))))
  (let [store (or @(:store standin)
                  (reset! (:store standin)
                          (es/start {:conn {:type :sqlite :database-file (:store-path standin)}
                                     :event-pubsub nil :logger nil})))
        r (es/append store {:tenant-id tenant
                            :events [(es/->event {:type type :body body})]})]
    (when (:cognitect.anomalies/category r)
      (throw (ex-info "stand-in could not append its handoff" {:r r})))
    (ev type body)))

(defn- refuse [code & [data]]
  {:error {:code -32000 :message (str "refused: " code) :data (merge {:refusal code} data)}})

(defn- ok [x] {:result x})

;; ---------------------------------------------------------------------------
;; Default behaviour of each context, as its seam.md describes it

(defmulti behave (fn [standin method _params] [(:context standin) method]))

(defmethod behave :default [standin method _]
  {:error {:code -32601 :message (str "Method not found: " method)}})

;; Specification
(defmethod behave ["specification" "specification/stamped-items"] [standin _ _]
  (ok {:slices []}))

(defmethod behave ["specification" "specification/stamp-slice"] [standin _ {:keys [slice source unstamped empty-title-lines]}]
  (if-not (.exists (io/file source))
    (refuse "no-spec-found")
    (let [state (:state standin)
          start (get-in @state [:next slice] 1)
          stamped (vec (map-indexed
                        (fn [i {:keys [line title]}]
                          (append-handoff! standin :specification/scenario-stamped
                                           {:item-id (format "%s-%03d" slice (+ start i)) :slice slice
                                            :source source :line line :title title :stamped-at (now)}))
                        unstamped))]
      (swap! state assoc-in [:next slice] (+ start (count unstamped)))
      (ok {:events (cond-> stamped
                     (seq empty-title-lines)
                     (conj (ev :specification/empty-title-headings-reported
                               {:slice slice :source source :lines empty-title-lines :reported-at (now)})))}))))

;; Cycle preparation
(defmethod behave ["cycle-preparation" "cycle-preparation/source-cuts"] [standin _ _]
  (ok {:cuts (vec (vals (:cuts @(:state standin))))}))

(defmethod behave ["cycle-preparation" "cycle-preparation/pin-source-cut"] [standin _ {:keys [cut-id commit tree-digest]}]
  (let [commits (try (u/<-json (slurp (:commits standin))) (catch Exception _ {}))
        tree (get commits (keyword commit))]
    (cond
      (get-in @(:state standin) [:cuts cut-id]) (refuse "cut-already-pinned")
      (nil? tree) (refuse "not-a-commit")
      (not= tree tree-digest) (refuse "tree-digest-not-the-commits")
      :else (let [e (append-handoff! standin :cycle-preparation/source-cut-pinned
                                     {:cut-id cut-id :commit commit :tree-digest tree-digest :pinned-at (now)})]
              (swap! (:state standin) assoc-in [:cuts cut-id] e)
              (ok {:events [e]})))))

;; Grader lifecycle
(defmethod behave ["grader-lifecycle" "grader-lifecycle/instrument-goldens"] [standin _ _]
  (ok {:goldens []}))

;; Measurement
(defmethod behave ["measurement" "measurement/measurements-to-report"] [_ _ _] (ok {:cycle-ids []}))

(defn- handed-in [standin type params]
  (swap! (:state standin) update :handed (fnil conj []) [type params])
  (ok {:events [(ev type params)]}))

(defmethod behave ["measurement" "specification/scenario-stamped"] [standin m p]
  (swap! (:state standin) update :items (fnil conj #{}) (:item-id p))
  (handed-in standin :specification/scenario-stamped p))

(defmethod behave ["measurement" "cycle-preparation/source-cut-pinned"] [standin _ p]
  (swap! (:state standin) update :cuts (fnil conj #{}) (:cut-id p))
  (handed-in standin :cycle-preparation/source-cut-pinned p))

(defmethod behave ["measurement" "regeneration-cycle/gate-attempt-passed"] [standin _ p]
  (swap! (:state standin) assoc-in [:gated (:cycle-id p)] p)
  (handed-in standin :regeneration-cycle/gate-attempt-passed p))

(defmethod behave ["measurement" "measurement/derive-checklist"] [standin _ {:keys [cut-id selection-id items unstamped skipped-slices]}]
  (let [s @(:state standin)]
    (cond
      (not (contains? (:cuts s) cut-id)) (refuse "cut-not-pinned")
      (seq unstamped) (refuse "unstamped-scenarios" {:unstamped unstamped})
      (empty? items) (refuse "empty-checklist")
      (some #(not (contains? (:items s) (:item-id %))) items)
      (refuse "item-never-stamped" {:item-ids (vec (remove (:items s) (map :item-id items)))})
      :else
      (let [digest (u/sha256-of-text (pr-str items))
            e (ev :measurement/checklist-derived
                  {:cut-id cut-id :selection-id selection-id :checklist-digest digest :items items
                   :total (count items) :skipped-slices skipped-slices :derived-at (now)})]
        (swap! (:state standin) assoc-in [:checklists cut-id] {:items items :digest digest :selection-id selection-id})
        (ok {:events [e]})))))

(defmethod behave ["measurement" "measurement/file-item-verdicts"] [standin _ {:keys [cycle-id candidate-digest grader-digest approved-grader-digests verdicts]}]
  (let [s @(:state standin)
        gated (get-in s [:gated cycle-id])
        checklist (get-in s [:checklists (:cut-id gated)])
        ids (set (map :item-id (:items checklist)))]
    (cond
      (or (nil? gated) (not= candidate-digest (:candidate-digest gated))) (refuse "not-the-gated-candidate")
      (nil? checklist) (refuse "no-checklist")
      (empty? verdicts) (refuse "zero-verdicts")
      (some #(not (#{"pass" "fail"} (:verdict %))) verdicts) (refuse "bad-verdict-value")
      (some #(not (ids (:item-id %))) verdicts)
      (refuse "unknown-item" {:item-ids (vec (remove ids (map :item-id verdicts)))})
      (get-in s [:filed cycle-id]) (refuse "measurement-already-filed")
      (not (some #{grader-digest} approved-grader-digests)) (refuse "grader-not-approved")
      :else
      (let [vs (mapv #(-> (select-keys % [:item-id :verdict]) (assoc :evidence (or (:evidence %) ""))) verdicts)]
        (swap! (:state standin) update-in [:verdicts cycle-id] (fnil into []) vs)
        (ok {:events [(ev :measurement/item-verdicts-filed
                          {:cycle-id cycle-id :candidate-digest candidate-digest :grader-digest grader-digest
                           :verdicts vs :filed-at (now)})]})))))

(defmethod behave ["measurement" "measurement/file-score"] [standin _ {:keys [cycle-id approved-grader-digests]}]
  (let [s @(:state standin)
        gated (get-in s [:gated cycle-id])
        checklist (get-in s [:checklists (:cut-id gated)])
        filed (into {} (map (juxt :item-id identity) (get-in s [:verdicts cycle-id])))]
    (cond
      (nil? gated) (refuse "not-the-gated-candidate")
      (empty? filed) (refuse "zero-verdicts")
      (get-in s [:filed cycle-id]) (refuse "measurement-already-filed")
      :else
      (let [items (vec (for [{:keys [item-id]} (:items checklist)]
                         (or (get filed item-id) {:item-id item-id :verdict "fail" :reason "unreached" :evidence ""})))
            passes (count (filter #(= "pass" (:verdict %)) items))
            e (append-handoff! standin :measurement/measurement-filed
                               {:cycle-id cycle-id :cut-id (:cut-id gated) :selection-id (:selection-id checklist)
                                :candidate-digest (:candidate-digest gated) :checklist-digest (:digest checklist)
                                :grader-digests approved-grader-digests :total (count items) :passes passes
                                :unreached (count (filter #(= "unreached" (:reason %)) items))
                                :items items :superseded [] :filed-at (now)})]
        (swap! (:state standin) assoc-in [:filed cycle-id] true)
        ;; the policy report-scoreboard-once reports before file-score responds
        (append-handoff! standin :measurement/scoreboard-reported
                         {:cycle-id cycle-id :selection-id (:selection-id checklist) :passes passes
                          :total (count items) :rate (format "%.1f" (* 100.0 (/ passes (count items))))
                          :previous-cycle-id nil :previous-selection-id nil
                          :new-items (mapv :item-id items) :dropped-items [] :reported-at (now)})
        (ok {:events [e]})))))

(defmethod behave ["measurement" "measurement/fail-score-stage"] [standin _ {:keys [cycle-id refusal detail]}]
  (if (get-in @(:state standin) [:filed cycle-id])
    (refuse "measurement-already-filed")
    (do (swap! (:state standin) assoc-in [:filed cycle-id] :failed)
        (ok {:events [(append-handoff! standin :measurement/score-stage-failed
                                       {:cycle-id cycle-id :refusal refusal :detail detail :failed-at (now)})]}))))

;; Regeneration cycle
(defn- cycle-of [standin id] (get-in @(:state standin) [:cycles id]))
(defn- update-cycle! [standin id f & args] (apply swap! (:state standin) update-in [:cycles id] f args))

(defn- fail-cycle! [standin id stage reason detail]
  (let [c (cycle-of standin id)]
    (when (= "running" (:status c))
      (update-cycle! standin id assoc :status "failed" :stage stage
                     :failure {:stage stage :reason reason :detail detail})
      (ev :regeneration-cycle/cycle-failed
          {:cycle-id id :stage stage :reason reason :detail detail :concluded-at (now)}))))

(defmethod behave ["regeneration-cycle" "regeneration-cycle/cycles-to-conclude"] [_ _ _] (ok {:cycle-ids []}))

(defmethod behave ["regeneration-cycle" "cycle-preparation/source-cut-pinned"] [standin _ p]
  (swap! (:state standin) update :cuts (fnil conj #{}) (:cut-id p))
  (handed-in standin :cycle-preparation/source-cut-pinned p))

(defmethod behave ["regeneration-cycle" "measurement/measurement-filed"] [standin _ p]
  (when (= "score" (:stage (cycle-of standin (:cycle-id p))))
    (update-cycle! standin (:cycle-id p) assoc :stage "scoreboard" :measured true))
  (handed-in standin :measurement/measurement-filed p))

(defmethod behave ["regeneration-cycle" "measurement/score-stage-failed"] [standin _ p]
  (when (= "score" (:stage (cycle-of standin (:cycle-id p))))
    (fail-cycle! standin (:cycle-id p) "score" "score-stage-failed" (:detail p)))
  (handed-in standin :measurement/score-stage-failed p))

(defmethod behave ["regeneration-cycle" "measurement/scoreboard-reported"] [standin _ p]
  (let [c (cycle-of standin (:cycle-id p))]
    (when (and (= "running" (:status c)) (:measured c))
      (update-cycle! standin (:cycle-id p) assoc :status "passed" :stage "result")))
  (handed-in standin :measurement/scoreboard-reported p))

(defmethod behave ["regeneration-cycle" "regeneration-cycle/record-harness-self-check"]
  [standin _ {:keys [cycle-id cut-id occasion attempt budget outcome] :as p}]
  (let [c (cycle-of standin cycle-id)]
    (cond
      (not (contains? (:cuts @(:state standin)) cut-id)) (refuse "cut-not-pinned")
      (and (nil? c) (not= "before-fence" occasion)) (refuse "not-the-cycles-first-fact")
      (contains? (:self-checks c) [occasion attempt]) (refuse "self-check-already-recorded")
      :else
      (do
        (when-not c
          (update-cycle! standin cycle-id (constantly {:cycle-id cycle-id :cut-id cut-id :status "running"
                                                        :stage "fence" :budget budget :gate-attempts 0
                                                        :consecutive-gate-refusals 0 :attempts {}})))
        (update-cycle! standin cycle-id update :self-checks (fnil conj #{}) [occasion attempt])
        (update-cycle! standin cycle-id assoc :harness-digest (:harness-digest p))
        (if (= "passed" outcome)
          (ok {:events [(ev :regeneration-cycle/harness-self-check-passed
                            (assoc (select-keys p [:cycle-id :cut-id :occasion :attempt :harness-digest :goldens-digest
                                                   :identity-probe-digest :skipped-checks :grader-digests :budget])
                                   :checked-at (now)))]})
          (let [e (ev :regeneration-cycle/harness-self-check-failed
                      (assoc (select-keys p [:cycle-id :cut-id :occasion :attempt :budget :harness-digest
                                             :failing-check :detail :reworded-goldens])
                             :checked-at (now)))]
            (fail-cycle! standin cycle-id (if (= "before-fence" occasion) "fence" "gate")
                         "harness-self-check-failed" (:detail p))
            (ok {:events [e]})))))))

(defmethod behave ["regeneration-cycle" "regeneration-cycle/prove-clean-room"]
  [standin _ {:keys [cycle-id cut-id clean-room-commit fenced-paths root-commits commits unmatched-paths
                     paths-left-in-tree paths-reachable-in-object-graph]}]
  (cond
    (seq unmatched-paths) (refuse "fenced-path-matched-nothing" {:paths unmatched-paths})
    (not= 1 root-commits) (refuse "root-commits-not-one")
    (not= 1 commits) (refuse "commits-not-one")
    (seq paths-left-in-tree) (refuse "fenced-path-left-in-tree" {:paths paths-left-in-tree})
    (seq paths-reachable-in-object-graph) (refuse "fenced-path-reachable-in-object-graph" {:paths paths-reachable-in-object-graph})
    :else (do (update-cycle! standin cycle-id assoc :stage "regenerate" :clean-room-commit clean-room-commit)
              (ok {:events [(ev :regeneration-cycle/clean-room-proved
                                {:cycle-id cycle-id :cut-id cut-id :clean-room-commit clean-room-commit
                                 :fenced-paths fenced-paths :proved-at (now)})]}))))

(defmethod behave ["regeneration-cycle" "regeneration-cycle/record-regenerated-candidate"]
  [standin _ {:keys [cycle-id attempt purpose candidate-digest parent-candidate-digest]}]
  (update-cycle! standin cycle-id #(-> % (assoc :stage "gate" :gate-attempts attempt :candidate-digest candidate-digest)
                                       (assoc-in [:attempts attempt] {:cycle-id cycle-id :attempt attempt
                                                                      :candidate-digest candidate-digest :installed? false})))
  (ok {:events [(ev :regeneration-cycle/candidate-regenerated
                    (cond-> {:cycle-id cycle-id :attempt attempt :purpose purpose :candidate-digest candidate-digest
                             :regenerated-at (now)}
                      parent-candidate-digest (assoc :parent-candidate-digest parent-candidate-digest)))]}))

(defmethod behave ["regeneration-cycle" "regeneration-cycle/install-candidate-in-gate-room"]
  [standin _ {:keys [cycle-id attempt candidate-digest purpose gate-room]}]
  (update-cycle! standin cycle-id assoc-in [:attempts attempt :installed?] true)
  (ok {:events [(ev :regeneration-cycle/candidate-installed-in-gate-room
                    {:cycle-id cycle-id :attempt attempt :candidate-digest candidate-digest :purpose purpose
                     :gate-room gate-room :installed-at (now)})]}))

(defmethod behave ["regeneration-cycle" "regeneration-cycle/record-gate-attempt"]
  [standin _ {:keys [cycle-id attempt candidate-digest outcome reason identity-probe-present seam-answered-before-boot
                     boot-budget-seconds boot-polls answered-on-poll identity-probe-result evidence-root detail]}]
  (let [c (cycle-of standin cycle-id)]
    (cond
      (not identity-probe-present) (refuse "identity-probe-missing")
      seam-answered-before-boot (refuse "seam-answered-before-boot")
      (= "passed" outcome)
      (let [e (append-handoff! standin :regeneration-cycle/gate-attempt-passed
                               {:cycle-id cycle-id :cut-id (:cut-id c) :attempt attempt :candidate-digest candidate-digest
                                :boot-budget-seconds boot-budget-seconds :boot-polls boot-polls
                                :answered-on-poll answered-on-poll :identity-probe-result identity-probe-result
                                :evidence-root evidence-root :passed-at (now)})]
        (update-cycle! standin cycle-id #(-> % (assoc :stage "score" :consecutive-gate-refusals 0)
                                             (update-in [:attempts attempt] assoc :verdict "passed" :evidence-root evidence-root)))
        (ok {:events [e]}))
      :else
      (let [e (ev :regeneration-cycle/gate-attempt-failed
                  {:cycle-id cycle-id :attempt attempt :candidate-digest candidate-digest :reason reason
                   :boot-budget-seconds boot-budget-seconds :boot-polls boot-polls :evidence-root evidence-root
                   :detail detail :failed-at (now)})]
        (update-cycle! standin cycle-id #(-> % (assoc :consecutive-gate-refusals 0)
                                             (update-in [:attempts attempt] assoc :verdict "failed" :reason reason
                                                        :evidence-root evidence-root)))
        (when (>= attempt (get-in c [:budget :gate-attempt-limit] 3))
          (fail-cycle! standin cycle-id "gate" "gate-attempts-exhausted" detail))
        (ok {:events [e]})))))

(defmethod behave ["regeneration-cycle" "regeneration-cycle/record-gate-refusal"]
  [standin _ {:keys [cycle-id attempt refusal detail]}]
  (let [e (ev :regeneration-cycle/gate-attempt-refused
              {:cycle-id cycle-id :attempt attempt :refusal refusal :detail detail :refused-at (now)})]
    (update-cycle! standin cycle-id update :consecutive-gate-refusals inc)
    (when (>= (:consecutive-gate-refusals (cycle-of standin cycle-id)) 3)
      (fail-cycle! standin cycle-id "gate" "infrastructure" detail))
    (ok {:events [e]})))

(defmethod behave ["regeneration-cycle" "regeneration-cycle/fail-cycle"] [standin _ {:keys [cycle-id stage reason detail]}]
  (if-let [e (fail-cycle! standin cycle-id stage reason detail)]
    (ok {:events [e]})
    (refuse "cycle-concluded")))

(defmethod behave ["regeneration-cycle" "regeneration-cycle/cycle-progress"] [standin _ {:keys [cycle-id]}]
  (let [c (cycle-of standin cycle-id)]
    (ok {:cycle (when c
                  (-> (select-keys c [:cycle-id :cut-id :status :stage :consecutive-gate-refusals :budget
                                      :harness-digest :clean-room-commit :candidate-digest :gate-attempts :failure])
                      (assoc :attempts (vec (vals (sort (:attempts c)))))))})))

;; ---------------------------------------------------------------------------
;; The server

(defonce server (atom nil))

(defn standins
  "Every stand-in started, in the order started: [{:context :requests ...}]."
  []
  (:standins @@server))

(defn latest [context]
  (last (filter #(= context (:context %)) (standins))))

(defn requests
  "What the latest stand-in of a context was handed: [[method params] ...]."
  [context]
  (some-> (latest context) :requests deref))

(defn- serve [^Socket sock script]
  (let [in (BufferedReader. (InputStreamReader. (.getInputStream sock) "UTF-8"))
        out (OutputStreamWriter. (.getOutputStream sock) "UTF-8")
        header (u/<-json (.readLine in))
        standin {:context (:context header) :store-path (:store header) :commits (:commits header)
                 :header header :state (atom {}) :store (atom nil) :requests (atom [])}]
    (swap! @server update :standins conj standin)
    (try
      (loop []
        (when-let [line (.readLine in)]
          (let [{:keys [id method params]} (u/<-json line)
                _ (swap! (:requests standin) conj [method params])
                scripted (get-in @script [(:context standin) method])
                reply (cond
                        (= :exit scripted) ::exit
                        (= :hang scripted) ::hang
                        (fn? scripted) (scripted standin params)
                        :else (behave standin method params))]
            (cond
              (= ::exit reply) nil
              (= ::hang reply) (do (Thread/sleep Long/MAX_VALUE) nil)
              :else (do (.write out (str (u/->json (merge {:jsonrpc "2.0" :id id} reply)) "\n"))
                        (.flush out)
                        (recur))))))
      (catch Exception e (u/log "stand-in" (:context standin) "failed:" (.getMessage e)) (.printStackTrace e))
      (finally
        (try (.close sock) (catch Exception _ nil))
        (when-let [s @(:store standin)] (try (es/stop s) (catch Exception _ nil)))))))

(defn start!
  "Start the stand-in server once; `script` is an atom {context {method
   :exit | :hang | (fn [standin params] reply)}} the tests set."
  [script]
  (when-not @server
    (register-handoffs!)
    (let [ss (ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
          state (atom {:port (.getLocalPort ss) :standins [] :socket ss})]
      (reset! server state)
      (doto (Thread. (fn []
                       (loop []
                         (when-let [s (try (.accept ss) (catch Exception _ nil))]
                           (doto (Thread. #(serve s script)) (.setDaemon true) (.start))
                           (recur)))))
        (.setDaemon true)
        (.start))))
  (:port @@server))

(defn reset-standins! [] (swap! @server assoc :standins []))
