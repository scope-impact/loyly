#!/usr/bin/env bb
;; gen_checklist: derive the scoring checklist from the spec, one item per stamped
;; scenario.
;;
;; The checklist is never hand written. It sets the scoring denominator, and a
;; denominator that can drift for any reason other than the spec moving makes every past
;; cycle incomparable, silently. Hence the two refusals below: a scenario nobody stamped
;; stops the run rather than shrinking the list, and an empty spec stops it rather than
;; producing a checklist that has nothing in it that could fail.

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[babashka.classpath :as cp])
(cp/add-classpath (.getPath (.getParentFile (.getCanonicalFile (io/file *file*)))))
(require '[hjson])

(def root (hjson/root *file*))

;; This tool's own name, for the one message that tells an operator to run its sibling.
;; The reason sentence is fixed by the spec; the command must name a file that exists
;; (@harness-023).
(def stamper "harness/stamp_item_ids.clj")

;; A title is "at least one character, then optional trailing whitespace", matched
;; lazily, so "Scenario:" followed by three spaces is an item named " " (@harness-049)
;; while "Scenario:" followed by nothing is not an item at all (@harness-021).
(def scenario-line #"^\s*Scenario(?: Outline)?:\s*(.+?)\s*$")
(def rule-line #"^\s*Rule:\s*(.*?)\s*$")
(def item-tag #"@item-(\d+-\d+)")

(def skipped-statuses #{"planned" "backlog"})

(defn slice-status
  "The first line of the slice's plan.md beginning with \"status:\", and the
  non-whitespace run after it. Front matter works, and so does a bare line anywhere in
  the file (@harness-027)."
  [dir]
  (let [plan (io/file dir "plan.md")]
    (when (.isFile plan)
      (second (re-find #"(?m)^status:\s*(\S+)" (slurp plan :encoding "UTF-8"))))))

(defn scan
  "Read one spec file into [items errors]. The rule and the pending tag are both reset
  at the start of each file; within it, a pending tag is cleared ONLY by being consumed
  by a Scenario line, which is a wart and is specified as such (@harness-020)."
  [f]
  (let [path (hjson/rel root f)]
    (loop [lines (str/split (slurp f :encoding "UTF-8") #"\n" -1)
           n 1, rule nil, pending nil, items [], errors []]
      (if-let [line (first lines)]
        (let [stripped (str/trim line)]
          (cond
            ;; A tag line is recognised only when the stripped line begins with "@";
            ;; the LAST item tag seen before the scenario wins (@harness-019).
            (str/starts-with? stripped "@")
            (recur (rest lines) (inc n) rule
                   (or (last (map second (re-seq item-tag line))) pending)
                   items errors)

            (re-find rule-line line)
            (recur (rest lines) (inc n) (second (re-find rule-line line)) pending items errors)

            (re-find scenario-line line)
            (let [name (second (re-find scenario-line line))]
              (if pending
                (recur (rest lines) (inc n) rule nil
                       (conj items (array-map "id" pending
                                              "name" name
                                              "rule" rule
                                              "source" (str path ":" n)))
                       errors)
                (recur (rest lines) (inc n) rule nil items
                       (conj errors (str "  " path ":" n ": UNSTAMPED: " name)))))

            :else (recur (rest lines) (inc n) rule pending items errors)))
        [items errors]))))

(let [args (vec *command-line-args*)
      out-path (second (drop-while #(not= "--out" %) args))
      files (hjson/spec-files root)
      ;; The planned-slice filter, and it is loud on purpose: it could obviously be
      ;; abused to shrink a denominator by demoting a slice (@harness-027). The skip
      ;; lines land on stdout ahead of the JSON, which breaks `gen_checklist | jq`.
      ;; That is a known wart, specified as it stands (@harness-028).
      files (vec (remove (fn [f]
                           (let [dir (.getParentFile f)
                                 status (slice-status dir)]
                             (when (skipped-statuses status)
                               (hjson/out (str "gen_checklist: skipping " (.getName dir)
                                               " (status: " status ")\n"))
                               true)))
                         files))]
  (when (empty? files)
    (hjson/refuse (str "gen_checklist: no NN-slug/spec.feature under " (hjson/plan-dir root)
                       ". An empty checklist lets a scoring run report a flawless cycle in"
                       " which nothing was ever checked, so none is written.")))
  (let [items (reduce (fn [acc f]
                        (let [[items errors] (scan f)]
                          ;; The run stops as soon as one file finishes with offenders,
                          ;; so later files are never read (@harness-024).
                          (when (seq errors)
                            (hjson/refuse (str "gen_checklist: some scenarios carry no item id."
                                               " A checklist built now would quietly score fewer"
                                               " items than the spec contains, so stamp them first"
                                               " with " stamper ":\n"
                                               (str/join "\n" errors))))
                          (into acc items)))
                      [] files)
        text (str (hjson/write (array-map "items" items
                                          "counts" (array-map "total" (count items))))
                  "\n")]
    (if out-path
      (hjson/spit-utf8 out-path text)
      (hjson/out text))
    ;; The counts line goes to STDERR so that stdout stays pipeable JSON, and its shape
    ;; is a Python dict repr, which is now contract (@harness-012).
    (hjson/err (str "checklist: {'total': " (count items) "}\n"))))
