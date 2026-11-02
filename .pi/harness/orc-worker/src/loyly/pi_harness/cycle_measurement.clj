(ns loyly.pi-harness.cycle-measurement
  (:require [ai.obney.grain.anomalies.interface :as anomalies]
            [ai.obney.grain.command-processor-v2.interface :as command-processor :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.time.interface :as time]
            [clojure.string :as str]
            [loyly.pi-harness.evidence-manifest :as evidence-manifest]
            [cognitect.anomalies :as anom]))

(def item-result-schema
  [:map
   [:item-id :string]
   [:verdict [:enum "pass" "fail"]]
   [:evidence :string]
   [:graded? :boolean]])

(def instrument-conformance-schema
  [:map
   [:passed :boolean]
   [:total :int]
   [:passed-count :int]
   [:items [:vector item-result-schema]]])

(def measurement-schema
  [:map
   [:cycle-id :string]
   [:selection-id :string]
   [:cut :string]
   [:backend [:enum "host" "gondolin"]]
   [:total :int]
   [:passed :int]
   [:failed :int]
   [:complete? :boolean]
   [:items [:vector item-result-schema]]
   [:instrument-conformance {:optional true} instrument-conformance-schema]
   [:evidence-manifest {:optional true} evidence-manifest/manifest-schema]])

(defschemas commands
  {:loyly/record-cycle-measurement [:map [:measurement measurement-schema]]})

(defschemas events
  {:loyly/cycle-measurement-recorded measurement-schema})

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
        duplicate (some (fn [[item-id n]] (when (> n 1) item-id)) (frequencies verdict-ids))]
    (when selected-duplicates
      (throw (ex-info (str "approved selection contains duplicate item " (first selected-duplicates))
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
                              (throw (ex-info (str "independent grader returned invalid verdict for " item-id)
                                              {:item-id item-id :verdict verdict})))
                            (when (str/blank? evidence)
                              (throw (ex-info (str "independent grader returned missing evidence for " item-id)
                                              {:item-id item-id})))
                            {:item-id item-id :verdict verdict :evidence evidence :graded? true})
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

(defn- measurement-tag [cycle-id]
  [:cycle-measurement (java.util.UUID/nameUUIDFromBytes (.getBytes cycle-id "UTF-8"))])

(defn find-measurement [{:keys [event-store tenant-id]} cycle-id]
  (reduce (fn [measurement event]
            (if (= :loyly/cycle-measurement-recorded (:event/type event))
              (select-keys event [:cycle-id :selection-id :cut :backend :total :passed
                                  :failed :complete? :items :instrument-conformance
                                  :evidence-manifest])
              measurement))
          nil
          (event-store/read event-store
                            {:tenant-id tenant-id :tags #{(measurement-tag cycle-id)}})))

(defcommand :loyly record-cycle-measurement
  {:grain.event-model/produces #{:loyly/cycle-measurement-recorded}}
  "Persist the strict result for one approved regeneration cycle."
  [{:keys [command] :as context}]
  (let [measurement (:measurement command)
        cycle-id (:cycle-id measurement)
        existing (find-measurement context cycle-id)]
    (cond
      (= existing measurement) {:command/result existing}
      existing {::anom/category ::anom/conflict
                ::anom/message "cycle identity already names a different measurement"}
      :else {:command-result/events
             [(event-store/->event {:type :loyly/cycle-measurement-recorded
                                    :tags #{(measurement-tag cycle-id)}
                                    :body measurement})]})))

(defn record! [context measurement]
  (let [result (command-processor/process-command
                (assoc context :command
                       {:command/id (random-uuid)
                        :command/timestamp (time/now)
                        :command/name :loyly/record-cycle-measurement
                        :measurement measurement}))]
    (when (anomalies/anomaly? result)
      (throw (ex-info (or (::anom/message result) "cycle measurement command failed") result)))
    (find-measurement context (:cycle-id measurement))))
