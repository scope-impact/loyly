;; harness/v2/store_reader.clj: the assembly gate's reader of a candidate's Grain store
;; (.pi/spec/decisions.md, "Spec round 5" 2), started once by harness/v2/assemble.clj. Each
;; line on stdin is an EDN request {:store <file> :types #{<event type keyword>}}; the answer is
;; one EDN line on stdout, {:events [{:tenant <id> :type <keyword> :event <fields>}]}, the
;; events of those types, tenants in order and each tenant's events in store order, the
;; fields without Grain's own :event/* keys and with instants and UUIDs as text, as the seams
;; carry them; or {:error <message>}. It reads the store directly, with Grain's own reader, as
;; harness/v2/event_store_check.clj does, never through the candidate's seam, and opens a copy
;; (store, -wal, -shm), because opening a Grain store writes to it.
(ns store-reader
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.walk :as walk]))

(defn copy-store [store]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "assemble-store" (make-array java.nio.file.attribute.FileAttribute 0)))
        target (io/file dir "store.sqlite")]
    (doseq [suffix ["" "-wal" "-shm"]
            :let [f (io/file (str store suffix))]
            :when (.isFile f)]
      (io/copy f (io/file (str target suffix))))
    (str target)))

(defn read-all [store types]
  (let [path (copy-store store)
        s (es/start {:conn {:type :sqlite :database-file path}})]
    (try
      (vec (for [t (sort-by str (keys (es/tenants s)))
                 e (into [] (es/read s {:tenant-id t}))
                 :when (types (:event/type e))]
             {:tenant (str t) :type (:event/type e)
              :event (into {} (remove (fn [[k _]] (= "event" (namespace k)))) e)}))
      (finally (es/stop s)))))

(defn as-text
  "Values as the seams carry them: an instant as ISO-8601 UTC text, a UUID as text."
  [x]
  (walk/postwalk
   (fn [v] (cond (instance? java.util.Date v) (str (.toInstant ^java.util.Date v))
                 (instance? java.time.Instant v) (str v)
                 (instance? java.time.OffsetDateTime v) (str (.toInstant ^java.time.OffsetDateTime v))
                 (uuid? v) (str v)
                 :else v))
   x))

(loop []
  (when-let [line (read-line)]
    (println (pr-str (try (let [{:keys [store types]} (edn/read-string line)]
                            {:events (as-text (read-all store (set types)))})
                          (catch Throwable t {:error (str (class t) ": " (ex-message t))}))))
    (flush)
    (recur)))
