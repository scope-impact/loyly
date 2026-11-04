(ns loyly.pi-harness.core.oracle.oracle
  (:require [clojure.java.io :as io]
            [loyly.pi-harness.core.oracle.model-resolver :as model-resolver]
            [loyly.pi-harness.core.oracle.oci-oracle :as oci-oracle]))

(defn evaluate-cut
  ([cut-root] (evaluate-cut cut-root {:allItems true} {}))
  ([cut-root selection-or-project] (evaluate-cut cut-root selection-or-project {}))
  ([cut-root selection-or-project {:keys [oracle-pack-ref oracle-cache-root fixture-runner]}]
   (let [selection (cond
                     (map? selection-or-project) selection-or-project
                     (string? selection-or-project) {:projectId selection-or-project :allItems true}
                     :else {:allItems true})]
     (try
       (let [contract (model-resolver/resolve-contract cut-root selection)
             _ (when-not oracle-pack-ref
                 (throw (ex-info
                         "a pinned product oracle is required for measured regeneration"
                         {})))
             resolved-pack (oci-oracle/resolve-pack oracle-pack-ref)
             preflight (oci-oracle/validate-pack! resolved-pack selection contract)
             catalog (oci-oracle/layer-content resolved-pack oci-oracle/catalog-type)
             selected-grader-paths
             (into (set (keep #(get-in % [:grader :path]) (:selected-entries preflight)))
                   (keep :path (:graderArtifacts catalog)))
             grader-root (oci-oracle/materialize-grader!
                          resolved-pack
                          (or oracle-cache-root
                              (io/file (System/getProperty "java.io.tmpdir")
                                       "pi-harness-oracles"))
                          selected-grader-paths)
             observability-root (when resolved-pack
                          (oci-oracle/materialize-observability!
                           resolved-pack
                           (or oracle-cache-root
                               (io/file (System/getProperty "java.io.tmpdir")
                                        "pi-harness-oracles"))))
             pack (oci-oracle/validate-pack! resolved-pack selection contract
                                              {:grader-root grader-root
                                               :fixture-runner fixture-runner})]
         {:passed true
          :exit-code 0
          :item-ids (:selected-item-ids pack)
          :artifacts (:artifacts contract)
          :oracle-digest (:digest pack)
          :oracle-pack-digest (:digest pack)
          :oracle-layers (:layers pack)
          :grader-root grader-root
          :observability-root observability-root
          :calibration-results (:calibration-results pack)
          :stdout (str "product oracle: " (count (:selected-item-ids pack))
                       " selected catalogue entries; runnable calibration verified\n")
          :stderr ""})
       (catch Exception error
         {:passed false
          :exit-code 1
          :item-ids []
          :artifacts []
          :oracle-digest nil
          :stdout ""
          :stderr (.getMessage error)})))))
