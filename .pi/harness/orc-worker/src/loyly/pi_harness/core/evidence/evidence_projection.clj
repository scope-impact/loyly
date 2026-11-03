(ns loyly.pi-harness.core.evidence.evidence-projection
  "Report projections derived from an already-persisted observation."
  (:require [clojure.java.io :as io]
            [loyly.pi-harness.core.evidence.allure-evidence :as allure]
            [loyly.pi-harness.core.evidence.otel-evidence :as otel]))

(defn project!
  [projection-root persisted-evaluation]
  (let [manifest (:evidence-manifest persisted-evaluation)
        evaluation (dissoc persisted-evaluation :evidence-manifest)]
    {:allure (allure/write-observation!
              (io/file projection-root "allure-results")
              evaluation projection-root manifest)
     :otel (otel/write-evaluation!
            (io/file projection-root "otel-results") evaluation)}))
