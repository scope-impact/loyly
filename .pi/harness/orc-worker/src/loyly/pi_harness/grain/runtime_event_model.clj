(ns loyly.pi-harness.grain.runtime-event-model
  "Load and strictly reconcile the committed Pi-harness Event Model at boot."
  (:require [ai.obney.grain.event-model-validator.interface :as validator]))

(defn- project-root []
  (or (System/getenv "PI_HARNESS_PROJECT_ROOT")
      (System/getenv "LOYLEY_PROJECT_ROOT")
      (let [cwd (java.io.File. (System/getProperty "user.dir"))]
        (.getCanonicalPath (if (= "orc" (.getName cwd))
                             (.getParentFile cwd)
                             cwd)))))

(defn load-and-verify!
  ([] (load-and-verify! nil))
  ([event-store]
   (load-file (str (project-root)
                   "/.pi/model/regeneration_event_model.clj"))
   ;; Command envelopes in the model intentionally use :map while the live
   ;; schemas refine their contents. Lenient comparison accepts that refinement;
   ;; strict coverage and wiring checks remain fatal.
   (validator/verify-or-throw!
    (cond-> {:schema-match :lenient}
      event-store (assoc :event-store event-store)))))
