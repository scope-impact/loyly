(ns loyly.regen.support
  "Drives the product through its public seam: JSON-RPC request lines in, response
   lines out, against a fresh Grain store per test."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [is]]
            [loyly.regen.seam :as seam]
            [loyly.regen.system :as system]
            [ai.obney.grain.event-store-v3.interface :as es])
  (:import [java.security MessageDigest]))

(def ^:dynamic *store-file* nil)

(defn with-fresh-product
  "clojure.test :each fixture: a new product on a new SQLite store file."
  ([f] (with-fresh-product {} f))
  ([opts f]
   (let [file (java.io.File/createTempFile "loyly-regen-test" ".sqlite")]
     (.delete file)
     (system/start! (merge {:store-file (str file) :periodic? false} opts))
     (try
       (binding [*store-file* (str file)] (f))
       (finally
         (system/stop!)
         (.delete file)
         (.delete (java.io.File. (str file "-wal")))
         (.delete (java.io.File. (str file "-shm"))))))))

(def ^:private ids (atom 0))

(defn rpc
  "Sends one request through the seam; returns the parsed response."
  ([method] (rpc method {}))
  ([method params]
   (let [id (swap! ids inc)
         line (json/write-str {"jsonrpc" "2.0" "id" id "method" method "params" params})
         resp (json/read-str (seam/handle-line line))]
     (is (= id (get resp "id")) "the response carries the request's id")
     resp)))

(defn result [resp] (get resp "result"))
(defn error [resp] (get resp "error"))
(defn refusal [resp] (get-in resp ["error" "data" "refusal"]))
(defn events [resp] (get-in resp ["result" "events"]))
(defn event-types [resp] (mapv #(get % "type") (events resp)))

(defn recorded! [resp]
  (is (nil? (error resp)) (str "expected the method to record, got " (pr-str (error resp))))
  resp)

;; ---------------------------------------------------------------------------
;; The store, read directly (as the gate does)
;; ---------------------------------------------------------------------------

(defn store-events []
  (into [] (remove #(= :grain/tx (:event/type %)))
        (es/read (:event-store (system/ctx)) {:tenant-id system/tenant-id})))

(defn model-events
  "Every event of this context's model or a consumed handoff in the store."
  []
  (filterv #(#{"regeneration-cycle" "cycle-preparation" "grader-lifecycle" "measurement"}
             (namespace (:event/type %)))
           (store-events)))

(defn model-event-count [] (count (model-events)))

;; ---------------------------------------------------------------------------
;; The goldens digest, as seam.md defines it (computed here independently)
;; ---------------------------------------------------------------------------

(defn sha256 [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(def goldens (atom {}))

(defn goldens-digest
  ([] (goldens-digest @goldens))
  ([gs] (str "sha256:" (sha256 (apply str (for [[id dg] (sort-by key gs)] (str id "\t" dg "\n")))))))

;; ---------------------------------------------------------------------------
;; Handoffs, handed in
;; ---------------------------------------------------------------------------

(def commit-of {"K-3" "3333333333333333333333333333333333333333"
                "K-4" "4444444444444444444444444444444444444444"})

(defn pin-cut! [cut-id]
  (recorded! (rpc "cycle-preparation/source-cut-pinned"
                  {"cut-id" cut-id
                   "commit" (get commit-of cut-id "abcdefabcdefabcdefabcdefabcdefabcdefabcd")
                   "tree-digest" (str "tree-" cut-id)
                   "pinned-at" "2026-11-07T09:00:00Z"})))

(defn write-golden!
  ([golden-id digest] (write-golden! golden-id digest (str "S-" golden-id)))
  ([golden-id digest scenario-digest]
   (swap! goldens assoc golden-id digest)
   (recorded! (rpc "grader-lifecycle/instrument-golden-written"
                   {"golden-id" golden-id "scenario" (str "Scenario of " golden-id)
                    "scenario-digest" scenario-digest
                    "path" (str "harness/goldens/" golden-id ".edn") "digest" digest
                    "written-at" "2026-11-07T09:05:00Z"}))))

(defn move-golden! [golden-id from to reason]
  (swap! goldens assoc golden-id to)
  (recorded! (rpc "grader-lifecycle/instrument-golden-moved"
                  {"golden-id" golden-id "from-digest" from "to-digest" to
                   "reason" reason "additions-only" (= reason "spec-grew") "note" "moved"
                   "scenario-digest" (str "S2-" golden-id) "moved-at" "2026-11-07T09:10:00Z"})))

(defn file-measurement! [cycle-id candidate]
  (rpc "measurement/measurement-filed"
       {"cycle-id" cycle-id "cut-id" "K-3" "selection-id" "SEL-1" "candidate-digest" candidate
        "checklist-digest" "CL-1" "grader-digests" ["R-1"] "total" 2 "passes" 1 "unreached" 0
        "items" [{"item-id" "04-001" "verdict" "pass" "evidence" "e1"}
                 {"item-id" "04-002" "verdict" "fail" "evidence" "e2"}]
        "superseded" [] "filed-at" "2026-11-07T11:00:00Z"}))

(defn report-scoreboard! [cycle-id]
  (rpc "measurement/scoreboard-reported"
       {"cycle-id" cycle-id "selection-id" "SEL-1" "passes" 1 "total" 2 "rate" "50.0"
        "previous-cycle-id" nil "previous-selection-id" nil
        "new-items" ["04-001" "04-002"] "dropped-items" [] "reported-at" "2026-11-07T11:05:00Z"}))

(defn fail-score-stage! [cycle-id refusal]
  (rpc "measurement/score-stage-failed"
       {"cycle-id" cycle-id "refusal" refusal "detail" "score refused" "failed-at" "2026-11-07T11:00:00Z"}))

;; ---------------------------------------------------------------------------
;; Commands, as ORC runtime issues them
;; ---------------------------------------------------------------------------

(defn budget [limit]
  {"gate-attempt-limit" limit "max-outer-cycles" 5 "max-continuations" 3
   "stage-timeouts-seconds" {"fence" 3600 "regenerate" 3600 "gate" 3600 "score" 3600}})

(defn self-check-params [overrides]
  (merge {"cycle-id" "C-7" "cut-id" "K-3" "occasion" "before-fence" "attempt" 0
          "harness-digest" "H-1" "goldens-digest" (goldens-digest)
          "identity-probe-digest" "P-1" "outcome" "passed"
          "skipped-checks" [] "missing-goldens" [] "reworded-goldens" []
          "grader-digests" ["R-1"] "budget" (budget 3)}
         overrides))

(defn self-check [overrides]
  (rpc "regeneration-cycle/record-harness-self-check" (self-check-params overrides)))

(defn clean-room-params [overrides]
  (merge {"cycle-id" "C-7" "cut-id" "K-3" "clean-room-commit" "R-1"
          "fenced-paths" ["harness/score.clj" "harness/scoreboard.clj" "harness/cycles"]
          "root-commits" 1 "commits" 1 "unmatched-paths" [] "paths-left-in-tree" []
          "paths-reachable-in-object-graph" []}
         overrides))

(defn prove-clean-room [overrides]
  (rpc "regeneration-cycle/prove-clean-room" (clean-room-params overrides)))

(defn regenerate [overrides]
  (rpc "regeneration-cycle/record-regenerated-candidate"
       (merge {"cycle-id" "C-7" "attempt" 1 "purpose" "fresh-generation" "candidate-digest" "D-1"
               "mounted-evidence" []}
              overrides)))

(defn install [overrides]
  (rpc "regeneration-cycle/install-candidate-in-gate-room"
       (merge {"cycle-id" "C-7" "attempt" 1 "candidate-digest" "D-1" "purpose" "fresh-generation"
               "gate-room" "G-1"}
              overrides)))

(defn gate-params [overrides]
  (merge {"cycle-id" "C-7" "attempt" 1 "candidate-digest" "D-1" "harness-digest" "H-1"
          "outcome" "passed" "identity-probe-present" true "identity-probe-digest" "P-1"
          "seam-answered-before-boot" false "boot-budget-seconds" 10 "boot-polls" 20
          "answered-on-poll" 2 "identity-probe-result" "kalibra" "evidence-root" "E-1"
          "detail" "suite green" "grader-digests" ["R-1"]}
         overrides))

(defn gate-attempt
  "A gate verdict; an override of nil leaves that field out."
  [overrides]
  (rpc "regeneration-cycle/record-gate-attempt"
       (into {} (remove (comp nil? val)) (gate-params overrides))))

(defn failed-gate-attempt [overrides]
  (gate-attempt (merge {"outcome" "failed" "reason" "suite-red" "answered-on-poll" nil
                        "identity-probe-result" nil "detail" "suite red"}
                       overrides)))

(defn gate-refusal [overrides]
  (rpc "regeneration-cycle/record-gate-refusal"
       (merge {"cycle-id" "C-7" "attempt" 1 "refusal" "seam-answered-before-boot"
               "detail" "an orphan answered"}
              overrides)))

(defn fail-cycle [overrides]
  (rpc "regeneration-cycle/fail-cycle"
       (merge {"cycle-id" "C-7" "stage" "fence" "reason" "fence-refused" "detail" "fence refused"}
              overrides)))

(defn pass-cycle [cycle-id]
  (rpc "regeneration-cycle/pass-cycle" {"cycle-id" cycle-id}))

(defn progress
  ([] (result (rpc "regeneration-cycle/cycle-progress" {})))
  ([cycle-id] (get (result (rpc "regeneration-cycle/cycle-progress" {"cycle-id" cycle-id})) "cycle")))

(defn to-conclude [] (get (result (rpc "regeneration-cycle/cycles-to-conclude" {})) "cycle-ids"))

;; ---------------------------------------------------------------------------
;; Givens, built through the seam
;; ---------------------------------------------------------------------------

(defn opened!
  "Cut K-3 pinned, goldens written, cycle C-7 opened by a passing self-check before the fence."
  ([] (opened! 3))
  ([limit]
   (pin-cut! "K-3")
   (write-golden! "checklist" "g-1")
   (recorded! (self-check {"budget" (budget limit)}))))

(defn proved!
  ([] (proved! 3))
  ([limit] (opened! limit) (recorded! (prove-clean-room {}))))

(defn regenerated!
  ([] (regenerated! 3))
  ([limit] (proved! limit) (recorded! (regenerate {}))))

(defn installed!
  "Candidate D-1 installed in gate room G-1 for attempt 1, its self-check before the gate passed."
  ([] (installed! 3))
  ([limit]
   (regenerated! limit)
   (recorded! (install {}))
   (recorded! (self-check {"occasion" "before-gate" "attempt" 1}))))

(defn gate-passed! []
  (installed!)
  (recorded! (gate-attempt {})))

(defn attempt-failed!
  "Gate attempt n failed (its candidate D-n regenerated, installed and self-checked first)."
  [n]
  (when (> n 1)
    (recorded! (regenerate {"attempt" n "purpose" "verification-repair" "candidate-digest" (str "D-" n)
                            "parent-candidate-digest" (str "D-" (dec n))
                            "mounted-evidence" [(str "E-" (dec n))]})))
  (recorded! (install {"attempt" n "candidate-digest" (str "D-" n)
                       "purpose" (if (= 1 n) "fresh-generation" "verification-repair")
                       "gate-room" (str "G-" n)}))
  (recorded! (self-check {"occasion" "before-gate" "attempt" n}))
  (recorded! (failed-gate-attempt {"attempt" n "candidate-digest" (str "D-" n)
                                   "evidence-root" (str "E-" n)})))

(defn reset-goldens! [] (reset! goldens {}))
