;; improve.clj STATE-ROOT REPO CYCLE OUT-DIR: one GEPA reflective mutation of a builder
;; instruction's operator-appended part, as a proposal for a person (../bin/improve-instruction.sh).
;;
;; CYCLE is NN-cMM (a spec-v2 cycle's run-state name). Read, all after the cycle is done:
;;   STATE/prompts/CYCLE/instruction.md, system.md, prompt.md: the instruction as handed, and the
;;     two files harness/v2/prompt.clj generated; the appended part is the instruction's lines that
;;     neither generated file holds (the operator's paragraphs). Only that part is mutated.
;;   STATE/builders/CYCLE*/launch-*/launch.txt: each builder launch's wall clock and exit
;;   STATE/builders/CYCLE*/steers.jsonl, stops.jsonl: the supervisor's steers and stops, verbatim
;;   STATE/logs/gate-cMM*-sliceNN.log: each gate attempt's verdict and FAIL/FATAL lines
;;   REPO/harness/v2/cycles/NN/MM/scores.json: the final score, as an aggregate only (passes of
;;     the total). Per-item verdicts and their evidence are never read: score evidence never
;;     becomes builder input, and this proposal may become builder input once approved.
;; Each builder attempt becomes one GEPA ReflectiveExample (Inputs, Generated Outputs, Feedback;
;; gepa.core.reflection), formatted by GEPA and handed to gepa.core.proposer/propose-new-instruction
;; with a prompt -> response function that runs one pi turn (../builder-host/turn.ts) on pi's own
;; login (LOYLY_PI_MODEL). GEPA's optimize! loop is not run: evaluating a candidate instruction is
;; a whole regeneration cycle, so the next cycle, after a person approves, is the evaluation.
;; Writes OUT-DIR/proposal.md, proposed-appended.md, side-info.md, prompt.md and the turn's store.
;; Nothing is applied.
(require '[ai.obney.orc.gepa.core.proposer :as proposer]
         '[ai.obney.orc.gepa.core.reflection :as reflection]
         '[clojure.data.json :as json]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as sh]
         '[clojure.string :as str])

(defn die [& xs] (binding [*out* *err*] (println (apply str "improve: " xs))) (System/exit 2))
(defn slurp? [f] (let [f (io/file f)] (when (.isFile f) (slurp f))))
(defn kv [text] (into {} (keep #(let [[k v] (str/split % #"=" 2)] (when v [k v]))) (str/split-lines (or text ""))))
(defn jsonl [f] (when-let [t (slurp? f)] (mapv #(json/read-str % :key-fn keyword) (remove str/blank? (str/split-lines t)))))
(defn minutes [a b]
  (try (format "%.0f min" (/ (- (.toEpochMilli (java.time.Instant/parse b)) (.toEpochMilli (java.time.Instant/parse a))) 60000.0))
       (catch Exception _ "?")))

(let [[state repo cycle out] *command-line-args*
      _ (when-not (and state repo cycle out) (die "usage: improve.clj STATE-ROOT REPO CYCLE OUT-DIR"))
      [_ nn mm] (or (re-matches #"(\d\d)-c(\d+)" cycle) (die "CYCLE is NN-cMM, not " cycle))
      pdir (io/file state "prompts" cycle)
      instruction (or (slurp? (io/file pdir "instruction.md")) (die "no " pdir "/instruction.md"))
      generated (set (map str/trim (str/split-lines (str (slurp? (io/file pdir "system.md")) "\n" (slurp? (io/file pdir "prompt.md"))))))
      appended (->> (str/split-lines instruction) (map str/trim) (remove str/blank?) (remove generated) (str/join "\n\n"))
      _ (when (str/blank? appended) (die "no appended paragraphs in " pdir "/instruction.md (nothing to mutate)"))
      bdirs (->> (.listFiles (io/file state "builders"))
                 (filter #(re-matches (re-pattern (str (java.util.regex.Pattern/quote cycle) "(-a\\d+)?")) (.getName %)))
                 (sort-by #(.getName %)))
      launches (for [b bdirs
                     l (sort-by #(.getName %) (filter #(re-matches #"launch-\d+" (.getName %)) (or (seq (.listFiles b)) [])))
                     :let [info (kv (slurp? (io/file l "launch.txt")))] :when (seq info)]
                 {:builder (.getName b) :launch (.getName l) :info info})
      steers (vec (mapcat #(jsonl (io/file % "steers.jsonl")) bdirs))
      stops (vec (mapcat #(jsonl (io/file % "stops.jsonl")) bdirs))
      gates (->> (.listFiles (io/file state "logs"))
                 (filter #(re-matches (re-pattern (str "gate-c" (Long/parseLong mm) "(-a\\d+)?-slice" nn "\\.log")) (.getName %)))
                 (sort-by #(Long/parseLong (or (second (re-find #"-a(\d+)-" (.getName %))) "1")))
                 (mapv (fn [f] (let [lines (str/split-lines (slurp f))]
                                 {:log (.getName f)
                                  :verdict (if (some #(str/includes? % "=== gate OK") lines) "passed" "failed")
                                  :failing (vec (take 12 (filter #(re-find #"^\s*(FAIL\b|FATAL)|FAILED" %) lines)))}))))
      scores (some-> (slurp? (io/file repo "harness/v2/cycles" nn (format "%02d" (Long/parseLong mm)) "scores.json")) (json/read-str :key-fn keyword) :items)
      score (when (seq scores) (format "%d of %d items passed" (count (filter #(= "pass" (:verdict %)) scores)) (count scores)))
      attempts (max 1 (count gates) (count launches))
      example (fn [i]
                (let [l (nth launches i nil) g (nth gates i nil) info (:info l)]
                  (reflection/make-reflective-example
                    {"cycle" cycle "attempt" (inc i) "builder model" (get info "model" "openai-codex/gpt-6.1-sol")
                     "repair of a failed gate" (if (pos? i) "yes" "no")}
                    (if l
                      {"builder wall clock" (minutes (get info "start") (get info "end" (get info "start")))
                       "builder exit" (get info "exit" "not recorded")}
                      "no builder launch recorded")
                    (str/join "\n"
                      (concat
                        [(if g (str "Gate attempt " (inc i) ": " (:verdict g) ".") "No gate log recorded for this attempt.")]
                        (map #(str "Gate: " (str/trim %)) (:failing g))
                        (for [s steers :when (or (nil? (:launch s)) (str/ends-with? (str (:launch s)) (str (:builder l) "/" (:launch l))))]
                          (str "The supervisor steered the builder (" (:time s) "): " (str/trim (:message s))))
                        (for [s stops :when (str/ends-with? (str (:launch s)) (str (:builder l) "/" (:launch l)))]
                          (str "The supervisor stopped the builder (" (:time s) "): " (:reason s)))
                        (when (= i (dec attempts)) [(str "Final score: " (or score "not filed") ".")]))))))
      examples (mapv example (range attempts))
      side-info (reflection/format-reflective-examples examples)
      template (str "I provided a clean-room builder (a coding agent that regenerates one bounded context from its specification, alone, in a VM) with the following operator paragraphs, appended to a generated build prompt that stays as it is:\n```\n<curr_param>\n```\n\n"
                    "The following are the builder's attempts in one regeneration cycle, with what happened and feedback: the gate's verdict and failing lines, what a supervisor had to tell the builder while it worked, and the cycle's final score as an aggregate:\n```\n<side_info>\n```\n\n"
                    "Your task is to write new operator paragraphs for the builder.\n\n"
                    "Keep every paragraph of the current text that still applies. Turn each steer and each gate failure into general process guidance the builder could have followed from the start (environment, test discipline, time budget, structure the gate checks), so no steer is needed next time. "
                    "Never state what a scenario's answer or expected behaviour is, never name item ids, verdicts or scores, and never mention the supervisor, the grader or this cycle: the builder must not learn anything about grading. Keep it short: a few paragraphs of plain prose, one topic each.\n\n"
                    "Provide the new paragraphs within ``` blocks.")
      prompt (proposer/render-proposal-prompt appended side-info template)
      _ (.mkdirs (io/file out))
      _ (spit (io/file out "prompt.md") prompt)
      model (or (System/getenv "LOYLY_PI_MODEL") "openai-codex/gpt-6.1-sol")
      rid (str "propose-" (subs (apply str (map #(format "%02x" %) (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes prompt "UTF-8")))) 0 12))
      turn (str (io/file (System/getenv "LOYLY_HARNESS_DIR") "builder-host" "turn.ts"))
      llm-fn (fn [p]
               (let [pf (io/file out "prompt-sent.md")
                     _ (spit pf p)
                     {:keys [exit out err]} (sh/sh "node" "--no-warnings" turn (str (io/file out "session.sqlite")) rid (str pf)
                                                   :env (assoc (into {} (System/getenv)) "LOYLY_PI_MODEL" model))]
                 (when-not (zero? exit) (die "the pi turn failed: " err))
                 out))
      {:keys [proposed-instruction raw-response]} (proposer/propose-new-instruction llm-fn appended side-info :prompt-template template)]
  (spit (io/file out "side-info.md") side-info)
  (spit (io/file out "response.md") raw-response)
  (spit (io/file out "proposed-appended.md") (str proposed-instruction "\n"))
  (spit (io/file out "proposal.md")
        (str "# Builder instruction proposal for the cycle after " cycle "\n\n"
             "**Not applied.** An instruction change is an operator decision, like the fence plan: a person "
             "reads this, edits it if needed, and only then appends it to the next cycle's instruction in "
             "place of the current appended paragraphs. `harness/v2/prompt.clj`'s generated part stays as it is.\n\n"
             "- Method: one GEPA reflective mutation (ORC `components/gepa` at af086675: "
             "`reflection/make-reflective-example`, `format-reflective-examples`, `proposer/propose-new-instruction` "
             "with a slice-specific template holding `<curr_param>` and `<side_info>`); the proposer's LLM is one pi turn, "
             model ", request `" rid "` in `session.sqlite` here. GEPA's optimize! loop is not run: the next cycle is the evaluation.\n"
             "- Inputs: " (count launches) " builder launch(es), " (count gates) " gate attempt(s), " (count steers) " steer(s), "
             (count stops) " stop(s), final score " (or score "not filed") " (aggregate only; no per-item verdicts or evidence were read).\n\n"
             "## Current appended paragraphs\n\n```\n" appended "\n```\n\n"
             "## Proposed appended paragraphs\n\n```\n" proposed-instruction "\n```\n\n"
             "## What GEPA was shown (side info)\n\n```\n" side-info "\n```\n"))
  (println (str out "/proposal.md"))
  (shutdown-agents)
  (System/exit 0))
