(ns loyly.grading
  (:require [loyly.effects :as effects] [loyly.domain :as d] [loyly.process :as process]
            [clojure.java.io :as io] [clojure.edn :as edn] [clojure.string :as str] [clojure.data.json :as json]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.evaluation.interface]
            [ai.obney.orc.evaluation.core.scale :as scale]
            [ai.obney.grain.command-processor-v2.interface :as cp]))
(def live-vms (atom {}))
(defn quote-sh [s] (str "'" (str/replace s "'" "'\\''") "'"))
(defn start-vm! [env room seconds]
  (let [image (get env "LOYLY_GRADER_IMAGE")]
    (when-not (and image (.isDirectory (io/file image))) (throw (ex-info "LOYLY_GRADER_IMAGE is not a directory" {})))
    (let [pb (ProcessBuilder. ^java.util.List (cond-> ["gondolin" "bash" "--image" image] room (into ["--mount-hostfs" (str room ":" room)]) true (into ["--allow-host" "egress-blocked.invalid" "--" "sleep" (str seconds)])))
          _ (.redirectOutput pb java.lang.ProcessBuilder$Redirect/DISCARD) _ (.redirectError pb java.lang.ProcessBuilder$Redirect/DISCARD)
          p (.start pb) _ (.close (.getOutputStream p)) deadline (+ (System/currentTimeMillis) 30000)]
      (try
        (loop []
          (let [listing (process/run! "." ["gondolin" "list"] 5 {})
                row (some #(when (re-find (re-pattern (str "\\b" (.pid p) "\\b")) %) %) (str/split-lines (:out listing)))
                session (when row (first (str/split (str/trim row) #"\s+")))]
            (cond session {:process p :session session}
                  (or (not (.isAlive p)) (> (System/currentTimeMillis) deadline)) (throw (ex-info "Gondolin VM did not become available" {:pid (.pid p)}))
                  :else (do (Thread/sleep 100) (recur)))))
        (catch Exception e (process/stop! p) (throw e))))))
(defn stop-vm! [vm] (when-let [p (:process vm)] (process/stop! p)))
(defn exec-vm [vm room command seconds]
  (process/run! "." ["gondolin" "exec" "--sock" (:session vm) "--" "sh" "-c" (str (when room (str "cd " (quote-sh room) " && ")) command)] seconds {}))
(defn grading-vm-leaf [ctx]
  (let [in (effects/input ctx) env (json/read-str (:handed-in in))]
    (try
      (let [vm (start-vm! env nil 60)]
        (try
          (let [r (exec-vm vm nil "true" 30)]
            (when-not (zero? (:exit r)) (throw (ex-info "Grading VM failed true" r)))
            {:run "gate-passed" :halt "{}"})
          (finally (stop-vm! vm))))
      (catch Exception e (effects/halt-output "drive-seam" "effect-could-not-run" (.getMessage e))))))
(defn clear-evidence-leaf [_] {:evidence ""})
(defn shell-caller [bb ctx]
  (let [value #(get-in bb [% :value]) env (json/read-str (value :handed-in)) item (value :item)
        gate (value :gate-room) candidate (json/read-str (value :candidate) :key-fn keyword)
        room (str (.getParent (io/file gate)) "/grading-" (:item-id item) "-" (:tick-id ctx))
        conf (json/read-str (value :config))]
    (let [vm (locking live-vms
               (or (get @live-vms (:tick-id ctx))
                   (do
                     (process/copy! (str (.getParent (io/file gate)) "/clean") room)
                     (process/remove! (str room "/.git"))
                     (process/copy! (:path candidate) (str room "/" (get conf "APP_DIR")))
                     (let [started (start-vm! env room 120)]
                       (swap! live-vms assoc (:tick-id ctx) started)
                       started))))]
      (fn [_ args tool-context]
        (let [command (or (get args "command") (:command args))
              key (:orc/idempotency-key tool-context)
              receipt (io/file (.getParent (io/file gate)) "grading-actions" (str (subs (d/digest (str key)) 7) ".edn"))]
          (when-not (string? command) (throw (ex-info "shell requires command" {})))
          (when-not key (throw (ex-info "shell requires ORC action identity" {})))
          (locking live-vms
            (if (.exists receipt)
              (let [stored (edn/read-string (slurp receipt))]
                (if-let [result (:result stored)] result
                  (throw (ex-info "Prior shell action has no durable completion; refusing replay" {:action key}))))
              (do
                (io/make-parents receipt)
                (spit receipt (pr-str {:action key :state :started}))
                (let [seconds (min 60 (max 1 (long (Math/ceil (/ (double (or (:orc/timeout-ms tool-context) 60000)) 1000)))))
                      result (exec-vm vm room command seconds)]
                  (spit receipt (pr-str {:action key :result result}))
                  result)))))))))
(def binary-scale (scale/discrete-scale {:min 1 :max 2 :bands {1 "Fail: the scenario is not carried by the candidate." 2 "Pass: the candidate carries the scenario, established by black-box observations."}}))
(defn judge-leaf [ctx]
  (let [in (effects/input ctx) env (json/read-str (:handed-in in)) item (:item in)]
    (try
      (let [module {:inputs [{:name :item :spec :string} {:name :evidence :spec :string}]
                    :outputs [{:name :reasoning :spec :string} {:name :level :spec :int}]
                    :instructions (str (slurp (get env "LOYLY_GRADER_RUBRIC")) "\nReason first, then choose level 1 (fail) or 2 (pass).\n" (scale/render-bands binary-scale))}
            result (llm/predict :loyly-model module {:item (json/write-str (:item in)) :evidence (:evidence in)} {:with-metadata? true :validate? false :model (get env "LOYLY_GRADER_MODEL")})
            raw (or (:outputs result) result) level (scale/coerce-level (:level raw)) reason (:reasoning raw)]
        (if (and (#{1 2} level) (string? reason) (not (str/blank? reason)))
          (let [score (scale/level->unit-score binary-scale level)
                result (cp/process-command (assoc ctx :command {:command/name :evaluation/record-judge-score :command/id (random-uuid) :command/timestamp (java.time.OffsetDateTime/now)
                  :sheet-id (:id (orc/get-sheet-by-name ctx "cycle-runner/grade.v1")) :tick-id (:tick-id ctx) :node-id (:node-id ctx) :judge-name (str "checklist/" (:item-id item))
                  :judge-config {:scale binary-scale} :score score :feedback reason :dimensions []}))]
            (when-not (:command-result/events result) (throw (ex-info "Judge score recording failed" {:result result})))
            {:score (json/write-str {:item-id (:item-id item) :verdict (if (= level 2) "pass" "fail") :reason reason})})
          (do (binding [*out* *err*] (println "Judge returned no valid reason/level:" (pr-str raw))) {:score ""})))
      (catch Exception e (binding [*out* *err*] (println "Judge failed:" (.getMessage e) (pr-str (ex-data e)))) {:score ""})
      (finally (stop-vm! (get @live-vms (:tick-id ctx))) (swap! live-vms dissoc (:tick-id ctx))))))
(defn no-score-leaf [ctx]
  (stop-vm! (get @live-vms (:tick-id ctx))) (swap! live-vms dissoc (:tick-id ctx)) {:score ""})
