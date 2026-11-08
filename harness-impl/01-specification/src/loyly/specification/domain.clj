(ns loyly.specification.domain
  "The Grain building blocks of the Specification event model: the command
   :specification/stamp-slice (rules StampFirstScenarios and StampSlice), the
   events it produces, the read model and the query :specification/stamped-items."
  (:require [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [cognitect.anomalies :as anom]
            [loyly.specification.reading :as reading]))

;; ---------------------------------------------------------------------------
;; Schemas: the command's and the query's params, and each event's body
;; ---------------------------------------------------------------------------

(defschemas schemas
  {:specification/stamp-slice
   [:map
    [:slice [:re "^[0-9]+$"]]
    [:source :string]
    [:unstamped [:vector [:map [:line pos-int?] [:title :string]]]]
    [:empty-title-lines [:vector pos-int?]]]

   :specification/scenario-stamped
   [:map
    [:item-id [:re "^[0-9]+-[0-9]{3,}$"]]
    [:slice [:re "^[0-9]+$"]]
    [:source :string]
    [:line pos-int?]
    [:title :string]
    [:stamped-at inst?]]

   :specification/empty-title-headings-reported
   [:map
    [:slice :string]
    [:source :string]
    [:lines [:vector pos-int?]]
    [:reported-at inst?]]

   :specification/stamped-items
   [:map [:slice {:optional true} :string]]})

(def stamped-items-schema
  [:map
   [:slice :string]
   [:next-number pos-int?]
   [:items [:vector [:map [:item-id :string] [:source :string] [:line pos-int?] [:title :string]]]]
   [:empty-title-lines {:optional true} [:vector pos-int?]]])

;; ---------------------------------------------------------------------------
;; Read model: per slice, every identity ever stamped and the ledger's next number
;; ---------------------------------------------------------------------------

(defn- item-number [item-id]
  (parse-long (second (re-matches #"^[0-9]+-([0-9]+)$" item-id))))

(defn- slice-entry [state slice]
  (or (get state slice) {:slice slice :next-number 1 :items []}))

(defreadmodel :specification stamped-items
  {:events #{:specification/scenario-stamped :specification/empty-title-headings-reported}
   :version 1
   :schema stamped-items-schema}
  "Per slice: every item identity ever stamped, its source and title, and the
   next free number of the slice's ledger, which never goes down."
  [state event]
  (case (:event/type event)
    :specification/scenario-stamped
    (let [{:keys [slice item-id source line title]} event
          entry (slice-entry state slice)]
      (assoc state slice
             (-> entry
                 (update :items conj {:item-id item-id :source source :line line :title title})
                 (update :next-number max (inc (item-number item-id))))))

    :specification/empty-title-headings-reported
    (let [{:keys [slice lines]} event]
      (assoc state slice (assoc (slice-entry state slice) :empty-title-lines lines)))

    state))

(defn stamped-items
  "The read model's state: {slice entry}."
  [context]
  (rmp/project context :specification/stamped-items))

;; ---------------------------------------------------------------------------
;; Command: stamp a slice
;; ---------------------------------------------------------------------------

(defn- refusal [refusal message & {:as data}]
  (merge {::anom/category ::anom/conflict
          ::anom/message message
          :refusal refusal}
         data))

(defn- refusals
  "The first `requires` of StampFirstScenarios/StampSlice the offer breaks, or nil.
   Requires 3 only chooses the rule; it is the presence of a ledger."
  [{:keys [slice source unstamped empty-title-lines]}]
  (cond
    (not (reading/spec-exists source))
    (refusal "no-spec-found"
             (str "No spec found at " source "."))

    (not= slice (reading/slice-of source))
    (refusal "not-this-slice"
             (str source " is the spec of slice " (reading/slice-of source)
                  ", not of slice " slice "."))

    (not= (vec unstamped) (reading/unstamped-scenarios source))
    (refusal "unstamped-mismatch"
             (str "The scenarios offered are not exactly the unstamped scenarios of "
                  source ", in file order."))

    (not= (vec empty-title-lines) (reading/empty-title-lines-of source))
    (refusal "empty-title-mismatch"
             (str "The empty-title headings offered are not exactly those of " source "."))))

(defcommand :specification stamp-slice
  {:authorized? (constantly true)
   :grain.event-model/reads #{:specification/stamped-items}
   :grain.event-model/produces #{:specification/scenario-stamped
                                 :specification/empty-title-headings-reported}}
  "Stamp every unstamped scenario of one slice's spec file with the slice's next
   item identities, in file order. Nothing is renumbered or reused; an empty-title
   heading is reported, never stamped."
  [context]
  (let [{:keys [slice source unstamped empty-title-lines] :as command} (:command context)]
    (or (refusals command)
        (let [first-number (:next-number (get (stamped-items context) slice) 1)
              now (java.util.Date.)
              reported (when (seq empty-title-lines)
                         [(->event {:type :specification/empty-title-headings-reported
                                    :body {:slice slice
                                           :source source
                                           :lines (vec empty-title-lines)
                                           :reported-at now}})])
              stamped (map-indexed
                       (fn [i {:keys [line title]}]
                         (->event {:type :specification/scenario-stamped
                                   :body {:item-id (format "%s-%03d" slice (+ first-number i))
                                          :slice slice
                                          :source source
                                          :line line
                                          :title title
                                          :stamped-at now}}))
                       unstamped)
              events (vec (concat reported stamped))]
          {:command-result/events events
           :command/result {:events events}}))))
