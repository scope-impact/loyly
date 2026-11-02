(ns loyly.pi-harness.regeneration-event-model
  "Harness-owned Grain Event Model for selection, observations, and measurement.

   ORC :sheet events describe workflow execution. Löyly product events describe
   the regenerated application. Neither stream is duplicated here."
  (:require [ai.obney.grain.event-model.interface :refer [defeventmodel]]))

(defeventmodel :loyly.pi-harness
  {:description "Durable approval and measurement records for Pi-hosted regeneration cycles."

   :commands
   {:loyly/record-selection
    {:description "Record the deterministic selection contract shown for review."
     :schema [:map [:contract :map]]
     :reads #{:loyly/selections}
     :produces #{:loyly/selection-recorded}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "PreviewSelection"}]}

    :loyly/approve-selection
    {:description "Approve only the exact digest of a recorded selection."
     :schema [:map [:selection-id :string] [:selection-digest :string]]
     :reads #{:loyly/selections}
     :produces #{:loyly/selection-approved}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "ApproveSelection"}]}

    :loyly/record-observation
    {:description "Record one evidence-backed item observation."
     :schema [:map [:observation :map]]
     :reads #{:loyly/cycle-observations}
     :produces #{:loyly/observation-recorded}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "CandidateGraded"}]}

    :loyly/record-cycle-measurement
    {:description "Record the strict reduction of one approved denominator."
     :schema [:map [:measurement :map]]
     :reads #{:loyly/cycle-measurements}
     :produces #{:loyly/cycle-measurement-recorded}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "RecordMeasurement"}]}}

   :events
   {:loyly/selection-recorded
    {:description "A deterministic selection contract was recorded for review."
     :schema [:map
              [:selectionId :string]
              [:selectionDigest :string]
              [:status [:enum "awaiting-approval" "approved"]]
              [:projectId :string]
              [:containerId :string]
              [:componentId :string]
              [:cut :string]
              [:slice :string]
              [:items [:vector :map]]]}

    :loyly/selection-approved
    {:description "The exact digest of a recorded selection was approved."
     :schema [:map [:selectionId :string] [:selectionDigest :string]]}

    :loyly/observation-recorded
    {:description "One identified producer recorded a purpose-scoped claim for one cycle item."
     :schema [:map
              [:cycle-id :string]
              [:cut-digest :string]
              [:project-id :string]
              [:container-id :string]
              [:component-id :string]
              [:item-id :string]
              [:subject-digest :string]
              [:producer :string]
              [:producer-authority [:enum "candidate" "harness-observer" "independent-grader"]]
              [:evidence-purpose [:enum "assertion" "diagnostic" "calibration"]]
              [:claim-type :string]
              [:result [:enum "pass" "fail" "inconclusive"]]
              [:reached? :boolean]
              [:evidence :string]
              [:collection [:map
                            [:complete? :boolean]
                            [:sampled? :boolean]
                            [:dropped :int]]]
              [:correlation [:map
                             [:operation-id {:optional true} :string]
                             [:trace-id {:optional true} :string]
                             [:event-id {:optional true} :string]]]
              [:artifacts [:vector
                           [:map
                            [:digest :string]
                            [:media-type :string]
                            [:location :string]]]]
              [:oracle-digest :string]]}

    :loyly/cycle-measurement-recorded
    {:description "The immutable strict measurement for one cycle was recorded."
     :schema [:map
              [:cycle-id :string]
              [:selection-id :string]
              [:cut :string]
              [:backend [:enum "host" "gondolin"]]
              [:total :int]
              [:passed :int]
              [:failed :int]
              [:complete? :boolean]
              [:items [:vector :map]]]}}

   :read-models
   {:loyly/selections
    {:description "Recorded selections with approval status."
     :consumes #{:loyly/selection-recorded :loyly/selection-approved}
     :version 1}

    :loyly/cycle-observations
    {:description "Evidence grouped by cycle, item, kind, and producer."
     :consumes #{:loyly/observation-recorded}
     :version 1}

    :loyly/cycle-measurements
    {:description "Strict cycle measurements keyed by cycle identity."
     :consumes #{:loyly/cycle-measurement-recorded}
     :version 1}}

   :queries
   {:loyly/selection
    {:description "Return one recorded selection with its approval status."
     :schema [:map [:selection-id :string]]
     :reads #{:loyly/selections}}

    :loyly/cycle-observations
    {:description "Return all recorded observations for one cycle."
     :schema [:map [:cycle-id :string]]
     :reads #{:loyly/cycle-observations}}

    :loyly/cycle-measurement
    {:description "Return the strict measurement for one cycle."
     :schema [:map [:cycle-id :string]]
     :reads #{:loyly/cycle-measurements}}}

   :screens
   {:loyly/pi-regeneration
    {:description "Pi tool boundary for selection approval and measured regeneration."
     :queries #{:loyly/selection
                :loyly/cycle-observations
                :loyly/cycle-measurement}
     :commands #{:loyly/record-selection
                 :loyly/approve-selection
                 :loyly/record-observation
                 :loyly/record-cycle-measurement}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :surface :name "PiRegenerationHarness"}]}}

   :flows
   {:loyly/selection-approval
    {:description "A deterministic selection is recorded and approved by exact digest."
     :steps [{:from [:screen :loyly/pi-regeneration]
              :to [:command :loyly/record-selection]}
             {:from [:command :loyly/record-selection]
              :to [:event :loyly/selection-recorded]}
             {:from [:event :loyly/selection-recorded]
              :to [:read-model :loyly/selections]}
             {:from [:screen :loyly/pi-regeneration]
              :to [:command :loyly/approve-selection]}
             {:from [:command :loyly/approve-selection]
              :to [:event :loyly/selection-approved]}
             {:from [:event :loyly/selection-approved]
              :to [:read-model :loyly/selections]}]}

    :loyly/measurement-recording
    {:description "Independent observations are reduced and recorded as one strict measurement."
     :steps [{:from [:screen :loyly/pi-regeneration]
              :to [:command :loyly/record-observation]}
             {:from [:command :loyly/record-observation]
              :to [:event :loyly/observation-recorded]}
             {:from [:event :loyly/observation-recorded]
              :to [:read-model :loyly/cycle-observations]}
             {:from [:screen :loyly/pi-regeneration]
              :to [:command :loyly/record-cycle-measurement]}
             {:from [:command :loyly/record-cycle-measurement]
              :to [:event :loyly/cycle-measurement-recorded]}
             {:from [:event :loyly/cycle-measurement-recorded]
              :to [:read-model :loyly/cycle-measurements]}]}}})
