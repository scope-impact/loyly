(ns loyly.pi-harness.core.grading.compose
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def required-services #{"app" "test"})
(defn- command-result [result]
  {:passed (zero? (or (:exitCode result) 1))
   :exit-code (or (:exitCode result) 1)
   :stdout (or (:stdout result) "")
   :stderr (or (:stderr result) "")})

(defn- render-stream [evidence stream]
  (->> [:compose-runtime :compose-config :compose-services :compose-test
        :compose-up :compose-logs :compose-down]
       (map (fn [command-name]
              (let [content (or (get-in evidence [command-name stream]) "")]
                (when-not (str/blank? content)
                  (str (name command-name) ":\n" content)))))
       (remove nil?)
       (str/join "\n")))

(defn interpret-verification [evidence]
  (let [config (try
                 (json/read-str (get-in evidence [:compose-config :stdout] "")
                                :key-fn keyword)
                 (catch Exception _ nil))
        services (->> (get-in evidence [:compose-services :stdout] "")
                      str/split-lines
                      (remove str/blank?)
                      set)
        required-results (map #(command-result (get evidence %))
                              [:compose-config :compose-services :compose-test
                               :compose-up :compose-down])
        services-present? (every? services required-services)
        healthcheck-present? (some? (get-in config [:services :app :healthcheck]))
        published-ports? (boolean (some (fn [[_ service]] (seq (:ports service))) (:services config)))
        passed (and services-present?
                    healthcheck-present?
                    (not published-ports?)
                    (every? :passed required-results))
        errors (cond-> []
                 (not services-present?)
                 (conj "compose services must include app and test")

                 (not healthcheck-present?)
                 (conj "app service must define a healthcheck")

                 published-ports?
                 (conj "compose services must not publish host ports"))
        stderr (str/join "\n" (remove str/blank?
                                       [(render-stream evidence :stderr)
                                        (str/join "\n" errors)]))]
    {:passed (boolean passed)
     :exit-code (if passed 0 1)
     :stdout (render-stream evidence :stdout)
     :stderr stderr}))
