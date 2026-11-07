;; harness/v2/assemble.clj ROOT ROOM01 ROOM02 ROOM03 ROOM04 ROOM05: the first assembly gate of
;; spec v2 (.pi/spec/decisions.md, "First light" 5; "Spec round 4" 3), run by
;; harness/v2/assemble.sh. It drives the five gated candidates ONLY through their public seams
;; (ROOM/harness-impl/NN-<context>/start.sh, JSON-RPC 2.0 over stdio, each context's seam.md)
;; and never reads a candidate's source.
;;
;;   1. The shared-store experiment: producers and consumers run on ONE LOYLY_GRAIN_STORE,
;;      first one after the other, then concurrently; does the consumer see the producer's
;;      events without a hand-in? Recorded as evidence, never worked around.
;;   2. The relay assembly: each candidate on its own fresh store. One end-to-end story; every
;;      handoff a producer emits (taken from its command result's `events`, in order) is handed
;;      in UNCHANGED (its `type` the method, its other fields the params) to every consumer the
;;      event models name (a read model's :consumes or a processor's :subscribes of a foreign
;;      event). Measurement's scoreboard-reported is issued by its policy and is in no command
;;      result (measurement/seam.md, "The policy runs first"), so it is read through
;;      measurement/scoreboard, the fields of that event.
;;   3. Checks, PASS/FAIL with evidence; then the round-4 open question: a cycle the stage clock
;;      fails at the score stage, measured anyway. Reported as a finding, not a check.
;;
;; Exit 1 when a contract check or the happy path fails. Every process it starts is stopped.
(ns assemble
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def contexts {"01" "specification" "02" "measurement" "03" "grader-lifecycle"
               "04" "regeneration-cycle" "05" "cycle-preparation"})
(def ctx->slice (into {} (map (fn [[k v]] [v k]) contexts)))
(def call-timeout-ms 180000)

(def root (fs/canonicalize (first *command-line-args*)))
(def rooms (zipmap (sort (keys contexts)) (rest *command-line-args*)))
(def work (str (fs/create-temp-dir {:prefix "assemble."})))

;; ---------------------------------------------------------------- the contracts
(defn model-of [ctx]
  (->> (fs/glob (fs/path root ".pi/spec" ctx "event-model") "*.edn")
       (map (comp edn/read-string slurp str))
       (map #(get % (keyword ctx)))
       (apply merge-with merge)))

(defn schema-keys [schema]
  (let [fields (rest schema)
        k (fn [f] (name (first f)))
        opt? (fn [f] (and (map? (second f)) (:optional (second f))))]
    {:required (set (map k (remove opt? fields))) :all (set (map k fields))}))

(def handoffs   ; "area/event" -> {:producer ctx :required #{} :all #{}}
  (into {} (for [ctx (vals contexts)
                 [t {:keys [schema]}] (get-in (edn/read-string (slurp (str (fs/path root ".pi/spec" ctx "event-model/handoffs.edn"))))
                                              [(keyword ctx) :events])
                 :let [t (str (namespace t) "/" (name t))]]
             [t (assoc (schema-keys schema) :producer ctx)])))

(def consumers  ; "area/event" -> [ctx ...], from the consumers' own event models
  (let [pairs (for [ctx (vals contexts)
                    :let [m (model-of ctx)]
                    blk (concat (vals (:read-models m)) (vals (:todo-processors m)))
                    t (concat (:consumes blk) (:subscribes blk))
                    :when (not= (namespace t) ctx)]
                [(str (namespace t) "/" (name t)) ctx])]
    (reduce (fn [acc [t ctx]] (update acc t (fnil #(vec (distinct (conj % ctx))) []))) {} (sort pairs))))

;; ---------------------------------------------------------------- the checks
(def checks (atom []))
(defn check! [kind label ok evidence]
  (swap! checks conj {:kind kind :label label :ok (boolean ok) :evidence evidence})
  (println (str (if ok "PASS" "FAIL") " [" (name kind) "] " label ": " evidence)))
(defn note [& xs] (println (apply str xs)))
(defn clip [x n] (let [s (if (string? x) x (json/generate-string x))] (if (> (count s) n) (str (subs s 0 n) "...") s)))

;; ---------------------------------------------------------------- the seam
(def procs (atom []))
(defn stop! [c]
  (when-let [pr (:proc c)]
    (try (.close ^java.io.Writer (:w c)) (catch Exception _))
    (p/destroy-tree pr)
    (deref pr 10000 nil)))
(defn stop-all! [] (run! stop! @procs) (reset! procs []))

(defn start! [slice store tag & {:keys [env dir]}]
  (let [ctx (contexts slice)
        sh (str (fs/path (rooms slice) "harness-impl" (str slice "-" ctx) "start.sh"))
        dir (or dir (str (fs/create-dirs (fs/path work tag slice))))
        base (into {} (remove (fn [[k _]] (or (str/starts-with? k "LOYLY_") (= k "GATE_STORE"))) (System/getenv)))
        pr (p/process {:dir dir :env (merge base {"LOYLY_GRAIN_STORE" store} env)
                       :err :write :err-file (io/file dir (str tag "-" slice ".stderr.log"))}
                      sh)
        c {:slice slice :ctx ctx :tag tag :proc pr :dir dir
           :r (io/reader (:out pr)) :w (io/writer (:in pr)) :id (atom 0)}]
    (swap! procs conj c)
    c))

(defn call [c method params]
  (let [id (swap! (:id c) inc)
        req {"jsonrpc" "2.0" "id" id "method" method "params" params}]
    (note "  -> " (:tag c) "/" (:slice c) " " method " " (clip params 260))
    (.write ^java.io.Writer (:w c) (str (json/generate-string req) "\n"))
    (.flush ^java.io.Writer (:w c))
    (let [line (deref (future (.readLine ^java.io.BufferedReader (:r c))) call-timeout-ms ::timeout)]
      (cond
        (= line ::timeout) (throw (ex-info (str method " to " (:ctx c) ": no answer in " (/ call-timeout-ms 1000) "s") {}))
        (nil? line) (throw (ex-info (str (:ctx c) " exited before answering " method
                                         " (stderr: " (:dir c) ")") {}))
        :else (let [msg (json/parse-string line)]
                (when (not= id (get msg "id"))
                  (throw (ex-info (str (:ctx c) " answered id " (get msg "id") " to request " id) {})))
                (note "  <- " (clip (or (get msg "result") {"error" (get msg "error")}) 400))
                msg)))))

(defn result [c method params]
  (let [m (call c method params)]
    (when (get m "error")
      (throw (ex-info (str method " refused by " (:ctx c) ": " (clip (get m "error") 300)) {:error (get m "error")})))
    (get m "result")))

;; ---------------------------------------------------------------- the fixtures
(def commit "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51")
(def tree "t-assembly")
(def titles ["a stamped scenario is measured" "a measured scenario is reported"])

(defn spec-file! [dir]
  (let [f (fs/path dir "plan" "09-assembly" "spec.feature")]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (str "Feature: assembly\n"
                       "  Scenario: " (titles 0) "\n    Given one\n    Then two\n"
                       "\n"
                       "  Scenario: " (titles 1) "\n    Given three\n    Then four\n"))
    (str f)))
(def unstamped [{"line" 2 "title" (titles 0)} {"line" 6 "title" (titles 1)}])

(defn fixtures-env [dir]
  (let [commits (str (fs/path dir "project-commits.json"))
        digests (str (fs/path dir "scenario-digests.json"))]
    (spit commits (json/generate-string {commit tree}))
    (spit digests (json/generate-string {"@item-09-001" "s-1"}))
    {"LOYLY_PROJECT_COMMITS" commits "LOYLY_SCENARIO_DIGESTS" digests}))

(defn sha256-hex [^String s]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))))
(defn goldens-digest [goldens]   ; regeneration-cycle/seam.md, "The goldens digest"
  (str "sha256:" (sha256-hex (apply str (for [g (sort-by #(get % "golden-id") goldens)]
                                          (str (get g "golden-id") "\t" (get g "digest") "\n"))))))

(def day 86400)
(defn budget [score-timeout]
  {"gate-attempt-limit" 3 "max-outer-cycles" 1 "max-continuations" 0
   "stage-timeouts-seconds" {"fence" day "regenerate" day "gate" day "score" score-timeout}})
(defn self-check [cycle cut occasion attempt gdigest score-timeout]
  {"cycle-id" cycle "cut-id" cut "occasion" occasion "attempt" attempt "harness-digest" "h-1"
   "goldens-digest" gdigest "identity-probe-digest" "p-1" "outcome" "passed" "skipped-checks" []
   "missing-goldens" [] "reworded-goldens" [] "grader-digests" ["r-1"] "budget" (budget score-timeout)})

;; ---------------------------------------------------------------- the relay
(def emitted (atom []))      ; [{:type :event :by producer}] in emission order
(def handed (atom []))       ; [{:type :to ctx :event :echo :error}]

(defn relay! [cs producer events]
  (doseq [e events
          :let [t (get e "type")]
          :when (contains? handoffs t)]
    (let [{:keys [required all]} (handoffs t)
          ks (set (remove #{"type"} (keys e)))]
      (check! :contract (str "produced " t " fits its contract")
              (and (= producer (:producer (handoffs t))) (every? ks required) (every? all ks))
              (str "fields " (sort ks)))
      (swap! emitted conj {:type t :event e :by producer})
      (doseq [ctx (consumers t)]
        (let [m (call (cs (ctx->slice ctx)) t (dissoc e "type"))]
          (swap! handed conj {:type t :to ctx :event e
                              :echo (first (get-in m ["result" "events"])) :error (get m "error")})))))
  events)

(defn cmd! [cs slice method params]
  (let [evs (get (result (cs slice) method params) "events")]
    (relay! cs (contexts slice) evs)))

(defn scoreboard-handoff [cs cycle]
  (let [sb (get (result (cs "02") "measurement/scoreboard" {"cycle-id" cycle}) "scoreboard")]
    (when sb (assoc (dissoc sb "restatement") "type" "measurement/scoreboard-reported"))))

(defn progress [cs cycle] (get (result (cs "04") "regeneration-cycle/cycle-progress" {"cycle-id" cycle}) "cycle"))

(defn gate-cycle! [cs cycle cut gdigest cand score-timeout]
  (cmd! cs "04" "regeneration-cycle/record-harness-self-check" (self-check cycle cut "before-fence" 0 gdigest score-timeout))
  (cmd! cs "04" "regeneration-cycle/prove-clean-room"
        {"cycle-id" cycle "cut-id" cut "clean-room-commit" (str "c-" cycle) "fenced-paths" ["harness-impl"]
         "root-commits" 1 "commits" 1 "unmatched-paths" [] "paths-left-in-tree" [] "paths-reachable-in-object-graph" []})
  (cmd! cs "04" "regeneration-cycle/record-regenerated-candidate"
        {"cycle-id" cycle "attempt" 1 "purpose" "fresh-generation" "candidate-digest" cand "mounted-evidence" []})
  (cmd! cs "04" "regeneration-cycle/install-candidate-in-gate-room"
        {"cycle-id" cycle "attempt" 1 "candidate-digest" cand "purpose" "fresh-generation" "gate-room" (str "gate-room-" cycle)})
  (cmd! cs "04" "regeneration-cycle/record-harness-self-check" (self-check cycle cut "before-gate" 1 gdigest score-timeout))
  (cmd! cs "04" "regeneration-cycle/record-gate-attempt"
        {"cycle-id" cycle "attempt" 1 "candidate-digest" cand "harness-digest" "h-1" "outcome" "passed"
         "identity-probe-present" true "identity-probe-digest" "p-1" "seam-answered-before-boot" false
         "boot-budget-seconds" 30 "boot-polls" 30 "answered-on-poll" 5 "identity-probe-result" "proved"
         "evidence-root" (str "/evidence/" cycle "/1") "detail" "" "grader-digests" ["r-1"]}))

(defn measure! [cs cycle cand item-ids]
  (cmd! cs "02" "measurement/file-item-verdicts"
        {"cycle-id" cycle "candidate-digest" cand "grader-digest" "r-1" "approved-grader-digests" ["r-1"]
         "verdicts" (vec (for [i item-ids] {"item-id" i "verdict" "pass" "evidence" (str "assembly " i)}))})
  (let [evs (cmd! cs "02" "measurement/file-score" {"cycle-id" cycle "approved-grader-digests" ["r-1"]})
        sb (scoreboard-handoff cs cycle)]
    (note "  (scoreboard-reported of " cycle " read through measurement/scoreboard: the policy's event is in no command result)")
    (when sb (relay! cs "measurement" [sb]))
    {:filed (first (filter #(= "measurement/measurement-filed" (get % "type")) evs)) :scoreboard sb}))

;; ---------------------------------------------------------------- 1. the shared-store experiment
(defn experiment! []
  (note "\n=== 1. the shared-store experiment: one LOYLY_GRAIN_STORE, no hand-ins")
  (let [answers (atom {})]
    (doseq [[mode concurrent?] [["sequential" false] ["concurrent" true]]]
      (let [dir (str (fs/create-dirs (fs/path work (str "shared-" mode))))
            store (str (fs/path dir "events.sqlite"))
            env (fixtures-env dir)
            source (spec-file! dir)
            cut (str "K-" mode)
            start (fn [s] (start! s store (str "shared-" mode) :env env :dir dir))
            run-producers (fn [c1 c5 c3]
                            (let [st (get (result c1 "specification/stamp-slice"
                                                  {"slice" "09" "source" source "unstamped" unstamped "empty-title-lines" []})
                                          "events")]
                              (result c5 "cycle-preparation/pin-source-cut" {"cut-id" cut "commit" commit "tree-digest" tree})
                              (result c3 "grader-lifecycle/write-instrument-golden"
                                      {"golden-id" "checklist" "scenario" "@item-09-001" "scenario-digest" "s-1"
                                       "path" "harness/oracle/checklist.golden.json" "digest" "g-1"})
                              st))
            probe-consumers (fn [c2 c4 stamped]
                              (let [items (vec (for [e stamped]
                                                 {"item-id" (get e "item-id") "scenario-digest" (str "s-" (get e "item-id"))
                                                  "rule" nil "source" (str (get e "source") ":" (get e "line")) "title" (get e "title")}))
                                    m2 (call c2 "measurement/derive-checklist"
                                             {"cut-id" cut "selection-id" "whole-plan" "items" items "unstamped" [] "skipped-slices" []})
                                    m4 (call c4 "regeneration-cycle/record-harness-self-check"
                                             (self-check (str "C-" mode) cut "before-fence" 0
                                                         (goldens-digest [{"golden-id" "checklist" "digest" "g-1"}]) day))]
                                [m2 m4]))]
        (note "--- " mode ": store " store)
        (try
          (let [[m2 m4]
                (if concurrent?
                  (let [c2 (start "02") c4 (start "04") c1 (start "01") c5 (start "05") c3 (start "03")
                        stamped (run-producers c1 c5 c3)
                        r (probe-consumers c2 c4 stamped)]
                    (stop-all!) r)
                  (let [c1 (start "01") c5 (start "05") c3 (start "03")
                        stamped (run-producers c1 c5 c3)]
                    (stop-all!)
                    (let [c2 (start "02") c4 (start "04") r (probe-consumers c2 c4 stamped)]
                      (stop-all!) r)))
                saw (fn [m] (if (get m "error") (str "refused " (get-in m ["error" "data" "refusal"] (get-in m ["error" "code"])))
                                "accepted"))
                ;; the store read directly, as the gate's event-store check does: whose facts are where
                tenants (->> (p/shell {:out :string} "sqlite3" "-separator" " " store
                                  (str "select tenant_id, group_concat(distinct type) from events"
                                       " where type not like ':sheet/%' and type not like ':grain/%' and type not like ':rlm/%'"
                                       " group by tenant_id union select id, '(only ORC and Grain events)' from tenants"
                                       " where id not in (select tenant_id from events where type not like ':sheet/%'"
                                       " and type not like ':grain/%' and type not like ':rlm/%')"))
                             :out str/split-lines (remove str/blank?) vec)]
            (swap! answers assoc mode {:measurement (saw m2) :regeneration-cycle (saw m4) :tenants tenants})
            (note "EXPERIMENT " mode ": measurement/derive-checklist of " cut " (cut pinned and items stamped by the producers on this store, nothing handed in): "
                  (saw m2) "; regeneration-cycle/record-harness-self-check on " cut " at the goldens digest of the golden written on this store: " (saw m4))
            (note "EXPERIMENT " mode ": the one store file holds " (count tenants) " Grain tenants: " (str/join "; " tenants)))
          (catch Exception ex
            (stop-all!)
            (swap! answers assoc mode {:error (ex-message ex)})
            (note "EXPERIMENT " mode ": " (ex-message ex))))))
    @answers))

;; ---------------------------------------------------------------- 2. the relay story
(defn story! []
  (note "\n=== 2. the relay assembly: each candidate on its own fresh store; handoffs relayed unchanged")
  (let [dir (str (fs/create-dirs (fs/path work "relay")))
        env (fixtures-env dir)
        source (spec-file! dir)
        cs (into {} (for [s (sort (keys contexts))]
                      [s (start! s (str (fs/path dir (str s "-events.sqlite"))) "relay" :env env :dir dir)]))
        out (atom {})]
    (try
      ;; Specification stamps; Cycle preparation pins; Grader lifecycle writes and moves a golden
      (let [stamped (cmd! cs "01" "specification/stamp-slice"
                          {"slice" "09" "source" source "unstamped" unstamped "empty-title-lines" []})
            stamped (filterv #(= "specification/scenario-stamped" (get % "type")) stamped)
            item-ids (mapv #(get % "item-id") stamped)
            _ (cmd! cs "05" "cycle-preparation/pin-source-cut" {"cut-id" "K-1" "commit" commit "tree-digest" tree})
            _ (cmd! cs "03" "grader-lifecycle/write-instrument-golden"
                    {"golden-id" "checklist" "scenario" "@item-09-001" "scenario-digest" "s-1"
                     "path" "harness/oracle/checklist.golden.json" "digest" "g-1"})
            _ (cmd! cs "03" "grader-lifecycle/move-instrument-golden"
                    {"golden-id" "checklist" "digest" "g-2" "reason" "instrument-behavior-changed"
                     "additions-only" false "note" "the assembly gate's move" "scenario-digest" "s-1"})
            goldens (get (result (cs "03") "grader-lifecycle/instrument-goldens" {}) "goldens")
            gdigest (goldens-digest goldens)
            ;; Measurement derives the checklist of the pinned cut from the stamped items
            items (vec (for [e stamped]
                         {"item-id" (get e "item-id") "scenario-digest" (str "s-" (get e "item-id")) "rule" nil
                          "source" (str (get e "source") ":" (get e "line")) "title" (get e "title")}))
            checklist (first (cmd! cs "02" "measurement/derive-checklist"
                                   {"cut-id" "K-1" "selection-id" "whole-plan" "items" items "unstamped" [] "skipped-slices" []}))
            ;; the Regeneration cycle: self-check, clean room, candidate, gate attempt passed
            _ (gate-cycle! cs "C-1" "K-1" gdigest "sha256:cand-1" day)
            gated (progress cs "C-1")
            ;; Measurement: verdicts, score, scoreboard; handed back to the Regeneration cycle
            {:keys [filed scoreboard]} (measure! cs "C-1" "sha256:cand-1" item-ids)
            concluded (progress cs "C-1")
            to-conclude (get (result (cs "04") "regeneration-cycle/cycles-to-conclude" {}) "cycle-ids")
            pinned (first (get (result (cs "05") "cycle-preparation/source-cuts" {}) "cuts"))
            stamped-q (mapv #(get % "item-id") (get-in (result (cs "01") "specification/stamped-items" {"slice" "09"}) ["slices" 0 "items"]))
            gap (first (filter #(= "regeneration-cycle/gate-attempt-passed" (:type %)) @emitted))]
        (swap! out assoc :happy
               {:item-ids item-ids :stamped-q stamped-q :checklist checklist :pinned pinned :goldens goldens
                :gdigest gdigest :gated gated :filed filed :scoreboard scoreboard :concluded concluded
                :to-conclude to-conclude :gap (:event gap)}))
      (catch Exception ex
        (swap! out assoc :happy-error (ex-message ex))
        (note "STORY STOPPED: " (ex-message ex))))
    ;; 3b. the round-4 open question: the stage clock fails C-2 at the score stage, then Measurement files anyway
    (when-not (:happy-error @out)
      (note "\n=== 3b. the round-4 open question: C-2 failed at the score stage by the stage clock, measured anyway")
      (try
        (let [gdigest (get-in @out [:happy :gdigest])
              item-ids (get-in @out [:happy :item-ids])
              _ (gate-cycle! cs "C-2" "K-1" gdigest "sha256:cand-2" 1)
              _ (note "  waiting for the stage clock (every 30 s) to time out C-2's 1-second score stage ...")
              failed (loop [n 0]
                       (let [c (progress cs "C-2")]
                         (if (or (not= "running" (get c "status")) (>= n 16)) c
                             (do (Thread/sleep 5000) (recur (inc n))))))
              how (if (= "failed" (get failed "status")) "the stage clock"
                      (do (note "  the stage clock did not fail C-2 within 80 s; the fail-cycle method instead")
                          (call (cs "04") "regeneration-cycle/fail-cycle"
                                {"cycle-id" "C-2" "stage" "score" "reason" "budget" "detail" "assembly gate"})
                          "the fail-cycle method"))
              failed (progress cs "C-2")
              n-before (count @handed)
              {:keys [filed scoreboard]} (measure! cs "C-2" "sha256:cand-2" item-ids)
              after (progress cs "C-2")
              m2 (get (result (cs "02") "measurement/cycle-measurement" {"cycle-id" "C-2"}) "measurement")]
          (swap! out assoc :open {:how how :failed failed :after after :filed filed :scoreboard scoreboard
                                  :m2 m2 :handins (subvec @handed n-before)}))
        (catch Exception ex
          (swap! out assoc :open-error (ex-message ex))
          (note "OPEN QUESTION PROBE STOPPED: " (ex-message ex)))))
    (stop-all!)
    @out))

;; ---------------------------------------------------------------- the verdicts
(defn judge! [{:keys [happy happy-error open open-error]} exp]
  (note "\n=== 3. checks")
  (note "consumers by the event models: " (json/generate-string consumers))
  (check! :contract "every handoff of every producer has a consumer in the event models"
          (and (seq handoffs) (every? consumers (keys handoffs)))
          (str (count handoffs) " handoffs; without a consumer: " (vec (sort (remove consumers (keys handoffs))))))
  (let [refused (filter :error @handed)
        echo-bad (remove #(or (:error %) (= (:echo %) (:event %))) @handed)]
    (check! :contract "every produced handoff is accepted by every consumer (no -32602)"
            (and (seq @handed) (empty? refused))
            (if (seq refused)
              (str/join "; " (for [r refused] (str (:type r) " -> " (:to r) " " (clip (:error r) 200))))
              (str (count @handed) " hand-ins, " (count (distinct (map :type @handed))) " handoff types, none refused")))
    (check! :contract "each consumer recorded the handoff exactly as given (echo equals the producer's event)"
            (and (seq @handed) (empty? echo-bad))
            (if (seq echo-bad) (str/join "; " (for [r echo-bad] (str (:type r) " -> " (:to r) " echoed " (clip (:echo r) 200))))
                (str (count @handed) " echoes equal"))))
  (let [per (group-by :to @handed)
        bad (for [[ctx hs] per
                  :let [want (for [e @emitted :when (some #{ctx} (consumers (:type e)))] (:event e))
                        got (map :echo hs)]
                  :when (not= want got)] ctx)]
    (check! :contract "order preserved: each consumer took its handoffs in the producers' order"
            (and (seq per) (empty? bad))
            (str (str/join ", " (for [[ctx hs] per] (str ctx " " (str/join ">" (map #(last (str/split (:type %) #"/")) hs)))))
                 (when (seq bad) (str "; out of order at " (vec bad))))))
  (if happy-error
    (check! :happy "the story ran end to end" false happy-error)
    (let [{:keys [item-ids stamped-q checklist pinned goldens gdigest gated filed scoreboard concluded to-conclude gap]} happy
          cl-ids (mapv #(get % "item-id") (get checklist "items"))
          filed-ids (mapv #(get % "item-id") (get filed "items"))]
      (check! :happy "the cycle ends passed with its measurement filed and its scoreboard reported"
              (and (= "passed" (get concluded "status")) (= "result" (get concluded "stage")) filed scoreboard (= [] to-conclude))
              (str "C-1 " (get concluded "status") " at " (get concluded "stage") "; measurement-filed "
                   (when filed (str (get filed "passes") "/" (get filed "total"))) ", scoreboard "
                   (when scoreboard (get scoreboard "rate")) ", cycles-to-conclude " (json/generate-string to-conclude)))
      (check! :happy "the cut id agrees across contexts"
              (apply = "K-1" (map #(get % "cut-id") [pinned checklist gated gap filed concluded]))
              (str "05 " (get pinned "cut-id") ", 02 checklist " (get checklist "cut-id") ", 04 " (get gated "cut-id")
                   ", gate-attempt-passed " (get gap "cut-id") ", measurement-filed " (get filed "cut-id")))
      (check! :happy "the candidate digest agrees across contexts"
              (apply = "sha256:cand-1" (map #(get % "candidate-digest") [gated gap filed concluded]))
              (str "04 " (get gated "candidate-digest") ", gate-attempt-passed " (get gap "candidate-digest")
                   ", measurement-filed " (get filed "candidate-digest") ", concluded " (get concluded "candidate-digest")))
      (check! :happy "the goldens digest agrees: 03's current goldens, by seam.md's formula, open 04's cycle"
              (and (= ["g-2"] (map #(get % "digest") goldens)) (some? gated))
              (str "03 goldens " (json/generate-string (map #(select-keys % ["golden-id" "digest"]) goldens))
                   " -> " gdigest ", accepted by 04's self-checks"))
      (check! :happy "the checklist item ids agree: stamped (01) = checklist (02) = measured (02) = scoreboard total"
              (and (= item-ids stamped-q cl-ids filed-ids) (= 2 (count item-ids)) (= (count item-ids) (get scoreboard "total")))
              (str "01 " item-ids " (query " stamped-q "), checklist " cl-ids ", measurement-filed " filed-ids
                   ", scoreboard total " (get scoreboard "total")))))
  (note "\n=== findings")
  (doseq [[mode a] exp] (note "EXPERIMENT " mode ": " (json/generate-string a)))
  (cond
    open-error (note "OPEN QUESTION: probe stopped: " open-error)
    open (let [{:keys [how failed after filed scoreboard m2 handins]} open]
           (note "OPEN QUESTION (round 4 §3), C-2 failed at the score stage by " how ":")
           (note "  regeneration-cycle: " (get after "status") " at " (get after "stage") ", failure "
                 (json/generate-string (get after "failure")) " (unchanged by the hand-ins: " (= failed after) ")")
           (note "  measurement: cycle-measurement status " (get m2 "status") ", " (get filed "passes") "/" (get filed "total")
                 " filed; scoreboard rate " (get scoreboard "rate") ", previous cycle " (get scoreboard "previous-cycle-id"))
           (note "  hand-ins to regeneration-cycle: " (str/join ", " (for [h handins] (str (:type h) (if (:error h) " REFUSED" " accepted, ignored by the fold")))))
           (note "  => " (if (and (= "failed" (get after "status")) (= "filed" (get m2 "status")))
                            "they disagree: the Regeneration cycle says C-2 failed with no score; Measurement holds a filed, reported measurement of C-2 (a baseline for the next cycle)"
                            "they do not disagree as expected; see above")))))

(defn -main []
  (note "assembly gate: work " work)
  (doseq [s (sort (keys contexts))
          :let [sh (fs/path (rooms s) "harness-impl" (str s "-" (contexts s)) "start.sh")]]
    (note "  " s " " (contexts s) ": " sh (if (fs/executable? sh) "" "  MISSING"))
    (when-not (fs/executable? sh) (println "FATAL: no candidate at" (str sh)) (System/exit 1)))
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop-all!))
  (let [exp (experiment!)
        out (story!)]
    (judge! out exp)
    (let [failed (remove :ok @checks)]
      (note "\n=== assembly gate " (if (seq failed) "FAILED" "OK") ": "
            (count (filter :ok @checks)) "/" (count @checks) " checks pass")
      (System/exit (if (seq failed) 1 0)))))

(-main)
