#!/usr/bin/env bb
;; scoreboard: read one cycle's record and report the rate, and the comparison with the
;; cycle before it.
;;
;; The two numbers a reader wants to compare are the two headline rates, and when the
;; spec grows between cycles those rates have different denominators, so the rate can
;; fall with no regression behind it. So the comparison is restated on the shared items
;; only, both directions are shown separately, and a net is never computed: a net hides
;; a regression behind an unrelated repair.

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[babashka.classpath :as cp])
(cp/add-classpath (.getPath (.getParentFile (.getCanonicalFile (io/file *file*)))))
(require '[hjson])

(def root (hjson/root *file*))

(defn pct
  "One decimal, ties rounded to EVEN. 1/16 is 6.25 exactly and must render as 6.2: the
  obvious formatter in most languages rounds ties away from zero and would print 6.3,
  which is the single easiest way to make this instrument disagree with its own history
  (@harness-046)."
  [n d]
  (-> (java.math.BigDecimal. (double (/ (* 100.0 n) d)))
      (.setScale 1 java.math.RoundingMode/HALF_EVEN)
      (.toPlainString)))

(defn rate
  "\"<n>/<d>\" right aligned in seven, then \" = \", then the percentage right aligned in
  five with one decimal and a \"%\". A zero denominator renders as \" =   n/a\" and no
  percentage is computed (@harness-045)."
  [n d]
  (str (hjson/ralign (str n "/" d) 7)
       (if (zero? d) " =   n/a" (str " = " (hjson/ralign (pct n d) 5) "%"))))

(defn verdicts-of [f]
  (when (.isFile f)
    (let [items (get (hjson/read-json (slurp f :encoding "UTF-8")) "items")]
      (reduce (fn [m i] (assoc m (get i "id") (get i "verdict"))) {} items))))

(let [cycle (first *command-line-args*)]
  ;; The message on this path is deliberately NOT contract (@harness-044).
  (when-not cycle
    (hjson/refuse "scoreboard: usage: scoreboard <cycle>"))
  (let [scores-rel (str "harness/cycles/" cycle "/scores.json")
        this (verdicts-of (io/file root scores-rel))]
    ;; A record of nothing and no record are the same amount of measurement
    ;; (@harness-043).
    (when (empty? this)
      (hjson/refuse (str "scoreboard: nothing recorded at " scores-rel)))
    (let [total (count this)
          passes (count (filter #(= "pass" %) (vals this)))
          ;; The predecessor is the argument read as an integer minus one, formatted back
          ;; to the same width. A non-numeric argument has no predecessor (@harness-042).
          prev-cycle (when (re-matches #"\d+" cycle)
                       (format (str "%0" (count cycle) "d") (dec (Long/parseLong cycle))))
          prev (when prev-cycle (verdicts-of (io/file root (str "harness/cycles/" prev-cycle "/scores.json"))))
          head [(str "cycle " cycle ": " passes "/" total " checklist items pass") ""]]
      (hjson/out
       (str/join
        "\n"
        (concat
         head
         (if (nil? prev)
           ["  no previous cycle to diff against: baseline cycle."]
           (let [shared (filter (set (keys prev)) (keys this))
                 added (remove (set (keys prev)) (keys this))
                 dropped (remove (set (keys this)) (keys prev))
                 shared-n (count shared)
                 prev-pass (count (filter #(= "pass" (prev %)) shared))
                 this-pass (count (filter #(= "pass" (this %)) shared))
                 better (count (filter #(and (= "fail" (prev %)) (= "pass" (this %))) shared))
                 worse (count (filter #(and (= "pass" (prev %)) (= "fail" (this %))) shared))]
             (concat
              [(str "  against cycle " prev-cycle ", counting only the " shared-n
                     " items both cycles carried:")
               (str "    cycle " prev-cycle "  " (rate prev-pass shared-n))
               (str "    cycle " cycle "  " (rate this-pass shared-n)
                    "   <- these two are the fair comparison")
               ;; Three literal spaces, then the count right aligned in three, so that the
               ;; counts land in the same column as the rates above them.
               (str "    now passing   " (hjson/ralign better 3) "   <- repaired since then")
               (str "    now failing   " (hjson/ralign worse 3) "   <- broken since then")]
              ;; Printed only when the item set moved, in either direction
              ;; (@harness-047). The wrapping of the sentence is contract.
              (when (or (seq added) (seq dropped))
                ["" (str "  THE ITEM SET MOVED: " (count prev) " items -> " total
                         " (" (count added) " added, " (count dropped) " dropped),"
                         " so the two headline")
                 "  rates count different things and must not be read against each other."
                 "  The added items cover work last cycle's spec never asked for."]))))
         [""]))))))
