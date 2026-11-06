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

(defn model-events
  "{event-type schema} of every *.edn fragment in DIR."
  [dir]
  (into {} (for [f (sort-by str (.listFiles (io/file dir)))
                 :when (str/ends-with? (.getName f) ".edn")
                 [_area am] (edn/read-string (slurp f))
                 [k {:keys [schema]}] (:events am)]
             [k schema])))

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

(defn body [e]
  (into {} (remove (fn [[k _]] (or (= "event" (namespace k)) (= ::tenant k)))) e))

(defn check [model-dir store]
  (let [schemas (model-events model-dir)]
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
                           (cond (contains? schemas t) "" (grain-own? t) "   (Grain's own)" :else "   (not in the event model)"))))
        (if (not-any? #(contains? schemas (:event/type %)) events)
          ["the event store holds no event of the model: the candidate did not record its facts in Grain"]
          (vec (concat
                (for [t (sort-by str (distinct (map :event/type events)))
                      :when (not (or (contains? schemas t) (grain-own? t)))]
                  (str "event type " (subs (str t) 1) " is not an event of " model-dir))
                (for [e events
                      :let [schema (schemas (:event/type e))]
                      :when schema
                      :let [explain (m/explain (mu/closed-schema (m/schema schema)) (body e))]
                      :when explain]
                  (str "event " (:event/id e) " (" (subs (str (:event/type e)) 1) ") does not match its schema: "
                       (pr-str (me/humanize explain)))))))))))

(let [[model-dir store] *command-line-args*
      _ (when-not (and model-dir store)
          (println "usage: event_store_check.clj EVENT-MODEL-DIR STORE") (System/exit 2))
      problems (try (check model-dir store)
                    (catch Throwable t [(str "the event store could not be read: " (.getMessage t))]))]
  (doseq [p problems] (println "  FAIL " p))
  (println (if (seq problems)
             (str "event-store check FAILED: " (count problems) " problem(s)")
             "event-store check OK: every event is an event of the model, with exactly its fields"))
  (shutdown-agents)
  (System/exit (if (seq problems) 1 0)))
