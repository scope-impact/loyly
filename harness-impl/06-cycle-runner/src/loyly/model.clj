(ns loyly.model
  (:require [clojure.edn :as edn] [clojure.java.io :as io]
            [ai.obney.grain.event-model.interface :as em]
            [ai.obney.grain.event-model-validator.interface :as guard]
            [ai.obney.grain.schema-util.interface :as schema]
            [ai.obney.grain.event-store-v3.interface.event-definition :as definition]
            [ai.obney.grain.command-processor-v2.interface :as command]
            [ai.obney.grain.query-processor.interface :as query]
            [ai.obney.grain.read-model-processor-v2.interface :as projection]
            [ai.obney.grain.todo-processor-v2.interface :as processor]
            [ai.obney.grain.periodic-task.interface :as periodic]))
(defn merge-model [& xs] (apply merge-with (fn [a b] (if (and (map? a) (map? b)) (merge-model a b) b)) xs))
(def model (apply merge-model (for [f (sort-by str (file-seq (io/file "event-model"))) :when (.endsWith (str f) ".edn")] (edn/read-string (slurp f)))))
(defn blocks [kind] (apply merge (map kind (vals model))))
(defn install! [command-handler query-handler reducer processor-handler periodic-handler]
  (doseq [[area fragment] model] (em/register-event-model! area fragment))
  (doseq [[id b] (blocks :events)]
    (definition/register-event-definition! id (:description b) {:schema (:schema b)} {:ns "loyly.model"}))
  (doseq [[id b] (blocks :commands)]
    (schema/register! {id (:schema b)})
    (command/register-command! id #(command-handler id %) {:grain.event-model/reads (:reads b) :grain.event-model/produces (:produces b)}))
  (doseq [[id b] (blocks :queries)]
    (schema/register! {id (:schema b)})
    (query/register-query! id #(query-handler id %) {:grain.event-model/reads (:reads b)}))
  (doseq [[id b] (blocks :read-models)]
    (projection/register-read-model! id #(reducer id %1 %2) (cond-> {:events (:consumes b) :version 1} (:schema b) (assoc :schema (:schema b)))))
  (doseq [[id b] (blocks :todo-processors)]
    (processor/register-processor! id #(processor-handler id %) {:topics (:subscribes b) :grain.event-model/reads (:reads b) :grain.event-model/produces (:produces b)}))
  (doseq [[id b] (blocks :periodic-tasks)]
    (periodic/register-periodic-trigger! id #(periodic-handler id %1 %2) {:schedule (:schedule b) :grain.event-model/produces (:produces b)}))
  (guard/verify-or-throw!))
