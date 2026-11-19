(ns spec.allure
  "Add parsed Gherkin context to a derived Allure report without changing filed results."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str])
  (:import [io.cucumber.gherkin GherkinParser]
           [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]))

(defn fail [message] (throw (ex-info message {})))
(def repo-root (str/trim (:out (sh/sh "git" "rev-parse" "--show-toplevel"))))
(defn git [& args]
  (let [{:keys [exit out err]} (apply sh/sh "git" "-C" repo-root args)]
    (when-not (zero? exit) (fail (str "git " (str/join " " args) ": " err)))
    out))
(defn scenario-children [children feature-name rule-name path]
  (for [child children
        :when (.isPresent (.getScenario child))
        :let [s (.get (.getScenario child))
              tags (mapv #(.getName %) (.getTags s))
              item-tags (keep #(second (re-matches #"@item-([0-9]{2}-[0-9]{3})" %)) tags)]
        :when (seq item-tags)]
    (do
      (when-not (= 1 (count item-tags)) (fail (str "multiple item tags in " path ":" (.getName s))))
      {:id (first item-tags)
       :feature feature-name :rule rule-name :name (.getName s)
       :keyword (str/trim (.getKeyword s)) :tags tags :path path
       :line (.getLine (.getLocation s))
       :steps (mapv (fn [step] (str (str/trim (.getKeyword step)) " " (.getText step)))
                    (.getSteps s))})))
(defn parse-feature [cut path]
  (let [source (git "show" (str cut ":" path))
        parser (-> (GherkinParser/builder) (.includePickles false) (.includeSource false) .build)
        stream (ByteArrayInputStream. (.getBytes source StandardCharsets/UTF_8))]
    (with-open [in stream]
      (let [envelopes (doall (iterator-seq (.iterator (.parse parser path in))))
            errors (keep #(when (.isPresent (.getParseError %)) (.get (.getParseError %))) envelopes)
            _ (when (seq errors) (fail (str "Gherkin parse error in " path ": " (first errors))))
            documents (keep #(when (.isPresent (.getGherkinDocument %))
                               (.get (.getGherkinDocument %))) envelopes)]
        (vec (mapcat
              (fn [document]
                (when (.isPresent (.getFeature document))
                  (let [feature (.get (.getFeature document))
                        name (.getName feature)]
                    (mapcat (fn [child]
                              (if (.isPresent (.getRule child))
                                (let [rule (.get (.getRule child))]
                                  (scenario-children (.getChildren rule) name (.getName rule) path))
                                (scenario-children [child] name nil path)))
                            (.getChildren feature)))))
              documents))))))
(defn scenarios-at [cut]
  (let [paths (->> (str/split-lines (git "ls-tree" "-r" "--name-only" cut ".pi/spec"))
                   (filter #(str/ends-with? % "/spec.feature"))
                   sort)
        rows (vec (mapcat #(parse-feature cut %) paths))
        ids (map :id rows)]
    (when (empty? rows) (fail "no stamped Gherkin scenarios at spec cut"))
    (when-not (= (count ids) (count (set ids))) (fail "duplicate stamped Gherkin item"))
    (into {} (map (juxt :id identity) rows))))
(defn gherkin-text [{:keys [feature rule name keyword tags steps]}]
  (str "Feature: " feature "\n"
       (when rule (str "  Rule: " rule "\n"))
       "\n"
       (when (seq tags) (str "    " (str/join " " tags) "\n"))
       "    " keyword ": " name "\n"
       (str/join "\n" (map #(str "      " %) steps)) "\n"))
(defn read-json [file] (json/parse-string (slurp file) true))
(defn write-json [file value] (spit file (str (json/generate-string value {:pretty true}) "\n")))
(defn result-id [result]
  (let [ids (keep (fn [{:keys [name value]}]
                    (when (= name "tag") (second (re-matches #"item-([0-9]{2}-[0-9]{3})" value))))
                  (:labels result))]
    (when-not (= 1 (count ids)) (fail (str "Allure result must have one stamped item tag: " (:uuid result))))
    (first ids)))
(defn enrich [result scenario cut]
  (let [id (:id scenario)
        tag-labels (for [tag (:tags scenario) :when (not= tag (str "@item-" id))]
                     {:name "tag" :value tag})
        source-url (str "https://github.com/ragavsathish/loyly/blob/" cut "/"
                        (:path scenario) "#L" (:line scenario))
        source-name (str (:uuid result) "-gherkin.feature")]
    {:source-name source-name
     :source-text (gherkin-text scenario)
     :result (-> result
                 (assoc :name (:name scenario))
                 (update :labels into (concat [{:name "epic" :value "Löyly spec v2"}
                                               {:name "feature" :value (:feature scenario)}
                                               {:name "story" :value (or (:rule scenario) (:name scenario))}]
                                              tag-labels))
                 (update :links (fnil conj []) {:name "Gherkin source at approved cut"
                                                :url source-url :type "link"})
                 (update :attachments conj {:name "Gherkin scenario at approved cut"
                                            :source source-name :type "text/plain"}))}))
(defn -main [cycle cut]
  (when-not (and (re-matches #"[0-9]+" (or cycle ""))
                 (re-matches #"[0-9a-f]{40}" (or cut "")))
    (fail "usage: clojure -M -m spec.allure CYCLE FULL_SPEC_COMMIT"))
  (let [root repo-root
        source (io/file root "harness/v2/cycles/06" cycle "allure-results")
        scores (read-json (io/file root "harness/v2/cycles/06" cycle "scores.json"))
        output (io/file root ".pi/harness/.state/pages" (str "allure-cycle-" cycle "-results"))
        scenarios (scenarios-at cut)
        files (sort (filter #(str/ends-with? (.getName %) "-result.json")
                            (or (.listFiles source) [])))
        results (mapv (fn [file] [file (read-json file)]) files)
        ids (mapv (comp result-id second) results)
        score-ids (mapv :id (:items scores))]
    (when-not (= (set ids) (set score-ids)) (fail "Allure item tags differ from filed score"))
    (when-not (= (count ids) (count (set ids))) (fail "duplicate Allure item tag"))
    (when (some nil? (map scenarios ids)) (fail "Allure item missing from parsed Gherkin"))
    (doseq [[_ result] results
            :let [id (result-id result)
                  score (first (filter #(= id (:id %)) (:items scores)))
                  expected (if (= "pass" (:verdict score)) "passed" "failed")]]
      (when-not (= expected (:status result)) (fail (str "Allure status differs from filed score: " id))))
    (when (and (.exists output) (seq (.listFiles output)))
      (fail (str "derived Allure directory is not empty: " output)))
    (.mkdirs output)
    (doseq [file (.listFiles source)]
      (Files/copy (.toPath file) (.toPath (io/file output (.getName file)))
                  (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING])))
    (doseq [[file result] results
            :let [{:keys [source-name source-text result]}
                  (enrich result (scenarios (result-id result)) cut)]]
      (write-json (io/file output (.getName file)) result)
      (spit (io/file output source-name) source-text))
    (println "Allure Gherkin enrichment:" (count results) "filed items from"
             (count (set (map :path (map scenarios ids)))) "parsed feature files at" cut)
    (println (str output))
    (shutdown-agents)))
