(ns orc.sheet.event-model
  "The ORC :sheet blocks the harness area dispatches or projects, declared so the
   harness topology resolves. ORC registers no Event Model of its own; these
   schemas are copied from obneyai/orc projects/orc-service at af086675
   (orc_service/interface/schemas.clj) and must stay in step with that pin.
   ORC owns these blocks and registers their live definitions."
  (:require [ai.obney.grain.event-model.interface :refer [defeventmodel]]))

(defeventmodel :sheet
  {:description "The ORC execution blocks the Pi harness dispatches or projects."

   :commands
   {:sheet/tick-tree
    {:description "Start one durable ORC tick of a sheet."
     :schema [:map
              [:sheet-id :uuid]
              [:tick-id {:optional true} :uuid]
              [:parent-tick-id {:optional true} :uuid]
              [:correlation-id {:optional true} :uuid]
              [:inputs {:optional true} :map]
              [:input-sources {:optional true} [:map-of :keyword [:map [:tick-id :uuid] [:event-id :uuid]]]]
              [:use-version {:optional true} :int]
              [:force-draft {:optional true} :boolean]
              [:options {:optional true} :map]
              [:tool-context {:optional true} :map]]
     :produces #{:sheet/tree-tick-started}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "DispatchSubmittedTaskToOrc"}]}}

   :events
   {:sheet/tree-tick-started
    {:description "An ORC tick started."
     :schema [:map
              [:sheet-id :uuid]
              [:tick-id :uuid]
              [:parent-tick-id {:optional true} :uuid]
              [:correlation-id {:optional true} :uuid]
              [:iteration {:optional true} :int]
              [:seed-sources {:optional true} [:map-of :keyword [:map [:tick-id :uuid] [:event-id :uuid]]]]
              [:execution-snapshot {:optional true} :map]
              [:version-number {:optional true} :int]
              [:options {:optional true} :map]
              [:tool-context {:optional true} :map]]}

    :sheet/node-execution-started
    {:description "One node of an ORC tick started."
     :schema [:map
              [:sheet-id :uuid]
              [:tick-id :uuid]
              [:node-id :uuid]
              [:inputs [:map-of :keyword :any]]
              [:resumed-from-event-id {:optional true} :uuid]]}

    :sheet/node-execution-completed
    {:description "One node of an ORC tick completed."
     :schema [:map
              [:sheet-id :uuid]
              [:tick-id :uuid]
              [:node-id :uuid]
              [:status [:enum :success :failure :running :tree-generated :partial :timeout :blocked]]
              [:write-keys {:optional true} [:vector :keyword]]
              [:write-profile {:optional true} [:map-of :keyword :map]]
              [:rejected-write-keys {:optional true} [:vector :keyword]]
              [:rejected-write-profile {:optional true} [:map-of :keyword :map]]
              [:source-tick-id {:optional true} [:maybe :uuid]]
              [:timestamp [:fn inst?]]]}

    :sheet/tree-tick-completed
    {:description "An ORC tick completed with a root status."
     :schema [:map
              [:sheet-id :uuid]
              [:tick-id :uuid]
              [:correlation-id {:optional true} :uuid]
              [:iteration {:optional true} :int]
              [:root-status [:enum :success :failure :running :tree-generated :partial :timeout :blocked]]
              [:node-id {:optional true} :uuid]
              [:exec-context {:optional true} :map]
              [:input-seed? {:optional true} :boolean]]}

    :sheet/tick-cancelled
    {:description "An ORC tick was cancelled."
     :schema [:map
              [:sheet-id :uuid]
              [:tick-id :uuid]
              [:reason {:optional true} [:maybe :string]]]}}})
