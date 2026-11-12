(ns runner.cache
  (:require [ai.obney.grain.kv-store.interface.protocol :as p]))
;; A replaceable projection cache, never a source of facts. Every cold projection
;; is rebuilt from the handed-in Grain store.
(defrecord ProjectionCache [entries]
  p/KVStore
  (start [this] this)
  (stop [_] (reset! entries {}))
  (get! [_ {:keys [k]}] (get @entries (vec k)))
  (put! [_ {:keys [k v]}] (swap! entries assoc (vec k) v) nil)
  (put-batch! [_ {:keys [entries]}]
    (doseq [{:keys [k v]} entries] (swap! (:entries _) assoc (vec k) v)) nil))
(defn cache [] (->ProjectionCache (atom {})))
