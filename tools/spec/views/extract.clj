;; Extract the event-model page's data from the Pi harness specs, as one JSON projection.
;;   bb extract.clj <repo-root> <model.edn> <coverage.json> <out.json>   (called by build.sh)
;; Sources, all read-only:
;;   .pi/model/*.allium                      behaviour (entities, rules, invariants, surfaces)
;;   <model.edn>                              the Grain Event Model, as grain-validate.clj --dump
;;                                            evaluated it from .pi/model/event-model/**/*.clj
;;   <coverage.json>                          tools/spec/coverage.py --report: the gate's own
;;                                            classification of every obligation
;;   .pi/model/plan/NN-slug/{spec,drafts}.feature   examples, tags, tiers
;;   .pi/architecture.md                      the C4 views
;; The page holds no facts of its own: everything it shows comes from here.
(require '[clojure.edn :as edn] '[clojure.string :as str] '[cheshire.core :as json] '[clojure.java.io :as io]
         '[clojure.java.shell :as sh])
(def root (nth *command-line-args* 0))
(def model-dir (str root "/.pi/model"))
(def plan-dir (str model-dir "/plan"))
(defn rel [p] (str/replace (str p) (str root "/") ""))
(defn nm [k] (if (keyword? k) (if (namespace k) (str (namespace k) "/" (name k)) (name k)) (str k)))
(defn files [dir ext] (->> (.listFiles (io/file dir)) (map #(.getName %)) (filter #(str/ends-with? % ext)) sort))

;; ---------- Allium: regex over the source (top-level blocks start at column 0) ----------
(defn blocks [src kw]
  (for [[whole n] (re-seq (re-pattern (str "(?ms)^" kw " (\\w+) \\{.*?\\n\\}")) src)] {:name n :src whole}))
(defn header [src]
  (->> (str/split-lines src) (drop-while #(re-find #"^-- (allium:|[\w-]+\.allium\s*$)" %)) (take-while #(str/starts-with? % "--"))
       (map #(str/trim (str/replace % #"^--" ""))) (remove str/blank?) (str/join " ")))
(defn clauses [body kw]
  (map (comp str/trim second) (re-seq (re-pattern (str "(?m)^\\s+" kw ":\\s*(.*)$")) body)))
(defn emits [body]
  (let [ens (second (re-find #"(?s)ensures:(.*)" body))]
    (->> (re-seq #"(?:(\w+)[./])?([A-Z]\w+)\(" (or ens ""))
         (remove (fn [[_ q _]] (and q (re-find #"^[A-Z]" q))))   ; Entity.created(
         (map (fn [[_ q n]] (if q (str q "." n) n))) distinct vec)))
(defn prose [p] (str/join " " (map #(str/trim (str/replace % #"^\s*--" "")) (str/split-lines p))))
(defn parse-allium [f]
  (let [src (slurp (str model-dir "/" f))]
    {:id (str/replace f ".allium" "") :file (str ".pi/model/" f) :header (header src)
     :uses (into {} (for [[_ m a] (re-seq #"(?m)^use \"(?:\./)?([\w-]+)\.allium\" as (\w+)" src)] [a m]))
     :entities (vec (concat (for [b (blocks src "entity")] (assoc b :external false))
                            (for [b (blocks src "external entity")] (assoc b :external true))))
     :actors (vec (map :name (blocks src "actor")))
     :enums (vec (for [[_ n vs] (re-seq #"(?m)^enum (\w+) \{([^}]*)\}" src)] {:name n :values (mapv str/trim (str/split vs #"\|"))}))
     :rules (vec (for [{n :name s :src} (blocks src "rule")]
                   {:name n :src s :when (first (clauses s "when")) :requires (vec (clauses s "requires")) :emits (emits s)}))
     :invariants (vec (blocks src "invariant"))
     :contracts (vec (for [{n :name s :src} (blocks src "contract")]
                       {:name n :src s
                        :invariants (vec (for [[_ i p] (re-seq #"@invariant (\w+)\n((?:\s+--[^\n]*\n?)+)" s)] {:name i :text (prose p)}))}))
     :surfaces (vec (for [{n :name s :src} (blocks src "surface")]
                      {:name n :src s
                       :facing (second (re-find #"facing (\w+):" s))
                       :facingType (some-> (re-find #"facing \w+: (?:\w+[./])?(\w+)" s) second)
                       :provides (vec (map second (re-seq #"(?m)^\s{8}(\w+)\(" (or (second (re-find #"(?s)provides:(.*?)(?:\n\s{4}\w+:|\n\s{4}@|\n\})" s)) ""))))
                       :guarantees (vec (for [[_ g p] (re-seq #"@guarantee (\w+)\n((?:\s+--[^\n]*\n?)+)" s)] {:name g :text (prose p)}))}))
     :openQuestions (vec (map second (re-seq #"(?m)^open question \"([^\"]+)\"" src)))}))

;; ---------- the Grain Event Model, as evaluated ----------
;; a schema may hold a function ([:fn inst?]); it prints as #object[clojure.core$inst_QMARK_ ..]
(defn obj [_ v] (let [c (str (first v))] (symbol (-> (subs c (inc (.lastIndexOf c "$"))) (str/replace "_QMARK_" "?") (str/replace "_BANG_" "!") (str/replace "_" "-")))))
(def dump (edn/read-string {:default obj} (slurp (nth *command-line-args* 1))))
(def kinds {:commands "commands" :events "events" :read-models "readModels" :queries "queries"
            :todo-processors "processors" :periodic-tasks "periodic" :screens "screens"})
(defn kv [m] (into {} (for [[k v] m] [(nm k) (cond (keyword? v) (nm v) (set? v) (vec (sort (map nm v))) (map? v) (kv v)
                                                    (vector? v) (mapv #(if (map? %) (kv %) (if (keyword? %) (nm %) (str %))) v) :else v)])))
(defn block-json [v]
  (-> (select-keys v [:description :reads :produces :consumes :subscribes :queries :commands :schedule])
      kv (assoc "schema" (some-> (:schema v) pr-str) "allium" (mapv #(-> (select-keys % [:kind :name :spec]) kv) (:grain/allium v)))))
(def areas
  (into (sorted-map)
        (for [[area am] (:model dump)]
          [(nm area)
           (merge {:file (get-in dump [:files area]) :description (:description am)}
                  (into {} (for [[k label] kinds] [label (into (sorted-map) (for [[bk bv] (get am k)] [(nm bk) (block-json bv)]))]))
                  {:flows (into (sorted-map) (for [[fk f] (:flows am)]
                                               [(nm fk) {:description (:description f)
                                                         :steps (mapv (fn [{:keys [from to]}] [(some-> from first nm) (some-> from second nm) (some-> to first nm) (some-> to second nm)]) (:steps f))}]))})])))

;; ---------- Gherkin ----------
(def tiers #{"@minimum" "@balance" "@deferred"})
(defn parse-feature [path slice kind]
  (let [lines (str/split-lines (slurp path))]
    (loop [ls (map-indexed vector lines) rule nil tags [] cur nil out []]
      (if (empty? ls) (cond-> out cur (conj cur))
        (let [[i raw] (first ls) l (str/trim raw) more (rest ls)]
          (cond
            (str/starts-with? l "#") (recur more rule tags cur out)
            (str/starts-with? l "@") (recur more rule (into tags (str/split l #"\s+")) nil (cond-> out cur (conj cur)))
            (str/starts-with? l "Rule:") (recur more (str/trim (subs l 5)) [] nil (cond-> out cur (conj cur)))
            (re-find #"^Scenario( Outline)?:" l)
            (recur more rule [] {:slice slice :kind kind :rule rule :line (inc i)
                                 :title (str/trim (str/replace l #"^Scenario( Outline)?:" ""))
                                 :item (some #(when (str/starts-with? % "@item-") (subs % 6)) tags)
                                 :tier (or (some #(when (tiers %) (subs % 1)) tags) (when (some #{"@draft"} tags) "draft") "untiered")
                                 :tags tags :steps [] :file (rel path)} (cond-> out cur (conj cur)))
            (and cur (or (re-find #"^(Given|When|Then|And|But|\*) " l) (str/starts-with? l "|") (str/starts-with? l "Examples:")))
            (recur more rule tags (update cur :steps conj l) out)
            :else (recur more rule tags cur out)))))))

(def module-ids (set (map #(str/replace % ".allium" "") (files model-dir ".allium"))))
(def slices
  (vec (for [d (sort (.listFiles (io/file plan-dir))) :when (.isDirectory d)
             :let [id (.getName d) slug (str/replace id #"^\d+-" "")
                   spec (io/file d "spec.feature") drafts (io/file d "drafts.feature")
                   src (if (.exists spec) (slurp spec) "")
                   feat (second (re-find #"(?m)^Feature:\s*(.*)$" src))
                   desc (some->> (re-find #"(?ms)^Feature:[^\n]*\n(.*?)(?:\n\s*\n|\n\s*(?:Rule:|@|Scenario))" src) second
                                 str/split-lines (map str/trim) (str/join " "))]]
         {:id id :n (re-find #"^\d+" id) :slug slug :module (when (module-ids slug) slug)
          :feature feat :description desc
          :specFile (when (.exists spec) (rel spec)) :draftsFile (when (.exists drafts) (rel drafts))})))
(def scenarios
  (vec (for [s slices k ["spec" "drafts"] :let [f (io/file plan-dir (:id s) (str k ".feature"))] :when (.exists f)
             sc (parse-feature (str f) (:id s) k)]
         (assoc sc :module (:module s)))))

;; ---------- .pi/architecture.md: the C4 views ----------
(def arch (let [f (io/file root ".pi/architecture.md")] (if (.exists f) (slurp f) "")))
(def c4 (mapv (fn [[_ t s]] {:title t :source (str/trim s)})
              (re-seq #"(?s)### (Level \d: [^\n]+)\n.*?```mermaid\n(.*?)```" arch)))

(def modules (into (sorted-map) (for [f (files model-dir ".allium")] [(str/replace f ".allium" "") (parse-allium f)])))
(def coverage (json/parse-string (slurp (nth *command-line-args* 2)) true))
(def commit (str/trim (:out (sh/sh "git" "-C" root "log" "-1" "--format=%h" "--" ".pi/model" ".pi/architecture.md"))))
(def dirty (not (str/blank? (:out (sh/sh "git" "-C" root "status" "--porcelain" "--" ".pi/model" ".pi/architecture.md")))))

(spit (nth *command-line-args* 3)
      (json/generate-string {:title "Pi Harness" :source ".pi/model" :commit commit :dirty dirty
                             :modules modules :areas areas :slices slices :scenarios scenarios
                             :coverage coverage :c4 c4}))
(println "modules" (count modules)
         "rules" (reduce + (map (comp count :rules) (vals modules)))
         "areas" (count areas)
         "blocks" (reduce + (for [a (vals areas) k (vals kinds)] (count (get a k))))
         "flows" (reduce + (map (comp count :flows) (vals areas)))
         "slices" (count slices)
         "scenarios" (count (filter #(= "spec" (:kind %)) scenarios))
         "drafts" (count (filter #(= "drafts" (:kind %)) scenarios))
         "c4" (count c4))
