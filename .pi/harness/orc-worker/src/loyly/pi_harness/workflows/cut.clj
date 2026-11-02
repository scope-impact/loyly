(ns loyly.pi-harness.workflows.cut
  "ORC workflow for preparing an immutable source cut."
  (:require [ai.obney.orc.orc-service.interface :as orc]))

+(defn workflow []
  (orc/workflow "pi-harness-cut-preparation"
    (orc/blackboard
      {:source-commit :string
       :execution-backend :string
       :cut-result [:map
                    [:stage :string]
                    [:source-commit :string]
                    [:cutId :string]
                    [:treeId :string]]})
    (orc/code "cut"
      :fn "loyly.pi-harness.core.worker/prepare-cut-stage"
      :reads [:source-commit :execution-backend]
      :writes [:cut-result])))
