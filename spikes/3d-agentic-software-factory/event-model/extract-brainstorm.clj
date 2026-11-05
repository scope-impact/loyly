(require '[ai.obney.grain.event-store-v3.interface :as event-store]
         '[ai.obney.orc.orc-service.interface :as orc]
         '[clojure.data.json :as json]
         '[clojure.java.io :as io]
         '[pi-harness.store :as store])

(defn json-value [value]
  (cond
    (keyword? value) (if-let [ns (namespace value)]
                       (str ns "/" (name value))
                       (name value))
    (uuid? value) (str value)
    (instance? java.time.temporal.TemporalAccessor value) (str value)
    (map? value) (into {} (map (fn [[k v]] [(json-value k) (json-value v)])) value)
    (sequential? value) (mapv json-value value)
    (set? value) (mapv json-value (sort-by str value))
    :else value))

(def node-fields
  [:id :name :type :parent-id :children-ids :executor :model :tools
   :reads :writes :instruction :status :retry :failure-policy
   :success-policy :preserve-failures?])

(defn architecture-state [ctx source-label]
  (let [sheets (orc/get-sheets-all ctx)
        sheet (first sheets)
        nodes (orc/get-nodes-for-sheet ctx (:id sheet))
        blackboard (orc/get-blackboard-for-sheet ctx (:id sheet))
        events (into [] (event-store/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))
        evidence-events (remove #(= :grain/tx (:event/type %)) events)
        event-counts (into (sorted-map)
                           (map (fn [[event-type items]] [event-type (count items)]))
                           (group-by :event/type events))
        leafs (filter #(= :leaf (:type %)) nodes)]
    {:schemaVersion 1
     :kind "grain-orc-architecture-brainstorm"
     :source {:room "harness-regen-9"
              :implementation "harness-impl"
              :state source-label
              :readBoundary "Grain event store → ORC read model"
              :contentHash (:content-hash sheet)}
     :sheet (select-keys sheet [:id :name :root-node-id :created-at :content-hash])
     :summary {:sheetCount (count sheets)
               :nodeCount (count nodes)
               :stageCount (count leafs)
               :blackboardKeyCount (count blackboard)
               :eventCount (count events)
               :evidenceEventCount (count evidence-events)
               :transactionReceiptCount (- (count events) (count evidence-events))
               :idleNodeCount (count (filter #(= :idle (:status %)) nodes))
               :instructionCount (count (filter (comp some? :instruction) nodes))
               :populatedValueCount (count (filter (comp some? :value) blackboard))}
     :nodes (mapv #(select-keys % node-fields) nodes)
     :blackboard (mapv #(select-keys % [:key :schema :value :version]) blackboard)
     :eventCounts event-counts
     :events events
     :interpretation {:declaration "The Grain projection records the ORC workflow topology and typed blackboard contract."
                      :execution "All nodes are idle and all blackboard values are nil at version 0. This is architecture state, not evidence that a cycle ran."
                      :instructions "The recorded :instruction fields are nil. No raw prompt text exists in this state snapshot to display."}}))

(let [[state-dir output-file source-label] *command-line-args*]
  (when-not (and state-dir output-file)
    (throw (ex-info "usage: clojure -M extract-brainstorm.clj <state-dir> <output.json> [source-label]" {})))
  (let [ctx (store/open! state-dir nil)]
    (try
      (let [data (json-value (architecture-state ctx (or source-label state-dir)))
            output (io/file output-file)]
        (io/make-parents output)
        (spit output (str (json/write-str data :escape-slash false :indent true) "\n"))
        (println "wrote" (.getPath output)))
      (finally
        (store/close! ctx)))))
