(ns measurement.support
  "Test support: a fresh product per test on a fresh SQLite store, driven only
   through the public seam (JSON-RPC lines), and the gate's store check run
   after every test."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [is]]
            [measurement.seam :as seam]
            [measurement.store-check :as store-check]
            [measurement.system :as system])
  (:import [java.io File]))

(def ^:dynamic *sys* nil)

(defn with-product
  "clojure.test :each fixture."
  [f]
  (let [file (File/createTempFile "measurement-test" ".db")
        path (.getPath file)
        _ (.delete file)
        sys (system/start {:store-path path :tenant-id (random-uuid)})]
    (try
      (binding [*sys* sys]
        (f)
        (let [problems (store-check/check (:ctx sys))]
          (is (empty? problems) (str "store check: " (pr-str (take 5 problems))))))
      (finally
        (system/stop sys)
        (doseq [suffix ["" "-wal" "-shm"]] (.delete (File. (str path suffix))))))))

(def ^:private ids (atom 0))

(defn rpc-raw [line]
  (some-> (seam/handle-line (:ctx *sys*) line) json/read-str))

(defn rpc
  "Call `method` with `params` through the seam; returns the parsed response."
  [method params]
  (let [id (swap! ids inc)
        resp (rpc-raw (json/write-str {"jsonrpc" "2.0" "id" id "method" method "params" params}))]
    (assert (= id (get resp "id")) (str "response id mismatch: " resp))
    resp))

(defn result [resp] (get resp "result"))
(defn refusal [resp] (get-in resp ["error" "data" "refusal"]))
(defn events [resp] (get-in resp ["result" "events"]))
(defn event-types [resp] (mapv #(get % "type") (events resp)))

;; ---------------------------------------------------------------------------
;; Handed-in handoffs
;; ---------------------------------------------------------------------------

(def t0 "2026-11-07T10:00:00Z")

(defn slice-of [item-id] (first (clojure.string/split item-id #"-")))

(defn stamp! [& item-ids]
  (doseq [[i id] (map-indexed vector item-ids)]
    (let [r (rpc "specification/scenario-stamped"
                 {"item-id" id "slice" (slice-of id)
                  "source" (str ".pi/plan/" (slice-of id) "/spec.feature:" (+ 10 i))
                  "line" (+ 10 i) "title" (str "scenario " id) "stamped-at" t0})]
      (assert (result r) (pr-str r)))))

(defn pin! [cut-id]
  (let [r (rpc "cycle-preparation/source-cut-pinned"
               {"cut-id" cut-id "commit" (apply str (repeat 40 "a")) "tree-digest" (str "tree-" cut-id)
                "pinned-at" t0})]
    (assert (result r) (pr-str r))
    r))

(defn gate!
  ([cycle-id cut-id candidate] (gate! cycle-id cut-id candidate 1))
  ([cycle-id cut-id candidate attempt]
   (let [r (rpc "regeneration-cycle/gate-attempt-passed"
                {"cycle-id" cycle-id "cut-id" cut-id "attempt" attempt "candidate-digest" candidate
                 "boot-budget-seconds" 30 "boot-polls" 10 "answered-on-poll" 2
                 "identity-probe-result" "ok" "evidence-root" (str "evidence/" cycle-id)
                 "passed-at" t0})]
     (assert (result r) (pr-str r))
     r)))

;; ---------------------------------------------------------------------------
;; Commands and queries
;; ---------------------------------------------------------------------------

(defn item [id & {:keys [rule title source]}]
  {"item-id" id "scenario-digest" (str "sd-" id) "rule" rule
   "source" (or source (str ".pi/plan/" (slice-of id) "/spec.feature:7"))
   "title" (or title (str "scenario " id))})

(defn derive!
  [cut-id items & {:keys [selection unstamped skipped] :or {selection "whole-plan"}}]
  (rpc "measurement/derive-checklist"
       {"cut-id" cut-id "selection-id" selection
        "items" (mapv #(if (string? %) (item %) %) items)
        "unstamped" (or unstamped []) "skipped-slices" (or skipped [])}))

(defn checklist [cut-id] (get (result (rpc "measurement/checklist" {"cut-id" cut-id})) "checklist"))

(defn verdict
  ([id v] {"item-id" id "verdict" v})
  ([id v reason] {"item-id" id "verdict" v "reason" reason}))

(defn file-verdicts!
  [cycle-id candidate grader verdicts & {:keys [approved] :or {approved ["G-1"]}}]
  (rpc "measurement/file-item-verdicts"
       {"cycle-id" cycle-id "candidate-digest" candidate "grader-digest" grader
        "approved-grader-digests" approved "verdicts" verdicts}))

(defn file-score! [cycle-id & {:keys [approved] :or {approved ["G-1"]}}]
  (rpc "measurement/file-score" {"cycle-id" cycle-id "approved-grader-digests" approved}))

(defn fail-stage! [cycle-id refusal detail]
  (rpc "measurement/fail-score-stage" {"cycle-id" cycle-id "refusal" refusal "detail" detail}))

(defn measurement [cycle-id]
  (get (result (rpc "measurement/cycle-measurement" {"cycle-id" cycle-id})) "measurement"))

(defn scoreboard [cycle-id]
  (get (result (rpc "measurement/scoreboard" {"cycle-id" cycle-id})) "scoreboard"))

(defn to-report []
  (get (result (rpc "measurement/measurements-to-report" {})) "cycle-ids"))

(defn measured!
  "A cycle measured end to end: its cut pinned, its checklist derived from
   `verdicts`' items (all stamped), gated, verdicts filed, score filed."
  [{:keys [cycle cut selection candidate verdicts] :or {selection "whole-plan" candidate "D-1"}}]
  (let [ids (mapv first verdicts)]
    (apply stamp! ids)
    (pin! cut)
    (let [d (derive! cut ids :selection selection)]
      (assert (or (result d) (= "checklist-already-derived" (refusal d))) (pr-str d)))
    (gate! cycle cut candidate)
    (let [f (file-verdicts! cycle candidate "G-1" (mapv (fn [[id v]] (verdict id v)) verdicts))]
      (assert (result f) (pr-str f)))
    (let [s (file-score! cycle)]
      (assert (result s) (pr-str s))
      s)))

(defn ids-of [n slice]
  (mapv #(format "%s-%03d" slice %) (range 1 (inc n))))
