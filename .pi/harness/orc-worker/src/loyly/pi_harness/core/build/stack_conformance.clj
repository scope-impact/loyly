(ns loyly.pi-harness.core.build.stack-conformance
  (:require [clojure.string :as str]
            [loyly.pi-harness.core.build.project-profile :as project-profile]))

(defn- files-under [root]
  (let [directory (java.io.File. root)]
    (if (.isDirectory directory)
      (filter #(.isFile %) (file-seq directory))
      [])))

(defn- relative-path [root file]
  (-> (.toPath (java.io.File. root))
      (.relativize (.toPath file))
      str
      (str/replace "\\" "/")))

(defn- first-instruction [dockerfile]
  (when (.isFile dockerfile)
    (->> (str/split-lines (slurp dockerfile))
         (map str/trim)
         (remove #(or (str/blank? %) (str/starts-with? % "#")))
         first)))

(defn check [output-root target-stack]
  (let [{:keys [id base]} (project-profile/stack-requirements target-stack)
        dockerfile (java.io.File. output-root "impl/Dockerfile")
        instruction (first-instruction dockerfile)
        paths (set (map #(relative-path output-root %) (files-under output-root)))
        extensions (set (map #(let [name (.getName %)]
                                (when-let [index (str/last-index-of name ".")]
                                  (subs name index)))
                             (files-under output-root)))
        source? (case id
                  "rust" (contains? extensions ".rs")
                  "java" (contains? extensions ".java")
                  "rails" (and (contains? paths "impl/Gemfile")
                                (contains? paths "impl/config/application.rb")
                                (some #(and (str/starts-with? % "impl/app/")
                                            (str/ends-with? % ".rb")) paths))
                  "python" (contains? extensions ".py")
                  "node" (boolean (some extensions [".js" ".mjs" ".cjs" ".ts"]))
                  false)
        expected (str "FROM " base)
        errors (cond-> []
                 (not= instruction expected)
                 (conj (str "first Dockerfile instruction must be exactly " expected
                            ", observed " (pr-str instruction)))
                 (not source?)
                 (conj (str "candidate source does not satisfy requested " id " stack")))
        passed (empty? errors)]
    {:passed passed
     :exit-code (if passed 0 1)
     :stdout (if passed (str "confirmed requested stack " id " using " base) "")
     :stderr (str/join "\n" errors)
     :stack id
     :base base}))
