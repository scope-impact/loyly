(ns spec.structural
  "Named source-level structural assertions over already parsed Allium and Grain models."
  (:require [clojure.string :as str])
  (:import [java.io File]))
(def root (str/trim (:out ((requiring-resolve 'clojure.java.shell/sh) "git" "rev-parse" "--show-toplevel"))))
(def base (str root "/.pi/spec/regeneration-cycle"))
(defn source [& parts] (slurp (str base "/" (str/join "/" parts))))
(defn check-budget []
  (let [configs (for [f (sort (.listFiles (File. (str base "/allium"))))
                      :when (str/ends-with? (.getName ^File f) ".allium")
                      [_ block] (re-seq #"(?s)\nconfig \{(.*?)\n\}" (slurp f))
                      :let [names (set (map second (re-seq #"(?m)^\s*(boot_budget_seconds|boot_polls):" block)))]
                      :when (seq names)]
                  [(.getName ^File f) names])
        src (source "allium" "gate-attempt.allium")
        schemas (source "event-model" "gate-attempt.edn")]
    (cond
      (not= [["gate-attempt.allium" #{"boot_budget_seconds" "boot_polls"}]] (vec configs))
      (str "the boot budget is not declared once as seconds and polls together: " (vec configs))
      :else
      (or (some (fn [[event fields]]
                  (let [emits (map second (re-seq (re-pattern (str "\\b" event "\\(([^\\n]*)\\)")) src))]
                    (if (empty? emits) (str "no " event " emission in gate-attempt.allium")
                      (some (fn [e] (some (fn [field] (when-not (str/includes? e (str field ":"))
                                                    (str event " is emitted without " field))) fields)) emits))))
                [["GateAttemptPassed" ["boot_budget_seconds" "boot_polls" "answered_on_poll"]]
                 ["GateAttemptFailed" ["boot_budget_seconds" "boot_polls"]]])
          (when-not (re-find #"\n\s*boot_margin_polls: boot_polls - answered_on_poll\n" src)
            "the margin is not derived from the poll that answered (GateAttempt.boot_margin_polls)")
          (some (fn [[event fields]]
                  (let [body (second (re-find (re-pattern (str "(?s):regeneration-cycle/" event "\\n(.*?)\\]\\}")) schemas))]
                    (if-not body (str "no event regeneration-cycle/" event " in the event model")
                      (some (fn [field] (when-not (str/includes? body (str "[:" field " "))
                                          (str "event " event " has no " field " in its schema"))) fields))))
                [["gate-attempt-passed" ["boot-budget-seconds" "boot-polls" "answered-on-poll"]]
                 ["gate-attempt-failed" ["boot-budget-seconds" "boot-polls"]]])))))
(def checks {"budget-in-seconds-and-polls" check-budget})
(defn -main [& args]
  (if (some #{"--names"} args)
    (doseq [name (sort (keys checks))] (println name))
    (let [bad (for [[name f] (sort checks) :let [why (f)]]
                (do (println "structural" name (if why (str "FAIL: " why) "ok")) why))]
      (System/exit (if (some some? bad) 1 0)))))
