;; A read-only summary of ORC's own Grain evidence for one grading fixture.
;; Run from harness/v2: clojure -M -m profile-store STORE > profile-store.json
;; It copies the store and WAL before opening it, as event_store_check.clj does.
(ns profile-store
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]))

(defn digest [s]
  (let [bytes (.digest (doto (MessageDigest/getInstance "SHA-256")
                         (.update (.getBytes (str s) "UTF-8"))))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) bytes))))

(defn file-digest [path]
  (let [md (MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (doseq [suffix ["" "-wal" "-shm"]
            :let [file (io/file (str path suffix))]
            :when (.isFile file)]
      (.update md (.getBytes suffix "UTF-8"))
      (with-open [in (io/input-stream file)]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n)
              (.update md buf 0 n)
              (recur))))))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest md)))))

(defn copy-store [store]
  (let [dir (.toFile (Files/createTempDirectory "loyly-profile-"
                                               (make-array FileAttribute 0)))
        target (io/file dir "store.sqlite")]
    (doseq [suffix ["" "-wal" "-shm"]
            :let [f (io/file (str store suffix))]
            :when (.isFile f)]
      (io/copy f (io/file (str target suffix))))
    (str target)))

(defn read-events [store]
  (let [copy (copy-store store)
        snapshot-sha256 (file-digest copy)
        dir (.getParentFile (io/file copy))]
    (try
      (let [handle (es/start {:conn {:type :sqlite :database-file copy}})]
        (try
          {:events (vec (mapcat (fn [tenant] (into [] (es/read handle {:tenant-id tenant})))
                                (sort-by str (keys (es/tenants handle)))))
           :snapshot-sha256 snapshot-sha256}
          (finally (es/stop handle))))
      (finally
        (doseq [file (.listFiles dir)] (.delete file))
        (.delete dir)))))

(defn usage [e]
  (let [u (or (:own-usage e) (:usage e))]
    (when (map? u)
      {:prompt-tokens (or (:prompt-tokens u) (:prompt_tokens u) 0)
       :completion-tokens (or (:completion-tokens u) (:completion_tokens u) 0)
       :total-tokens (or (:total-tokens u) (:total_tokens u) 0)})))

(defn summarize [store]
  (let [{:keys [events snapshot-sha256]} (read-events store)
        names (into {} (for [e events :when (= :sheet/node-name-set (:event/type e))]
                         [(:node-id e) (:name e)]))
        instructions (into {} (for [e events :when (= :sheet/node-instruction-set (:event/type e))]
                                [(:node-id e) (digest (:instruction e))]))
        sheets (into {} (for [e events :when (= :sheet/sheet-created (:event/type e))]
                          [(:sheet-id e) (:name e)]))
        counts (into (sorted-map)
                     (for [[kind n] (frequencies (map (comp str :event/type) events))]
                       [kind n]))]
    {:version 1
     :store-sha256 snapshot-sha256
     :event-count (count events)
     :event-counts counts
     :traces (vec (for [e events :when (= :sheet/execution-traced (:event/type e))]
                    {:trace-id (str (:trace-id e))
                     :root-trace-id (str (:root-trace-id e))
                     :parent-trace-id (some-> (:parent-trace-id e) str)
                     :sheet (get sheets (:sheet-id e))
                     :status (str (:status e))
                     :duration-ms (:duration-ms e)
                     :node-count (count (:node-traces e))
                     :input-profile (:input-snapshot e)
                     :output-profile (:output-snapshot e)}))
     :nodes (vec (for [e events :when (= :sheet/node-execution-completed (:event/type e))]
                   {:tick-id (str (:tick-id e))
                    :name (get names (:node-id e))
                    :sheet (get sheets (:sheet-id e))
                    :status (str (:status e))
                    :duration-ms (:duration-ms e)
                    :usage (usage e)
                    :instruction-sha256 (get instructions (:node-id e))}))
     :judge-scores (count (filter #(= :judge/score-emitted (:event/type %)) events))}))

(defn -main [& [store]]
  (when-not (and store (.isFile (io/file store)))
    (binding [*out* *err*] (println "usage: clojure -M -m profile-store STORE"))
    (System/exit 2))
  (println (json/write-str (summarize store))))
