(ns loyly.pi-harness.independent-evidence
  (:require [clojure.string :as str]
            [loyly.pi-harness.independent-grader :as grader]))

(defn interpret [expected-item-ids evidence]
  (let [grade (grader/interpret-verdicts expected-item-ids
                                         (get-in evidence [:independent-grade :stdout]))
        commands [:independent-runtime :independent-evidence :independent-up
                  :independent-grade :independent-down]
        command-failures (->> commands
                              (remove #(zero? (or (get-in evidence [% :exitCode]) 1)))
                              (map name)
                              vec)
        streams (->> [:independent-runtime :independent-evidence :independent-up :independent-grade
                      :independent-logs :independent-down]
                     (mapcat (fn [command]
                               (for [stream [:stdout :stderr]
                                     :let [content (get-in evidence [command stream])]
                                     :when (not (str/blank? content))]
                                 (str (name command) " " (name stream) ":\n" content))))
                     (str/join "\n"))
        passed (and (:passed grade) (empty? command-failures))]
    (assoc grade
           :passed (boolean passed)
           :exit-code (if passed 0 1)
           :stdout streams
           :stderr (str/join "\n" (remove str/blank?
                                          [(:stderr grade)
                                           (when (seq command-failures)
                                             (str "independent grader commands failed: "
                                                  (str/join ", " command-failures)))])))))
