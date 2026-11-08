(ns loyly.specification.workflow
  "The ORC workflow each command method runs as: specification/stamping, named
   after the event model's flow the command :specification/stamp-slice belongs to."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.interface :as orc]
            [clojure.edn :as edn]
            [loyly.specification.reading :as reading]))

(defonce ^{:doc "The running product's context, for the workflow's code nodes."}
  app-context (atom nil))

(defn decide
  "Code node: run the command :specification/stamp-slice through Grain's command
   processor, inside this ORC tick. Writes the reply and the tags to write."
  [{:keys [inputs]}]
  (let [ctx @app-context
        {:keys [slice source unstamped empty-title-lines]} inputs
        result (cp/process-command
                (assoc ctx :command {:command/name :specification/stamp-slice
                                     :command/id (random-uuid)
                                     :command/timestamp (time/now)
                                     :slice slice
                                     :source source
                                     :unstamped (vec unstamped)
                                     :empty-title-lines (vec empty-title-lines)}))]
    (if-let [category (:cognitect.anomalies/category result)]
      {:reply (pr-str (cond-> {:anomaly category
                               :message (:cognitect.anomalies/message result)}
                        (:refusal result) (assoc :refusal (:refusal result))))
       :stamps []}
      (let [events (get-in result [:command/result :events])]
        {:reply (pr-str {:events events})
         :stamps (->> events
                      (filter #(= :specification/scenario-stamped (:event/type %)))
                      (mapv (fn [{:keys [line item-id]}]
                              {:line line :tag (str "@item-" item-id)})))}))))

(defn tag-spec-file
  "Code node: write each identity handed out into the spec file, on a line of
   its own directly above its scenario's heading."
  [{:keys [inputs]}]
  (let [{:keys [source stamps]} inputs]
    (when (seq stamps)
      (let [f (reading/spec-file source)]
        (spit f (reading/write-item-tags (slurp f) stamps))))
    {:tagged (count stamps)}))

(def stamping
  (orc/workflow "specification/stamping"
    (orc/blackboard
     {:slice [:string {:description "The slice asked for"}]
      :source [:string {:description "The slice's spec file"}]
      :unstamped [:vector [:map [:line :int] [:title :string]]]
      :empty-title-lines [:vector :int]
      :reply [:string {:description "The command's reply, as EDN"}]
      :stamps [:vector [:map [:line :int] [:tag :string]]]
      :tagged [:int {:description "How many tags were written"}]})
    (orc/sequence "stamp"
      (orc/code "decide"
        :fn "loyly.specification.workflow/decide"
        :reads [:slice :source :unstamped :empty-title-lines]
        :writes [:reply :stamps])
      (orc/code "tag-spec-file"
        :fn "loyly.specification.workflow/tag-spec-file"
        :reads [:source :stamps]
        :writes [:tagged]))))

(defn build! [ctx]
  (orc/build-workflow! ctx stamping))

;; A tick's trace is assembled by a processor after the tick completes; the
;; reply waits for it, so a stop right after the reply leaves no tick untraced.
(defn- traced? [ctx tick-id]
  (some #(= tick-id (:trace-id %))
        (into [] (es/read (:event-store ctx)
                          {:tenant-id (:tenant-id ctx)
                           :types #{:sheet/execution-traced}
                           :reverse? true
                           :limit 20}))))

(defn- await-trace! [ctx tick-id]
  (let [deadline (+ (System/currentTimeMillis) 15000)]
    (loop []
      (when-not (or (traced? ctx tick-id) (> (System/currentTimeMillis) deadline))
        (Thread/sleep 10)
        (recur)))))

(defn run-stamping!
  "Run the stamping workflow for one stamp-slice command. Returns the command's
   reply: {:events [...]} or {:anomaly .. :message .. :refusal ..}."
  [ctx sheet-id params]
  (let [tick-id (random-uuid)
        result (orc/execute ctx sheet-id
                            {:slice (:slice params)
                             :source (:source params)
                             :unstamped (vec (:unstamped params))
                             :empty-title-lines (vec (:empty-title-lines params))}
                            :timeout-ms 60000
                            :tick-id tick-id)
        _ (await-trace! ctx tick-id)
        reply (get-in result [:outputs :reply])]
    (if (and (= :success (:status result)) reply)
      (edn/read-string reply)
      (throw (ex-info (str "The stamping workflow did not complete: "
                           (or (:error result) (:status result)))
                      {:status (:status result) :error (:error result)})))))
