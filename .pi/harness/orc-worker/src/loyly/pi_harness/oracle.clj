(ns loyly.pi-harness.oracle
  (:require [clojure.java.io :as io]
            [loyly.pi-harness.model-resolver :as model-resolver]
            [loyly.pi-harness.oci-oracle :as oci-oracle]))

(defn- sha256 [value]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest (.getBytes (str value) "UTF-8"))))))

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
             resolved-pack (when oracle-pack-ref
                             (oci-oracle/resolve-pack oracle-pack-ref))
             grader-root (when resolved-pack
                           (oci-oracle/materialize-grader!
                            resolved-pack
                            (or oracle-cache-root
                                (io/file (System/getProperty "java.io.tmpdir")
                                         "pi-harness-oracles"))))
             pack (when resolved-pack
                    (oci-oracle/validate-pack! resolved-pack selection contract
                                               {:grader-root grader-root
                                                :fixture-runner fixture-runner}))
             oracle-digest (if pack
                             (sha256 (str (:digest contract) "\n" (:digest pack)))
                             (:digest contract))]
         (cond-> {:passed true
                  :exit-code 0
                  :item-ids (mapv :id (:items contract))
                  :artifacts (:artifacts contract)
                  :native-oracle-digest (:digest contract)
                  :oracle-digest oracle-digest
                  :stdout (str "native oracle: " (count (:items contract))
                               " selected items across " (count (:artifacts contract)) " artifacts"
                               (when pack "; runnable OCI oracle calibration verified") "\n")
                  :stderr ""}
           pack (assoc :oracle-pack-digest (:digest pack)
                       :oracle-layers (:layers pack)
                       :grader-root grader-root
                       :calibration-results (:calibration-results pack))))
       (catch Exception error
         {:passed false
          :exit-code 1
          :item-ids []
          :artifacts []
          :oracle-digest nil
          :stdout ""
          :stderr (.getMessage error)})))))
