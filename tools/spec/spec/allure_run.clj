(ns spec.allure-run
  "Build a derived Allure input from a grader-only room and its observed probe events."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [spec.allure :as allure])
  (:import [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]
           [java.time OffsetDateTime]))

(defn fail [message] (throw (ex-info message {})))
(defn sha256 [file]
  (let [bytes (Files/readAllBytes (.toPath (io/file file)))
        md (MessageDigest/getInstance "SHA-256")]
    (.update md bytes)
    (apply str (map #(format "%02x" (bit-and 255 %)) (.digest md)))))
(defn millis [at]
  (.toEpochMilli (.toInstant (OffsetDateTime/parse at))))
(defn probe-name [{:keys [type phase name event_count trace_count]}]
  (case type
    "capture" (case phase
                "before" "Capture before boundary"
                "active" "Observe active candidate and VM"
                "after" "Capture after shutdown boundary"
                (fail (str "unknown capture phase: " phase)))
    "store" (str "Inspect Grain store (" event_count " events, " trace_count " traces)")
    "jvm" "Capture live JVM diagnostic"
    "jfr" "Capture Java Flight Recorder sample"
    "mark" (case name
             "model-requests" "Record model request count"
             "run-id" "Record observed run identity"
             "trace-id" "Record observed Grain trace identity"
             "no-store" "Record why no Grain store was opened"
             (str "Record " name))
    (fail (str "unknown probe event type: " type))))
(defn step [event]
  (let [at (millis (:at_utc event))]
    {:name (probe-name event)
     :status "passed"
     :stage "finished"
     :start at
     :stop at}))
(defn case-events [events id]
  (let [rows (->> events (filter #(= id (:case %))) (sort-by :at_utc) vec)
        phases (map :phase (filter #(= "capture" (:type %)) rows))]
    (when-not (= 1 (count (filter #(= "before" %) phases)))
      (fail (str id " must have one before capture")))
    (when-not (= 1 (count (filter #(= "after" %) phases)))
      (fail (str id " must have one after capture")))
    (when-not (= "before" (first phases))
      (fail (str id " first capture is not before")))
    (when-not (= "after" (last phases))
      (fail (str id " last capture is not after")))
    (mapv step rows)))
(defn result-files [dir]
  (sort (filter #(str/ends-with? (.getName %) "-result.json")
                (or (.listFiles (io/file dir)) []))))
(defn -main [room cut output-path]
  (when-not (and room (re-matches #"[0-9a-f]{40}" (or cut "")) output-path)
    (fail "usage: clojure -M -m spec.allure-run GRADING_ROOM FULL_SPEC_COMMIT OUTPUT_DIR"))
  (let [room (io/file room)
        results (io/file room "work/allure-results")
        verdicts-file (io/file room "work/verdicts.json")
        profile (allure/read-json (io/file room "work/profile.json"))
        events-file (io/file room "work/profile-events.jsonl")
        output (io/file output-path)
        verdicts (allure/read-json verdicts-file)
        events (mapv #(json/parse-string % true) (remove str/blank? (str/split-lines (slurp events-file))))
        scenarios (allure/scenarios-at cut)
        ids (mapv :id verdicts)
        files (result-files results)
        result-ids (mapv (comp allure/result-id allure/read-json) files)]
    (when-not (= (:events_sha256 profile) (sha256 events-file))
      (fail "profile does not bind probe event bytes"))
    (when-not (= (:verdicts_sha256 profile) (sha256 verdicts-file))
      (fail "profile does not bind verdict bytes"))
    (when-not (= (:grader_instruction_sha256 profile)
                 (sha256 (io/file room ".pi/harness/grader-instruction-06.md")))
      (fail "profile does not bind grader instruction bytes"))
    (when-not (= ids (mapv :id (:cases profile)))
      (fail "profile cases differ from verdict order"))
    (when-not (= (count ids) (count files))
      (fail "Allure result count differs from verdict count"))
    (when-not (= (count ids) (count (set ids))) (fail "duplicate verdict item"))
    (when-not (= (set ids) (set result-ids))
      (fail "Allure result item tags differ from verdicts"))
    (when (some nil? (map scenarios ids)) (fail "item missing from approved Gherkin"))
    (when (and (.exists output) (seq (.listFiles output)))
      (fail (str "derived Allure output is not empty: " output)))
    (.mkdirs output)
    (doseq [file (.listFiles results)]
      (Files/copy (.toPath file) (.toPath (io/file output (.getName file)))
                  (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING])))
    (doseq [file files
            :let [base (allure/read-json file)
                  id (allure/result-id base)
                  verdict (first (filter #(= id (:id %)) verdicts))
                  expected (if (= "pass" (:verdict verdict)) "passed" "failed")]]
      (when-not (= expected (:status base))
        (fail (str "Allure status differs from verdict: " id)))
      (let [{:keys [source-name source-text result]}
            (allure/enrich base (scenarios id) cut)
            result (assoc result :steps (case-events events id))]
        (allure/write-json (io/file output (.getName file)) result)
        (spit (io/file output source-name) source-text)))
    (println "Derived Allure results:" (count files) "items with observed grader steps at" (.getPath output))
    (shutdown-agents)))
