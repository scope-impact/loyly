(ns loyly.specification.reading
  "The one reading of Gherkin: contract ScenarioReading (allium/context.allium).
   The stamper reads a spec file only through these functions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private heading-re #"^\s*(Scenario|Scenario Outline):(.*)$")
(def ^:private item-tag-re #"^@item-([0-9]+)-([0-9]+)$")

(defn- heading-rest
  "The text after the colon of a scenario heading, or nil when `line` is no
   scenario heading (OutlineYesTemplateNo)."
  [line]
  (when-let [[_ _ after] (re-matches heading-re line)]
    after))

(defn is-scenario-heading
  "A line that starts, after optional whitespace, with \"Scenario:\" or
   \"Scenario Outline:\" and has something after the colon. An empty-title
   heading is no scenario (EmptyTitleIsNoScenario)."
  [line]
  (boolean (some-> (heading-rest line) seq)))

(defn empty-title-heading?
  [line]
  (= "" (heading-rest line)))

(defn title-of
  "The heading's title: trimmed (TitleIsTrimmed); a whitespace-only title is
   its first whitespace character, one space or one tab
   (WhitespaceTitleIsAScenario)."
  [line]
  (let [after (heading-rest line)]
    (cond
      (nil? after) nil
      (str/blank? after) (subs after 0 1)
      :else (str/trim after))))

(defn- tag-line? [line]
  (str/starts-with? (str/trim line) "@"))

(defn item-tag-of
  "The last @item-<slice>-<number> tag of a contiguous tag run, or nil
   (ItemTagFromTheContiguousTagRun, OtherTagsDoNotStamp)."
  [tag-run]
  (->> tag-run
       (mapcat #(str/split (str/trim %) #"\s+"))
       (filter #(re-matches item-tag-re %))
       last))

(defn- tag-run-above
  "The contiguous tag lines directly above line index `i` (0-based), top first."
  [lines i]
  (loop [j (dec i) run ()]
    (if (and (>= j 0) (tag-line? (nth lines j)))
      (recur (dec j) (cons (nth lines j) run))
      (vec run))))

(defn read-lines
  "The file's lines, split on \\n with a trailing \\r dropped from each."
  [text]
  (mapv #(str/replace % #"\r$" "")
        (str/split text #"\n" -1)))

(defn spec-file ^java.io.File [source]
  (io/file source))

(defn slice-of
  "The slice is the name of the folder holding the spec, up to its first \"-\",
   verbatim. A folder not starting with a digit holds no slice: nil
   (SliceFromTheDirectory)."
  [source]
  (let [dir (some-> (spec-file source) .getAbsoluteFile .getParentFile .getName)]
    (when (and dir (re-find #"^[0-9]" dir))
      (first (str/split dir #"-" 2)))))

(defn spec-exists
  "A spec is found when the source is an existing file in a slice folder."
  [source]
  (let [f (spec-file source)]
    (boolean (and (.isFile f) (slice-of source)))))

(defn scan
  "Read spec text once: every scenario heading (1-based line, title, item tag)
   and every empty-title heading line, in file order."
  [text]
  (let [lines (read-lines text)]
    (reduce
     (fn [acc i]
       (let [line (nth lines i)]
         (cond
           (is-scenario-heading line)
           (update acc :scenarios conj {:line (inc i)
                                        :title (title-of line)
                                        :item-tag (item-tag-of (tag-run-above lines i))})
           (empty-title-heading? line)
           (update acc :empty-title-lines conj (inc i))
           :else acc)))
     {:scenarios [] :empty-title-lines []}
     (range (count lines)))))

(defn unstamped-scenarios-in [text]
  (->> (:scenarios (scan text))
       (remove :item-tag)
       (mapv #(select-keys % [:line :title]))))

(defn unstamped-scenarios [source]
  (unstamped-scenarios-in (slurp (spec-file source))))

(defn empty-title-lines-of [source]
  (:empty-title-lines (scan (slurp (spec-file source)))))

(defn item-tag
  "The tag that carries an item identity: @item-<slice>-<number>, the number
   zero padded to three digits."
  [slice number]
  (format "@item-%s-%03d" slice number))

(defn write-item-tags
  "Spec text with each tag in `stamps` ({:line n :tag \"@item-..\"}, lines of
   `text`) written on a line of its own directly above that heading, with the
   heading's indentation. Every other line is kept as it was."
  [text stamps]
  (let [raw (str/split text #"\n" -1)
        by-line (into {} (map (juxt :line :tag)) stamps)]
    (->> raw
         (map-indexed
          (fn [i line]
            (if-let [tag (by-line (inc i))]
              (let [indent (re-find #"^\s*" line)
                    cr (if (str/ends-with? line "\r") "\r" "")]
                [(str indent tag cr) line])
              [line])))
         (apply concat)
         (str/join "\n"))))
