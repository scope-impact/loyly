(ns loyly.pi-harness.observation
  (:require [ai.obney.grain.anomalies.interface :as anomalies]
            [ai.obney.grain.command-processor-v2.interface :as command-processor :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

(def observation-keys
  [:cycle-id :cut-digest :project-id :container-id :component-id :item-id :subject-digest
   :producer :evidence-kind :result :reached? :evidence :oracle-digest])

(def observation-schema
  [:map
   [:cycle-id :string]
   [:cut-digest :string]
   [:project-id :string]
   [:container-id :string]
   [:component-id :string]
   [:item-id :string]
   [:subject-digest :string]
   [:producer :string]
   [:evidence-kind [:enum "tool" "grader"]]
   [:result [:enum "pass" "fail"]]
   [:reached? :boolean]
   [:evidence :string]
   [:oracle-digest :string]])

(defschemas commands
  {:loyly/record-observation [:map [:observation observation-schema]]})

(defschemas events
  {:loyly/observation-recorded observation-schema})

(defn- uuid-tag [tag value]
  [tag (java.util.UUID/nameUUIDFromBytes (.getBytes value "UTF-8"))])

(defn- cycle-tag [cycle-id]
  (uuid-tag :observation-cycle cycle-id))

(defn- observation-identity [observation]
  (str (:cycle-id observation) "|" (:item-id observation) "|"
       (:evidence-kind observation) "|" (:producer observation)))

(defn- identity-tag [observation]
  (uuid-tag :observation (observation-identity observation)))

(defn find-cycle [{:keys [event-store tenant-id]} cycle-id]
  (->> (event-store/read event-store
                         {:tenant-id tenant-id :tags #{(cycle-tag cycle-id)}})
       (reduce (fn [observations event]
                 (if (= :loyly/observation-recorded (:event/type event))
                   (conj observations (select-keys event observation-keys))
                   observations))
               [])
       (sort-by (juxt :evidence-kind :producer))
       vec))

(defn- find-observation [context observation]
  (some #(when (and (= (:item-id observation) (:item-id %))
                    (= (:evidence-kind observation) (:evidence-kind %))
                    (= (:producer observation) (:producer %)))
           %)
        (find-cycle context (:cycle-id observation))))

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
             [(event-store/->event {:type :loyly/observation-recorded
                                    :tags #{(cycle-tag (:cycle-id observation))
                                            (identity-tag observation)}
                                    :body observation})]})))

(defn record! [context observation]
  (let [result (command-processor/process-command
                (assoc context :command
                       {:command/id (random-uuid)
                        :command/timestamp (time/now)
                        :command/name :loyly/record-observation
                        :observation observation}))]
    (when (anomalies/anomaly? result)
      (throw (ex-info (or (::anom/message result) "observation command failed") result)))
    (find-observation context observation)))
