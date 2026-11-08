(ns loyly.cycle-runner.test-runner
  "Run the whole suite; exit non-zero on any failure, or when no test was discovered."
  (:require [clojure.test :as t]))

(def suites
  '[loyly.cycle-runner.unit-test
    loyly.cycle-runner.run-start-test
    loyly.cycle-runner.relay-test
    loyly.cycle-runner.self-check-test
    loyly.cycle-runner.fence-test
    loyly.cycle-runner.regenerate-test
    loyly.cycle-runner.gate-test
    loyly.cycle-runner.verdicts-test
    loyly.cycle-runner.halt-test
    loyly.cycle-runner.seam-test])

(defn -main [& only]
  (let [nss (if (seq only) (map symbol only) suites)]
    (apply require nss)
    (let [{:keys [test fail error]} (apply t/run-tests nss)]
      (println (str "ran " test " tests"))
      (shutdown-agents)
      (System/exit (if (and (pos? test) (zero? fail) (zero? error)) 0 1)))))
