(ns loyly.grader-lifecycle.system
  "Starts the candidate: the Grain store LOYLY_GRAIN_STORE names (SQLite), one
   tenant shared by the product and ORC, every registered Grain todo processor
   (ORC's included) started with its :processor-name, the area's event model
   registered and verified by Grain's boot guard, and the ORC workflow built on
   that same store."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-model-validator.interface :as validator]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.pubsub.interface :as ps]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.orc.orc-service.interface :as orc]
            [loyly.grader-lifecycle.model :as model]
            [loyly.grader-lifecycle.domain]
            [loyly.grader-lifecycle.queries]
            [loyly.grader-lifecycle.workflow :as workflow])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def tenant-id
  "The one tenant of this candidate's store, shared by ORC."
  #uuid "6c6f796c-7900-4000-8000-000000000003")

(defn- delete-tree [^File f]
  (when (.isDirectory f)
    (run! delete-tree (.listFiles f)))
  (.delete f))

(defn register-model!
  "Registers the room's model and its schemas, then refuses to go on when
   Grain's boot guard finds the running blocks and the model apart."
  []
  (model/register-schemas!)
  (model/register-event-model!)
  (validator/verify-or-throw!))

(defn start
  "Options: :store-path (a SQLite file; nil for an in-memory store) and
   :digests-path (the scenario digests file)."
  [{:keys [store-path digests-path]}]
  (register-model!)
  ;; The in-process projection cache is keyed by tenant only: never carry it
  ;; from another store into this one.
  (rmp/l1-clear!)
  (let [pubsub (ps/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn (if store-path
                                       {:type :sqlite :database-file store-path}
                                       {:type :in-memory})
                               :event-pubsub pubsub})
        cache-dir (.toFile (Files/createTempDirectory "loyly-gl-cache" (make-array FileAttribute 0)))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir (str cache-dir)
                                               :db-name "projections"}))
        ctx {:tenant-id tenant-id
             :event-store event-store
             :event-pubsub pubsub
             :cache cache
             :loyly/scenario-digests-path digests-path}
        processors (vec (for [[processor-name {:keys [handler-fn topics]}] @tp/processor-registry*]
                          (tp/start {:context ctx
                                     :event-pubsub pubsub
                                     :handler-fn handler-fn
                                     :topics topics
                                     :processor-name processor-name})))
        sheet-id (orc/build-workflow! ctx workflow/definition)]
    {:ctx ctx
     :sheet-id sheet-id
     :pubsub pubsub
     :event-store event-store
     :cache cache
     :cache-dir cache-dir
     :processors processors}))

(defn stop [{:keys [processors cache cache-dir event-store pubsub]}]
  (run! #(try (tp/stop %) (catch Exception _ nil)) processors)
  (try (kv/stop cache) (catch Exception _ nil))
  (try (es/stop event-store) (catch Exception _ nil))
  (try (ps/stop pubsub) (catch Exception _ nil))
  (rmp/l1-clear!)
  (delete-tree cache-dir))
