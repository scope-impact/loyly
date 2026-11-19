(ns spec.glossary
  "Glossary word lint for the legacy model and spec v2."
  (:require [clojure.string :as str] [clojure.java.shell :as sh])
  (:import [java.io File]))
(def root (str/trim (:out (sh/sh "git" "rev-parse" "--show-toplevel"))))
(def model (str root "/.pi/model"))
(def spec (str root "/.pi/spec"))
(defn stem [w]
  (let [w (str/lower-case w) n (count w)]
    (cond (and (> n 4) (str/ends-with? w "ies")) (str (subs w 0 (- n 3)) "y")
          (and (> n 4) (str/ends-with? w "sses")) (subs w 0 (- n 2))
          (and (> n 3) (str/ends-with? w "s") (not (str/ends-with? w "ss")) (not (str/ends-with? w "us"))) (subs w 0 (dec n))
          :else w)))
(defn words [phrase]
  (let [phrase (-> phrase (str/replace #"([a-z0-9])([A-Z])" "$1 $2")
                   (str/replace #"([A-Z]+)([A-Z][a-z])" "$1 $2"))]
    (->> (str/split phrase #"[\s_/\-\\]+")
         (filter #(re-matches #"[A-Za-z][A-Za-z0-9]*" %)) (mapv stem))))
(defn phrases [line]
  (->> (str/split line #"[,;:.!?()\[\]{}\"`'|<>=+*#&/]")
       (map words) (filter seq)))
(defn glossary []
  (let [text (slurp (str model "/GLOSSARY.md"))
        blocks (str/split text #"\n(?=\*\*[^*\n]+\*\*:)")]
    (reduce (fn [[terms avoided] block]
              (if-let [[_ term] (re-find #"^\*\*([^*\n]+)\*\*:" block)]
                (let [term (str/trim term)
                      avoid (second (re-find #"(?s)_Avoid_:\s*(.+?)(?:\n\s*\n|\z)" (str block "\n\n")))
                      items (for [raw (str/split (str/join " " (str/split (or avoid "") #"\s+")) #",")
                                  :let [item (-> raw (str/replace #"\([^)]*\)" "") str/trim (str/replace #"\.$" "") str/trim)]
                                  :when (seq item)]
                              [(words item) item term])]
                  [(conj terms [(words term) term]) (into avoided items)])
                [terms avoided])) [[] []] blocks)))
(defn seam-terms []
  (let [f (File. (str model "/affordances.yaml"))]
    (if-not (.isFile f) []
      (let [text (slurp f)
            names (set (concat (map second (re-seq #"(?m)^  ([A-Z]\w+):\s*$" text))
                               (map second (re-seq #"rpc:\s*([\w-]+)" text))
                               (map second (re-seq #"tool:\s*\{name:\s*(\w+)\}" text))
                               (mapcat #(map str/trim (str/split (second %) #","))
                                       (re-seq #"(?m)^  \w+:\s*\{enum:\s*\[([^\]]*)\]\}" text))))]
        (for [n (sort names) :when (seq (words n))] [(words n) n])))))
(defn positions [xs ws]
  (for [i (range (inc (- (count xs) (count ws)))) :when (= ws (subvec (vec xs) i (+ i (count ws))))] i))
(defn scan [path terms avoided v2?]
  (mapcat (fn [[no line]]
            (if (re-find (if v2? #"(?i)formerly|avoid|legacy" #"(?i)formerly|avoid") line) []
              (for [ws (phrases line)
                    :let [spans (for [[tws _] terms i (positions ws tws)] [i (+ i (count tws))])]
                    [aws text term] avoided :when (seq aws)
                    i (positions ws aws)
                    :let [j (+ i (count aws))]
                    :when (not-any? (fn [[a b]] (and (<= a i) (<= j b))) spans)]
                [(str/replace (.getPath (File. path)) (str root "/") "") no text term (str/trim line)])))
          (map-indexed (fn [i line] [(inc i) line]) (str/split-lines (slurp path)))))
(defn all-files [dir exts]
  (->> (file-seq (File. dir)) (filter #(.isFile ^File %))
       (filter #(contains? exts (second (re-find #"\.([^.]+)$" (.getName ^File %)))))
       (map #(.getPath ^File %)) sort))
(defn run [v2? args]
  (let [[terms avoided] (glossary) terms (into terms (seam-terms))
        specified (when (some #{"--file"} args) (->> (drop-while #(not= % "--file") args) rest (take-while #(not (str/starts-with? % "--"))) (map #(.getAbsolutePath (File. %)))))
        reported (if v2? (map #(str spec "/" %) ["event-storm.md" "event-storm.edn" "legacy-trace.md"])
                     (all-files (str model "/plan") #{"feature"}))
        enforced (if v2? (remove (set reported) (all-files spec #{"allium" "edn" "md" "feature"}))
                     (concat (all-files model #{"allium"})
                             (filter #(str/includes? % "/event-model/loyly/") (all-files (str model "/event-model/loyly") #{"clj"}))
                             [(str root "/.pi/architecture.md")]
                             (all-files (str model "/docs/adr") #{"md"})))
        found (vec (mapcat #(scan % terms avoided v2?) (or specified (filter #(.isFile (File. %)) enforced))))
        extra (when-not specified (vec (mapcat #(scan % terms avoided v2?) (filter #(.isFile (File. %)) reported))))]
    (doseq [[f no text term line] found]
      (println (format "AVOIDED %s:%d '%s' (avoid for %s): %s" f no text term (subs line 0 (min 120 (count line))))))
    (when-not (some #{"--quiet"} args)
      (doseq [[f no text term line] extra]
        (println (format "%s %s:%d '%s' (avoid for %s): %s" (if v2? "REPORTED" "PLAN   ") f no text term (subs line 0 (min 120 (count line)))))))
    (if specified
      (println (format "glossary: %d findings in %d files" (count found) (count specified)))
      (if v2?
        (println (format "glossary: %d terms, %d avoided | %d findings in .pi/spec (%d files) | %d reported (R0 board, legacy trace)" (count terms) (count avoided) (count found) (count enforced) (count extra)))
        (println (format "glossary: %d terms, %d avoided | %d findings | %d plan/ hits (reported, for the product owner)" (count terms) (count avoided) (count found) (count extra)))))
    (if (seq found) 1 0)))
(defn -main [& args]
  (let [v2? (some #{"--v2"} args)] (System/exit (run v2? (remove #{"--v2"} args)))))
