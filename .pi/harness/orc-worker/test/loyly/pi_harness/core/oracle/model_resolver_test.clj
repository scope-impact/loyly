(ns loyly.pi-harness.core.oracle.model-resolver-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.core.oracle.model-resolver :as resolver]))

(deftest selection-resolves-items-and-discovers-model-artifacts
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-resolver-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        model (io/file root "model" "loyly")]
    (try
      (.mkdirs model)
      (spit (io/file model "spec.feature")
            "Feature: Booking\n\n  @item-01-001\n  Scenario: First\n\n  @item-01-002\n  Scenario: Second\n")
      (spit (io/file model "new-component.model") "component")
      (let [contract (resolver/resolve-contract root {:items ["@item-01-002"]})]
        (is (= ["@item-01-002"] (mapv :id (:items contract))))
        (is (= #{"model/loyly/spec.feature" "model/loyly/new-component.model"}
               (set (map :path (:artifacts contract))))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))
