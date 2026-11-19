(ns loyly.plan
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [loyly.domain :as domain]))
(defn git [repo & args]
  (let [{:keys [exit err out]} (apply shell/sh "git" "-C" repo args)]
    (if (zero? exit) out (throw (ex-info err {:args args})))))
(defn read-scenario [lines i]
  (let [text (->> (drop (inc i) lines)
                  (take-while #(not (re-find #"^\s*(?:@|Scenario(?: Outline)?:|Rule:|Feature:|Background:)" %)))
                  (cons (nth lines i)) reverse (drop-while str/blank?) reverse (str/join "\n"))
        previous (take i lines)
        tags (->> previous reverse (take-while #(or (str/blank? %) (re-find #"^\s*[@#]" %))) (str/join "\n"))]
    {:line (inc i) :title (str/trim (second (str/split (nth lines i) #":" 2)))
     :item-id (second (re-find #"@item-(\d+-\d+)" tags))
     :rule (last (keep #(second (re-find #"^\s*Rule:\s*(.*)" %)) previous))
     :scenario-digest (domain/digest text)}))
(defn slices [repo commit settings]
  (let [plan (settings "PLAN_DIR") selected (set (remove str/blank? (str/split (get settings "PLAN_SLICES" "") #"\s+")))]
    (vec (for [entry (str/split-lines (git repo "ls-tree" (str commit ":" plan)))
               :let [[_ mode name id] (re-matches #"(\d+) \w+ [0-9a-f]+\t((\d+)-.*)" entry)]
               :when (and id (or (empty? selected) (selected id)))
               :let [path (str plan "/" name)
                     target (if (= "120000" mode)
                              (str (.normalize (.toPath (io/file plan (str/trim (git repo "show" (str commit ":" path))))))) path)
                     text (git repo "show" (str commit ":" target "/spec.feature"))
                     lines (str/split-lines text)]]
           {:slice id :name name :target target :path (str path "/spec.feature") :text text
            :scenarios (vec (keep-indexed (fn [i line] (when (re-find #"^\s*Scenario(?: Outline)?:" line) (read-scenario lines i))) lines))}))))
