;; harness/v2/event_store_check.clj EVENT-MODEL-DIR STORE: the event-store check of the gate
;; (.pi/spec/decisions.md, "First light", 3). Run by harness/v2/event-store-check.sh.
;;
;; The gate handed the candidate STORE, a Grain SQLite event store file, through
;; LOYLY_GRAIN_STORE (the context's seam.md). After the suite and the identity probe, this
;; reads that store directly, with Grain's own reader, and never through the candidate's
;; seam. It refuses when:
;;   - there is no store, or it holds no event of the model
;;   - an event's type is not an event of EVENT-MODEL-DIR/*.edn (the slice's event model, read
;;     from this checkout, not from the room, which the builder could write). Grain's own
;;     bookkeeping (types in the grain or grain.* namespaces, such as the :grain/tx its
;;     appender writes with every batch) is the stack's, not the candidate's: it is listed
;;     and neither counted nor refused.
;;   - an event's fields, other than Grain's own :event/* keys, are not exactly its schema
;;     (the model's malli schema, closed: no missing, extra or ill-typed field)
;;   - a handoff event of another context is not one the slice consumes. A consumed handoff
;;     (a :<producer>/<event> the slice's model names that the producer's
;;     event-model/handoffs.edn, beside EVENT-MODEL-DIR's context in this checkout, defines)
;;     reaches a lone candidate through its seam, standing in for the stubbed producer
;;     (decisions.md, "Slice 02"): it is listed, held to exactly its contract's schema like a
;;     model event, and neither counted as the candidate's own nor owed an ORC run. Any other
;;     foreign type is refused like an unknown one.
;;   - ORC did not run on this store (seam.md, "The event store"; decisions.md, "First light",
;;     4). ORC's own events (types in the sheet, rlm or intervention namespaces, those of the
;;     orc-service component) are listed like Grain's, neither counted nor refused. Each
;;     event a command of the model produced must be written inside a run of the ORC workflow
;;     named after the command's flow (:specification/stamping for :specification/stamp-slice)
;;     that completed with success. The linkage is the store's own order, within the event's
;;     tenant: the event lies after the run's :sheet/tree-tick-started and the run has a
;;     :sheet/tree-tick-completed with :root-status :success; the run's workflow is the name
;;     of its sheet (:sheet/sheet-created). ORC writes the started event before the run's
;;     first leaf and the completed one after its last, so a command issued outside ORC, or
;;     in a run that failed or never ended, is refused. It cannot tell two overlapping runs
;;     of the same workflow apart, which the gate's one caller never makes.
;;   - rule "each effect in its own leaf" (spec round 14; cycle-runner/seam.md, "The trees"),
;;     only for a model whose flow steps declare leaves: a step from a command to its event
;;     with :leaf, a name, or :effect for the leaf named by the event's own :effect. Each such
;;     event must lie between a :sheet/node-execution-started and the next
;;     :sheet/node-execution-completed (status :success) of the same node and tick, the node
;;     a leaf of a sheet named after the step's flow, its name (:sheet/node-name-set) the
;;     declared one, or that name followed by ":" and a qualifier. A flow run as one leaf
;;     around orchestration of the candidate's own (one "perform-command" leaf) fails it. A
;;     model that declares no leaf (slices 01 to 05 today) is not checked for leaves.
;;   - rule "one terminal completion per tick": within a tenant, a tick has more than one
;;     :sheet/tree-tick-completed whose :root-status is terminal (not :running), or a trace has
;;     more than one :sheet/execution-traced (by :trace-id). ORC's processors append these; a
;;     processor started without its :processor-name skips Grain's checkpoint and CAS, so
;;     at-least-once delivery appends them twice. A :running completion followed by a fresh
;;     tree-tick-started is ORC re-ticking, and is not counted.
;;   - rule "processors are checkpointed": a tenant holds events ORC's processors write
;;     (:sheet/tree-tick-completed, :sheet/execution-traced) but no
;;     :grain/todo-processor-checkpoint, the event Grain's todo processor appends, atomically
;;     with each output, only when it was started with a :processor-name. Grain's checkpoint
;;     and effect-failure events are Grain's own (the grain namespace), listed and allowed.
;; The store is copied first and the copy is opened, because opening a Grain store writes to it.
(ns event-store-check
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.util :as mu]))

(defn consumed-handoffs
  "{type schema} of the handoff events of other contexts that FRAGMENTS (the slice's model,
  read from DIR) name, each from its producer's DIR/../../<producer>/event-model/handoffs.edn."
  [dir fragments]
  (let [own (set (map name (mapcat keys fragments)))
        spec (.getParentFile (.getParentFile (.getCanonicalFile (io/file dir))))]
    (into {} (for [k (set (filter #(and (keyword? %) (namespace %) (not (own (namespace %))))
                                  (tree-seq coll? seq fragments)))
                   :let [f (io/file spec (namespace k) "event-model" "handoffs.edn")]
                   :when (.isFile f)
                   :let [schema (get-in (edn/read-string (slurp f)) [(keyword (namespace k)) :events k :schema])]
                   :when schema]
               [k schema]))))

(defn load-model
  "The *.edn fragments of DIR merged: {:events {type schema}, :consumed {type schema},
  :workflows {event-type #{name}}, :leaves {event-type {flow leaf}}}, :consumed being the
  handoff events of other contexts the model consumes, :workflows naming, for each event a
  command produces, the flows that command belongs to, and :leaves, for each event a step
  from a command declares a :leaf for, that leaf in each such flow."
  [dir]
  (let [fragments (for [f (sort-by str (.listFiles (io/file dir)))
                        :when (str/ends-with? (.getName f) ".edn")]
                    (edn/read-string (slurp f)))
        areas (for [fr fragments [_area am] fr] am)
        commands (apply merge (map :commands areas))
        flows (apply merge (map :flows areas))
        flows-of (fn [c] (set (for [[fname {:keys [steps]}] flows
                                    {:keys [from to]} steps
                                    :when (some #{[:command c]} [from to])]
                                (subs (str fname) 1))))]
    {:events (into {} (for [am areas [k {:keys [schema]}] (:events am)] [k schema]))
     :consumed (consumed-handoffs dir fragments)
     :leaves (apply merge-with merge {}
                    (for [[fname {:keys [steps]}] flows
                          {:keys [from to leaf]} steps
                          :when (and leaf (= :command (first from)) (= :event (first to)))]
                      {(second to) {(subs (str fname) 1) leaf}}))
     :workflows (apply merge-with into {}
                       (for [[c {:keys [produces]}] commands
                             :let [ws (flows-of c)]
                             :when (seq ws)
                             e produces]
                         {e ws}))}))

(defn copy-store
  "Copy STORE and its -wal and -shm companions into a fresh directory; the copy's path."
  [store]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "event-store-check" (make-array java.nio.file.attribute.FileAttribute 0)))
        target (io/file dir "store.sqlite")]
    (doseq [suffix ["" "-wal" "-shm"]
            :let [f (io/file (str store suffix))]
            :when (.isFile f)]
      (io/copy f (io/file (str target suffix))))
    (str target)))

(defn read-all [path]
  (let [store (es/start {:conn {:type :sqlite :database-file path}})]
    (try
      (vec (for [t (sort-by str (keys (es/tenants store)))
                 e (into [] (es/read store {:tenant-id t}))]
             (assoc e ::tenant t)))
      (finally (es/stop store)))))

(defn grain-own? [t]
  (let [n (namespace t)] (boolean (and n (or (= n "grain") (str/starts-with? n "grain."))))))

(def orc-namespaces #{"sheet" "rlm" "intervention"})

(defn orc-own? [t] (contains? orc-namespaces (namespace t)))

(defn workflow-problems
  "For each event whose type a command of a flow produces (WORKFLOWS), in EVENTS (store
  order, all tenants), whether it lies inside a run of its flow's ORC workflow that completed
  with success. Prints the runs; returns the problems."
  [workflows events]
  (let [owed (filter #(workflows (:event/type %)) events)]
    (cond
      (empty? owed) []

      (not-any? #(orc-own? (:event/type %)) events)
      [(str "the store holds no ORC event: ORC did not run on the store handed in through "
            "LOYLY_GRAIN_STORE, so nothing shows that "
            (str/join ", " (sort (set (mapcat workflows (map :event/type owed)))))
            " ran; ORC must run on that store, not on one of its own")]

      :else
      (let [by-tenant (group-by ::tenant events)
            runs (for [[t es] by-tenant
                       :let [names (into {} (for [e es :when (= :sheet/sheet-created (:event/type e))]
                                              [(:sheet-id e) (:name e)]))]
                       [i e] (map-indexed vector es)
                       :when (= :sheet/tree-tick-started (:event/type e))
                       :let [done (first (keep-indexed
                                          (fn [j c] (when (and (= :sheet/tree-tick-completed (:event/type c))
                                                               (= (:tick-id e) (:tick-id c))
                                                               (not= :running (:root-status c)))
                                                      [j (:root-status c)]))
                                          es))]]
                   {:tenant t :tick (:tick-id e) :workflow (names (:sheet-id e) (str (:sheet-id e)))
                    :from i :to (first done) :status (second done)})]
        (doseq [[w rs] (sort-by key (group-by :workflow runs))]
          (println (format "  ORC workflow %s: %d run(s), %d completed with success" w (count rs)
                           (count (filter #(= :success (:status %)) rs)))))
        (vec (for [[t es] by-tenant
                   [i e] (map-indexed vector es)
                   :let [ws (workflows (:event/type e))]
                   :when ws
                   :let [inside (filter #(and (= t (:tenant %)) (ws (:workflow %)) (< (:from %) i)
                                              (or (nil? (:to %)) (< i (:to %))))
                                        runs)
                         what (str "event " (:event/id e) " (" (subs (str (:event/type e)) 1) ")")
                         wf (str/join " or " (sort ws))]
                   :when (not-any? #(= :success (:status %)) inside)]
               (if (empty? inside)
                 (str what " was not written inside a run of the ORC workflow " wf
                      ": its command did not run through ORC on this store")
                 (str what " was written inside a run of the ORC workflow " wf
                      " that never completed with success ("
                      (str/join ", " (map #(str "tick " (:tick %) ": "
                                                (if (:status %) (name (:status %)) "no tree-tick-completed"))
                                          inside))
                      ")"))))))))

(defn leaf-problems
  "Rule \"each effect in its own leaf\": each event LEAVES declares a leaf for lies inside a
  completed execution of that leaf, in a sheet named after one of its flows."
  [leaves events]
  (if (empty? leaves)
    []
    (vec (for [[t es] (group-by ::tenant events)
               :let [sheets (into {} (for [e es :when (= :sheet/sheet-created (:event/type e))] [(:sheet-id e) (:name e)]))
                     names (into {} (for [e es :when (= :sheet/node-name-set (:event/type e))] [(:node-id e) (:name e)]))
                     runs (for [[a e] (map-indexed vector es)
                                :when (= :sheet/node-execution-started (:event/type e))
                                :let [[b c] (first (keep-indexed
                                                    (fn [j c] (when (and (> j a) (= :sheet/node-execution-completed (:event/type c))
                                                                         (= (:tick-id e) (:tick-id c)) (= (:node-id e) (:node-id c)))
                                                                [j c]))
                                                    es))]
                                :when b]
                            {:from a :to b :status (:status c) :workflow (sheets (:sheet-id e))
                             :name (str (names (:node-id e) (:node-id e)))})]
               [i e] (map-indexed vector es)
               :let [wanted (leaves (:event/type e))]
               :when wanted
               :let [wanted (update-vals wanted #(if (= :effect %) (str (:effect e)) %))
                     inside (filter #(and (< (:from %) i (:to %)) (= :success (:status %))) runs)]
               :when (not-any? #(= (wanted (:workflow %)) (first (str/split (:name %) #":" 2))) inside)]
           (str "rule \"each effect in its own leaf\" failed: event " (:event/id e) " ("
                (subs (str (:event/type e)) 1) (when (:effect e) (str ", effect " (:effect e)))
                ") was not written inside a leaf named "
                (str/join " or " (sort (for [[l ws] (group-by val wanted)]
                                         (str l " of the ORC workflow " (str/join " or " (sort (map key ws)))))))
                (if (seq inside)
                  (str "; it lies inside " (str/join ", " (map #(str "the leaf " (:name %) " of " (:workflow %)) inside)))
                  "; it lies inside no leaf")
                ": each effect or command is a leaf of its own, named for it (cycle-runner/seam.md, \"The trees\")")))))

(def processor-fix
  "start each processor with its :processor-name (tp/start {... :processor-name ...}), so Grain checkpoints each output and drops a redelivered event")

(defn duplicate-problems
  "Rule \"one terminal completion per tick\": within a tenant, at most one terminal
  :sheet/tree-tick-completed per :tick-id and one :sheet/execution-traced per :trace-id."
  [events]
  (let [doubled (fn [pred k]
                  (sort-by str (for [[[t id] es] (group-by (juxt ::tenant k) (filter pred events))
                                     :when (> (count es) 1)]
                                 [t id es])))]
    (vec (concat
          (for [[t id es] (doubled #(and (= :sheet/tree-tick-completed (:event/type %))
                                         (not= :running (:root-status %)))
                                   :tick-id)]
            (str "rule \"one terminal completion per tick\" failed: tick " id " (tenant " t ") has "
                 (count es) " terminal sheet/tree-tick-completed events ("
                 (str/join ", " (map #(str (some-> (:root-status %) name) " " (:event/id %)) es))
                 "): an ORC processor appended its output more than once; " processor-fix))
          (for [[t id es] (doubled #(= :sheet/execution-traced (:event/type %)) :trace-id)]
            (str "rule \"one terminal completion per tick\" failed: trace " id " (tenant " t ") has "
                 (count es) " sheet/execution-traced events ("
                 (str/join ", " (map #(str (:event/id %)) es))
                 "): an ORC processor appended its output more than once; " processor-fix))))))

(def processor-written #{:sheet/tree-tick-completed :sheet/execution-traced})

(defn checkpoint-problems
  "Rule \"processors are checkpointed\": a tenant with events ORC's processors write holds
  Grain's :grain/todo-processor-checkpoint events too."
  [events]
  (vec (for [[t es] (sort-by (comp str key) (group-by ::tenant events))
             :let [written (filter #(processor-written (:event/type %)) es)]
             :when (and (seq written)
                        (not-any? #(= :grain/todo-processor-checkpoint (:event/type %)) es))]
         (str "rule \"processors are checkpointed\" failed: tenant " t " holds "
              (count written) " event(s) ORC's processors wrote ("
              (str/join ", " (sort (set (map #(subs (str (:event/type %)) 1) written))))
              ") but no grain/todo-processor-checkpoint: the processors ran without Grain's "
              "checkpoint, so a redelivered event is processed again; " processor-fix))))

(defn body [e]
  (into {} (remove (fn [[k _]] (or (= "event" (namespace k)) (= ::tenant k)))) e))

(defn check [model-dir store]
  (let [{own :events consumed :consumed workflows :workflows leaves :leaves} (load-model model-dir)
        schemas (merge consumed own)]
    (cond
      (empty? own)
      [(str "the event model at " model-dir " has no event: nothing to check against")]

      (not (and (.isFile (io/file store)) (pos? (.length (io/file store)))))
      [(str "no event store at " store ": the candidate recorded nothing where the gate told it to (LOYLY_GRAIN_STORE)")]

      :else
      (let [events (read-all (copy-store store))]
        (println (str "event store " store ": " (count events) " events in "
                      (count (distinct (map ::tenant events))) " tenants"))
        (doseq [[t n] (sort-by (comp str key) (frequencies (map :event/type events)))]
          (println (format "  %4d  %s%s" n (subs (str t) 1)
                           (cond (contains? own t) "" (contains? consumed t) "   (a consumed handoff, handed in)"
                                 (grain-own? t) "   (Grain's own)"
                                 (orc-own? t) "   (ORC's own)" :else "   (not in the event model)"))))
        (when (seq consumed)
          (println (str "  consumed handoffs allowed, each with its contract's schema: "
                        (str/join ", " (sort (map #(subs (str %) 1) (keys consumed)))))))
        (if (not-any? #(contains? own (:event/type %)) events)
          ["the event store holds no event of the model: the candidate did not record its facts in Grain"]
          (vec (concat
                (for [t (sort-by str (distinct (map :event/type events)))
                      :when (not (or (contains? schemas t) (grain-own? t) (orc-own? t)))]
                  (str "event type " (subs (str t) 1) " is not an event of " model-dir))
                (for [e events
                      :let [schema (schemas (:event/type e))]
                      :when schema
                      :let [explain (m/explain (mu/closed-schema (m/schema schema)) (body e))]
                      :when explain]
                  (str "event " (:event/id e) " (" (subs (str (:event/type e)) 1) ") does not match its schema: "
                       (pr-str (me/humanize explain))))
                (workflow-problems workflows events)
                (leaf-problems leaves events)
                (duplicate-problems events)
                (checkpoint-problems events))))))))

(let [[model-dir store] *command-line-args*
      _ (when-not (and model-dir store)
          (println "usage: event_store_check.clj EVENT-MODEL-DIR STORE") (System/exit 2))
      problems (try (check model-dir store)
                    (catch Throwable t [(str "the event store could not be read: " (.getMessage t))]))]
  (doseq [p problems] (println "  FAIL " p))
  (println (if (seq problems)
             (str "event-store check FAILED: " (count problems) " problem(s)")
             (str "event-store check OK: every event is an event of the model or a handoff it consumes, "
                  "with exactly its fields, or Grain's or ORC's own, and each event a command produced lies inside a completed run "
                  "of its ORC workflow (in its own named leaf, where the model names one), each tick completed once, and ORC's processors checkpointed")))
  (shutdown-agents)
  (System/exit (if (seq problems) 1 0)))
