(ns loyly.booking-core-event-model
  "Grain Event Model for Löyly's booking-core slice.

   This model is intentionally authored before generated implementation code. The
   behavioral source is model/loyly/booking-core.allium and the executable
   acceptance source is docs/plan/01-booking-core/spec.feature."
  (:require [ai.obney.grain.event-model.interface :refer [defeventmodel]]))

(defeventmodel :loyly
  {:description "Standing sauna-slot booking for a Nordic housing cooperative."

   :commands
   {:loyly/configure-dressing-room-days
    {:description "Configure the weekdays and slot starts for a dressing room."
     :schema [:map
              [:dressing-room-id :uuid]
              [:sauna-id :uuid]
              [:weekdays [:vector :keyword]]
              [:slot-starts [:vector :string]]]
     :reads #{:loyly/weekly-schedule}
     :produces #{:loyly/dressing-room-days-configured}
     :grain/allium [{:spec "model/loyly/booking-core.allium"
                     :kind :rule :name "ConfigureDays"}]}

    :loyly/request-standing-slot
    {:description "Request a recurring weekly slot for a household."
     :schema [:map
              [:household-id :uuid]
              [:dressing-room-id :uuid]
              [:weekday :keyword]
              [:start-time :string]]
     :reads #{:loyly/booking-state :loyly/weekly-schedule}
     :produces #{:loyly/standing-slot-confirmed
                 :loyly/standing-slot-refused
                 :loyly/invoicing-signal-raised}
     :grain/allium [{:spec "model/loyly/booking-core.allium"
                     :kind :rule :name "RequestStandingSlot"}]}

    :loyly/release-standing-slot
    {:description "Give up a household's recurring weekly slot."
     :schema [:map
              [:slot-id :uuid]
              [:household-id :uuid]]
     :reads #{:loyly/booking-state}
     :produces #{:loyly/standing-slot-released}
     :grain/allium [{:spec "model/loyly/booking-core.allium"
                     :kind :rule :name "ReleaseStandingSlot"}]}}

   :events
   {:loyly/dressing-room-days-configured
    {:description "A dressing room's open days and slot starts were configured."
     :schema [:map
              [:dressing-room-id :uuid]
              [:sauna-id :uuid]
              [:weekdays [:vector :keyword]]
              [:slot-starts [:vector :string]]]}

    :loyly/standing-slot-confirmed
    {:description "A standing slot was confirmed."
     :schema [:map
              [:slot-id :uuid]
              [:household-id :uuid]
              [:dressing-room-id :uuid]
              [:weekday :keyword]
              [:start-time :string]
              [:end-time :string]]}

    :loyly/standing-slot-refused
    {:description "A standing slot request was refused with an actionable reason."
     :schema [:map
              [:household-id :uuid]
              [:dressing-room-id :uuid]
              [:weekday :keyword]
              [:start-time :string]
              [:reason [:enum :closed-day :held :no-room]]
              [:available-days {:optional true} [:vector :keyword]]]}

    :loyly/standing-slot-released
    {:description "A standing slot was released by its household."
     :schema [:map [:slot-id :uuid] [:household-id :uuid]]}

    :loyly/invoicing-signal-raised
    {:description "A confirmed standing slot raised its billing signal."
     :schema [:map [:slot-id :uuid] [:household-id :uuid]]}}

   :read-models
   {:loyly/booking-state
    {:description "The single booking record and its lifecycle state."
     :consumes #{:loyly/standing-slot-confirmed
                 :loyly/standing-slot-released}
     :version 1}

    :loyly/weekly-schedule
    {:description "Bookable slots derived from dressing-room sauna days and bookings."
     :consumes #{:loyly/dressing-room-days-configured
                 :loyly/standing-slot-confirmed
                 :loyly/standing-slot-released}
     :version 1}

    :loyly/heater-schedule
    {:description "Heater schedule derived from confirmed standing slots."
     :consumes #{:loyly/standing-slot-confirmed
                 :loyly/standing-slot-released}
     :version 1}

    :loyly/billing-signals
    {:description "Billing signals derived from confirmed standing slots."
     :consumes #{:loyly/invoicing-signal-raised
                 :loyly/standing-slot-released}
     :version 1}}

   :queries
   {:loyly/weekly-schedule
    {:description "Return the weekly schedule for a sauna."
     :schema [:map [:sauna-id :uuid]]
     :reads #{:loyly/weekly-schedule}}

    :loyly/heater-schedule
    {:description "Return heating periods derived from bookings."
     :schema [:map [:sauna-id :uuid]]
     :reads #{:loyly/heater-schedule}}

    :loyly/billing-signals
    {:description "Return billing signals for a household."
     :schema [:map [:household-id :uuid]]
     :reads #{:loyly/billing-signals}}}

   :screens
   {:loyly/manage-sauna-days
    {:description "Manager configuration surface for sauna days and slot starts."
     :queries #{:loyly/weekly-schedule}
     :commands #{:loyly/configure-dressing-room-days}
     :grain/allium [{:spec "model/loyly/booking-core.allium"
                     :kind :surface :name "ManagerBooking"}]}

    :loyly/resident-booking
    {:description "Resident schedule and standing-slot management surface."
     :queries #{:loyly/weekly-schedule
                :loyly/heater-schedule}
     :commands #{:loyly/configure-dressing-room-days
                 :loyly/request-standing-slot
                 :loyly/release-standing-slot}
     :grain/allium [{:spec "model/loyly/booking-core.allium"
                     :kind :surface :name "ResidentBooking"}]}}

   :flows
   {:loyly/standing-slot-lifecycle
    {:description "A resident requests a slot, views projections, and may release it."
     :steps [{:from [:screen :loyly/resident-booking]
              :to [:command :loyly/request-standing-slot]}
             {:from [:command :loyly/request-standing-slot]
              :to [:event :loyly/standing-slot-confirmed]}
             {:from [:event :loyly/standing-slot-confirmed]
              :to [:read-model :loyly/booking-state]}
             {:from [:read-model :loyly/booking-state]
              :to [:query :loyly/weekly-schedule]}
             {:from [:query :loyly/weekly-schedule]
              :to [:screen :loyly/resident-booking]}
             {:from [:screen :loyly/resident-booking]
              :to [:command :loyly/release-standing-slot]}
             {:from [:command :loyly/release-standing-slot]
              :to [:event :loyly/standing-slot-released]}]}

    :loyly/sauna-configuration
    {:description "A manager configures dressing-room availability."
     :steps [{:from [:screen :loyly/manage-sauna-days]
              :to [:command :loyly/configure-dressing-room-days]}
             {:from [:command :loyly/configure-dressing-room-days]
              :to [:event :loyly/dressing-room-days-configured]}
             {:from [:event :loyly/dressing-room-days-configured]
              :to [:read-model :loyly/weekly-schedule]}
             {:from [:read-model :loyly/weekly-schedule]
              :to [:query :loyly/weekly-schedule]}
             {:from [:query :loyly/weekly-schedule]
              :to [:screen :loyly/manage-sauna-days]}]}}})


