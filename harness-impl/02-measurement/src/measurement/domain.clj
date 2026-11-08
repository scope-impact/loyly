(ns measurement.domain
  "Pure domain functions of Measurement: the checklist digest, the contract
   Scoring, the contract RateFormat and the contract Comparison."
  (:import [java.math BigDecimal RoundingMode]
           [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Checklist digest: the same items in the same order give the same digest.
;; ---------------------------------------------------------------------------

(defn- field [s] (if (nil? s) "\u0000" (str (count s) ":" s)))

(defn checklist-digest [items]
  (let [canonical (apply str
                         (for [{:keys [item-id scenario-digest rule source title]} items]
                           (str (field item-id) (field scenario-digest) (field rule)
                                (field source) (field title) "\n")))
        md (MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes ^String canonical "UTF-8"))]
    (str "sha256:" (apply str (map #(format "%02x" (bit-and % 0xff)) bytes)))))

(defn duplicates
  "Values that occur more than once, in first-occurrence order."
  [xs]
  (let [freqs (frequencies xs)]
    (vec (distinct (filter #(> (freqs %) 1) xs)))))

;; ---------------------------------------------------------------------------
;; Scoring
;; ---------------------------------------------------------------------------

(defn- all-verdicts
  "Every verdict of every filing, in filing order, with its grader digest."
  [filings]
  (for [{:keys [grader-digest verdicts]} filings
        v verdicts]
    (assoc v :grader-digest grader-digest)))

(defn scored-items
  "Every checklist item once, in checklist order: the last verdict filed for it
   stands; an item with no verdict fails with reason unreached."
  [checklist-items filings]
  (let [last-by-item (reduce (fn [acc v] (assoc acc (:item-id v) v)) {} (all-verdicts filings))]
    (mapv (fn [{:keys [item-id]}]
            (if-let [v (get last-by-item item-id)]
              (cond-> {:item-id item-id
                       :verdict (:verdict v)
                       :evidence (or (:evidence v) "")}
                (:reason v) (assoc :reason (:reason v)))
              {:item-id item-id :verdict "fail" :reason "unreached" :evidence ""}))
          checklist-items)))

(defn superseded
  "Every verdict filed for an item before its last one, in filing order."
  [filings]
  (let [vs (vec (all-verdicts filings))
        last-index (reduce (fn [acc [i v]] (assoc acc (:item-id v) i)) {} (map-indexed vector vs))]
    (vec (for [[i v] (map-indexed vector vs)
               :when (not= i (get last-index (:item-id v)))]
           {:item-id (:item-id v) :grader-digest (:grader-digest v) :verdict (:verdict v)}))))

(defn passes-of [items] (count (filter #(= "pass" (:verdict %)) items)))

(defn unreached-of [items] (count (filter #(= "unreached" (:reason %)) items)))

;; ---------------------------------------------------------------------------
;; RateFormat: one decimal, rounded half to even; "n/a" over zero.
;; ---------------------------------------------------------------------------

(defn rate [passes total]
  (if (zero? total)
    "n/a"
    (let [pct (.divide (.multiply (BigDecimal/valueOf (long passes)) (BigDecimal/valueOf 100))
                       (BigDecimal/valueOf (long total))
                       1
                       RoundingMode/HALF_EVEN)]
      (str (.toPlainString pct) "%"))))

;; ---------------------------------------------------------------------------
;; Comparison
;; ---------------------------------------------------------------------------

(defn- verdicts-by-item [measurement]
  (into {} (map (juxt :item-id :verdict)) (:items measurement)))

(defn comparison
  "The scoreboard fields that compare `measurement` with `previous` (or nil)."
  [measurement previous]
  (if-not previous
    {:new-items [] :dropped-items []}
    (let [cur (verdicts-by-item measurement)
          prev (verdicts-by-item previous)
          cur-ids (mapv :item-id (:items measurement))
          prev-ids (mapv :item-id (:items previous))
          shared (filterv #(contains? prev %) cur-ids)
          shared-passes (count (filter #(= "pass" (cur %)) shared))
          shared-prev-passes (count (filter #(= "pass" (prev %)) shared))]
      {:shared {:items (count shared)
                :previous-passes shared-prev-passes
                :passes shared-passes
                :previous-rate (rate shared-prev-passes (count shared))
                :rate (rate shared-passes (count shared))
                :newly-passing (filterv #(and (= "fail" (prev %)) (= "pass" (cur %))) shared)
                :newly-failing (filterv #(and (= "pass" (prev %)) (= "fail" (cur %))) shared)}
       :new-items (filterv #(not (contains? prev %)) cur-ids)
       :dropped-items (filterv #(not (contains? cur %)) prev-ids)})))

(defn item-set-moved? [{:keys [new-items dropped-items]}]
  (boolean (or (seq new-items) (seq dropped-items))))
