(ns loyly.specification.runner
  "Runs every test namespace under test/; exits non-zero on any failure or
   error, or when no test was found."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn- test-namespaces []
  (->> (file-seq (io/file "test"))
       (map #(.getPath ^java.io.File %))
       (filter #(str/ends-with? % "_test.clj"))
       (map #(-> %
                 (str/replace #"^test/" "")
                 (str/replace #"\.clj$" "")
                 (str/replace "/" ".")
                 (str/replace "_" "-")
                 symbol))
       sort))

(defn -main [& _]
  (let [nss (test-namespaces)]
    (apply require nss)
    (let [{:keys [test pass fail error]} (apply t/run-tests nss)]
      (println (format "Ran %d tests: %d assertions passed, %d failed, %d errors." test pass fail error))
      (shutdown-agents)
      (System/exit (if (and (pos? test) (zero? fail) (zero? error)) 0 1)))))
