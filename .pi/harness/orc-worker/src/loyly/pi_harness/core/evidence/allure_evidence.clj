(ns loyly.pi-harness.core.evidence.allure-evidence
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def required-fields
  [:role :builder :evidence-kind :producer :container-id :component-id :item-id
   :cycle-id :cut-digest :subject-digest :oracle-digest :room :scope
   :result :reached? :evidence])

(defn- sha256 [value]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest (.getBytes (str value) "UTF-8"))))))

(defn- validate! [observation]
  (let [missing (filter #(or (nil? (get observation %))
                             (and (string? (get observation %))
                                  (str/blank? (get observation %))))
                        required-fields)]
    (when (seq missing)
      (throw (ex-info "Allure observation is missing required metadata"
                      {:missing (vec missing)})))
    (when-not (#{"pass" "fail" "broken"} (:result observation))
      (throw (ex-info "Allure observation result must be pass, fail, or broken"
                      {:result (:result observation)})))
    (when-not (boolean? (:reached? observation))
      (throw (ex-info "Allure observation reached? must be boolean"
                      {:reached? (:reached? observation)})))
    observation))

(defn- label [name value]
  {:name name :value (str value)})

(defn- artifact-attachments! [directory uuid observation evidence-root manifest]
  (when (and evidence-root manifest)
    (->> (:artifacts manifest)
         (filter #(or (nil? (:item-id %)) (= (:item-id observation) (:item-id %))))
         (map-indexed
          (fn [index artifact]
            (let [source-file (io/file evidence-root (:path artifact))
                  source (str uuid "-artifact-" index "-" (.getName source-file))
                  target (io/file directory source)]
              (try
                (java.nio.file.Files/createLink (.toPath target) (.toPath source-file))
                (catch Exception _
                  (io/copy source-file target)))
              {:name (:path artifact) :source source :type (:media-type artifact)})))
         vec)))

(defn- write-observation* [results-root observation evidence-root manifest]
  (validate! observation)
  (let [directory (io/file results-root)
        _ (.mkdirs directory)
        uuid (str (java.util.UUID/randomUUID))
        now (System/currentTimeMillis)
        item-key (str/join ":" (map observation [:container-id :component-id :item-id]))
        history-key (str/join ":" [item-key (:evidence-kind observation) (:producer observation)])
        attachment-source (str uuid "-evidence.json")
        attachment-file (io/file directory attachment-source)
        manifest-source (str uuid "-manifest.json")
        manifest-attachment (when manifest
                              (spit (io/file directory manifest-source) (json/write-str manifest))
                              {:name "evidence manifest" :source manifest-source :type "application/json"})
        artifact-attachments (artifact-attachments! directory uuid observation evidence-root manifest)
        result-file (io/file directory (str uuid "-result.json"))
        labels (cond-> [(label "role" (:role observation))
                        (label "builder" (:builder observation))
                        (label "evidence_kind" (:evidence-kind observation))
                        (label "producer" (:producer observation))
                        (label "container_id" (:container-id observation))
                        (label "component_id" (:component-id observation))
                        (label "item_id" (:item-id observation))
                        (label "cycle_id" (:cycle-id observation))
                        (label "cut_digest" (:cut-digest observation))
                        (label "subject_digest" (:subject-digest observation))
                        (label "oracle_digest" (:oracle-digest observation))
                        (label "room" (:room observation))]
                 (:evaluation-id observation)
                 (conj (label "evaluation_id" (:evaluation-id observation)))
                 (:evidence-digest observation)
                 (conj (label "evidence_digest" (:evidence-digest observation)))
                 (:profile-kind observation)
                 (conj (label "profile_kind" (:profile-kind observation)))
                 (:grader-id observation) (conj (label "grader_id" (:grader-id observation))))
        status ({"pass" "passed" "fail" "failed" "broken" "broken"}
                (:result observation))
        allure (cond->
                 {:uuid uuid
                  :historyId (sha256 history-key)
                  :testCaseId (sha256 item-key)
                  :name (:item-id observation)
                  :fullName (str/join "." [(:container-id observation)
                                            (:component-id observation)
                                            (:item-id observation)])
                  :status status
                  :stage "finished"
                  :start now
                  :stop now
                  :labels labels
                  :parameters [{:name "reached" :value (str (:reached? observation))}
                               {:name "scope" :value (:scope observation)}]
                  :attachments (into [{:name "evidence"
                                       :source attachment-source
                                       :type "application/json"}]
                                     (concat (when manifest-attachment [manifest-attachment])
                                             artifact-attachments))}
                 (not= "pass" (:result observation))
                 (assoc :statusDetails {:message (:evidence observation)}))]
    (spit attachment-file (json/write-str observation))
    (spit result-file (json/write-str allure))
    (.getAbsolutePath result-file)))


(defn write-observation!
  ([results-root observation]
   (write-observation* results-root observation nil nil))
  ([results-root observation evidence-root manifest]
   (write-observation* results-root observation evidence-root manifest)))
