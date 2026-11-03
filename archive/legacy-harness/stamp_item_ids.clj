#!/usr/bin/env bb
;; stamp_item_ids: give every scenario under docs/plan/ a stable id, carried in the
;; spec file itself.
;;
;; The id lives in the spec because anything computed from the scenario's wording loses
;; the cycle to cycle join the moment a rule is reworded (harness/spec.feature, the Rule
;; above @harness-008). Two consequences follow and both are load bearing: the stamper
;; is idempotent, and it never renumbers.

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[babashka.classpath :as cp])
(cp/add-classpath (.getPath (.getParentFile (.getCanonicalFile (io/file *file*)))))
(require '[hjson])

(def root (hjson/root *file*))

;; A scenario heading is optional whitespace then "Scenario:" or "Scenario Outline:",
;; and nothing else. "Scenario Template:" is not one (@harness-003).
(def scenario-line #"^\s*Scenario(?: Outline)?:")
(def item-tag #"@item-\d+-(\d+)")

(defn slice-of
  "The slice number: the directory name up to the first \"-\", verbatim (@harness-001)."
  [f]
  (re-find #"^[^-]*" (.getName (.getParentFile f))))

(defn next-number
  "One past the highest number in ANY @item-<digits>-<digits> anywhere in the file, on
  any line. Only the second group is compared, so a stray tag from another slice still
  pushes the counter (@harness-004). A file with no tags starts at 1."
  [lines]
  (inc (reduce max 0 (map #(Long/parseLong (second %))
                          (mapcat #(re-seq item-tag %) lines)))))

(defn stamped?
  "Walk upward from line `i` over the CONTIGUOUS run of lines whose stripped text starts
  with \"@\", and ask whether any of them carries an item tag. A blank line, a comment or
  any other text ends the run (@harness-005)."
  [lines i]
  (loop [j (dec i)]
    (if (and (>= j 0) (str/starts-with? (str/trim (nth lines j)) "@"))
      (if (re-find item-tag (nth lines j)) true (recur (dec j)))
      false)))

(defn stamp-text
  "The file's new text and how many tags it gained."
  [text slice]
  (let [lines (str/split text #"\n" -1)]
    (loop [i 0, out [], n (next-number lines), added 0]
      (if (= i (count lines))
        [(str/join "\n" out) added]
        (let [line (nth lines i)]
          (if (and (re-find scenario-line line) (not (stamped? lines i)))
            (recur (inc i)
                   (conj out (str (re-find #"^\s*" line)
                                  "@item-" slice "-" (format "%03d" n))
                         line)
                   (inc n) (inc added))
            (recur (inc i) (conj out line) n added)))))))

(let [files (hjson/spec-files root)]
  (if (empty? files)
    ;; Exit 0, not 1: an absent spec is a legitimate state early in a project, and the
    ;; refusal for it lives at checklist time instead (@harness-011).
    (hjson/out (str "stamp: no NN-slug/spec.feature under " (hjson/plan-dir root)
                    ": nothing to stamp yet.\n"
                    "       (the spec is written first; stamping is idempotent, run it again then)\n"))
    (let [total (reduce
                 (fn [total f]
                   (let [text (slurp f :encoding "UTF-8")
                         [new-text added] (stamp-text text (slice-of f))]
                     (when (pos? added)
                       (hjson/spit-utf8 f new-text)
                       (hjson/out (str "stamped " (hjson/ralign added 3)
                                       "  " (hjson/rel root f) "\n")))
                     (+ total added)))
                 0 files)]
      (hjson/out (str "total stamped: " total "\n")))))
