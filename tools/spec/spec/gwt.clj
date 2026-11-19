(ns spec.gwt
  "Given/When/Then validation using Cucumber's JVM Gherkin parser."
  (:require [clojure.string :as str] [clojure.java.shell :as sh])
  (:import [io.cucumber.gherkin GherkinParser]
           [java.io File FileInputStream]))

(defn children [folder]
  (->> (.listFiles (File. folder))
       (filter #(.isDirectory ^File %))
       (sort-by #(.getName ^File %))))
(defn feature-files [folder]
  (for [d (children folder) name ["spec.feature" "drafts.feature"]
        :let [f (File. d name)] :when (.isFile f)] f))
(defn scenarios [feature]
  (mapcat (fn [child]
            (cond
              (.isPresent (.getScenario child)) [(.get (.getScenario child))]
              (.isPresent (.getRule child))
              (keep (fn [c] (when (.isPresent (.getScenario c)) (.get (.getScenario c))))
                    (.getChildren (.get (.getRule child))))
              :else []))
          (.getChildren feature)))
(defn parse-file [^File f]
  (let [parser (-> (GherkinParser/builder) (.includePickles false) (.includeSource false) .build)]
    (with-open [in (FileInputStream. f)]
      (let [envelopes (doall (iterator-seq (.iterator (.parse parser (.getPath f) in))))
            errors (keep #(when (.isPresent (.getParseError %)) (.get (.getParseError %))) envelopes)]
        (when (seq errors) (throw (ex-info (str "Gherkin parse error: " f " " (first errors)) {})))
        (mapcat (fn [e] (when (.isPresent (.getGherkinDocument e))
                          (let [doc (.get (.getGherkinDocument e))]
                            (when (.isPresent (.getFeature doc)) (scenarios (.get (.getFeature doc))))))) envelopes)))))
(defn run [folder]
  (let [fs (vec (feature-files folder))
        all (for [f fs s (parse-file f)] [f s])
        bad (for [[f s] all
                  :let [have (set (map (comp str/trim #(.getKeyword %)) (.getSteps s)))
                        missing (remove have ["Given" "When" "Then"])]
                  :when (seq missing)]
              (str "  INCOMPLETE " (str/replace (.getPath ^File f) (str (.getPath (File. folder)) "/") "")
                   ": " (subs (.getName s) 0 (min 80 (count (.getName s))))
                   " (no " (str/join ", " missing) ")"))]
    (doseq [line bad] (println line))
    (println (format "gwt-lint: %d scenarios in %d files, %d incomplete" (count all) (count fs) (count bad)))
    (if (seq bad) 1 0)))
(defn -main [& args]
  (let [root (str/trim (:out (sh/sh "git" "rev-parse" "--show-toplevel")))
        folder (or (first args) (str root "/.pi/spec"))]
    (System/exit (run folder))))
