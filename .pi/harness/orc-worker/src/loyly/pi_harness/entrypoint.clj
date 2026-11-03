(ns loyly.pi-harness.entrypoint
  (:require [ai.obney.orc.demos.pi-agent-loop.system :as system]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [loyly.pi-harness.interface :as harness]
            [loyly.pi-harness.grain.interface :as model]
            [loyly.pi-harness.workflows.generation :as generation]
            [loyly.pi-harness.workflows.regeneration :as regeneration]
            [loyly.pi-harness.core.selection.slice-selection :as slice-selection]))

(defn- context [] (harness/runtime-context))

(defn preview-selection! [{:keys [cut slice itemIds] :as request}]
  (when-not (and (string? cut) (string? slice))
    (throw (ex-info "preview-selection requires cut and slice" {:request request})))
  (let [cuts-root (or (System/getenv "LOYLEY_CUTS_ROOT")
                      (throw (ex-info "LOYLEY_CUTS_ROOT is not configured" {})))
        project-root (or (System/getenv "LOYLEY_PROJECT_ROOT")
                         (throw (ex-info "LOYLEY_PROJECT_ROOT is not configured" {})))
        project-id (or (System/getenv "PI_HARNESS_PROJECT_ID")
                       (.getName (java.io.File. project-root)))
        contract (slice-selection/resolve-selection
                  (str cuts-root "/" cut) cut slice
                  {:project-id project-id :container-id project-id
                   :component-id (str/replace slice #"^\d+-" "")
                   :item-ids itemIds})]
    (model/record-selection! (context) contract)))

(defn approve-selection! [{:keys [selectionId selectionDigest]}]
  (model/approve-selection! (context) selectionId selectionDigest))

(defn oracle-proposal [{:keys [proposalId]}]
  (model/query-oracle-proposal (context) proposalId))

(defn approve-oracle-proposal! [{:keys [proposalId proposalDigest approvedBy]}]
  (model/approve-oracle-proposal!
   (context) proposalId proposalDigest approvedBy))

(defn- with-approved-selection [{:keys [selectionId] :as request}]
  (let [selection (model/find-selection (context) selectionId)]
    (when-not (= "approved" (:status selection))
      (throw (ex-info "an approved slice selection is required"
                      {:selectionId selectionId})))
    (-> request
        (dissoc :selectionId)
        (assoc :selectionContract selection))))

(defn submit-approved! [request]
  (harness/submit-approved-slice! (with-approved-selection request)))

(defn- dispatch [method params]
  (case method
    "preview-selection" (preview-selection! params)
    "approve-selection" (approve-selection! params)
    "oracle-proposal" (oracle-proposal params)
    "approve-oracle-proposal" (approve-oracle-proposal! params)
    "prepare-cut" (harness/prepare-cut! params)
    "optimize-prompt" (harness/optimize-builder-prompt! params)
    "submit-approved-slice" (submit-approved! params)
    "submit" (submit-approved! params)
    "execution-status" (harness/execution-status! params)
    "builder-session" (harness/builder-session! params)
    "workflow-contract" (regeneration/contract)
    "generation-workflow-contract" (generation/contract)
    "wait" (harness/wait! params)
    (throw (ex-info "unknown method" {:method method}))))

(defn -main [& _]
  (harness/start-runtime! system/start!)
  (try
    (doseq [line (line-seq (java.io.BufferedReader. *in*))]
      (when-not (str/blank? line)
        (let [{:keys [id method params]} (json/read-str line :key-fn keyword)]
          (try
            (harness/respond! id true (dispatch method params))
            (catch Exception error
              (harness/respond! id false (.getMessage error)))))))
    (finally
      (harness/stop-runtime! system/stop!))))
