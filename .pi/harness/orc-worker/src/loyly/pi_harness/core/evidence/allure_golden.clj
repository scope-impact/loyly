(ns loyly.pi-harness.core.evidence.allure-golden
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(def ^:private execution-labels
  #{"builder" "cycle_id" "cut_digest" "subject_digest" "oracle_digest" "room"})

(def ^:private execution-json-keys
  #{"builder" "cycle-id" "cycleId" "cut-digest" "cutDigest"
    "subject-digest" "subjectDigest" "oracle-digest" "oracleDigest" "room"})

(defn- sha256 [^bytes content]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest content)))))

(defn- canonical-json [value]
  (cond
    (map? value) (into (sorted-map)
                       (keep (fn [[key child]]
                               (let [key-name (name key)]
                                 (when-not (execution-json-keys key-name)
                                   [key-name (canonical-json child)]))))
                       value)
    (vector? value) (mapv canonical-json value)
    (sequential? value) (mapv canonical-json value)
    :else value))

(defn- attachment-bytes [file media-type]
  (if (= "application/json" media-type)
    (.getBytes (json/write-str
                (canonical-json (json/read-str (slurp file))))
               "UTF-8")
    (java.nio.file.Files/readAllBytes (.toPath file))))

(defn- normalize-attachment [results-root attachment]
  (let [file (io/file results-root (:source attachment))
        media-type (:type attachment)]
    {:name (:name attachment)
     :mediaType media-type
     :digest (str "sha256:" (sha256 (attachment-bytes file media-type)))}))

(defn normalize-result [results-root result-file]
  (let [result (json/read-str (slurp result-file) :key-fn keyword)
        labels (->> (:labels result)
                    (remove #(execution-labels (:name %)))
                    (map (juxt #(keyword (:name %)) :value))
                    (into (sorted-map)))
        normalized (cond->
                    {:schemaVersion 1
                     :name (:name result)
                     :fullName (:fullName result)
                     :status (:status result)
                     :stage (:stage result)
                     :labels labels
                     :parameters (->> (:parameters result)
                                      (sort-by (juxt :name :value))
                                      vec)
                     :attachments (->> (:attachments result)
                                       (map #(normalize-attachment results-root %))
                                       (sort-by (juxt :name :mediaType :digest))
                                       vec)}
                     (get-in result [:statusDetails :message])
                     (assoc :statusDetails
                            {:message (get-in result [:statusDetails :message])}))]
    normalized))

(defn record! [results-root result-file golden-file]
  (let [golden (normalize-result results-root result-file)
        destination (io/file golden-file)]
    (.mkdirs (.getParentFile destination))
    (spit destination (str (json/write-str golden) "\n"))
    golden))

(defn verify [results-root result-file golden-file]
  (let [expected (json/read-str (slurp golden-file) :key-fn keyword)
        actual (normalize-result results-root result-file)]
    {:passed (= expected actual)
     :expected expected
     :actual actual}))

(defn write-allure! [results-root golden]
  (when (seq (:attachments golden))
    (throw (ex-info "normalized calibration results with attachments require source content"
                    {:golden golden})))
  (let [directory (io/file results-root)
        _ (.mkdirs directory)
        uuid (str (java.util.UUID/randomUUID))
        now (System/currentTimeMillis)
        result-file (io/file directory (str uuid "-result.json"))
        result (cond->
                {:uuid uuid
                 :name (:name golden)
                 :fullName (:fullName golden)
                 :status (:status golden)
                 :stage (:stage golden)
                 :start now
                 :stop now
                 :labels (->> (:labels golden)
                              (map (fn [[label-name value]]
                                     {:name (name label-name) :value value}))
                              (sort-by (juxt :name :value))
                              vec)
                 :parameters (:parameters golden)
                 :attachments []}
                 (:statusDetails golden)
                 (assoc :statusDetails (:statusDetails golden)))]
    (spit result-file (json/write-str result))
    (.getAbsolutePath result-file)))
