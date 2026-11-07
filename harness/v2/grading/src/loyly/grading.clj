(ns loyly.grading
  "The grading step of harness/v2/score.sh (step 4), the legacy README's step 5 as is: grade each
   checklist item against the running app, however you grade, then file. Here the grader is an
   agent, the command LOYLY_GRADER_CMD, handed the pinned open-ended prompt prompt.md, and the
   step runs as one ORC workflow on a Grain SQLite store, CYCLE/grading.sqlite. The workflow is a
   sequence of three code steps, each result a sheet value in that store:

     render-prompt  prompt.md filled for the slice (item ids from CYCLE/checklist.json); its
                    sha256 recorded; written to CYCLE/grader-prompt.md
     run-grader     LOYLY_GRADER_CMD (bash -c), the prompt on stdin, its working directory a
                    fresh scratch directory, killed after LOYLY_GRADER_TIMEOUT seconds; stdout
                    and stderr kept in CYCLE/grader-stdout.log and grader-stderr.log
     read-verdicts  the grader's verdicts.json, validated, written to CYCLE/verdicts.json for
                    score.clj; its surprises.json, if any, copied to CYCLE/surprises.json

   Nothing here decides a score. An entry that is not {id, verdict pass|fail, evidence string},
   an id not on the checklist, and an id filed more than once are dropped with a note; an item
   left without a verdict is unreached, and score.clj fails it and keeps it in the denominator.
   No file, or one that is not a JSON list, is zero verdicts: score.clj refuses to file.

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
            [clojure.string :as str])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.security MessageDigest)
           (java.util.concurrent TimeUnit)))

(def default-command "claude -p --permission-mode bypassPermissions")
(def default-timeout-s 1800)
(def tenant-id #uuid "00000000-0000-0000-0000-0000000000a5")

(defn- note! [& xs] (binding [*out* *err*] (apply println "grading:" xs)))

(defn sha256 [^String s]
  (str "sha256:" (apply str (map #(format "%02x" %)
                                 (.digest (MessageDigest/getInstance "SHA-256")
                                          (.getBytes s "UTF-8"))))))

(defn- checklist-items [path]
  (get (json/read-str (slurp path :encoding "UTF-8")) "items"))

;; ---- 1. render the pinned prompt for the slice

(defn render [template {:keys [slice context spec-dir start room checklist]}]
  (let [items (str/join "\n" (map #(str "- `" (get % "id") "`: " (get % "name"))
                                  (checklist-items checklist)))]
    (reduce (fn [t [k v]] (str/replace t (str "{{" k "}}") v))
            template
            {"SLICE" slice "CONTEXT" context "SPEC_DIR" spec-dir "START" start "ROOM" room
             "ITEMS" items})))

(defn render-prompt [{:keys [inputs]}]
  (let [prompt (render (slurp (:template inputs) :encoding "UTF-8") inputs)]
    (spit (io/file (:cycle inputs) "grader-prompt.md") prompt :encoding "UTF-8")
    {:prompt prompt :prompt-digest (sha256 prompt)}))

;; ---- 2. run the grader command

(defn run-grader [{:keys [inputs]}]
  (let [{:keys [prompt command timeout-s cycle slice]} inputs
        dir (.toFile (Files/createTempDirectory (str "grade-" slice "-") (make-array FileAttribute 0)))
        pb (doto (ProcessBuilder. ["bash" "-c" command])
             (.directory dir)
             (.redirectOutput (io/file cycle "grader-stdout.log"))
             (.redirectError (io/file cycle "grader-stderr.log")))
        _ (note! "grader" (pr-str command) "in" (str dir) "for at most" timeout-s "s")
        p (.start pb)]
    (with-open [w (io/writer (.getOutputStream p) :encoding "UTF-8")] (.write w ^String prompt))
    (let [done? (.waitFor p (parse-long timeout-s) TimeUnit/SECONDS)]
      (when-not done?
        (run! #(.destroyForcibly ^java.lang.ProcessHandle %) (iterator-seq (.iterator (.descendants (.toHandle p)))))
        (.destroyForcibly p)
        (.waitFor p))
      (let [exit (if done? (str (.exitValue p)) "timeout")]
        (note! "grader exited:" exit)
        {:grader-dir (str dir) :grader-exit exit}))))

;; ---- 3. read and validate the verdicts

(defn validate
  "[verdicts notes]: the entries of RAW (the grader's verdicts.json text, or nil) that score.clj
   may file, in checklist order, and a note for everything dropped."
  [raw ids]
  (let [parsed (when raw (try (json/read-str raw) (catch Exception e {::bad (.getMessage e)})))]
    (cond
      (nil? raw) [[] ["no verdicts.json written: zero verdicts"]]
      (not (vector? parsed)) [[] [(str "verdicts.json is not a JSON list"
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
           (seq bad) (conj (str "dropped " (count bad) " entr" (if (= 1 (count bad)) "y" "ies")
                                " not shaped {id, verdict pass|fail, evidence string}: "
                                (str/join "; " (map #(let [s (json/write-str %)] (subs s 0 (min 120 (count s)))) bad))))
           (seq unknown) (conj (str "dropped ids not on the checklist: "
                                    (str/join ", " (map #(get % "id") unknown))))
           (seq twice) (conj (str "dropped ids filed more than once: " (str/join ", " twice))))]))))

(defn read-verdicts [{:keys [inputs]}]
  (let [{:keys [grader-dir checklist cycle]} inputs
        f (io/file grader-dir "verdicts.json")
        ids (mapv #(get % "id") (checklist-items checklist))
        [verdicts notes] (validate (when (.isFile f) (slurp f :encoding "UTF-8")) ids)
        missing (remove (set (map #(get % "id") verdicts)) ids)
        notes (cond-> notes
                (and (seq verdicts) (seq missing))
                (conj (str "no verdict for " (str/join ", " missing) ": unreached")))
        s (io/file grader-dir "surprises.json")]
    (run! note! notes)
    (spit (io/file cycle "verdicts.json") (json/write-str verdicts) :encoding "UTF-8")
    (when (.isFile s) (io/copy s (io/file cycle "surprises.json")))
    {:verdicts (json/write-str verdicts) :notes (json/write-str notes)}))

;; ---- the workflow

(def workflow
  (orc/workflow "grading"
    (orc/blackboard {:slice :string :context :string :spec-dir :string :start :string
                     :room :string :cycle :string :checklist :string :template :string
                     :command :string :timeout-s :string
                     :prompt :string :prompt-digest :string
                     :grader-dir :string :grader-exit :string
                     :verdicts :string :notes :string})
    (orc/sequence "grade"
      (orc/code "render-prompt" :fn "loyly.grading/render-prompt"
        :reads [:slice :context :spec-dir :start :room :checklist :template :cycle]
        :writes [:prompt :prompt-digest])
      (orc/code "run-grader" :fn "loyly.grading/run-grader"
        :reads [:prompt :command :timeout-s :cycle :slice]
        :writes [:grader-dir :grader-exit])
      (orc/code "read-verdicts" :fn "loyly.grading/read-verdicts"
        :reads [:grader-dir :checklist :cycle]
        :writes [:verdicts :notes]))))

(defn- start-store [store-file cache-dir]
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :sqlite :database-file (str store-file)}
                         :event-pubsub ps :logger nil})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir (str cache-dir) :db-name "grading"}))
        base {:event-store store :cache cache :tenant-id tenant-id :event-pubsub ps
              :command-registry (cp/global-command-registry)
              :query-registry (qp/global-query-registry)}
        ;; every Grain todo processor, ORC's included, starts with its :processor-name, so
        ;; Grain checkpoints it and writes each output once
        processors (mapv (fn [[proc-name {:keys [handler-fn topics]}]]
                           (tp/start {:event-pubsub ps :topics topics :handler-fn handler-fn
                                      :context base :processor-name proc-name}))
                         @tp/processor-registry*)]
    (assoc base :processors processors)))

(defn- stop-store [{:keys [processors event-pubsub event-store cache]}]
  (run! tp/stop processors)
  (pubsub/stop event-pubsub)
  (es/stop event-store)
  (kv/stop cache))

(defn- await-trace [{:keys [event-store]} tick-id]
  (let [deadline (+ (System/currentTimeMillis) 30000)]
    (while (and (empty? (into [] (es/read event-store {:tenant-id tenant-id
                                                        :types #{:sheet/execution-traced}
                                                        :tags #{[:tick tick-id]}})))
                (< (System/currentTimeMillis) deadline))
      (Thread/sleep 20))))

(defn -main [slice context spec-dir start room cycle]
  (let [timeout-s (or (System/getenv "LOYLY_GRADER_TIMEOUT") (str default-timeout-s))
        cache-dir (.toFile (Files/createTempDirectory "grading-cache-" (make-array FileAttribute 0)))
        ctx (start-store (io/file cycle "grading.sqlite") cache-dir)
        sheet-id (orc/build-workflow! ctx workflow)
        tick-id (random-uuid)
        result (orc/execute ctx sheet-id
                            {:slice slice :context context :spec-dir spec-dir :start start
                             :room room :cycle cycle
                             :checklist (str (io/file cycle "checklist.json"))
                             :template (str (.getCanonicalFile (io/file "prompt.md")))
                             :command (or (System/getenv "LOYLY_GRADER_CMD") default-command)
                             :timeout-s timeout-s}
                            :timeout-ms (* 1000 (+ 300 (parse-long timeout-s)))
                            :tick-id tick-id)]
    (await-trace ctx tick-id)
    (stop-store ctx)
    (if (= :success (:status result))
      (let [out (:outputs result)
            v #(or (get out %) (get out (name %)))]
        (note! "prompt" (v :prompt-digest) "; verdicts kept:"
               (count (json/read-str (v :verdicts))))
        (shutdown-agents)
        (System/exit 0))
      (do (note! "the grading workflow did not succeed:" (name (:status result)) (:error result))
          (shutdown-agents)
          (System/exit 1)))))
