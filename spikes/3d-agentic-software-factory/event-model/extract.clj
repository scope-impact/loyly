(require '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '[cheshire.core :as json])

(def root (first *command-line-args*))
(def out (second *command-line-args*))
(def model-root-rel ".pi/model")
(def model-dir (str root "/" model-root-rel))

(defn sha256 [value]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn name-of [x]
  (cond
    (keyword? x) (if (namespace x) (str (namespace x) "/" (name x)) (name x))
    (symbol? x) (str x)
    :else (str x)))

(defn json-value [x]
  (cond
    (map? x) (into (sorted-map) (map (fn [[k v]] [(name-of k) (json-value v)]) x))
    (set? x) (mapv json-value (sort-by name-of x))
    (sequential? x) (mapv json-value x)
    (or (keyword? x) (symbol? x)) (name-of x)
    :else x))

(defn files-with [dir suffix]
  (->> (.listFiles (io/file dir))
       (filter #(.isFile %))
       (map #(.getName %))
       (filter #(str/ends-with? % suffix))
       sort))

(defn relative-files-with [dir suffix]
  (let [root-path (.toPath (io/file dir))]
    (->> (file-seq (io/file dir))
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) suffix))
         (map #(-> (.relativize root-path (.toPath %))
                   str
                   (str/replace "\\" "/")))
         sort)))

(def feature-files (relative-files-with model-dir ".feature"))

(defn feature-module-id [filename]
  (or (second (re-find #"(?:^|/)plan/\d+-(.+)/spec\.feature$" filename))
      (str/replace (.getName (io/file filename)) ".feature" "")))

(defn top-blocks [source kind]
  (for [[whole block-name]
        (re-seq (re-pattern (str "(?ms)^" kind " (\\w+) \\{.*?\\n\\}")) source)]
    {:name block-name :source whole}))

(defn clause-lines [body label]
  (mapv (comp str/trim second)
        (re-seq (re-pattern (str "(?m)^\\s+" label ":\\s*(.*)$")) body)))

(defn provides [body]
  (let [section (second (re-find #"(?s)provides:(.*?)(?:\n\s{4}@|\n\})" body))]
    (mapv second (re-seq #"(?m)^\s{8}(\w+)\(" (or section "")))))

(defn parse-allium [filename]
  (let [source (slurp (str model-dir "/" filename))
        id (str/replace filename ".allium" "")
        feature (some #(when (= id (feature-module-id %)) %) feature-files)]
    {:id id
     :file (str model-root-rel "/" filename)
     :digest (sha256 source)
     :featureFile (when feature (str model-root-rel "/" feature))
     :description (->> (str/split-lines source)
                       (filter #(str/starts-with? % "-- "))
                       (remove #(str/starts-with? % "-- allium:"))
                       (map #(subs % 3))
                       (take 3)
                       (str/join " "))
     :entities (mapv #(select-keys % [:name :source :external])
                     (concat (map #(assoc % :external false) (top-blocks source "entity"))
                             (map #(assoc % :external true) (top-blocks source "external entity"))))
     :rules (mapv (fn [{:keys [name source]}]
                    {:name name
                     :source source
                     :digest (sha256 source)
                     :when (first (clause-lines source "when"))
                     :requires (clause-lines source "requires")
                     :requirementDigests (mapv sha256 (clause-lines source "requires"))
                     :ensures (clause-lines source "ensures")})
                  (top-blocks source "rule"))
     :invariants (mapv #(select-keys % [:name :source]) (top-blocks source "invariant"))
     :surfaces (mapv (fn [{:keys [name source]}]
                       {:name name
                        :source source
                        :facing (second (re-find #"facing (\w+):" source))
                        :provides (provides source)})
                     (top-blocks source "surface"))
     :openQuestions (mapv second (re-seq #"(?m)^open question \"([^\"]+)\"" source))}))

(defn read-forms [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (loop [forms []]
      (let [form (read r false ::eof)]
        (if (= form ::eof) forms (recur (conj forms form)))))))

(def event-model-form
  (some #(when (and (seq? %) (= 'defeventmodel (first %))) %) 
        (read-forms (str model-dir "/regeneration_event_model.clj"))))
(def event-model (json-value (nth event-model-form 2)))

(defn parse-feature [filename]
  (let [path (str model-dir "/" filename)
        lines (str/split-lines (slurp path))]
    (loop [remaining lines current-rule nil tags [] current nil scenarios []]
      (if (empty? remaining)
        (cond-> scenarios current (conj current))
        (let [line (str/trim (first remaining)) more (rest remaining)]
          (cond
            (str/starts-with? line "@")
            (recur more current-rule (into tags (str/split line #"\s+")) nil
                   (cond-> scenarios current (conj current)))

            (str/starts-with? line "Rule:")
            (recur more (str/trim (subs line 5)) [] nil
                   (cond-> scenarios current (conj current)))

            (re-find #"^Scenario( Outline)?:" line)
            (recur more current-rule []
                   {:file (str model-root-rel "/" filename)
                    :module (feature-module-id filename)
                    :rule current-rule
                    :title (str/trim (str/replace line #"^Scenario( Outline)?:" ""))
                    :item (some #(when (str/starts-with? % "@item-") (subs % 6)) tags)
                    :status (cond (some #{"@e2e"} tags) "e2e"
                                  (some #{"@skip"} tags) "skip"
                                  :else "unclassified")
                    :tags tags :steps []}
                   (cond-> scenarios current (conj current)))

            (and current (re-find #"^(Given|When|Then|And|But) " line))
            (recur more current-rule tags (update current :steps conj line) scenarios)

            :else (recur more current-rule tags current scenarios)))))))

(def architecture-source (slurp (str root "/.pi/architecture.md")))
(def c4-views
  (mapv (fn [[_ title source]] {:title title :source (str/trim source) :digest (sha256 (str/trim source))})
        (re-seq #"(?s)## (System Context|Container view|ORC worker component view).*?```mermaid\n(.*?)```"
                architecture-source)))

(def legacy-conformance
  (mapv (fn [[_ item status note]]
          {:item item :status status :note (str/trim note)})
        (re-seq #"(?m)^\| (01-\d+) \| ([^|]+) \| ([^|]+) \|$" architecture-source)))
(def slice-coverage
  (mapv (fn [[_ slice items grader reference]]
          {:item (str/trim slice)
           :status (str items " items · grader " (str/trim grader))
           :note (str/trim reference)})
        (re-seq #"(?m)^\| (\d{2} [^|]+?) \| (\d+) \| ([^|]+) \| ([^|]+) \|$"
                architecture-source)))
(def conformance (if (seq legacy-conformance) legacy-conformance slice-coverage))

(def allium-modules
  (mapv parse-allium
        (remove #{"booking-core.allium"}
                (files-with model-dir ".allium"))))
(def scenarios
  (mapv (fn [scenario]
          (assoc scenario :digest
                 (sha256 (pr-str (select-keys scenario [:module :rule :title :item :tags :steps])))))
        (mapcat parse-feature feature-files)))
(def modeled-module-ids (set (map :id allium-modules)))
(def scenario-only-modules
  (mapv (fn [module-id]
          (let [feature (some #(when (= module-id (feature-module-id %)) %) feature-files)
                source (slurp (str model-dir "/" feature))]
            {:id module-id
             :file (str model-root-rel "/" feature)
             :featureFile (str model-root-rel "/" feature)
             :digest (sha256 source)
             :description "Acceptance slice without a matching Allium behavior module; retained visibly as a modeling gap."
             :entities [] :rules [] :invariants [] :surfaces [] :openQuestions []
             :projectionGap true}))
        (sort (remove modeled-module-ids (distinct (map :module scenarios))))))
(def module-order
  (into {} (map-indexed (fn [index feature] [(feature-module-id feature) index]) feature-files)))
(def modules
  (->> (concat allium-modules scenario-only-modules)
       (sort-by #(get module-order (:id %) Integer/MAX_VALUE))
       vec))

(def source-commit
  (str/trim (:out (shell/sh "git" "-C" root "log" "-1" "--format=%H" "--"
                            model-root-rel ".pi/architecture.md"))))
(def payload
  {:title "Pi Harness Event Model"
   :source model-root-rel
   :sourceCommit source-commit
   :modules modules
   :eventModel event-model
   :scenarios scenarios
   :c4 c4-views
   :conformance conformance})
(spit out (json/generate-string (assoc payload :modelDigest (sha256 (json/generate-string payload)))))

(println "modules" (count modules)
         "rules" (reduce + (map #(count (:rules %)) modules))
         "scenarios" (count scenarios)
         "grain-blocks" (reduce + (map #(count (get event-model % {}))
                                        ["commands" "events" "read-models" "queries" "todo-processors" "screens" "flows"]))
         "c4-views" (count c4-views))
