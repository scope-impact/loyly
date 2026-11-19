#!/usr/bin/env bb
;; Project the black-box grader's authoritative verdicts into Allure result files.
;; One stamped item is one result. The profile is an attachment, not another test.
(require '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])
(import '[java.nio.charset StandardCharsets]
        '[java.security MessageDigest]
        '[java.util UUID])

(defn refuse [message] (throw (ex-info message {})))
(defn digest [algorithm bytes]
  (let [md (MessageDigest/getInstance algorithm)]
    (.update md bytes)
    (apply str (map #(format "%02x" (bit-and 255 %)) (.digest md)))))
(defn sha-file [path]
  (digest "SHA-256" (java.nio.file.Files/readAllBytes (.toPath (io/file path)))))
(defn md5 [value] (digest "MD5" (.getBytes value StandardCharsets/UTF_8)))
(defn read-json [path] (json/parse-string (slurp path) true))
(defn write-json [path value] (spit path (str (json/generate-string value {:pretty true}) "\n")))
(defn options [args]
  (when (odd? (count args)) (refuse "options must be --key value pairs"))
  (let [opts (into {} (map (fn [[k v]]
                            (when-not (str/starts-with? k "--") (refuse (str "bad option " k)))
                            [(keyword (subs k 2)) v])
                          (partition 2 args)))]
    (doseq [key [:checklist :verdicts :profile :out]]
      (when-not (get opts key) (refuse (str "missing --" (name key)))))
    opts))
(defn inputs [{:keys [checklist verdicts profile]}]
  (let [ids (->> (str/split-lines (slurp checklist)) (remove str/blank?) vec)
        rows (read-json verdicts)
        p (read-json profile)]
    (when (empty? ids) (refuse "empty checklist"))
    (when-not (every? #(re-matches #"[0-9]{2}-[0-9]{3}" %) ids)
      (refuse "bad item id"))
    (when-not (= ids (mapv :id rows)) (refuse "verdicts differ from checklist order"))
    (when-not (every? #(and (#{"pass" "fail"} (:verdict %)) (string? (:evidence %))) rows)
      (refuse "verdict must have pass/fail and text evidence"))
    (when-not (= ids (mapv :id (:cases p))) (refuse "profile cases differ from checklist"))
    (when-not (= (sha-file verdicts) (:verdicts_sha256 p))
      (refuse "profile does not bind these verdict bytes"))
    {:ids ids :rows rows :profile p}))
(defn result-for [verdict case profile]
  (let [id (:id verdict)
        slice (subs id 0 2)
        full-name (str "loyly.slice-" slice ".item-" id)
        uuid (str (UUID/nameUUIDFromBytes
                   (.getBytes (str "loyly/allure/" (:events_sha256 profile) "/"
                                   (:verdicts_sha256 profile) "/" id) StandardCharsets/UTF_8)))
        status (if (= "pass" (:verdict verdict)) "passed" "failed")
        attachment (str uuid "-attachment.json")]
    {:uuid uuid
     :result-name (str uuid "-result.json")
     :attachment-name attachment
     :attachment case
     :result (cond-> {:uuid uuid
                      :historyId (md5 full-name)
                      :testCaseId (md5 full-name)
                      :fullName full-name
                      :name id
                      :description (:evidence verdict)
                      :status status
                      :stage "finished"
                      :labels [{:name "tag" :value (str "item-" id)}
                               {:name "tag" :value (str "slice-" slice)}
                               {:name "suite" :value "cycle-runner"}
                               {:name "framework" :value "loyly-black-box-grader"}
                               {:name "loyly.profileStatus" :value (:status case)}]
                      :attachments [{:name "Grading profile"
                                     :source attachment
                                     :type "application/json"}]}
               (= status "failed")
               (assoc :statusDetails {:message (:evidence verdict)}))}))
(defn expected [data]
  (mapv #(result-for %1 %2 (:profile data))
        (:rows data) (get-in data [:profile :cases])))
(defn files-in [dir]
  (set (map #(.getName %) (or (.listFiles (io/file dir)) []))))
(defn export! [opts]
  (let [dir (io/file (:out opts))
        results (expected (inputs opts))]
    (when (and (.exists dir) (seq (files-in dir)))
      (refuse (str "Allure output is not empty: " dir)))
    (.mkdirs dir)
    (doseq [{:keys [result-name attachment-name result attachment]} results]
      (write-json (io/file dir result-name) result)
      (write-json (io/file dir attachment-name) attachment))
    (println "allure exported:" (count results) "item results")))
(defn verify! [opts]
  (let [dir (io/file (:out opts))
        results (expected (inputs opts))
        expected-files (set (mapcat (juxt :result-name :attachment-name) results))]
    (when-not (= expected-files (files-in dir))
      (refuse "Allure result and attachment files do not match the checklist"))
    (doseq [{:keys [result-name attachment-name result attachment]} results]
      (when-not (= result (read-json (io/file dir result-name)))
        (refuse (str "Allure result mismatch: " result-name)))
      (when-not (= attachment (read-json (io/file dir attachment-name)))
        (refuse (str "Allure attachment mismatch: " attachment-name))))
    (println "allure verified:" (count results) "item results with tag labels and profile attachments")))
(try
  (let [[mode & args] *command-line-args*
        opts (options args)]
    (case mode
      "export" (export! opts)
      "verify" (verify! opts)
      (refuse "usage: bb harness/v2/allure.clj export|verify --checklist FILE --verdicts FILE --profile FILE --out DIR")))
  (catch Exception e
    (binding [*out* *err*] (println "allure:" (.getMessage e)))
    (System/exit 1)))
