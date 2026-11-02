(ns loyly.pi-harness.project-profile
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(defn- safe-id! [label value]
  (when-not (and (string? value)
                 (re-matches #"[A-Za-z0-9._-]+" value)
                 (not (#{"." ".."} value)))
    (throw (ex-info (str "invalid " label) {label value})))
  value)

(defn- expand-template [template values]
  (reduce-kv (fn [result key value]
               (str/replace result (str "{" (name key) "}") (str value)))
             template values))

(defn load! [project-root project-id]
  (safe-id! :project-id project-id)
  (let [path (str project-root "/.pi/harness/config/projects/" project-id ".json")
        profile (try
                  (json/read-str (slurp path) :key-fn keyword)
                  (catch Exception error
                    (throw (ex-info "project harness profile is unavailable"
                                    {:project-id project-id :path path}
                                    error))))]
    (when-not (= 1 (:schemaVersion profile))
      (throw (ex-info "unsupported project harness profile version"
                      {:project-id project-id :schema-version (:schemaVersion profile)})))
    (doseq [key [:modelRoot :productOracleRef :graderProducer
                 :graderId :itemIdPrefix :itemGraderScript
                 :runtimeEvidenceContract]]
      (when-not (and (string? (get profile key))
                     (not (str/blank? (get profile key))))
        (throw (ex-info "project harness profile is incomplete"
                        {:project-id project-id :missing key}))))
    profile))

(defn resolved [project-root selection]
  (let [project-id (:projectId selection)
        component-id (safe-id! :component-id (:componentId selection))
        profile (load! project-root project-id)
        values {:projectId project-id :componentId component-id}]
    (reduce-kv (fn [result key value]
                 (assoc result key (if (string? value)
                                     (expand-template value values)
                                     value)))
               profile profile)))

(defn grader-script [profile item-id]
  (let [prefix (:itemIdPrefix profile)
        suffix (when (str/starts-with? item-id prefix)
                 (subs item-id (count prefix)))
        script (when suffix
                 (expand-template (:itemGraderScript profile)
                                  {:itemId item-id :itemSuffix suffix}))]
    (when-not (and script (re-matches #"[A-Za-z0-9._-]+" script))
      (throw (ex-info "project profile cannot resolve a safe item grader"
                      {:item-id item-id :item-id-prefix prefix :script script})))
    script))
