#!/usr/bin/env bb
;; score: file one cycle's verdicts against that cycle's checklist.
;;
;; Who produced the verdicts is not this tool's business; what a measurement is allowed
;; to look like is. Two things it will not do, both learned from a grading run whose
;; every batch failed on a network outage and then committed "scored" over an empty
;; record: it will not write a record from zero verdicts, and it will not drop an item
;; the grader never reached. A measurement nobody filed can still be taken again; a
;; measurement that was filed wrong cannot be untaken.

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[babashka.classpath :as cp])
(cp/add-classpath (.getPath (.getParentFile (.getCanonicalFile (io/file *file*)))))
(require '[hjson])

(def root (hjson/root *file*))

;; The command the missing-checklist refusal tells an operator to run. It must name the
;; generator as it is actually invoked here, not the one being replaced (@harness-040).
(def gen-cmd "bb harness/gen_checklist.clj")

(def unreached-evidence "not scored: the grader never reached this item")

(let [args (vec *command-line-args*)
      verdicts-path (second (drop-while #(not= "--verdicts" %) args))
      cycle (first (remove #{"--verdicts" verdicts-path} args))]
  (when-not cycle
    (hjson/refuse "score: usage: score <cycle> --verdicts <file>"))
  (when-not verdicts-path
    (hjson/refuse "score: --verdicts <file> is required"))
  (let [checklist-rel (str "harness/cycles/" cycle "/checklist.json")
        checklist-file (io/file root checklist-rel)
        scores-rel (str "harness/cycles/" cycle "/scores.json")]
    (when-not (.isFile checklist-file)
      (hjson/refuse (str "score: no " checklist-rel ": generate it first:\n"
                         "  " gen-cmd " --out " checklist-rel)))
    (let [items (get (hjson/read-json (slurp checklist-file :encoding "UTF-8")) "items")
          vfile (io/file verdicts-path)
          _ (when-not (.isFile vfile)
              (hjson/refuse (str "score: no verdicts at " verdicts-path
                                 ". A measurement that is not there is not a measurement:"
                                 " nothing was written.")))
          raw (slurp vfile :encoding "UTF-8")
          ;; A blank file is the shape the incident actually took, and it dies the same
          ;; clean death as an empty list rather than on the parser (@harness-033).
          _ (when (str/blank? raw)
              (hjson/refuse "score: 0 items scored. A run that graded nothing tells you the grading did not happen, not that every item failed. Nothing recorded."))
          verdicts (try (hjson/read-json raw)
                        (catch Exception e
                          ;; The parenthesised detail is the only text in this tool that
                          ;; is not fixed; everything around it is (@harness-034).
                          (hjson/refuse (str "score: " verdicts-path " is not valid JSON ("
                                             (hjson/detail e) "). A measurement that cannot be"
                                             " read is not a measurement: nothing was written."))))]
      (when-not (vector? verdicts)
        (hjson/refuse (str "score: " verdicts-path " should hold a list of verdicts, found "
                           (hjson/type-name verdicts) ". Nothing was written.")))
      (when (empty? verdicts)
        (hjson/refuse "score: 0 items scored. A run that graded nothing tells you the grading did not happen, not that every item failed. Nothing recorded."))
      ;; The two checks run in this order, so a verdicts file with both faults reports
      ;; the unknown ids and stops there (@harness-039).
      (let [known (set (map #(get % "id") items))
            unknown (remove known (map #(get % "id") verdicts))]
        (when (seq unknown)
          (hjson/refuse (str "score: verdicts for items not on the checklist"
                             " (the spec is the only author of the item list): "
                             (str/join ", " unknown)))))
      (let [bad (->> verdicts
                     (remove #(#{"pass" "fail"} (get % "verdict")))
                     (map #(get % "id")))]
        (when (seq bad)
          (hjson/refuse (str "score: verdict must be 'pass' or 'fail' for: " (str/join ", " bad)))))
      ;; The verdicts file is a lookup table by id and its order is discarded; duplicates
      ;; keep the last, silently (@harness-031).
      (let [by-id (reduce (fn [m v] (assoc m (get v "id") v)) {} verdicts)
            scored (mapv (fn [item]
                           (let [v (by-id (get item "id"))]
                             (apply array-map
                                    (concat (mapcat identity item)
                                            ["verdict" (if v (get v "verdict") "fail")
                                             "evidence" (if v
                                                          (get v "evidence" "")
                                                          unreached-evidence)]))))
                         items)
            passes (count (filter #(= "pass" (get % "verdict")) scored))
            unreached (- (count items) (count (filter #(by-id (get % "id")) items)))]
        (hjson/spit-utf8 (io/file root scores-rel)
                         (str (hjson/write (array-map "items" scored)) "\n"))
        (hjson/out (str "scored cycle " cycle ": " passes "/" (count items) " pass"
                        (when (pos? unreached)
                          (str " (" unreached " unreached, recorded as fail)"))
                        "\n"
                        "filed: " scores-rel "\n"))))))
