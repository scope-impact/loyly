(ns loyly.grading
  "The grading step of harness/v2/score.sh (step 4), the legacy README's step 5 as is: grade each
   checklist item against the running app, however you grade, then file. Two ORC workflows, on
   ONE Grain SQLite store that keeps every grading run, of every slice and cycle: the grading
   history, LOYLY_GRADING_STORE (default history.sqlite beside this file, not committed).

     score        the parent, a sequence:
                    load-checklist   the cycle's checklist.json, each item with its scenario's
                                     text from the context's spec.feature
                    grade            a fallback: delegate to open-grader; if that does not
                                     finish, zero verdicts, noted
                    check-verdicts   the rules below; CYCLE/verdicts.json for score.clj,
                                     CYCLE/surprises.json
     open-grader  the child, run by the delegate with its own blackboard: a sequence whose core
                  is one repl-researcher, ORC's agentic node, with the open-ended instruction
                  prompt.md. It decides itself how to grade: tool calls (loyly.seam: read the
                  spec, start the candidate, JSON-RPC over its stdio, stop it) inline or in
                  :code nodes it emits, and :llm nodes only where it wants a model's reading.

   Every model call, the researcher's turns and any :llm node it emits, goes to the provider :pi
   (loyly.pi-provider: one non-interactive pi session per call). The model is
   LOYLY_GRADER_MODEL (default pi-default: pi's own default).

   Each run's input blackboard carries slice, cycle, context, the candidate's start.sh and the
   prompt's digest, so every run in the history is findable (loyly.grading-history lists them).
   CYCLE/grading.json points into the history: store, sheet ids, the parent's and the delegated
   child's tick ids, model, provider, prompt digest.

   Nothing here decides a score. An entry that is not {id, verdict pass|fail, evidence string},
   an id not on the checklist, and an id filed more than once are dropped with a note; an item
   left without a verdict is unreached, and score.clj fails it and keeps it in the denominator.
   No verdicts at all is zero verdicts: score.clj refuses to file.

   Usage, from harness/v2/grading: clojure -M:jvm -m loyly.grading NN CONTEXT SPEC_DIR START ROOM CYCLE"
  (:require [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.pi-provider :as pi]
            [loyly.seam :as seam])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.security MessageDigest)))

(def default-timeout-s 1800)
(def default-max-iterations 40)
(def tenant-id #uuid "00000000-0000-0000-0000-0000000000a5")

(defn- note! [& xs] (binding [*out* *err*] (apply println "grading:" xs)))

(defn sha256 [^String s]
  (str "sha256:" (apply str (map #(format "%02x" %)
                                 (.digest (MessageDigest/getInstance "SHA-256")
                                          (.getBytes s "UTF-8"))))))

(defn- checklist-items [path]
  (get (json/read-str (slurp path :encoding "UTF-8")) "items"))

;; ---- load-checklist

(defn scenario-text
  "The scenario starting at 1-based LINE of FEATURE-LINES (its tag line or its Scenario line), up
   to the next tag, Scenario, Rule or Feature line."
  [feature-lines line]
  (let [head? #(re-find #"^\s*(@|Scenario|Rule:|Feature:|Background:)" %)
        [first-lines rest-lines] (split-with #(re-find #"^\s*(@|Scenario)" %)
                                             (drop (dec line) feature-lines))]
    (str/trim (str/join "\n" (concat first-lines (take-while (complement head?) rest-lines))))))

(defn load-checklist [{:keys [inputs]}]
  (let [{:keys [checklist spec-dir]} inputs
        feature (str/split-lines (slurp (io/file spec-dir "spec.feature") :encoding "UTF-8"))
        items (mapv (fn [it]
                      (let [line (some->> (get it "source") (re-find #":(\d+)$") second parse-long)]
                        {:id (get it "id") :name (get it "name")
                         :scenario (if line (scenario-text feature line) "")}))
                    (checklist-items checklist))]
    {:items items :ids (mapv :id items)}))

;; ---- grade: the fallback when the open grader does not finish

(defn grader-unfinished [_]
  (note! "the open grader did not finish: zero verdicts")
  {:verdicts [] :surprises [{:id nil :note "the open grader did not finish; no verdicts"}]})

;; ---- check-verdicts

(defn validate
  "[verdicts notes]: the entries of RAW (the verdicts as JSON text, or nil) that score.clj may
   file, in checklist order, and a note for everything dropped."
  [raw ids]
  (let [parsed (when raw (try (json/read-str raw) (catch Exception e {::bad (.getMessage e)})))]
    (cond
      (nil? raw) [[] ["no verdicts: zero verdicts"]]
      (not (vector? parsed)) [[] [(str "the verdicts are not a list"
                                       (when-let [b (and (map? parsed) (::bad parsed))] (str " (" b ")"))
                                       ": zero verdicts")]]
      :else
      (let [ok? #(and (map? %) (string? (get % "id")) (#{"pass" "fail"} (get % "verdict"))
                      (string? (get % "evidence")))
            bad (remove ok? parsed)
            good (filter ok? parsed)
            known (set ids)
            unknown (remove (comp known #(get % "id")) good)
            counts (frequencies (map #(get % "id") good))
            twice (sort (keep (fn [[id n]] (when (and (known id) (> n 1)) id)) counts))
            by-id (into {} (keep #(when (and (known (get % "id")) (= 1 (counts (get % "id"))))
                                    [(get % "id") (select-keys % ["id" "verdict" "evidence"])]))
                        good)]
        [(vec (keep by-id ids))
         (cond-> []
           (empty? parsed) (conj "an empty list of verdicts: zero verdicts")
           (seq bad) (conj (str "dropped " (count bad) " entr" (if (= 1 (count bad)) "y" "ies")
                                " not shaped {id, verdict pass|fail, evidence string}: "
                                (str/join "; " (map #(let [s (json/write-str %)] (subs s 0 (min 120 (count s)))) bad))))
           (seq unknown) (conj (str "dropped ids not on the checklist: "
                                    (str/join ", " (map #(get % "id") unknown))))
           (seq twice) (conj (str "dropped ids filed more than once: " (str/join ", " twice))))]))))

(defn- ->json-text [v]
  (cond (nil? v) nil
        (string? v) v
        :else (json/write-str v :key-fn #(if (keyword? %) (name %) (str %)))))

(defn check-verdicts [{:keys [inputs]}]
  (let [{:keys [verdicts surprises ids cycle]} inputs
        [kept notes] (validate (->json-text verdicts) ids)
        missing (remove (set (map #(get % "id") kept)) ids)
        notes (cond-> notes
                (and (seq kept) (seq missing))
                (conj (str "no verdict for " (str/join ", " missing) ": unreached")))]
    (run! note! notes)
    (spit (io/file cycle "verdicts.json") (json/write-str kept) :encoding "UTF-8")
    (when (seq surprises)
      (spit (io/file cycle "surprises.json") (->json-text surprises) :encoding "UTF-8"))
    {:kept (json/write-str kept) :notes (vec notes)}))

;; ---- the workflows

(def item-schema [:map [:id :string] [:name :string] [:scenario :string]])
(def verdict-schema [:map [:id :string] [:verdict :string] [:evidence :string]])
(def surprise-schema [:map [:id [:maybe :string]] [:note :string]])

(defn open-grader [{:keys [model instruction max-iterations]}]
  (orc/workflow "open-grader"
    (orc/blackboard {:slice :string :context :string :items [:vector item-schema]
                     :verdicts [:vector verdict-schema] :surprises [:vector surprise-schema]})
    (orc/sequence "open-grader"
      (orc/repl-researcher "grade"
        :model model
        :instruction instruction
        :reads [:slice :context :items]
        :writes [:verdicts :surprises]
        :mcp-tools seam/tool-names
        :max-iterations max-iterations
        :rlm {:sub-model model}
        :options {:optional-writes [:surprises]
                  :tool-arg-specs seam/tool-arg-specs}))))

(defn score [open-grader-sheet-id timeout-ms]
  (orc/workflow "score"
    (orc/blackboard {:slice :string :context :string :cycle :string :start :string
                     :spec-dir :string :checklist :string :prompt-digest :string
                     :model :string :provider :string
                     :items [:vector item-schema] :ids [:vector :string]
                     :verdicts [:vector verdict-schema] :surprises [:vector surprise-schema]
                     :kept :string :notes [:vector :string]})
    (orc/sequence "score"
      (orc/code "load-checklist" :fn "loyly.grading/load-checklist"
        :reads [:checklist :spec-dir]
        :writes [:items :ids])
      (orc/fallback "grade"
        (orc/delegate "open-grader"
          :target-sheet-id open-grader-sheet-id
          :reads [:slice :context :items]
          :writes [:verdicts :surprises]
          :timeout-ms timeout-ms)
        (orc/code "grader-unfinished" :fn "loyly.grading/grader-unfinished"
          :reads []
          :writes [:verdicts :surprises]))
      (orc/code "check-verdicts" :fn "loyly.grading/check-verdicts"
        :reads [:verdicts :surprises :ids :cycle]
        :writes [:kept :notes]))))

;; ---- the store: the grading history

(defn start-store [store-file cache-dir]
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :sqlite :database-file (str store-file)}
                         :event-pubsub ps :logger nil})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir (str cache-dir) :db-name "grading"}))
        base {:event-store store :cache cache :tenant-id tenant-id :event-pubsub ps
              :command-registry (cp/global-command-registry)
              :query-registry (qp/global-query-registry)
              :llm-provider :pi
              :call-tool-fn seam/call-tool}
        ;; every Grain todo processor, ORC's included, starts with its :processor-name, so
        ;; Grain checkpoints it and writes each output once
        processors (mapv (fn [[proc-name {:keys [handler-fn topics]}]]
                           (tp/start {:event-pubsub ps :topics topics :handler-fn handler-fn
                                      :context base :processor-name proc-name}))
                         @tp/processor-registry*)]
    (assoc base :processors processors)))

(defn stop-store [{:keys [processors event-pubsub event-store cache]}]
  (run! tp/stop processors)
  (pubsub/stop event-pubsub)
  (es/stop event-store)
  (kv/stop cache))

(defn read-events [{:keys [event-store]} q]
  (into [] (es/read event-store (assoc q :tenant-id tenant-id))))

(defn- await-trace [ctx tick-id]
  (let [deadline (+ (System/currentTimeMillis) 30000)]
    (while (and (empty? (read-events ctx {:types #{:sheet/execution-traced} :tags #{[:tick tick-id]}}))
                (< (System/currentTimeMillis) deadline))
      (Thread/sleep 20))))

(defn- child-ticks [ctx tick-id]
  (mapv :tick-id (read-events ctx {:types #{:sheet/tree-tick-started}
                                   :tags #{[:parent-tick tick-id]}})))

(defn store-path []
  (str (.getCanonicalFile (io/file (or (not-empty (System/getenv "LOYLY_GRADING_STORE"))
                                       "history.sqlite")))))

(defn -main [slice context spec-dir start _room cycle]
  (let [timeout-s (parse-long (or (System/getenv "LOYLY_GRADER_TIMEOUT") (str default-timeout-s)))
        model (or (not-empty (System/getenv "LOYLY_GRADER_MODEL")) pi/default-model)
        max-iterations (parse-long (or (System/getenv "LOYLY_GRADER_MAX_ITERATIONS")
                                       (str default-max-iterations)))
        instruction (slurp "prompt.md" :encoding "UTF-8")
        digest (sha256 instruction)
        store (store-path)
        cache-dir (.toFile (Files/createTempDirectory "grading-cache-" (make-array FileAttribute 0)))
        work-dir (.toFile (Files/createTempDirectory (str "grade-" slice "-") (make-array FileAttribute 0)))
        _ (pi/register!)
        _ (seam/configure! spec-dir start work-dir)
        _ (note! "history" store "; model" model "via pi; work dir" (str work-dir))
        ctx (start-store store cache-dir)
        child-id (orc/build-workflow! ctx (open-grader {:model model :instruction instruction
                                                        :max-iterations max-iterations}))
        parent-id (orc/build-workflow! ctx (score child-id (* 1000 timeout-s)))
        tick-id (random-uuid)
        result (try
                 (orc/execute ctx parent-id
                              {:slice slice :context context :cycle cycle :start start
                               :spec-dir spec-dir :checklist (str (io/file cycle "checklist.json"))
                               :prompt-digest digest :model model :provider "pi"}
                              :timeout-ms (* 1000 (+ 300 timeout-s))
                              :tick-id tick-id)
                 (finally (seam/stop-all!)))]
    (await-trace ctx tick-id)
    (let [children (child-ticks ctx tick-id)
          out (:outputs result)
          v #(or (get out %) (get out (name %)))]
      (spit (io/file cycle "grading.json")
            (json/write-str
             {:store (.getName (io/file store))
              :workflows {:score (str parent-id) :open-grader (str child-id)}
              :ticks {:score (str tick-id) :open-grader (mapv str children)}
              :status (name (:status result))
              :model model :provider "pi" :prompt-digest digest
              :verdicts-kept (count (some-> (v :kept) json/read-str))}
             :escape-slash false)
            :encoding "UTF-8")
      (stop-store ctx)
      (when-not (.isFile (io/file cycle "verdicts.json"))
        (spit (io/file cycle "verdicts.json") "[]"))
      (note! "prompt" digest "; score tick" (str tick-id) "; open-grader tick"
             (str/join "," children) "; verdicts kept:" (count (some-> (v :kept) json/read-str)))
      (shutdown-agents)
      (if (= :success (:status result))
        (System/exit 0)
        (do (note! "the score workflow did not succeed:" (name (:status result)) (:error result))
            (System/exit 1))))))
