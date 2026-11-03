(ns loyly.pi-harness.core.oracle.model-resolver
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.pi-harness.core.selection.gherkin :as gherkin]))

(def item-tag #"@item-(\d+-\d+)")
(def scenario-line #"^\s*Scenario(?: Outline)?:\s*(.+?)\s*$")
(def rule-line #"^\s*Rule:\s*(.*?)\s*$")

(defn- relative-path [root file]
  (-> (.relativize (.toPath (io/file root)) (.toPath file)) str (str/replace "\\" "/")))

(defn- sha256-value [value]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest (.getBytes (str value) "UTF-8"))))))

(defn- scan-feature [root file]
  (loop [lines (str/split-lines (slurp file)) line-number 1 rule nil pending nil items []]
    (if-let [line (first lines)]
      (let [tag (some->> (re-seq item-tag line) last second)
            next-rule (second (re-find rule-line line))
            scenario (second (re-find scenario-line line))]
        (cond
          tag (recur (rest lines) (inc line-number) rule (str "@item-" tag) items)
          next-rule (recur (rest lines) (inc line-number) next-rule pending items)
          scenario
          (let [scenario-lines
                (cons line
                      (take-while #(not (or (re-find item-tag %)
                                            (re-find rule-line %)
                                            (re-find scenario-line %)))
                                  (rest lines)))]
            (recur (rest lines) (inc line-number) rule nil
                   (conj items {:id pending
                                :scenario-digest
                                (gherkin/scenario-digest pending rule scenario-lines)
                                :name scenario :rule rule
                                :source (str (relative-path root file) ":" line-number)})))
          :else (recur (rest lines) (inc line-number) rule pending items)))
      items)))

(defn- sha256 [file]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [input (io/input-stream file)]
      (let [buffer (byte-array 8192)]
        (loop []
          (let [n (.read input buffer)]
            (when (pos? n)
              (.update digest buffer 0 n)
              (recur))))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn contract-digest [{:keys [items artifacts]}]
  (sha256-value (pr-str {:items (mapv #(select-keys % [:id :scenario-digest]) items)
                         :artifacts artifacts})))

(defn resolve-contract [cut-root {:keys [items slice allItems] :as selection}]
  (let [project-id (or (:projectId selection)
                       (let [roots (->> (.listFiles (io/file cut-root "model"))
                                        (filter #(.isDirectory %)) vec)]
                         (when-not (= 1 (count roots))
                           (throw (ex-info "selection requires projectId when the cut contains multiple models"
                                           {:model-roots (mapv #(.getName %) roots)})))
                         (.getName (first roots))))
        model-root (io/file cut-root "model" project-id)
        files (->> (file-seq model-root) (filter #(.isFile %)) (sort-by #(.getPath %)))
        discovered-items (mapcat #(scan-feature cut-root %) (filter #(str/ends-with? (.getName %) ".feature") files))
        requested (some->> items
                           (mapv #(if (map? %) (:id %) %))
                           (mapv #(if (str/starts-with? % "@item-") % (str "@item-" %)))
                           set)
        slice-prefix (when slice (second (re-find #"^(\d+)" slice)))
        unstamped (filterv #(nil? (:id %)) discovered-items)
        duplicates (->> discovered-items (keep :id) frequencies
                        (filter (fn [[_ n]] (> n 1))) (map first) sort vec)
        selected-items (->> discovered-items
                            (filter (fn [{:keys [id]}]
                                      (cond
                                        requested (contains? requested id)
                                        slice-prefix (str/starts-with? id (str "@item-" slice-prefix "-"))
                                        allItems true
                                        :else false)))
                            vec)
        resolved-ids (set (map :id selected-items))
        missing (when requested (sort (remove resolved-ids requested)))]
    (when-not (or (seq requested) slice-prefix allItems)
      (throw (ex-info "selection requires items or a numeric slice" {:selection selection})))
    (when (seq unstamped)
      (throw (ex-info (str "unstamped scenario at " (:source (first unstamped)))
                      {:scenarios unstamped})))
    (when (seq duplicates)
      (throw (ex-info (str "duplicate item id " (str/join ", " duplicates))
                      {:duplicates duplicates})))
    (when (seq missing)
      (throw (ex-info (str "selection contains unknown items: " (str/join ", " missing))
                      {:missing missing})))
    (when (empty? selected-items)
      (throw (ex-info "selection resolved no model items" {:selection selection})))
    (let [contract {:selection selection
                    :items selected-items
                    :artifacts (mapv (fn [file] {:path (relative-path cut-root file)
                                                 :digest (sha256 file)}) files)}]
      (assoc contract :digest (contract-digest contract)))))
