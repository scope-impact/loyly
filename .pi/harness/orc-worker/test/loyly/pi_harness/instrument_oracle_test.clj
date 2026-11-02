(ns loyly.pi-harness.instrument-oracle-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.instrument-oracle :as instrument-oracle]))

(def layer-files
  [["policy.json" "application/vnd.loyly.instrument-oracle.policy.v1+json"]
   ["instruments.json" "application/vnd.loyly.instrument-oracle.instruments.v1+json"]
   ["fixtures.json" "application/vnd.loyly.instrument-oracle.fixtures.v1+json"]
   ["black-box--carried.golden.json" "application/vnd.loyly.allure-golden.v1+json"]
   ["black-box--uncarried.golden.json" "application/vnd.loyly.allure-golden.v1+json"]])

(deftest oras-artifact-resolves-an-instrument-oracle-with-allure-goldens
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-instrument-oracle-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        source (io/file root "source")
        layout (io/file root "layout")]
    (try
      (.mkdirs source)
      (spit (io/file source "policy.json")
            (json/write-str {:version 1 :minimumFixturesPerInstrument 2}))
      (spit (io/file source "instruments.json")
            (json/write-str
             {:instruments [{:id "black-box"
                             :digest (str "sha256:" (apply str (repeat 64 "a")))}]}))
      (spit (io/file source "fixtures.json")
            (json/write-str
             {:fixtures [{:id "known-carried" :instrumentId "black-box"
                          :role "carried"}
                         {:id "known-uncarried" :instrumentId "black-box"
                          :role "uncarried"}]}))
      (spit (io/file source "black-box--carried.golden.json")
            (json/write-str
             {:schemaVersion 1
              :name "known-carried"
              :fullName "black-box.known-carried"
              :status "passed"
              :stage "finished"
              :labels {:instrument_id "black-box" :fixture_id "known-carried"}
              :parameters []
              :attachments []}))
      (spit (io/file source "black-box--uncarried.golden.json")
            (json/write-str
             {:schemaVersion 1
              :name "known-uncarried"
              :fullName "black-box.known-uncarried"
              :status "passed"
              :stage "finished"
              :labels {:instrument_id "black-box" :fixture_id "known-uncarried"}
              :parameters []
              :attachments []}))
      (let [push (apply shell/sh
                        (concat ["oras" "push" "--oci-layout" (str layout ":accepted")
                                 "--artifact-type"
                                 "application/vnd.loyly.instrument-oracle.v1"]
                                (map (fn [[name media-type]] (str name ":" media-type))
                                     layer-files)
                                [:dir (.getPath source)]))]
        (is (zero? (:exit push)) (:err push)))
      (let [oracle (instrument-oracle/resolve-pack (str layout ":accepted"))]
        (is (re-matches #"sha256:[0-9a-f]{64}" (:digest oracle)))
        (is (= "black-box" (get-in oracle [:instruments 0 :id])))
        (is (= #{"known-carried" "known-uncarried"}
               (set (map #(get-in % [:labels :fixture_id]) (:goldens oracle))))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest instrument-oracle-rejects-calibration-without-both-fixture-roles
  (let [pack {:policy {:version 1 :minimumFixturesPerInstrument 2}
              :instruments [{:id "black-box"
                             :digest (str "sha256:" (apply str (repeat 64 "a")))}]
              :fixtures [{:id "only-carried" :instrumentId "black-box" :role "carried"}]
              :goldens [{:schemaVersion 1
                         :name "only-carried"
                         :fullName "black-box.only-carried"
                         :status "passed"
                         :stage "finished"
                         :labels {:instrument_id "black-box" :fixture_id "only-carried"}
                         :parameters [] :attachments []}]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"carried and uncarried"
                          (instrument-oracle/validate! pack)))))

(deftest instrument-oracle-binds-the-files-that-it-calibrates
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-instrument-binding-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        instrument (io/file root "instrument.clj")
        pack {:instruments [{:id "bound-instrument"
                             :path "instrument.clj"
                             :digest (str "sha256:"
                                          "e9eaed5548b3cf7f44a38196dac4b0fb92607f063b11d546b459730f4e6b0520")}] }]
    (try
      (spit instrument "instrument\n")
      (is (= pack (instrument-oracle/verify-instrument-files! pack root)))
      (spit instrument "changed\n")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"digest mismatch"
                            (instrument-oracle/verify-instrument-files! pack root)))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest full-harness-oracle-rejects-an-omitted-required-instrument
  (let [pack {:policy {:version 1 :minimumFixturesPerInstrument 2}
              :instruments [{:id "allure-golden-normalizer"
                             :digest (str "sha256:" (apply str (repeat 64 "a")))}]
              :fixtures [{:id "carried" :instrumentId "allure-golden-normalizer"
                          :role "carried"}
                         {:id "uncarried" :instrumentId "allure-golden-normalizer"
                          :role "uncarried"}]
              :goldens [{:schemaVersion 1 :name "carried" :fullName "normalizer.carried"
                         :status "passed" :stage "finished"
                         :labels {:instrument_id "allure-golden-normalizer"
                                  :fixture_id "carried"}
                         :parameters [] :attachments []}
                        {:schemaVersion 1 :name "uncarried" :fullName "normalizer.uncarried"
                         :status "passed" :stage "finished"
                         :labels {:instrument_id "allure-golden-normalizer"
                                  :fixture_id "uncarried"}
                         :parameters [] :attachments []}]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"missing required instruments.*cycle-measurement"
                          (instrument-oracle/validate!
                           pack
                           #{"allure-golden-normalizer" "cycle-measurement"})))))

(deftest instrument-oracle-binds-a-multi-file-instrument
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-instrument-artifacts-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        pack {:instruments
              [{:id "full-pi-harness"
                :digest (str "sha256:"
                             "fcff09232a9e19d3f9af5ddb4a8080f7c39a45dddfb0bbb40c37eb767508a97b")
                :artifacts
                [{:path "a.txt"
                  :digest (str "sha256:"
                               "2c8b08da5ce60398e1f19af0e5dccc744df274b826abe585eaba68c525434806")}
                 {:path "b.txt"
                  :digest (str "sha256:"
                               "27dd8ed44a83ff94d557f9fd0412ed5a8cbca69ea04922d88c01184a07300a5a")}]}]}]
    (try
      (spit (io/file root "a.txt") "one\n")
      (spit (io/file root "b.txt") "two\n")
      (is (= pack (instrument-oracle/verify-instrument-files! pack root)))
      (spit (io/file root "b.txt") "changed\n")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"digest mismatch.*b.txt"
                            (instrument-oracle/verify-instrument-files! pack root)))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest instrument-oracle-binds-complete-source-roots
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-instrument-roots-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        source (io/file root "instrument" "a.txt")
        runner (io/file root "runner.clj")
        pack {:instruments
              [{:id "full-pi-harness"
                :roots ["instrument"]
                :files ["runner.clj"]
                :digest (str "sha256:"
                             "8e715830ea2a6848d1c8ef0055bc87633f9aee843e304ff4654c273a97387bf3")}]}]
    (try
      (.mkdirs (.getParentFile source))
      (spit source "leaf\n")
      (spit runner "root\n")
      (is (= pack (instrument-oracle/verify-instrument-files! pack root)))
      (spit (io/file root "instrument" "new.txt") "new\n")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"aggregate digest mismatch"
                            (instrument-oracle/verify-instrument-files! pack root)))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))
