(ns loyly.grading
  (:require [loyly.host :as host] [loyly.plan :as plan] [loyly.domain :as d]
            [clojure.java.io :as io] [clojure.string :as str]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.dsl :as bt] [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.evaluation.core.scale :as scale] [ai.obney.orc.evaluation.core.commands] [ai.obney.orc.evaluation.interface.schemas]))
(def bands (scale/discrete-scale {:min 1 :max 2 :bands {1 "Observed behavior fails the scenario" 2 "Observed behavior satisfies the scenario"}}))
(def item-schema [:map [:item-id :string] [:title :string] [:source :string]])
(defn clear-evidence [_] {:evidence ""})
(defn judge [{:keys [inputs] :as ctx}]
  (let [{:keys [item evidence rubric model]} inputs
        spec {:instructions (str rubric "\n" (scale/render-bands bands))
              :inputs [{:name :item :spec item-schema} {:name :evidence :spec :string}]
              :outputs [{:name :reasoning :spec :string :description "Explain the verdict from the evidence."}
                        {:name :level :spec :int :description "Choose band 1 or 2."}]}
        reply (llm/predict :cycle-grader spec (select-keys inputs [:item :evidence])
                           {:model model :use-function-calling? false :max-retries 0 :timeout-ms 60000})
        reason (:reasoning reply)]
    (when-not (str/blank? reason)
      (let [score (:score (scale/gate-banded-output bands reply))]
        (d/issue ctx :evaluation/record-judge-score
                 {:sheet-id (bt/sheet-id-for-name "cycle-runner/grade.v1") :tick-id (:tick-id ctx) :node-id (:node-id ctx) :judge-name (str "checklist/" (:item-id item))
                  :judge-config {:scale bands} :score score :feedback reason :dimensions []})))
    {:finished true}))
(defn quote-shell [s] (str "'" (str/replace (str s) "'" "'\\''") "'"))
(defn tool [board _]
  (let [session (get-in board [:session :value]) room (get-in board [:room :value])]
    (fn [name args & _]
      (when-not (= "shell" (clojure.core/name name)) (throw (ex-info "Unknown researcher tool" {})))
      (let [result (host/run room 60 {} ["gondolin" "exec" "--sock" session "--" "sh" "-c"
                                        (str "cd " (quote-shell room) " && " (or (:command args) (get args "command") (:cmd args) (get args "cmd") (:arg args) (get args "arg") (throw (ex-info "Shell command missing" {}))))])]
        {:exit (:exit result) :out (:out result) :stdout (:out result) :stderr ""}))))
(defn terminate [^Process proc]
  (with-open [xs (.descendants proc)]
    (let [children (vec (.toArray xs))]
      (.destroyForcibly proc)
      (doseq [^java.lang.ProcessHandle p (reverse children)] (.destroyForcibly p)))))
(defn with-vm [image room seconds f]
  (when-not (.isDirectory (io/file image)) (throw (ex-info "Grader image missing" {:image image})))
  (let [args (concat ["gondolin" "bash" "--image" image] (when room ["--mount-hostfs" (str room ":" room)])
                     ["--allow-host" "egress-blocked.invalid" "--" "sleep" (str seconds)])
        proc (.start (doto (ProcessBuilder. (vec args)) (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD)
                       (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)))
        limit (+ (System/currentTimeMillis) (* 1000 (min 60 seconds)))]
    (.close (.getOutputStream proc))
    (try
      (loop []
        (let [lines (str/split-lines (:out (host/run "." 5 {} ["gondolin" "list"])))
              sock (some (fn [l] (let [[id pid] (str/split (str/trim l) #"\s+")] (when (= (str (.pid proc)) pid) id))) lines)]
          (if (and sock (zero? (:exit (host/run "." 5 {} ["gondolin" "exec" "--sock" sock "--" "true"]))))
            (f sock)
            (if (and (.isAlive proc) (< (System/currentTimeMillis) limit))
              (do (Thread/sleep 200) (recur)) (throw (ex-info "Grader VM failed to become ready" {}))))))
      (finally (terminate proc)))))
(defn grade-item [ctx s item]
  (let [{:keys [env gate candidate config p]} s
        dir (host/child (env "LOYLY_RUNS_DIR") (:run-id p))
        room (host/child dir (str "grade-" (:item-id item)))
        app (host/child room (config "APP_DIR")) seconds (get-in p [:budget :stage-timeouts-seconds :score])
        repo (env "LOYLY_PROJECT_REPO") file (first (str/split (:source item) #":"))
        slice (some #(when (= file (:path %)) %) (:slices s))
        folder (:target slice)
        row (some #(when (= (:item-id item) (:item-id %)) %) (:scenarios slice))
        scenario (str/join "\n" (cons (str "Scenario: " (:title row))
                                       (take-while #(not (re-find #"^\s*(?:@|Scenario(?: Outline)?:|Rule:|Feature:)" %))
                                                   (drop (:line row) (str/split-lines (:text slice))))))
        tree (str/split-lines (plan/git repo "ls-tree" "-r" "--name-only" (:commit p) "--" folder))
        context (str "Checklist item " (:item-id item) "\n" scenario "\n"
                     (str/join "\n" (for [path tree :when (or (str/ends-with? path "/seam.md") (str/includes? path "/event-model/"))]
                                      (plan/git repo "show" (str (:commit p) ":" path)))))
        schema {:item item-schema :evidence :string :rubric :string :model :string :session :string :room :string :finished :boolean}
        wf (bt/workflow "cycle-runner/grade.v1" (bt/blackboard schema)
                        (bt/sequence "grade"
                          (bt/code "clear-evidence" :fn "loyly.grading/clear-evidence" :writes [:evidence])
                          (bt/repl-researcher "drive-seam" :provider :cycle-grader :model (env "LOYLY_GRADER_MODEL")
                                              :instruction (str "Drive the selected scenario through the public seam with shell. Report observations as evidence.\n" context)
                                              :reads [:item] :writes [:evidence] :options {:tool-arg-specs {"shell" ["command"]}} :rlm {:recursive? false :checkpointed? false} :mcp-tools ["shell"] :tool-caller-fn "loyly.grading/tool" :max-iterations 5)
                          (bt/code "judge" :fn "loyly.grading/judge" :reads [:item :evidence :rubric :model] :writes [:finished])))
        id (bt/build-workflow! ctx wf)
        tick (random-uuid)]
    (host/copy! (io/file gate) room)
    (io/make-parents app)
    (when-not (.exists app) (host/copy! (io/file candidate) app))
    (with-vm (env "LOYLY_GRADER_IMAGE") (str room) seconds
      (fn [session]
        (let [res (runtime/execute ctx id {:item item :room (str room) :session session :rubric (slurp (env "LOYLY_GRADER_RUBRIC"))
                                 :model (env "LOYLY_GRADER_MODEL")} :tick-id tick :parent-tick-id (:tick-id ctx) :timeout-ms (* 1000 seconds))]
          (when-not (= :success (:status res)) (binding [*out* *err*] (println "Grade tick failed" (select-keys res [:status :error :reason])))))))
    (when-let [e (some #(when (= tick (:tick-id %)) %) (d/rows ctx :judge/score-emitted))]
      {:item-id (:item-id item) :verdict (if (= 1.0 (:score e)) "pass" "fail") :evidence (:feedback e)})))
