(ns loyly.domain
  (:require [clojure.string :as str]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.event-definition :as events]
            [ai.obney.grain.schema-util.interface :as schema]
            [ai.obney.grain.time.interface :as time]))
(defn digest [text]
  (str "sha256:" (.formatHex (java.util.HexFormat/of) (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes ^String text "UTF-8")))))
(defn rows [system & types]
  (into [] (es/read (:event-store system) {:tenant-id (:tenant-id system) :types (set types)})))
(defn public [event]
  (assoc (into {} (remove (comp namespace key) event)) :type (subs (str (:event/type event)) 1)))
(defn event [type body]
  (es/->event {:type type :body body}))
(defn now [] (java.util.Date.))
(defn refusal [code] {:cognitect.anomalies/category :cognitect.anomalies/conflict :cognitect.anomalies/message code :refusal code})
(defn record-one [type time-field]
  (fn [_ p] {:command-result/events [(event type (assoc p time-field (now)))]}))
(defn stamp [sys {:keys [slice source unstamped empty-title-lines]}]
  (let [prior (filter #(= slice (:slice %)) (rows sys :specification/scenario-stamped))]
    {:command-result/events
     (cond-> (mapv (fn [i row] (event :specification/scenario-stamped
                                     (assoc row :slice slice :source source :item-id (format "%s-%03d" slice (+ (count prior) i 1)) :stamped-at (now))))
                   (range) unstamped)
       (seq empty-title-lines) (conj (event :specification/empty-title-headings-reported {:slice slice :source source :lines empty-title-lines :reported-at (now)})))}))
(defn pin [sys p]
  (if (some #(= (:cut-id p) (:cut-id %)) (rows sys :cycle-preparation/source-cut-pinned))
    (refusal "cut-already-pinned")
    ((record-one :cycle-preparation/source-cut-pinned :pinned-at) sys p)))
(defn checklist [sys {:keys [items cut-id unstamped] :as p}]
  (let [ids (map :item-id items) known (set (map :item-id (rows sys :specification/scenario-stamped)))]
    (cond
      (not (some #(= cut-id (:cut-id %)) (rows sys :cycle-preparation/source-cut-pinned))) (refusal "cut-not-pinned")
      (seq unstamped) (refusal "unstamped-scenario")
      (empty? items) (refusal "empty-checklist")
      (not-every? known ids) (refusal "unknown-item-id")
      (not= (count ids) (count (distinct ids))) (refusal "duplicate-item-id")
      (some #(= cut-id (:cut-id %)) (rows sys :measurement/checklist-derived)) (refusal "checklist-already-derived")
      :else ((record-one :measurement/checklist-derived :derived-at) sys
             (assoc (dissoc p :unstamped) :checklist-digest (digest (pr-str items)) :total (count items)))) ))
(defn effect [sys p]
  (let [position (inc (count (filter #(= (:run-id p) (:run-id %)) (rows sys :cycle-runner/effect-recorded))))]
    ((record-one :cycle-runner/effect-recorded :performed-at) sys (assoc p :position position))))
(def handlers {:cycle-runner/start-run (record-one :cycle-runner/run-started :started-at)
               :cycle-runner/halt-run (record-one :cycle-runner/run-halted :halted-at)
               :cycle-runner/record-effect effect
               :specification/stamp-slice stamp
               :cycle-preparation/pin-source-cut pin
               :measurement/derive-checklist checklist})
(defn register! [models handlers]
  (doseq [[_ defs] models]
    (doseq [[k {:keys [description schema]}] (:events defs)]
      (events/register-event-definition! k description {:schema schema} nil))
    (doseq [[k {:keys [schema reads produces]}] (:commands defs) :let [handler (handlers k)] :when handler]
      (schema/register! {k schema})
      (cp/register-command! k (fn [{:keys [command] :as ctx}] (handler ctx (into {} (remove (comp namespace key) command))))
                            {:grain.event-model/reads reads :grain.event-model/produces produces}))))
(defn issue [ctx method p]
  (let [r (cp/process-command (assoc ctx :command (assoc p :command/name method :command/id (random-uuid) :command/timestamp (time/now))))]
    (cond (:refusal r) {:refusal (:refusal r)}
          (:command-result/events r) {:events (mapv public (:command-result/events r))}
          :else (throw (ex-info (str method " failed: " (pr-str r)) {})))))
