(ns spec.coverage-v1
  "Legacy model coverage over Allium plan JSON and Gherkin scenarios."
  (:require [cheshire.core :as json] [clojure.string :as str]
            [spec.coverage-v2 :as v2] [spec.gwt :as gwt])
  (:import [java.io File]))
(def root v2/root)
(def model (str root "/.pi/model"))
(def plan-dir (str model "/plan"))
(defn files [] (sort (map #(.getName ^File %)
                          (filter #(str/ends-with? (.getName ^File %) ".allium")
                                  (.listFiles (File. model))))))
(defn classify [src o]
  (when-let [k (v2/kind (:category o))]
    (if (or (not= k "rule-failure") (nil? (:source_span o))) [k nil]
      (let [c (v2/clause src o)
            ex (re-find #"^requires:\s*(?:not )?exists (.+)$" c)
            redelivery (re-find #"^requires:\s*not exists (\w+)\{" c)]
        [k (cond (re-find #"^requires:\s*\w+\.(status|stage|path) = \w+$" c) "own-state"
                 (and ex (str/includes? src (str "requires: exists " (second ex)))
                      (str/includes? src (str "requires: not exists " (second ex)))) "branch-pair"
                 (and redelivery (:source_construct o)
                      (str/includes? (or (v2/rule-body src (:source_construct o)) "")
                                     (str (second redelivery) ".created("))) "redelivery")]))))
(defn declared [src]
  (for [[_ marker name prose] (v2/matches #"(?m)@(guarantee|invariant) (\w+)\n((?:[ \t]+--[^\n]*\n?)+)" src)
        :let [gate (second (re-find #"Verified by: gate ([\w-]+)" prose))
              text (str/join " " (for [line (str/split-lines prose) :when (seq (str/trim line))]
                                   (str/trim (subs (str/trim line) 2))))]]
    {:id (str (if (= marker "guarantee") "guarantee." "boundary.") name)
     :kind (if (= marker "guarantee") "guarantee" "boundary")
     :construct name :gate gate :text text}))
(defn obligations []
  (let [sources (into (sorted-map) (for [f (files)] [f (slurp (str model "/" f))]))]
    (loop [remaining (keys sources) seen #{} out [] planned #{}]
      (if-let [f (first remaining)]
        (let [[seen out planned]
              (reduce (fn [[seen out planned] o]
                        (let [id (:id o) planned (conj planned id)]
                          (if (seen id) [seen out planned]
                            (let [home (or (some (fn [[file src]]
                                                   (when (re-find (re-pattern (str "(?:^|\\n)(?:rule|invariant) "
                                                                                  (java.util.regex.Pattern/quote (str (:source_construct o))) " \\{")) src) file)) sources) f)
                                  src (sources home) [k unit] (classify src o)
                                  base {:id id :kind k :file home :construct (:source_construct o) :unit unit :gate nil}
                                  row (cond-> base
                                        (= k "rule-failure") (assoc :rule (:source_construct o)
                                                                    :n (Long/parseLong (last (str/split id #"\.")))
                                                                    :clause (str/replace (v2/clause src o) #"^requires:\s*" ""))
                                        (= k "rule-success") (assoc :rule (:source_construct o)))]
                              [(conj seen id) (cond-> out k (conj row)) planned]))))
                      [seen out planned] (v2/plan model f))
              [seen out] (reduce (fn [[seen out] row]
                                   (if (seen (:id row)) [seen out]
                                     [(conj seen (:id row)) (conj out (merge {:file f :unit nil} row))]))
                                 [seen out] (declared (sources f)))]
          (recur (rest remaining) seen out planned))
        [out planned]))))
(defn scenarios []
  (vec (for [f (gwt/feature-files plan-dir)
             s (gwt/parse-file f)
             :let [tags (map #(.getName %) (.getTags s))]]
         {:file (str/replace (.getPath ^File f) (str root "/") "")
          :line (.getLine (.getLocation s))
          :kind (if (= "spec.feature" (.getName ^File f)) "spec" "drafts")
          :item (some #(when (str/starts-with? % "@item-") (subs % 6)) tags)
          :title (.getName s)
          :alliumTags (vec (keep #(when (str/starts-with? % "@allium-") (subs % 8)) tags))})))
(defn tags [kind]
  (set (mapcat (fn [f] (when (= (str kind ".feature") (.getName ^File f))
                         (map second (v2/matches #"@allium-([\w.-]+)" (slurp f)))))
               (gwt/feature-files plan-dir))))
(defn run [args]
  (let [report (second (drop-while #(not= % "--report") args))
        [raw planned] (obligations) ss (scenarios) accepted (tags "spec") drafts (tags "drafts")
        obs (mapv (fn [o]
                    (assoc o :scenarios (vec (for [s ss :when (some #{(:id o)} (:alliumTags s))]
                                              (select-keys s [:file :line :kind :item :title])))
                             :status (cond (:unit o) "unit" (:gate o) "gate"
                                           (accepted (:id o)) "covered" (drafts (:id o)) "drafted"
                                           :else "uncovered"))) raw)
        must (remove #(or (:unit %) (:gate %)) obs)
        covered (filter #(= "covered" (:status %)) must)
        drafted (filter #(= "drafted" (:status %)) must)
        uncovered (filter #(= "uncovered" (:status %)) must)
        unit (sort (map :id (filter :unit obs)))
        gated (sort (map #(str (:id %) " -> gate " (:gate %)) (filter :gate obs)))
        known (into planned (map :id obs))
        unknown (sort (remove #(or (known %) (str/starts-with? % "context.")) (into accepted drafts)))]
    (println (format "must-cover %d | covered %d | drafted %d | uncovered %d | unit-level %d"
                     (count must) (count covered) (count drafted) (count uncovered) (count unit)))
    (doseq [o drafted] (println "  drafted  " (:id o)))
    (doseq [o uncovered] (println "  UNCOVERED" (:id o) (:file o)))
    (doseq [t unknown] (println "  UNKNOWN TAG" t))
    (doseq [g gated] (println "  structural" g))
    (println (str "unit-level: " (str/join ", " unit)))
    (when report
      (spit report (json/generate-string {:summary {:mustCover (count must) :covered (count covered)
                                                  :drafted (count drafted) :uncovered (count uncovered)
                                                  :unitLevel (count unit) :gated (count gated)}
                                         :ownStateFields ["status" "stage" "path"]
                                         :obligations obs :unknownTags unknown :planned (sort planned)}
                                        {:pretty true})))
    (if (or (seq uncovered) (seq unknown)) 1 0)))
(defn -main [& args] (System/exit (run args)))
