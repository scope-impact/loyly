;; Extract the spec pages' data, as one map: `world`. Loaded by render.clj (and by diff.clj for the
;; base ref), with `cfg` (project.edn) and `opts` {:root <project root> :coverage <coverage.json>}.
;; Sources, all read-only, per context <:spec-root>/<context>/ (names from :files):
;;   allium/*.allium                 behaviour (entities, rules, invariants, contracts, surfaces)
;;   event-model/*.edn               the Grain Event Model fragments, merged by load.clj
;;                                   (one home per block)
;;   event-model/handoffs.edn        the events the context hands to others
;;   spec.feature, drafts.feature    examples, tags, tiers
;;   <:plan-dir>/NN-<context>        optional: the slice number of an accepted context
;; <:architecture>, its C4 views (### Level N: … then a ```mermaid block), stamped with its own
;; commit; and <coverage.json>, the config's :coverage report, when there is one. The page holds
;; no facts of its own: everything it shows comes from here.
(require '[clojure.string :as str] '[cheshire.core :as json] '[clojure.java.io :as io]
         '[clojure.java.shell :as sh] '[clojure.edn :as edn])
(load-string (resource "load.clj"))   ; ns load: merge-model
(def root (:root opts))
(def SR (:spec-root cfg))   ; the spec root, relative to the project root
(def FL (:files cfg))
(def spec-dir (str root "/" SR))
(defn rel [p] (str/replace (str p) (str root "/") ""))
(defn nm [k] (if (keyword? k) (if (namespace k) (str (namespace k) "/" (name k)) (name k)) (str k)))
(defn files [dir ext] (->> (.listFiles (io/file dir)) (map #(.getName %)) (filter #(str/ends-with? % ext)) sort))

;; ---------- Allium: regex over the source (top-level blocks start at column 0) ----------
(defn blocks [src kw]
  (for [[whole n] (re-seq (re-pattern (str "(?ms)^" kw " (\\w+) \\{.*?\\n\\}")) src)] {:name n :src whole}))
(defn header [src]
  (->> (str/split-lines src) (drop-while #(re-find #"^-- (allium:|[\w./-]+\.allium\s*$)" %)) (take-while #(str/starts-with? % "--"))
       (map #(str/trim (str/replace % #"^--" ""))) (remove str/blank?) (str/join " ")))
(defn clauses [body kw]
  (map (comp str/trim second) (re-seq (re-pattern (str "(?m)^\\s+" kw ":\\s*(.*)$")) body)))
(defn emits [body]
  (let [ens (second (re-find #"(?s)ensures:(.*)" body))]
    (->> (re-seq #"(?:(\w+)[./])?([A-Z]\w+)\(" (or ens ""))
         (remove (fn [[_ q _]] (and q (re-find #"^[A-Z]" q))))   ; Entity.created(
         (map (fn [[_ q n]] (if q (str q "." n) n))) distinct vec)))
(defn doc-before   ; the -- comment lines right above a top-level block, as one paragraph
  [src whole]
  (when-let [i (str/index-of src whole)]
    (->> (str/split-lines (subs src 0 i)) reverse (take-while #(str/starts-with? % "--"))
         reverse (map #(str/trim (str/replace % #"^--" ""))) (remove #(re-find #"^-+$" %)) (str/join " ") str/trim not-empty)))
(defn prose [p] (str/join " " (map #(str/trim (str/replace % #"^\s*--" "")) (str/split-lines p))))
(defn parse-allium [ctx f]
  (let [src (slurp (str spec-dir "/" ctx "/" (:allium FL) "/" f))]
    {:id (str/replace f ".allium" "") :file (str SR "/" ctx "/" (:allium FL) "/" f) :header (header src)
     :uses (into {} (for [[_ m a] (re-seq #"(?m)^use \"(?:\./)?([\w-]+)\.allium\" as (\w+)" src)] [a m]))
     :entities (vec (concat (for [b (blocks src "entity")] (assoc b :external false))
                            (for [b (blocks src "external entity")] (assoc b :external true))))
     :actors (vec (map :name (blocks src "actor")))
     :enums (vec (for [[_ n vs] (re-seq #"(?m)^enum (\w+) \{([^}]*)\}" src)] {:name n :values (mapv str/trim (str/split vs #"\|"))}))
     :rules (vec (for [{n :name s :src} (blocks src "rule")]
                   {:name n :src s :doc (doc-before src s) :when (first (clauses s "when")) :requires (vec (clauses s "requires")) :emits (emits s)}))
     :invariants (vec (for [b (blocks src "invariant")] (assoc b :doc (doc-before src (:src b)))))
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

;; ---------- the Grain Event Model, from the EDN fragments ----------
(def kinds [:commands :events :read-models :queries :todo-processors :periodic-tasks :screens])
(defn names [v] (if (set? v) (vec (sort (map nm v))) (mapv nm v)))
(defn kv [m] (into {} (for [[k v] m] [(nm k) (cond (keyword? v) (nm v) (map? v) (kv v) (coll? v) (names v) :else v)])))
(defn block-data [v file]
  (merge {:description (:description v) :schema (:schema v) :schema-src (some-> (:schema v) pr-str) :file file
          :allium (mapv (fn [a] {:kind (nm (:kind a)) :name (nm (:name a)) :spec (:spec a)}) (:grain/allium v))
          :schedule (some-> (:schedule v) kv)}
         (into {} (for [k [:reads :produces :consumes :subscribes :queries :commands] :when (seq (get v k))] [k (names (get v k))]))))
(defn areas-of [ctx]
  (let [{:keys [model homes]} (load/merge-model (str spec-dir "/" ctx "/" (:event-model FL)))
        file (fn [area k bk] (str SR "/" ctx "/" (:event-model FL) "/" (get homes [area k bk] "area.edn")))]
    (into (sorted-map)
          (for [[area am] model]
            [(nm area)
             (merge {:file (str SR "/" ctx "/" (:event-model FL) "/area.edn") :description (:description am)}
                    (into {} (for [k kinds] [k (into (sorted-map) (for [[bk bv] (get am k)] [(nm bk) (block-data bv (file area k bk))]))]))
                    {:flows (into (sorted-map) (for [[fk f] (:flows am)]
                                                 [(nm fk) {:description (:description f)
                                                           :steps (mapv (fn [{:keys [from to]}] [(some-> from first nm) (some-> from second nm) (some-> to first nm) (some-> to second nm)]) (:steps f))}]))})]))))

;; ---------- Gherkin ----------
(def tiers (set (:tiers cfg)))
(def ITEM (:item-tag cfg))
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
                                 :item (some #(when (str/starts-with? % ITEM) (subs % (count ITEM))) tags)
                                 :tier (or (some #(when (tiers %) (subs % 1)) tags) (when (some #{"@draft"} tags) "draft") "untiered")
                                 :tags tags :steps [] :file (rel path)} (cond-> out cur (conj cur)))
            (and cur (or (re-find #"^(Given|When|Then|And|But|\*) " l) (str/starts-with? l "|") (str/starts-with? l "Examples:")))
            (recur more rule tags (update cur :steps conj l) out)
            :else (recur more rule tags cur out)))))))

;; ---------- coverage: the gate's report, each obligation with its rule and clause ----------
(def coverage (let [f (io/file (str (:coverage opts)))] (if (.exists f) (json/parse-string (slurp f) true) {})))
(defn obligations [ctx modules]
  (let [rules (into {} (for [m (vals modules) r (:rules m)] [(:name r) r]))]
    (vec (for [o (get-in coverage [:contexts (keyword ctx) :obligations])
               :let [[_ _ r n] (re-find #"^(rule-success|rule-failure)\.(\w+)(?:\.(\d+))?$" (:id o))
                     n (some-> n parse-long)]]
           (cond-> o
             r (assoc :rule r)
             n (assoc :n n :clause (get-in rules [r :requires (dec n)]))
             (string? (:unit o)) (update :unit str/replace "-" " "))))))

;; ---------- contexts, in their slice order ----------
(def slice-of   ; context -> "NN-context", from the plan's links (:plan-dir), when the project has one
  (into {} (when-let [d (:plan-dir cfg)] (for [l (.listFiles (io/file root d)) :let [c (.getName (.getCanonicalFile l))]] [c (.getName l)]))))
(def contexts
  (vec (for [d (sort-by #(or (slice-of (.getName %)) (when (vector? (:contexts cfg)) (let [i (.indexOf (:contexts cfg) (.getName %))] (when (>= i 0) (format "%02d" i)))) (str "99-" (.getName %))) (.listFiles (io/file spec-dir)))
             :when (.isDirectory (io/file d (:allium FL)))
             :when (or (= :discover (:contexts cfg :discover)) (some #{(.getName d)} (:contexts cfg)))
             :let [ctx (.getName d) sl (or (slice-of ctx) ctx)
                   spec (io/file d (:spec FL)) drafts (io/file d (:drafts FL))
                   src (if (.exists spec) (slurp spec) "")
                   modules (into (sorted-map) (for [f (files (io/file d (:allium FL)) ".allium")] [(str ctx "--" (str/replace f ".allium" "")) (parse-allium ctx f)]))
                   areas (areas-of ctx)
                   scen (vec (for [[k f] [["spec" spec] ["drafts" drafts]] :when (.exists f) sc (parse-feature (str f) sl k)] (assoc sc :ctx ctx)))
                   handoffs (let [f (io/file d (:event-model FL) (:handoffs FL))]
                              (when (.exists f) (vec (sort (map nm (mapcat (comp keys :events) (vals (edn/read-string (slurp f)))))))))]]
         {:id ctx :slice sl :n (re-find #"^\d+" sl) :accepted (boolean (slice-of ctx))
          :feature (second (re-find #"(?m)^Feature:\s*(.*)$" src))
          :description (or (some :description (vals areas)) "")
          :modules modules :areas areas :scenarios scen :handoffs (or handoffs [])
          :obligations (obligations ctx modules)
          :unknownTags (get-in coverage [:contexts (keyword ctx) :unknownTags])
          :specFile (when (.exists spec) (rel spec)) :draftsFile (when (.exists drafts) (rel drafts))
          :items (vec (sort (distinct (keep :item (filter #(= "spec" (:kind %)) scen)))))})))

;; ---------- the handoffs: producer -> event -> consumer, the assembly the relay drives ----------
(def handoffs
  (vec (for [p contexts e (:handoffs p)]
         {:producer (:id p) :event e
          :consumers (vec (for [c contexts :when (not= (:id c) (:id p))
                                :let [blocks (for [[_ a] (:areas c) k [:read-models :todo-processors :periodic-tasks :commands :queries :screens]
                                                   [bk b] (get a k) :when (some #{e} (concat (:consumes b) (:subscribes b) (:reads b)))] [bk k])]
                                :when (seq blocks)]
                            {:ctx (:id c) :blocks (vec blocks)}))})))

(def commit (str/trim (:out (sh/sh "git" "-C" root "log" "-1" "--format=%h" "--" SR (or (:plan-dir cfg) SR)))))
(def dirty (not (str/blank? (:out (sh/sh "git" "-C" root "status" "--porcelain" "--" SR (or (:plan-dir cfg) SR))))))

;; ---------- .pi/architecture.md: the C4 views, with their own source stamp ----------
(def ARCH (:architecture cfg))
(def arch (let [f (when ARCH (io/file root ARCH))] (if (and f (.exists f)) (slurp f) "")))
(def c4 (mapv (fn [[_ t s]] {:title t :source (str/trim s)})
              (re-seq #"(?s)### (Level \d: [^\n]+)\n.*?```mermaid\n(.*?)```" arch)))
(def arch-commit (str/trim (:out (sh/sh "git" "-C" root "log" "-1" "--format=%h" "--" (or ARCH ".")))))
(def arch-dirty (not (str/blank? (:out (sh/sh "git" "-C" root "status" "--porcelain" "--" (or ARCH "."))))))

(def world {:title (:title cfg) :source SR :commit commit :dirty dirty
            :contexts contexts :handoffs handoffs :coverage (:summary coverage)
            :items (vec (sort (mapcat :items contexts)))
            :c4 c4 :arch {:source ARCH :commit arch-commit :dirty arch-dirty}})
(println "contexts" (count contexts)
         "files" (reduce + (map (comp count :modules) contexts))
         "rules" (reduce + (for [c contexts m (vals (:modules c))] (count (:rules m))))
         "blocks" (reduce + (for [c contexts a (vals (:areas c)) k kinds] (count (get a k))))
         "flows" (reduce + (for [c contexts a (vals (:areas c))] (count (:flows a))))
         "items" (count (:items world))
         "drafts" (count (for [c contexts s (:scenarios c) :when (= "drafts" (:kind s))] s))
         "handoffs" (count handoffs) "edges" (reduce + (map (comp count :consumers) handoffs))
         "c4" (count c4))
