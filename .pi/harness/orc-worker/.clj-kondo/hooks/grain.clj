(ns hooks.grain
  (:require [clj-kondo.hooks-api :as api]))

(defn defcommand [{:keys [node]}]
  (let [[_ _ command-name & declarations] (:children node)
        fn-declarations (drop-while #(not= :vector (api/tag %)) declarations)]
    {:node (api/list-node
            (list* (api/token-node 'defn)
                   command-name
                   fn-declarations))}))

(defn defreadmodel [{:keys [node]}]
  (let [[_ _ read-model-name & declarations] (:children node)
        fn-declarations (drop-while #(not= :vector (api/tag %)) declarations)]
    {:node (api/list-node
            (list* (api/token-node 'defn)
                   read-model-name
                   fn-declarations))}))
