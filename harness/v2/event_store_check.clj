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

(defn load-model
  "The *.edn fragments of DIR merged: {:events {type schema}, :workflows {event-type #{name}}},
  :workflows naming, for each event a command produces, the flows that command belongs to."
  [dir]
  (let [areas (for [f (sort-by str (.listFiles (io/file dir)))
                    :when (str/ends-with? (.getName f) ".edn")
                    [_area am] (edn/read-string (slurp f))]
                am)
        commands (apply merge (map :commands areas))
        flows (apply merge (map :flows areas))
        flows-of (fn [c] (set (for [[fname {:keys [steps]}] flows
                                    {:keys [from to]} steps
                                    :when (some #{[:command c]} [from to])]
                                (subs (str fname) 1))))]
    {:events (into {} (for [am areas [k {:keys [schema]}] (:events am)] [k schema]))
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

(defn body [e]
  (into {} (remove (fn [[k _]] (or (= "event" (namespace k)) (= ::tenant k)))) e))

(defn check [model-dir store]
  (let [{schemas :events workflows :workflows} (load-model model-dir)]
    (cond
      (empty? schemas)
      [(str "the event model at " model-dir " has no event: nothing to check against")]

      (not (and (.isFile (io/file store)) (pos? (.length (io/file store)))))
      [(str "no event store at " store ": the candidate recorded nothing where the gate told it to (LOYLY_GRAIN_STORE)")]

      :else
      (let [events (read-all (copy-store store))]
        (println (str "event store " store ": " (count events) " events in "
                      (count (distinct (map ::tenant events))) " tenants"))
        (doseq [[t n] (sort-by (comp str key) (frequencies (map :event/type events)))]
          (println (format "  %4d  %s%s" n (subs (str t) 1)
                           (cond (contains? schemas t) "" (grain-own? t) "   (Grain's own)"
                                 (orc-own? t) "   (ORC's own)" :else "   (not in the event model)"))))
        (if (not-any? #(contains? schemas (:event/type %)) events)
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
                (workflow-problems workflows events))))))))

(let [[model-dir store] *command-line-args*
      _ (when-not (and model-dir store)
          (println "usage: event_store_check.clj EVENT-MODEL-DIR STORE") (System/exit 2))
      problems (try (check model-dir store)
                    (catch Throwable t [(str "the event store could not be read: " (.getMessage t))]))]
  (doseq [p problems] (println "  FAIL " p))
  (println (if (seq problems)
             (str "event-store check FAILED: " (count problems) " problem(s)")
             (str "event-store check OK: every event is an event of the model, with exactly its fields, "
                  "or Grain's or ORC's own, and each event a command produced lies inside a completed run "
                  "of its ORC workflow")))
  (shutdown-agents)
  (System/exit (if (seq problems) 1 0)))
