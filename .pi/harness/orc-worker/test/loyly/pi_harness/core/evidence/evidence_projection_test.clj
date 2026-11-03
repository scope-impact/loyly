(ns loyly.pi-harness.core.evidence.evidence-projection-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.core.evidence.evidence-projection :as projection]))

(def persisted-evaluation
  {:evaluation-id "evaluation-cycle-02-item-01-007-runtime"
   :evidence-digest "9effd8b6eeadf2df14b0d2e400d283ed78195705ad9e5ef52d24b7b5e2a3bdbe"
   :role "observed"
   :builder "openai-codex/gpt-5.6-luna"
   :evidence-kind "grader"
   :producer "runtime-instrument-conformance"
   :container-id "loyly"
   :component-id "01-booking-core"
   :item-id "@item-01-007"
   :cycle-id "cycle-02"
   :cut-digest "cut-abc"
   :subject-digest "subject-def"
   :oracle-digest "oracle-123"
   :room "gondolin"
   :scope "impl"
   :result "fail"
   :reached? true
   :evidence "billing signal did not name apartment 12"})

(defn- labels-by-name [result]
  (into {} (map (juxt :name :value) (:labels result))))

(defn- attributes-by-key [span]
  (into {} (map (fn [{:keys [key value]}]
                  [key (or (:stringValue value) (:boolValue value))])
                (:attributes span))))

(deftest persisted-invariant-evaluation-projects-to-allure-and-otel
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-evidence-projection-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (let [before persisted-evaluation
            {:keys [allure otel]} (projection/project! root persisted-evaluation)
            allure-result (json/read-str (slurp allure) :key-fn keyword)
            otel-result (json/read-str (slurp otel) :key-fn keyword)
            span (get-in otel-result [:resourceSpans 0 :scopeSpans 0 :spans 0])
            labels (labels-by-name allure-result)
            attributes (attributes-by-key span)]
        (is (= before persisted-evaluation))
        (is (= "evaluation-cycle-02-item-01-007-runtime"
               (labels "evaluation_id")
               (attributes "loyly.evaluation.id")))
        (is (= (:evidence-digest persisted-evaluation)
               (labels "evidence_digest")
               (attributes "loyly.evidence.digest")))
        (is (= "failed" (:status allure-result)))
        (is (= "fail" (attributes "loyly.evaluation.verdict")))
        (is (= "loyly.invariant.evaluation" (:name span))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))
