(ns measurement.test-runner
  "Runs every test namespace under test/ and exits non-zero on any failure, or
   when no test was discovered."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn- test-namespaces []
  (->> (file-seq (io/file "test"))
       (filter #(str/ends-with? (.getName ^java.io.File %) "_test.clj"))
       (map #(-> (.getPath ^java.io.File %)
                 (str/replace #"^test/" "")
                 (str/replace #"\.clj$" "")
                 (str/replace "/" ".")
                 (str/replace "_" "-")
                 symbol))
       sort))

(defn -main [& args]
  (let [nss (if (seq args) (map symbol args) (test-namespaces))]
    (apply require nss)
    (let [{:keys [test pass fail error]} (apply t/run-tests nss)]
      (println (format "\n%d tests, %d assertions, %d failures, %d errors" test (+ pass fail error) fail error))
      (shutdown-agents)
      (System/exit (if (and (pos? test) (zero? fail) (zero? error)) 0 1)))))
