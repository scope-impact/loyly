(ns harness.checklist
  "Derive scenario contracts directly from Gherkin; never infer a denominator from tags alone."
  (:require [clojure.string :as str]))

(defn scenarios [source hash-fn]
  (let [out (atom []) current (atom nil) tags (atom [])
        rule (atom "") background (atom []) feature-background (atom [])
        in-background (atom false) doc (atom nil)
        finish! (fn []
                  (when-let [s @current]
                    (let [ids (filter #(re-matches #"@item-\d+-\d+" %) (:tags s))]
                      (when-not (= 1 (count ids))
                        (throw (ex-info (str "unstamped or ambiguously stamped scenario: " (:name s)) {})))
                      (swap! out conj {:id (first ids) :name (:name s) :rule (:rule s)
                                       :scenario-digest (hash-fn (dissoc s :tags))})))
                  (reset! current nil))]
    (doseq [raw (str/split-lines (str/replace source "\r\n" "\n"))
            :let [line (str/trim raw)]]
      (cond
        @doc (do (cond @current (swap! current update :lines conj raw)
                       @in-background (swap! (if (str/blank? @rule) feature-background background) conj raw))
                 (when (str/starts-with? line @doc) (reset! doc nil)))
        (or (str/blank? line) (str/starts-with? line "#")) nil
        (or (str/starts-with? line "\"\"\"") (str/starts-with? line "```"))
        (do (reset! doc (subs line 0 3))
            (cond @current (swap! current update :lines conj line)
                  @in-background (swap! (if (str/blank? @rule) feature-background background) conj line)))
        (str/starts-with? line "@")
        (swap! tags into (str/split (first (str/split line #"\s+#")) #"\s+"))
        (re-find #"^(Scenario|Scenario Outline|Scenario Template):" line)
        (do (finish!) (reset! in-background false)
            (reset! current {:name (str/trim (second (str/split line #":" 2)))
                             :header line :rule @rule
                             :background (into @feature-background @background)
                             :tags @tags :lines []})
            (reset! tags []))
        (str/starts-with? line "Rule:")
        (do (finish!) (reset! rule line) (reset! background [])
            (reset! in-background false) (reset! tags []))
        (str/starts-with? line "Background:")
        (do (finish!) (reset! in-background true) (reset! tags []))
        (str/starts-with? line "Feature:") (reset! tags [])
        @in-background (swap! (if (str/blank? @rule) feature-background background) conj line)
        @current (swap! current update :lines conj line)
        :else nil))
    (finish!)
    (when (empty? @out) (throw (ex-info "empty scenario denominator" {})))
    (when-not (= (count @out) (count (set (map :id @out))))
      (throw (ex-info "duplicate stamped scenario identity" {})))
    @out))
