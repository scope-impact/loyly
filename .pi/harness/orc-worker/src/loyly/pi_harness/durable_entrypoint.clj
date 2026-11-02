(ns loyly.pi-harness.durable-entrypoint
  (:require [ai.obney.orc.demos.pi-agent-loop.system :as disposable-system]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [loyly.pi-harness.durable-system :as durable-system]
            [loyly.pi-harness.cycle-measurement :as cycle-measurement]
            [loyly.pi-harness.orc-worker :as worker]
            [loyly.pi-harness.selection-registry :as selection-registry]
            [loyly.pi-harness.slice-selection :as slice-selection]))

(defn- context [] (:context @worker/runtime))

(defn preview-selection! [{:keys [cut slice itemIds] :as request}]
  (when-not (and (string? cut) (string? slice))
    (throw (ex-info "preview-selection requires cut and slice" {:request request})))
  (let [cuts-root (or (System/getenv "PI_HARNESS_CUTS_ROOT")
                      (System/getenv "LOYLEY_CUTS_ROOT")
                      (throw (ex-info "PI_HARNESS_CUTS_ROOT is not configured" {})))
        project-root (or (System/getenv "PI_HARNESS_PROJECT_ROOT")
                         (System/getenv "LOYLEY_PROJECT_ROOT")
                         (throw (ex-info "PI_HARNESS_PROJECT_ROOT is not configured" {})))
        project-id (or (System/getenv "PI_HARNESS_PROJECT_ID")
                       (.getName (java.io.File. project-root)))
        contract (slice-selection/resolve-selection
                  (str cuts-root "/" cut) cut slice
                  {:project-id project-id
                   :container-id project-id
                   :component-id (str/replace slice #"^\d+-" "")
                   :item-ids itemIds})]
    (selection-registry/record! (context) contract)))

(defn approve-selection! [{:keys [selectionId selectionDigest]}]
  (selection-registry/approve! (context) selectionId selectionDigest))

(defn submit-approved! [{:keys [selectionId] :as request}]
  (let [selection (selection-registry/find-selection (context) selectionId)]
    (when-not (= "approved" (:status selection))
      (throw (ex-info "regeneration requires an approved slice selection"
                      {:selectionId selectionId})))
    (worker/submit! (-> request
                        (dissoc :selectionId)
                        (assoc :selectionContract selection)))))
(defn- dispatch [method params]
  (case method
    "preview-selection" (preview-selection! params)
    "approve-selection" (approve-selection! params)
    "prepare-cut" (worker/prepare-cut! params)
    "optimize-prompt" (worker/optimize-builder-prompt! params)
    "start-external-prompt-optimization" (worker/start-external-prompt-optimization! params)
    "submit-external-prompt-candidate" (worker/submit-external-prompt-candidate! params)
    "submit" (submit-approved! params)
    "wait" (worker/wait! params)
    "measurement" (cycle-measurement/find-measurement (context) (:cycleId params))
    (throw (ex-info "unknown method" {:method method}))))

(defn -main [& _]
  (with-redefs [disposable-system/start! durable-system/start!]
    (worker/ensure-runtime!))
  (try
    (doseq [line (line-seq (java.io.BufferedReader. *in*))]
      (when-not (str/blank? line)
        (let [{:keys [id method params]} (json/read-str line :key-fn keyword)]
          (try
            (worker/respond! id true (dispatch method params))
            (catch Exception error
              (worker/respond! id false (.getMessage error)))))))
    (finally
      (when @worker/runtime
        (durable-system/stop! @worker/runtime)
        (reset! worker/runtime nil))
      (shutdown-agents))))
