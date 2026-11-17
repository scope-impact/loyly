(ns loyly.main
  (:require [loyly.runtime :as runtime] [loyly.model :as model] [loyly.domain :as d] [loyly.effects :as effects] [loyly.grading]
            [clojure.data.json :as json] [malli.core :as m] [clojure.string :as str] [litellm.router :as router]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.core.read-models :as orm]))
(defn leaf [name reads writes]
  (let [base (first (str/split name #":")) grading? (#{"grading-vm" "clear-evidence" "judge" "no-score"} base)]
    (orc/code name :fn (str (if grading? "loyly.grading/" "loyly.effects/") base "-leaf") :reads reads :writes writes)))
(defn is-run [name value] (orc/condition name :check {:key :run :op :equals :value value}))
(defn continuing [name] (is-run name "running"))
(def item-schema [:map [:item-id :string] [:slice :string] [:source :string] [:title :string] [:scenario :string] [:seam :string] [:event-model :string]])
(def bb (merge (zipmap [:params :handed-in :config :run-dir :run :refusal :halt :cycle :failure :builder-instruction :builder-room :candidate :gate-room :evidence-root :gate-refusal :evidence :score] (repeat :string))
               {:slice [:map [:slice :string] [:path :string]] :slices [:vector [:map [:slice :string] [:path :string]]] :stamp [:map [:slice :string] [:run :string] [:halt {:optional true} [:map [:effect :string] [:reason :string] [:detail :string]]]] :stamps [:vector [:map [:stamp [:map [:slice :string] [:run :string] [:halt {:optional true} [:map [:effect :string] [:reason :string] [:detail :string]]]]]]] :attempt :int :item item-schema :items [:vector item-schema] :scores [:vector [:map [:score :string]]]}))
(def stage-writes [:run :halt :cycle :failure])
(def gate-reads [:params :handed-in :config :run-dir :attempt :candidate :gate-room])
(def gate-writes (into [:evidence-root :gate-refusal] stage-writes))
(defn not-run [name value] (orc/condition name :check {:key :run :op :not-equals :value value}))
(defn gate-retries [n]
  (orc/fallback (str "guard-result:" n)
    (not-run (str "not-refused:" n) "gate-refused")
    (apply orc/sequence (str "record-guard:" n)
      (concat [(leaf (str "gate-refusal:" n) [:params :attempt :gate-refusal] stage-writes)]
        (when (< n 3)
          [(orc/fallback (str "retry-or-ended:" n)
            (not-run (str "refusals-ended:" n) "running")
            (orc/sequence (str "retry-guard:" n)
              (leaf (str "gate:" (inc n)) gate-reads gate-writes)
              (gate-retries (inc n))))])))))
(defn gate-workflow []
  (orc/workflow "cycle-runner/gate-attempt.v1" (orc/blackboard bb)
    (orc/fallback "attempt"
      (orc/sequence "gate-attempt"
        (leaf "install" [:params :handed-in :config :run-dir :attempt :builder-room] (into [:candidate :gate-room] stage-writes))
        (continuing "installed")
        (leaf "self-check" [:params :handed-in :config :attempt] stage-writes)
        (continuing "checked")
        (leaf "gate:1" gate-reads gate-writes)
        (gate-retries 1)
        (orc/fallback "repair-or-ended"
          (not-run "not-running" "running")
          (leaf "repair" [:params :handed-in :config :run-dir :attempt :builder-room :candidate :evidence-root :builder-instruction] (into [:attempt :builder-room] stage-writes))))
      (not-run "attempt-ended" "running"))))
(defn gate-delegate [id gate]
  (orc/delegate (str "gate-attempt:" id) :target-sheet-id gate :timeout-ms 240000
    :reads [:params :handed-in :config :run-dir :attempt :builder-room :builder-instruction :cycle :run]
    :writes [:attempt :builder-room :candidate :gate-room :evidence-root :cycle :run :failure :halt]))
(defn grade-workflow [env]
  (orc/workflow "cycle-runner/grade.v1" (orc/blackboard bb)
    (orc/fallback "grade-or-unreached"
    (orc/sequence "grade-item"
      (leaf "clear-evidence" [] [:evidence])
      (orc/repl-researcher "drive-seam" :rlm true :provider :loyly-model :model (get env "LOYLY_GRADER_MODEL" "grader")
        :instruction (str "Drive this candidate over its public seam from fresh state. Read the item and its public contract with (get-input :item). Use shell to run programs in the grading room. Report evidence of what was set up, sent and observed.\n" (get env "research-contract" ""))
        :reads [:item :gate-room :candidate :handed-in :config] :writes [:evidence] :mcp-tools ["shell"]
        :tool-contracts {"shell" {:checkpoint-safe? true :arguments [:map ["command" :string]] :result [:map [:exit :int] [:out :string] [:err :string] [:timeout :boolean]]}}
        :tool-caller-fn "loyly.grading/shell-caller" :max-iterations 5 :options {:tool-arg-specs {"shell" ["command"]}})
      (leaf "judge" [:item :evidence :handed-in] [:score]))
    (leaf "no-score" [] [:score]))))
(defn workflow [env gate grade]
  (orc/workflow "cycle-runner/cycle.v1" (orc/blackboard bb)
    (orc/fallback "cycle"
      (orc/sequence "perform"
        (leaf "start" [:params] [:handed-in :config :run-dir :slices :run :refusal]) (continuing "start-accepted")
        (orc/map-each "stamp-slices" :from :slices :as :slice :into :stamps :parallel 1
          (leaf "stamp" [:params :handed-in :run-dir :slice] [:stamp]))
        (leaf "stamped" [:stamps] [:run :halt]) (continuing "stamp-recorded")
        (leaf "pin" [:params :handed-in] [:run :halt]) (continuing "pin-recorded")
        (leaf "checklist" [:params :handed-in :slices] [:run :halt :items]) (continuing "checklist-recorded")
        (leaf "grader" [:params :handed-in] [:run :halt]) (continuing "grader-approved")
        (leaf "self-check:0" [:params :handed-in :config] stage-writes) (continuing "self-check-recorded")
        (leaf "fence" [:params :handed-in :config :run-dir] stage-writes) (continuing "fence-recorded")
        (orc/fallback "render-instruction"
          (orc/llm "render" :provider :loyly-model :model (get env "LOYLY_RENDER_MODEL" "render")
            :instruction (get env "LOYLY_BUILDER_INSTRUCTION" "Render the builder instruction from the selected slices.")
            :reads [:config :slices] :writes [:builder-instruction])
          (leaf "render-failed" [:params] [:run :halt]))
        (continuing "instruction-ready")
        (leaf "regenerate" [:params :handed-in :config :run-dir :builder-instruction] (into [:attempt :builder-room] stage-writes))
        (continuing "candidate-ready")
        (gate-delegate 1 gate)
        (orc/fallback "second-attempt" (not-run "no-second-attempt" "running") (gate-delegate 2 gate))
        (orc/fallback "third-attempt" (not-run "no-third-attempt" "running") (gate-delegate 3 gate))
        (is-run "gate-passed" "gate-passed")
        (leaf "grading-vm" [:handed-in] [:run :halt]) (is-run "vm-ready" "gate-passed")
        (orc/map-each "grade-items" :from :items :as :item :into :scores :parallel 1
          (orc/delegate "grade-item" :target-sheet-id grade :timeout-ms 120000
            :reads [:item :gate-room :candidate :handed-in :config] :writes [:score]))
        (leaf "file-verdicts" [:params :handed-in :config :candidate :scores :attempt] stage-writes) (continuing "verdicts-filed")
        (leaf "file-score" [:params :handed-in :config :attempt] stage-writes) (is-run "cycle-passed" "cycle-passed")
        (leaf "conclude:passed" [:params :cycle] [:run]))
      (orc/sequence "failed-stage" (is-run "failing" "failing")
        (leaf "fail-cycle" [:params :failure :attempt] stage-writes) (leaf "conclude:stage" [:params :cycle] [:run]))
      (orc/sequence "failed-score" (is-run "scoring-failed" "scoring-failed")
        (leaf "fail-score-stage" [:params :failure :attempt] stage-writes) (leaf "conclude:score" [:params :cycle] [:run]))
      (orc/sequence "failed-cycle" (is-run "cycle-failed" "cycle-failed") (leaf "conclude:failed" [:params :cycle] [:run]))
      (orc/sequence "halted-run" (is-run "halting" "halting") (leaf "halt" [:params :halt] [:run]))
      (is-run "refused-start" "refused"))))
(defn research-contract [env p]
  ;; RLM describes structured input shapes. Supply the selected cut's public
  ;; documents separately, while the current item stays behind get-input.
  (try
    (str/join "\n\n"
      (for [slice (effects/slices-at env p (effects/config env))
            :let [folder (.getParent (clojure.java.io/file (:path slice)))
                  paths (str/split-lines (effects/git env "ls-tree" "-r" "--name-only" (:commit p) (str folder "/event-model/")))]]
        (str "Public contract for " (:slice slice) "\n"
          (effects/cut-file env p (:path slice)) "\n"
          (try (effects/cut-file env p (str folder "/seam.md")) (catch Exception _ "")) "\n"
          (str/join "\n" (map #(effects/cut-file env p %) paths)))))
    (catch Exception _ "")))
(defn build! [ctx env]
  (router/register! :loyly-model {:provider :openai :model (get env "LOYLY_RENDER_MODEL" "render")
    :config {:api-base (get env "LOYLY_MODEL_API_BASE" "http://127.0.0.1:1/v1") :api-key "fixture-or-host" :timeout 60000}})
  (let [gate (orc/build-workflow! ctx (gate-workflow)) grade (orc/build-workflow! ctx (grade-workflow env))]
    (orc/build-workflow! ctx (workflow env gate grade))))
(defn resume! [ctx]
  (let [pending (vec (orm/get-all-active-executions ctx))]
    (when (seq pending)
      (doseq [tick pending :let [raw (get-in (orm/get-tick-blackboard ctx (:tick-id tick)) [:handed-in :value])] :when raw]
        (let [env (json/read-str raw)]
          (router/register! :loyly-model {:provider :openai :model (get env "LOYLY_RENDER_MODEL")
            :config {:api-base (get env "LOYLY_MODEL_API_BASE") :api-key "fixture-or-host" :timeout 60000}})))
      (future (try
        (loop [remaining 30]
          (when (and (pos? remaining) (seq (orm/get-all-active-executions ctx)))
            (orc/resume-in-progress! ctx) (Thread/sleep 1000) (recur (dec remaining))))
        (catch Exception e (binding [*out* *err*] (println "Recovery stopped:" (.getMessage e)))))))))
(defn start! [path]
  (let [ctx (assoc (runtime/start! path) :llm-provider :loyly-model)
        ctx (assoc ctx :cycle-sheet (build! ctx (effects/environment)))] (resume! ctx) ctx))
(defn error [id code message data] {:jsonrpc "2.0" :id id :error {:code code :message message :data data}})
(defn command-workflow [ctx method]
  (let [flow (first (for [[_ area] model/model [name flow] (:flows area)
                          :when (some #(= [:command method] (:to %)) (:steps flow))] name))]
    (orc/build-workflow! ctx (orc/workflow (subs (str flow) 1)
      (orc/blackboard {:params :string :method :string :reply :string})
      (orc/code (name method) :fn "loyly.effects/command-leaf" :reads [:params :method] :writes [:reply])))))
(defn handle [ctx req]
  (let [id (:id req) method (keyword (:method req)) p (or (:params req) {})
        cmd (get (model/blocks :commands) method) query (get (model/blocks :queries) method)]
    (try
      (cond
        (contains? #{:cycle-runner/record-effect :cycle-runner/halt-run :cycle-runner/conclude-run} method) (error id -32601 "Unknown method" {})
        (not (or cmd query)) (error id -32601 "Unknown method" {})
        (not (m/validate (:schema (or cmd query)) p)) (error id -32602 "Invalid params" {})
        query {:jsonrpc "2.0" :id id :result (:query/result (qp/process-query (assoc ctx :query (assoc p :query/name method :query/id (random-uuid) :query/timestamp (java.time.OffsetDateTime/now)))))}
        (= method :cycle-runner/start-run)
        (let [result (orc/execute ctx (build! ctx (assoc (effects/environment) "research-contract" (research-contract (effects/environment) p))) {:params (json/write-str p) :attempt 0} :timeout-ms 240000)
              outputs (:outputs result) refusal (:refusal outputs)]
          (cond
            (not= :success (:status result)) (error id -32603 "Workflow did not complete" result)
            (= "refused" (:run outputs)) (error id -32000 refusal {:refusal refusal})
            :else {:jsonrpc "2.0" :id id :result {:events (mapv d/public-event (into [] (filter #(= (:run-id p) (:run-id %))) (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types #{:cycle-runner/run-started}})))}}))
        :else (let [r (orc/execute ctx (command-workflow ctx method) {:params (json/write-str p) :method (:method req)} :timeout-ms 30000)]
          (if (= :success (:status r))
            (let [reply (json/read-str (get-in r [:outputs :reply]) :key-fn keyword)]
              (if (:refusal reply) (error id -32000 (:refusal reply) reply) {:jsonrpc "2.0" :id id :result reply}))
            (error id -32603 "Command workflow failed" {}))))
      (catch Exception e (error id -32603 (.getMessage e) {})))))
(defn json-value [_ v] (if (instance? java.util.Date v) (.toString (.toInstant v)) v))
(defn serve! [ctx reader writer]
  (let [pool (java.util.concurrent.Executors/newCachedThreadPool)
        send! (fn [response] (locking writer (.write writer (str (json/write-str response :value-fn json-value) "\n")) (.flush writer)))]
    (doseq [line (line-seq reader)]
      (try (let [req (json/read-str line :key-fn keyword)]
        (.submit pool ^Runnable (fn [] (binding [*out* *err*] (send! (handle ctx req))))))
        (catch Exception _ (send! (error nil -32700 "Parse error" {})))))
    (.shutdown pool) (.awaitTermination pool 10 java.util.concurrent.TimeUnit/MINUTES)))
(defn -main [& _]
  (let [out *out* ctx (binding [*out* *err*] (start! (System/getenv "LOYLY_GRAIN_STORE")))]
    (try (serve! ctx (java.io.BufferedReader. *in*) out)
      (finally (runtime/stop! ctx) (shutdown-agents)))))
