(ns loyly.cycle-preparation.model
  "The room's event model for Cycle preparation, read as given from
   .pi/spec/cycle-preparation/event-model/*.edn, merged, and registered with
   Grain. Nothing here restates a block: every schema the building blocks use is
   taken from the loaded model."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.obney.grain.event-model.interface :as event-model]))

(def area :cycle-preparation)

(defn model-dir
  "Where the room's event model lives. start.sh and test.sh pass it as the
   system property loyly.event-model.dir (LOYLY_EVENT_MODEL_DIR overrides it,
   so a test can hand a model the running blocks disagree with); otherwise it is found relative to the
   candidate's directory (harness-impl/05-cycle-preparation)."
  []
  (or (not-empty (System/getenv "LOYLY_EVENT_MODEL_DIR"))
      (System/getProperty "loyly.event-model.dir")
      "../../.pi/spec/cycle-preparation/event-model"))

(defn- deep-merge [& ms]
  (apply merge-with
         (fn [a b] (if (and (map? a) (map? b)) (deep-merge a b) b))
         ms))

(defn load-model
  "Every *.edn fragment of the event model directory, merged into one model
   {area service-area}."
  ([] (load-model (model-dir)))
  ([dir]
   (let [files (->> (.listFiles (io/file dir))
                    (filter #(.endsWith (.getName ^java.io.File %) ".edn"))
                    (sort-by #(.getName ^java.io.File %)))]
     (when (empty? files)
       (throw (ex-info "No event model fragments found" {:dir dir})))
     (apply deep-merge (map #(edn/read-string (slurp %)) files)))))

(defonce ^:private loaded (delay (load-model)))

(defn model [] @loaded)

(defn area-map [] (get (model) area))

(defn command-schema [command] (get-in (area-map) [:commands command :schema]))
(defn command-block [command] (get-in (area-map) [:commands command]))
(defn event-block [event] (get-in (area-map) [:events event]))
(defn query-block [query] (get-in (area-map) [:queries query]))
(defn read-model-block [rm] (get-in (area-map) [:read-models rm]))

(defn flow-of-command
  "The flow (an ORC workflow name) a command belongs to in the model's :flows."
  [command]
  (some (fn [[flow-key {:keys [steps]}]]
          (when (some (fn [{:keys [from to]}]
                        (or (= from [:command command]) (= to [:command command])))
                      steps)
            flow-key))
        (:flows (area-map))))

(defn register!
  "Register the area's model, as given, with Grain."
  []
  (event-model/register-event-model! area (area-map)))
