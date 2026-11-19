(ns spec.coverage-v2
  "Coverage gate over Allium's parsed obligation plan and Gherkin tags."
  (:require [cheshire.core :as json] [clojure.string :as str] [clojure.java.shell :as sh]
            [spec.structural :as structural])
  (:import [java.io File]))
(def root (str/trim (:out (sh/sh "git" "rev-parse" "--show-toplevel"))))
(def spec (str root "/.pi/spec"))
(def kind {"rule_success" "rule-success" "rule_failure" "rule-failure" "invariant" "invariant"})
(def gates (into #{"allium" "grain" "coverage" "gwt" "glossary"} (keys structural/checks)))
(defn matches [pattern s] (re-seq pattern (or s "")))
(defn rule-body [src name] (second (re-find (re-pattern (str "(?s)(?:^|\\n)rule " (java.util.regex.Pattern/quote name) " \\{(.*?)\\n\\}")) src)))
(defn clause [src o] (when-let [span (:source_span o)] (str/trim (subs src (:start span) (:end span)))))
(defn trigger [body] (second (re-find #"when:\s*(?:\w+/)?(\w+)" (or body ""))))
(defn bindings [body]
  (let [body (or body "")
        lets (into {} (for [[_ k v] (matches #"\n\s*let (\w+) = ([^\n]+)" (str "\n" body))] [k (str/trim v)]))
        temporal (re-find #"when:\s*(\w+):\s*([\w.]+?)\.\w+" body)]
    (if temporal (assoc lets (second temporal) (nth temporal 2)) lets)))
(defn expand-exists [clause body]
  (str/replace (or clause "") #"\b(exists) (\w+)$"
               (fn [[_ exists name]] (str exists " " (get (bindings body) name name)))))
(defn bare [s] (str/replace (str/trim (or s "")) #"^\w+\." ""))
(defn guards [src o]
  (when-let [span (:source_span o)]
    (let [above (butlast (str/split (subs src 0 (:start span)) #"\n" -1))
          comments (reverse (take-while #(str/starts-with? (str/trim %) "--") (reverse above)))
          joined (str/join " " (map #(str/trim (subs (str/trim %) 2)) comments))
          segment (second (re-find #"guarded by: ([^(]+)" joined))]
      (when segment (vec (for [[_ ctx rule n] (matches #"(?:([a-z][\w-]*)/)?([A-Z]\w*)\.(\d+)" segment)] [(or ctx "") rule n]))))))
(defn classify [src rules o]
  (when-let [k (kind (:category o))]
    (if (or (not= k "rule-failure") (nil? (:source_span o))) [k nil]
      (let [c (clause src o) name (:source_construct o) body (rule-body src name)
            own (re-find #"^requires:\s*(\w+)\.(status|stage|path) = \w+$" c)
            entity (when own (-> (get (bindings body) (second own) "") bare (str/split #"[\{.]") first))
            ex (re-find #"^requires:\s*(not )?exists (.+)$" (expand-exists c body))
            pair (when ex
                   (let [neg (boolean (second ex)) target (bare (nth ex 2))]
                     (some (fn [[other {:keys [body]}]]
                             (when (and (not= other name) (= (trigger body) (trigger (rule-body src name))))
                               (some (fn [[_ oc]] (let [m (re-find #"^requires:\s*(not )?exists (.+)$" (expand-exists (str/trim oc) body))]
                                                    (and m (not= neg (boolean (second m))) (= target (bare (nth m 2))))))
                                     (matches #"\n\s*(requires:[^\n]+)" (str "\n" body))))) rules)))]
        [k (cond (seq (guards src o)) "guarded"
                 (and (seq entity) (re-find (re-pattern (str "(?:^|\\n)entity " (java.util.regex.Pattern/quote entity) " \\{")) src)) "own-state"
                 pair "branch-pair"
                 (when-let [[_ name] (re-find #"^requires:\s*not exists (\w+)\{" c)]
                   (and body (str/includes? body (str name ".created(")))) "redelivery")]))))
(defn declared [src]
  (for [[_ marker name prose] (matches #"(?m)@(guarantee|invariant) (\w+)\n((?:[ \t]+--[^\n]*\n?)+)" src)
        :let [clean (str/replace prose #"\s*--\s*" " ")
              gate (re-find #"Verified by: gate ([\w-]+)( \(planned\b)?" clean)]]
    {:id (str (if (= marker "guarantee") "guarantee." "boundary.") name)
     :kind (if (= marker "guarantee") "guarantee" "boundary")
     :gate (second gate) :planned (boolean (nth gate 2 nil))}))
(defn allium-files [ctx]
  (let [dir (File. (str spec "/" ctx "/allium"))]
    (if (.isDirectory dir) (sort (map #(.getName ^File %) (filter #(str/ends-with? (.getName ^File %) ".allium") (.listFiles dir)))) [])))
(defn plan [dir f]
  (let [r (sh/sh "allium" "plan" f :dir dir)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "allium plan " f " reports errors: " (:out r) (:err r)) {})))
    (:obligations (json/parse-string (:out r) true))))
(defn obligations [ctx]
  (let [dir (str spec "/" ctx "/allium") files (allium-files ctx)
        sources (into (sorted-map) (for [f files] [f (slurp (str dir "/" f))]))
        rules (into {} (for [[_ src] sources [_ name body] (matches #"(?s)(?:^|\n)rule (\w+) \{(.*?)\n\}" src)]
                         [name {:src src :body body}]))]
    (loop [remaining files seen #{} out [] planned #{}]
      (if-let [f (first remaining)]
        (let [[seen out planned]
              (reduce (fn [[seen out planned] o]
                        (let [id (:id o) planned (conj planned id)]
                          (if (contains? seen id) [seen out planned]
                            (let [home (or (some (fn [[file src]]
                                                   (when (re-find (re-pattern (str "(?:^|\\n)(?:rule|invariant) " (java.util.regex.Pattern/quote (str (:source_construct o))) " \\{")) src) file)) sources) f)
                                  src (sources home) [k unit] (classify src rules o)]
                              [(conj seen id) (cond-> out k (conj {:id id :kind k :file home :unit unit :gate nil
                                                                  :guards (when (= unit "guarded") (guards src o))})) planned]))))
                      [seen out planned] (plan dir f))
              [seen out] (reduce (fn [[seen out] g]
                                   (if (contains? seen (:id g)) [seen out]
                                     [(conj seen (:id g)) (conj out (merge {:file f :unit nil} g))]))
                                 [seen out] (declared (sources f)))]
          (recur (rest remaining) seen out planned))
        [out planned]))))
(def cache (atom {}))
(defn cached [ctx] (or (@cache ctx) (let [x (obligations ctx)] (swap! cache assoc ctx x) x)))
(defn tags [ctx kind]
  (let [f (File. (str spec "/" ctx "/" kind ".feature"))]
    (if (.isFile f) (set (map second (matches #"@allium-([\w.-]+)" (slurp f)))) #{})))
(defn contexts [args]
  (let [report (second (drop-while #(not= % "--report") args))
        named (remove #(or (str/starts-with? % "--") (= % report)) args)]
    (if (seq named) named (sort (for [f (.listFiles (File. spec)) :when (.isDirectory (File. f "allium"))] (.getName ^File f))))))
(defn guard-errors [ctx obs]
  (for [o obs [target-ctx rule n] (:guards o)
        :let [target-ctx (if (seq target-ctx) target-ctx ctx) id (str "rule-failure." rule "." n)
              exists? (.isDirectory (File. (str spec "/" target-ctx "/allium")))
              target (when exists? (some #(when (= id (:id %)) %) (first (cached target-ctx))))]
        :when (or (not exists?) (nil? target) (:unit target) (:gate target))]
    (str "  BAD GUARD " (:id o) " (" ctx "): "
         (cond (not exists?) (str "no context " target-ctx)
               (nil? target) (str target-ctx "/" id " is not a requires")
               :else (str target-ctx "/" id " is not must-cover")))))
(defn run [args]
  (let [listing (some #{"--list"} args)
        report (second (drop-while #(not= % "--report") args))
        cs (contexts args)
        total (atom {:must 0 :covered 0 :drafted 0 :uncovered 0 :unit 0 :gate 0})
        tails (atom []) rep (atom {}) bad (atom false)]
    (doseq [ctx cs]
      (let [[raw planned] (cached ctx) spec-tags (tags ctx "spec") draft-tags (tags ctx "drafts")
            obs (mapv (fn [o] (assoc o :status (cond (:unit o) "unit" (:gate o) "gate"
                                                    (spec-tags (:id o)) "covered" (draft-tags (:id o)) "drafted"
                                                    :else "uncovered"))) raw)
            must (remove #(or (:unit %) (:gate %)) obs)
            counts (frequencies (map :status must)) unit (filter :unit obs) gated (filter :gate obs)
            known (into planned (map :id obs))
            unknown (sort (remove #(or (known %) (str/starts-with? % "context.")) (into spec-tags draft-tags)))
            guards (vec (guard-errors ctx obs))
            gate-errors (for [o gated :when (and (not (:planned o)) (not (gates (:gate o))))]
                          (str "  UNKNOWN GATE " (:id o) " -> gate " (:gate o) " (" ctx ")"))
            n {:must (count must) :covered (get counts "covered" 0) :drafted (get counts "drafted" 0)
               :uncovered (get counts "uncovered" 0) :unit (count unit) :gate (count gated)}]
        (println (format "%-20s must-cover %3d | covered %3d | drafted %3d | uncovered %3d | unit-level %3d | gate-verified %2d"
                         ctx (:must n) (:covered n) (:drafted n) (:uncovered n) (:unit n) (:gate n)))
        (swap! total #(merge-with + % n))
        (swap! tails into (concat (for [o must :when (= "uncovered" (:status o))]
                                    (str "  UNCOVERED " (:id o) " " ctx "/allium/" (:file o)))
                                  (for [tag unknown] (str "  UNKNOWN TAG " tag " (" ctx ")"))
                                  guards gate-errors
                                  (when listing (for [o unit] (str "  unit-level " (:id o) " (" (:unit o) ") " ctx)))
                                  (when listing (for [o gated] (str "  structural " (:id o) " -> gate " (:gate o)
                                                                (when (:planned o) " (planned)") " " ctx)))))
        (reset! bad (or @bad (pos? (:uncovered n)) (seq unknown) (seq guards) (seq gate-errors)))
        (swap! rep assoc ctx {:obligations obs :unknownTags unknown})))
    (let [n @total]
      (println (format "%-20s must-cover %3d | covered %3d | drafted %3d | uncovered %3d | unit-level %3d | gate-verified %2d"
                       "total" (:must n) (:covered n) (:drafted n) (:uncovered n) (:unit n) (:gate n))))
    (doseq [line @tails] (println line))
    (when report (spit report (json/generate-string {:summary @total :contexts @rep} {:pretty true})))
    (if @bad 1 0)))
(defn -main [& args] (System/exit (run args)))
