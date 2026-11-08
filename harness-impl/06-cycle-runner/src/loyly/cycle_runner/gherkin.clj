(ns loyly.cycle-runner.gherkin
  "The runner's reading of a spec file: scenarios with their tag lines, Rule and line;
   the stripped copy the stamp is offered; the scenario digest (seam.md, Digests)."
  (:require [clojure.string :as str]
            [loyly.cycle-runner.util :as u]))

(def item-tag-re #"^@item-(\d+-\d+)$")

(defn- heading [line]
  (let [t (str/trim line)]
    (some (fn [[kw re]] (when-let [m (re-matches re t)] [kw (str/trim (second m))]))
          [[:scenario #"(?:Scenario Outline|Scenario Template|Scenario|Example):(.*)"]
           [:rule #"Rule:(.*)"]
           [:feature #"Feature:(.*)"]
           [:background #"Background:(.*)"]])))

(defn tag-line? [line] (str/starts-with? (str/trim line) "@"))

(defn- digest-lines
  "The scenario's lines from its heading to the line before the next heading, tag line or
   end of file, trailing blank lines dropped."
  [lines start]
  (let [body (cons (nth lines start)
                   (take-while #(not (or (heading %) (tag-line? %)))
                               (drop (inc start) lines)))]
    (->> body reverse (drop-while str/blank?) reverse)))

(defn scenario-digest [lines start]
  (u/sha256-of-string (str/join "\n" (digest-lines lines start))))

(defn read-feature
  "Every scenario of the text: {:line (1-based) :title :tags :item-id :rule :digest}, in file
   order, and :feature-tags."
  [text]
  (let [lines (vec (str/split-lines text))]
    (loop [i 0 tags [] rule nil feature-tags nil out []]
      (if (>= i (count lines))
        {:scenarios out :feature-tags (or feature-tags [])}
        (let [line (nth lines i)
              t (str/trim line)
              [kind title] (heading line)]
          (cond
            (tag-line? line)
            (recur (inc i) (into tags (str/split t #"\s+")) rule feature-tags out)

            (= kind :feature)
            (recur (inc i) [] nil tags out)

            (= kind :rule)
            (recur (inc i) [] title feature-tags out)

            (= kind :scenario)
            (let [item (some #(second (re-matches item-tag-re %)) tags)]
              (recur (inc i) [] rule feature-tags
                     (conj out {:line (inc i) :title title :tags tags :item-id item
                                :rule rule :digest (scenario-digest lines i)})))

            (or (str/blank? t) (str/starts-with? t "#"))
            (recur (inc i) tags rule feature-tags out)

            :else
            (recur (inc i) [] rule feature-tags out)))))))

(defn strip-item-tags
  "A copy with the @item-NN-SSS tags of its tag lines removed; a tag line left empty goes;
   comments and other tags stay."
  [text]
  (let [lines (str/split-lines text)
        out (keep (fn [line]
                    (if (tag-line? line)
                      (let [indent (re-find #"^\s*" line)
                            kept (remove #(re-matches item-tag-re %) (str/split (str/trim line) #"\s+"))]
                        (when (seq kept) (str indent (str/join " " kept))))
                      line))
                  lines)]
    (str (str/join "\n" out) (when (str/ends-with? text "\n") "\n"))))
