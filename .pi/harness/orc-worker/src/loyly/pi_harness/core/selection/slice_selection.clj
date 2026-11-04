(ns loyly.pi-harness.core.selection.slice-selection
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.pi-harness.core.selection.gherkin :as gherkin]))

(def ^:private item-tag #"@item-(\d+)-(\d+)")
(def ^:private rule-line #"^\s*Rule:\s*(.*?)\s*$")
(def ^:private scenario-line #"^\s*Scenario(?: Outline)?:\s*(.+?)\s*$")

(defn- sha256 [value]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest (.getBytes (str value) "UTF-8"))))))

(defn- parse-items [feature-path]
  (loop [lines (str/split-lines (slurp feature-path))
         line-number 1
         rule nil
         pending-id nil
         items []]
    (if-let [line (first lines)]
      (let [[_ tag-prefix item-number] (re-find item-tag line)
            next-rule (second (re-find rule-line line))
            scenario (second (re-find scenario-line line))]
        (cond
          tag-prefix
          (recur (rest lines) (inc line-number) rule
                 (str "@item-" tag-prefix "-" item-number) items)

          next-rule
          (recur (rest lines) (inc line-number) next-rule pending-id items)

          scenario
          (do
            (when-not pending-id
              (throw (ex-info "every scenario requires a stable @item identity"
                              {:line line-number :scenario scenario})))
            (let [scenario-lines
                  (cons line
                        (take-while #(not (or (re-find item-tag %)
                                              (re-find rule-line %)
                                              (re-find scenario-line %)))
                                    (rest lines)))]
              (recur (rest lines) (inc line-number) rule nil
                     (conj items {:id pending-id
                                  :scenario-digest
                                  (gherkin/scenario-digest pending-id rule scenario-lines)
                                  :name scenario
                                  :rule (or rule "")
                                  :source (str "docs/plan/" line-number)}))))

          :else
          (recur (rest lines) (inc line-number) rule pending-id items)))
      items)))

(defn resolve-selection
  ([cut-root cut slice]
   (resolve-selection cut-root cut slice {}))
  ([cut-root cut slice {:keys [project-id container-id component-id item-ids]}]
   (when-not (and (string? cut) (string? slice))
     (throw (ex-info "selection requires cut and slice strings" {:cut cut :slice slice})))
   (let [project-id (or project-id (.getName (io/file cut-root)))
         container-id (or container-id project-id)
         component-id (or component-id (str/replace slice #"^\d+-" ""))
         requested-ids (when (seq item-ids) (set item-ids))
         slice-prefix (second (re-find #"^(\d+)-" slice))
        feature-path (io/file cut-root "docs" "plan" slice "spec.feature")]
    (when-not slice-prefix
      (throw (ex-info "slice must begin with its numeric identity" {:slice slice})))
    (when-not (.isFile feature-path)
      (throw (ex-info "selected slice has no spec.feature" {:slice slice :path (str feature-path)})))
    (let [all-items (parse-items feature-path)
          known-ids (set (map :id all-items))
          unknown-ids (seq (remove known-ids requested-ids))
          _ (when unknown-ids
              (throw (ex-info "requested item does not belong to the selected slice"
                              {:slice slice :unknown-items (vec unknown-ids)})))
          items (if requested-ids
                  (filterv #(contains? requested-ids (:id %)) all-items)
                  all-items)
          ids (mapv :id items)
          duplicates (->> ids frequencies (keep (fn [[id n]] (when (> n 1) id))) sort vec)]
      (when (empty? items)
        (throw (ex-info "selected slice contains no scenarios" {:slice slice})))
      (when (seq duplicates)
        (throw (ex-info "selected slice contains duplicate item identities"
                        {:slice slice :duplicates duplicates})))
      (let [digest (sha256 (pr-str [project-id container-id component-id cut slice items]))]
        {:selectionId (str "selection-" (subs digest 0 24))
         :selectionDigest (str "sha256:" digest)
         :status "awaiting-approval"
         :projectId project-id
         :containerId container-id
         :componentId component-id
         :cut cut
         :slice slice
         :items items})))))

(defn slice-composition
  "List the immutable cut's existing slices and stable behavior items.

   This is discovery input for an operator session. It does not ask an LLM to
   select anything and it does not record or approve a selection."
  [cut-root cut]
  (when-not (and (string? cut) (not (str/blank? cut)))
    (throw (ex-info "slice composition requires a cut identity" {:cut cut})))
  (let [plan-root (io/file cut-root "docs" "plan")
        slices (->> (when (.isDirectory plan-root) (.listFiles plan-root))
                    (filter #(.isDirectory %))
                    (keep (fn [directory]
                            (let [feature (io/file directory "spec.feature")
                                  slice (.getName directory)]
                              (when (.isFile feature)
                                {:slice slice
                                 :componentId (str/replace slice #"^\d+-" "")
                                 :items (parse-items feature)}))))
                    (sort-by :slice)
                    vec)]
    (when (empty? slices)
      (throw (ex-info "cut contains no discoverable slices"
                      {:cut cut :path (str plan-root)})))
    {:cut cut :slices slices}))
