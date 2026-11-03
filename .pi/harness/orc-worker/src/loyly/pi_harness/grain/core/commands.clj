(ns loyly.pi-harness.grain.core.commands
  "Command handlers and deterministic domain operations for the harness model."
  (:require [ai.obney.grain.anomalies.interface :as anomalies]
            [ai.obney.grain.command-processor-v2.interface :as command-processor :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.time.interface :as time]
            [clojure.string :as str]
            [cognitect.anomalies :as anom]
            [loyly.pi-harness.grain.core.read-models :as read-models]
            [loyly.pi-harness.grain.interface.schemas]))

(defn- process! [context command message]
  (let [result (command-processor/process-command
                (assoc context :command
                       (merge {:command/id (random-uuid)
                               :command/timestamp (time/now)}
                              command)))]
    (when (anomalies/anomaly? result)
      (throw (ex-info (or (::anom/message result) message) result)))
    result))

(def ^:private projection-timeout-ms 5000)

(defn- await-projection!
  "Wait for an asynchronously maintained read model to expose an appended fact."
  [read-value expected message data]
  (let [deadline (+ (System/nanoTime) (* projection-timeout-ms 1000000))]
    (loop []
      (let [actual (read-value)]
        (cond
          (= expected actual) actual
          (>= (System/nanoTime) deadline)
          (throw (ex-info message (assoc data :expected expected :actual actual)))
          :else
          (do (Thread/sleep 10)
              (recur)))))))

(defcommand :loyly record-selection
  {:grain.event-model/produces #{:loyly/selection-recorded}}
  "Record the deterministic selection contract shown to the user."
  [{:keys [command] :as context}]
  (let [contract (:contract command)
        selection-id (:selectionId contract)
        existing (read-models/find-selection context selection-id)]
    (cond
      (= (dissoc existing :status) (dissoc contract :status))
      {:command/result existing}

      existing
      {::anom/category ::anom/conflict
       ::anom/message "selection identity already names a different contract"}

      :else
      {:command-result/events
       [(event-store/->event {:type :loyly/selection-recorded
                              :tags #{(read-models/selection-tag selection-id)}
                              :body contract})]})))

(defcommand :loyly approve-selection
  {:grain.event-model/produces #{:loyly/selection-approved}}
  "Approve only the exact digest of the recorded selection contract."
  [{:keys [command] :as context}]
  (let [selection-id (:selection-id command)
        selection-digest (:selection-digest command)
        selection (read-models/find-selection context selection-id)]
    (cond
      (nil? selection)
      {::anom/category ::anom/not-found ::anom/message "unknown selectionId"}

      (not= selection-digest (:selectionDigest selection))
      {::anom/category ::anom/conflict
       ::anom/message "selection digest does not match the reviewed contract"}

      (= "approved" (:status selection))
      {:command/result selection}

      :else
      {:command-result/events
       [(event-store/->event {:type :loyly/selection-approved
                              :tags #{(read-models/selection-tag selection-id)}
                              :body {:selectionId selection-id
                                     :selectionDigest selection-digest}})]})))

(defn record-selection! [context contract]
  (process! context {:command/name :loyly/record-selection :contract contract}
            "selection command failed")
  (read-models/find-selection context (:selectionId contract)))

(defn approve-selection! [context selection-id selection-digest]
  (process! context {:command/name :loyly/approve-selection
                     :selection-id selection-id
                     :selection-digest selection-digest}
            "selection command failed")
  (read-models/find-selection context selection-id))

(defcommand :loyly record-cycle-progress
  {:grain.event-model/produces #{:loyly/cycle-progress-recorded}}
  "Record the latest observational stage view for one durable ORC tick."
  [{:keys [command]}]
  (let [progress (:progress command)]
    {:command-result/events
     [(event-store/->event
       {:type :loyly/cycle-progress-recorded
        :tags #{(read-models/progress-tag (:tick-id progress))}
        :body progress})]}))

(defn record-cycle-progress! [context progress]
  (process! context {:command/name :loyly/record-cycle-progress
                     :progress progress}
            "cycle progress command failed")
  (await-projection! #(read-models/find-cycle-progress (dissoc context :cache)
                                                        (:tick-id progress))
                     progress
                     "cycle progress projection did not settle"
                     {:tick-id (:tick-id progress)}))

(defn- observation-identity [observation]
  (str (:cycle-id observation) "|" (:item-id observation) "|"
       (:evidence-kind observation) "|" (:producer observation)))

(defn- find-observation [context observation]
  (some #(when (and (= (:item-id observation) (:item-id %))
                    (= (:evidence-kind observation) (:evidence-kind %))
                    (= (:producer observation) (:producer %)))
           %)
        (read-models/find-cycle context (:cycle-id observation))))

(defcommand :loyly record-observation
  {:grain.event-model/produces #{:loyly/observation-recorded}}
  "Persist one tool or grader observation for a binary grading cycle."
  [{:keys [command] :as context}]
  (let [observation (:observation command)
        existing (find-observation context observation)]
    (cond
      (= existing observation) {:command/result existing}
      existing {::anom/category ::anom/conflict
                ::anom/message "observation identity already names different evidence"}
      :else {:command-result/events
             [(event-store/->event
               {:type :loyly/observation-recorded
                :tags #{(read-models/observation-cycle-tag (:cycle-id observation))
                        (read-models/uuid-tag :observation
                                              (observation-identity observation))}
                :body observation})]})))

(defn record-observation! [context observation]
  (process! context {:command/name :loyly/record-observation
                     :observation observation}
            "observation command failed")
  (await-projection! #(find-observation (dissoc context :cache) observation)
                     observation
                     "observation projection did not settle"
                     {:cycle-id (:cycle-id observation)
                      :item-id (:item-id observation)
                      :producer (:producer observation)}))

(defn- canonical-id [item-id]
  (when (string? item-id)
    (if (str/starts-with? item-id "@item-") item-id (str "@item-" item-id))))

(defn reduce-result
  [{:keys [cycle-id selection-id cut backend selected-items verdicts] :as input}]
  (when-not (and (string? cycle-id) (string? selection-id) (string? cut)
                 (#{"host" "gondolin"} backend)
                 (vector? selected-items) (seq selected-items) (vector? verdicts))
    (throw (ex-info "cycle measurement requires cycle, selection, cut, backend, selected items, and verdicts"
                    {:input input})))
  (let [selected-ids (mapv (comp canonical-id :id) selected-items)
        selected-set (set selected-ids)
        selected-duplicates (->> selected-ids frequencies
                                 (keep (fn [[item-id n]] (when (> n 1) item-id))) seq)
        normalized (mapv #(update % :item-id canonical-id) verdicts)
        verdict-ids (mapv :item-id normalized)
        unknown (seq (remove selected-set verdict-ids))
        duplicate (some (fn [[item-id n]] (when (> n 1) item-id))
                        (frequencies verdict-ids))]
    (when selected-duplicates
      (throw (ex-info (str "approved selection contains duplicate item "
                           (first selected-duplicates))
                      {:duplicates selected-duplicates})))
    (when unknown
      (throw (ex-info (str "independent grader returned unknown item " (first unknown))
                      {:unknown unknown})))
    (when duplicate
      (throw (ex-info (str "independent grader returned duplicate item " duplicate)
                      {:duplicate duplicate})))
    (let [by-id (into {} (map (juxt :item-id identity) normalized))
          items (mapv (fn [item-id]
                        (if-let [{:keys [verdict evidence]} (get by-id item-id)]
                          (do
                            (when-not (#{"pass" "fail"} verdict)
                              (throw (ex-info
                                      (str "independent grader returned invalid verdict for " item-id)
                                      {:item-id item-id :verdict verdict})))
                            (when (str/blank? evidence)
                              (throw (ex-info
                                      (str "independent grader returned missing evidence for " item-id)
                                      {:item-id item-id})))
                            {:item-id item-id :verdict verdict
                             :evidence evidence :graded? true})
                          {:item-id item-id
                           :verdict "fail"
                           :evidence "not graded: independent grader never reached this item"
                           :graded? false}))
                      selected-ids)
          passed (count (filter #(= "pass" (:verdict %)) items))]
      {:cycle-id cycle-id
       :selection-id selection-id
       :cut cut
       :backend backend
       :total (count items)
       :passed passed
       :failed (- (count items) passed)
       :complete? (every? :graded? items)
       :items items})))

(defcommand :loyly record-cycle-measurement
  {:grain.event-model/produces #{:loyly/cycle-measurement-recorded}}
  "Persist the strict result for one approved regeneration cycle."
  [{:keys [command] :as context}]
  (let [measurement (:measurement command)
        cycle-id (:cycle-id measurement)
        existing (read-models/find-measurement context cycle-id)]
    (cond
      (= existing measurement) {:command/result existing}
      existing {::anom/category ::anom/conflict
                ::anom/message "cycle identity already names a different measurement"}
      :else {:command-result/events
             [(event-store/->event
               {:type :loyly/cycle-measurement-recorded
                :tags #{(read-models/measurement-tag cycle-id)}
                :body measurement})]})))

(defn record-measurement! [context measurement]
  (process! context {:command/name :loyly/record-cycle-measurement
                     :measurement measurement}
            "cycle measurement command failed")
  (await-projection! #(read-models/find-measurement (dissoc context :cache)
                                                     (:cycle-id measurement))
                     measurement
                     "cycle measurement projection did not settle"
                     {:cycle-id (:cycle-id measurement)}))

(defcommand :loyly record-approved-slice-route
  {:grain.event-model/produces #{:loyly/approved-slice-operation-routed}}
  "Persist ORC's generation-versus-regeneration decision for an approved request."
  [{:keys [command] :as context}]
  (let [route (:route command)
        existing (read-models/find-approved-slice-route context (:request-id route))]
    (cond
      (= existing route) {:command/result existing}
      existing {::anom/category ::anom/conflict
                ::anom/message "approved-slice request identity already has a different operation route"}
      :else {:command-result/events
             [(event-store/->event
               {:type :loyly/approved-slice-operation-routed
                :tags #{(read-models/operation-route-tag (:request-id route))}
                :body route})]})))

(defn record-approved-slice-route! [context route]
  (process! context {:command/name :loyly/record-approved-slice-route :route route}
            "approved-slice route command failed")
  (await-projection! #(read-models/find-approved-slice-route (dissoc context :cache)
                                                               (:request-id route))
                     route
                     "approved-slice route projection did not settle"
                     {:request-id (:request-id route)}))

(defn- find-candidate [context candidate]
  (some #(when (= (:candidate-id candidate) (:candidate-id %)) %)
        (read-models/find-component-candidates
         context (:project-id candidate) (:container-id candidate) (:component-id candidate))))

(defcommand :loyly record-generated-candidate
  {:grain.event-model/produces #{:loyly/generated-candidate-recorded}}
  "Record a generated candidate as durable lineage for later ORC routing."
  [{:keys [command] :as context}]
  (let [candidate (:candidate command)
        existing (find-candidate context candidate)]
    (cond
      (= existing candidate) {:command/result existing}
      existing {::anom/category ::anom/conflict
                ::anom/message "candidate identity already names different lineage"}
      :else {:command-result/events
             [(event-store/->event
               {:type :loyly/generated-candidate-recorded
                :tags #{(read-models/candidate-tag (:candidate-id candidate))
                        (read-models/component-candidates-tag
                         (:project-id candidate) (:container-id candidate) (:component-id candidate))}
                :body candidate})]})))

(defn record-generated-candidate! [context candidate]
  (process! context {:command/name :loyly/record-generated-candidate
                     :candidate candidate}
            "generated candidate command failed")
  (await-projection! #(find-candidate (dissoc context :cache) candidate)
                     candidate
                     "generated candidate projection did not settle"
                     {:candidate-id (:candidate-id candidate)}))
