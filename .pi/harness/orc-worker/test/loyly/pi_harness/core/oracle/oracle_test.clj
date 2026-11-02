(ns loyly.pi-harness.core.oracle.oracle-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.core.oracle.oracle :as oracle]))

(def required-model-files
  ["booking-core.allium"
   "booking_core_event_model.clj"
   "affordances.yaml"
   "tokens.json"])

(def test-grader-source
  (str "#!/usr/bin/env bash\n"
       "set -uo pipefail\n"
       "BASE_URL=\"${1:?missing base URL}\"\n"
       "verdict=\"$(curl -fsS \"$BASE_URL/verdict\")\" || exit 2\n"
       "if printf '%s' \"$verdict\" | grep -q pass; then\n"
       "  printf '%s\\n' '[{\"itemId\":\"01-001\",\"verdict\":\"pass\",\"evidence\":\"fixture passed\"}]'\n"
       "  exit 0\n"
       "fi\n"
       "printf '%s\\n' '[{\"itemId\":\"01-001\",\"verdict\":\"fail\",\"evidence\":\"fixture failed\"}]'\n"
       "exit 1\n"))

(defn value-digest [value]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest (.getBytes value "UTF-8"))))))

(defn with-cut [spec f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-oracle-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        model (io/file root "model" "loyly")]
    (try
      (.mkdirs model)
      (spit (io/file model "spec.feature") spec)
      (doseq [name required-model-files]
        (spit (io/file model name) (str name "\n")))
      (f root)
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(defn file-digest [file]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [input (io/input-stream file)]
      (let [buffer (byte-array 8192)]
        (loop []
          (let [n (.read input buffer)]
            (when (pos? n)
              (.update digest buffer 0 n)
              (recur))))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn project-artifacts [root]
  (let [model-root (io/file root "model" "loyly")]
    (->> (file-seq model-root)
         (filter #(.isFile %))
         (sort-by #(.getPath %))
         (mapv (fn [file]
                 {:path (str "model/loyly/" (.getName file))
                  :digest (file-digest file)})))))

(def oracle-layer-types
  [["policy.json" "application/vnd.loyly.oracle.policy.v1+json"]
   ["project.json" "application/vnd.loyly.oracle.project.v1+json"]
   ["slice.json" "application/vnd.loyly.oracle.slice.v1+json"]
   ["calibration.json" "application/vnd.loyly.oracle.calibration.v1+json"]])

(def fixture-server-layer
  ["fixture-server.mjs" "application/vnd.loyly.oracle.fixture-server.v1+javascript"])

(def grader-layer
  ["grader/grader.sh" "application/vnd.loyly.oracle.grader-file.v1"])

(def test-fixture-server-source
  (str "import http from 'node:http';\n"
       "const role=process.argv[3];\n"
       "const server=http.createServer((_q,r)=>{r.end(role==='carried'?'pass':'fail')});\n"
       "server.listen(0,'127.0.0.1',()=>console.log('http://127.0.0.1:'+server.address().port));\n"))

(defn calibration-entry
  ([item-id] (calibration-entry item-id ["carried" "uncarried"]))
  ([item-id roles]
   {:itemId item-id
    :grader {:path "grader.sh"
             :digest (value-digest test-grader-source)}
    :fixtures (mapv (fn [role] {:role role :expected (if (= role "carried") "pass" "fail")})
                    roles)}))

(defn slice-entry
  ([item-id] (slice-entry item-id "Book a slot" nil))
  ([item-id name rule]
   {:id item-id :name name :rule rule}))

(defn fixture-result [request verdict-for]
  {:backend "gondolin"
   :cases (mapv (fn [{:keys [itemId role graderPath]}]
                  (let [verdict (verdict-for role)]
                    {:itemId itemId :role role :graderPath graderPath
                     :exitCode (if (= "pass" verdict) 0 1)
                     :stdout (json/write-str [{:verdict verdict}])
                     :stderr ""}))
                (:cases request))})

(defn calibrated-fixture-runner [request]
  (fixture-result request #(if (= "carried" %) "pass" "fail")))

(defn with-oras-pack
  ([layers f] (with-oras-pack layers true f))
  ([layers runnable? f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-oracle-pack-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        source (io/file root "source")
        layout (io/file root "layout")
        layer-types (cond-> oracle-layer-types
                      runnable? (conj fixture-server-layer)
                      true (conj grader-layer))]
    (try
      (.mkdirs source)
      (doseq [[name _] layer-types]
        (some-> (io/file source name) .getParentFile .mkdirs)
        (spit (io/file source name)
              (cond
                (= name "fixture-server.mjs")
                (get layers name test-fixture-server-source)

                (= name "grader/grader.sh")
                (get layers name test-grader-source)

                :else
                (json/write-str (get layers name {})))))
      (let [result (apply shell/sh
                          (concat ["oras" "push" "--oci-layout" (str layout ":accepted")
                                   "--artifact-type" "application/vnd.loyly.oracle.pack.v1"]
                                  (map (fn [[name media-type]] (str name ":" media-type))
                                       layer-types)
                                  [:dir (.getPath source)]))]
        (is (zero? (:exit result)) (:err result)))
      (f (str layout ":accepted"))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file)))))))

(deftest oracle-binds-a-real-runnable-oras-pack
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (with-oras-pack
        {"policy.json" {:version 1}
         "project.json" {:projectId "loyly" :artifacts (project-artifacts root)}
         "slice.json" {:sliceId "01-booking-core" :items [(slice-entry "@item-01-001")]}
         "calibration.json" {:items [(calibration-entry "@item-01-001")]}}
        (fn [pack-ref]
          (let [oracle-cache (io/file root ".pi" "harness" ".state" "oracles")
                result (oracle/evaluate-cut
                        root
                        {:projectId "loyly" :slice "01-booking-core"
                         :items [{:id "@item-01-001"}]}
                        {:oracle-pack-ref pack-ref
                         :oracle-cache-root oracle-cache
                         :fixture-runner calibrated-fixture-runner})]
            (is (true? (:passed result)))
            (is (re-matches #"sha256:[0-9a-f]{64}" (:oracle-pack-digest result)))
            (is (= (mapv second (conj oracle-layer-types fixture-server-layer grader-layer))
                   (mapv :media-type (:oracle-layers result))))
            (is (.isFile (io/file (:grader-root result) "grader.sh")))
            (is (= test-grader-source
                   (slurp (io/file (:grader-root result) "grader.sh"))))
            (is (= [{:item-id "@item-01-001" :role "carried"
                     :expected "pass" :observed "pass" :backend "gondolin"}
                    {:item-id "@item-01-001" :role "uncarried"
                     :expected "fail" :observed "fail" :backend "gondolin"}]
                   (:calibration-results result)))))))))

(deftest oracle-rejects-an-oci-project-layer-for-another-project
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (with-oras-pack
        {"policy.json" {:version 1}
         "project.json" {:projectId "another-project" :artifacts (project-artifacts root)}
         "slice.json" {:sliceId "01-booking-core" :items [(slice-entry "@item-01-001")]}
         "calibration.json" {:items [(calibration-entry "@item-01-001")]}}
        (fn [pack-ref]
          (let [result (oracle/evaluate-cut
                        root
                        {:projectId "loyly" :slice "01-booking-core"
                         :items [{:id "@item-01-001"}]}
                        {:oracle-pack-ref pack-ref})]
            (is (false? (:passed result)))
            (is (re-find #"project layer.*another-project.*loyly" (:stderr result)))))))))

(deftest oracle-rejects-a-pack-that-omits-a-selected-item
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (with-oras-pack
        {"policy.json" {:version 1}
         "project.json" {:projectId "loyly" :artifacts (project-artifacts root)}
         "slice.json" {:sliceId "01-booking-core" :items [(slice-entry "@item-01-999")]}
         "calibration.json" {:items [(calibration-entry "@item-01-999")]}}
        (fn [pack-ref]
          (let [result (oracle/evaluate-cut
                        root
                        {:projectId "loyly" :slice "01-booking-core"
                         :items [{:id "@item-01-001"}]}
                        {:oracle-pack-ref pack-ref})]
            (is (false? (:passed result)))
            (is (re-find #"selected item.*@item-01-001.*slice layer"
                         (:stderr result)))))))))

(deftest oracle-rejects-a-pack-with-a-stale-model-artifact
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (let [artifacts (assoc-in (project-artifacts root) [0 :digest]
                                (apply str (repeat 64 "0")))]
        (with-oras-pack
          {"policy.json" {:version 1}
           "project.json" {:projectId "loyly" :artifacts artifacts}
           "slice.json" {:sliceId "01-booking-core" :items [(slice-entry "@item-01-001")]}
           "calibration.json" {:items [(calibration-entry "@item-01-001")]}}
          (fn [pack-ref]
            (let [result (oracle/evaluate-cut
                          root
                          {:projectId "loyly" :slice "01-booking-core"
                           :items [{:id "@item-01-001"}]}
                          {:oracle-pack-ref pack-ref})]
              (is (false? (:passed result)))
              (is (re-find #"project artifacts do not match the pinned model contract"
                           (:stderr result))))))))))

(deftest oracle-rejects-an-item-without-both-calibration-roles
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (with-oras-pack
        {"policy.json" {:version 1 :minimumCalibrationFixtures 2}
         "project.json" {:projectId "loyly" :artifacts (project-artifacts root)}
         "slice.json" {:sliceId "01-booking-core" :items [(slice-entry "@item-01-001")]}
         "calibration.json" {:items [(calibration-entry "@item-01-001" ["carried"])]}}
        (fn [pack-ref]
          (let [result (oracle/evaluate-cut
                        root
                        {:projectId "loyly" :slice "01-booking-core"
                         :items [{:id "@item-01-001"}]}
                        {:oracle-pack-ref pack-ref})]
            (is (false? (:passed result)))
            (is (re-find #"@item-01-001.*carried and uncarried calibration fixtures"
                         (:stderr result)))))))))

(deftest oracle-rejects-a-pack-with-a-stale-grader-digest
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (let [entry (assoc-in (calibration-entry "@item-01-001")
                            [:grader :digest]
                            (apply str (repeat 64 "0")))]
        (with-oras-pack
          {"policy.json" {:version 1 :minimumCalibrationFixtures 2}
           "project.json" {:projectId "loyly" :artifacts (project-artifacts root)}
           "slice.json" {:sliceId "01-booking-core" :items [(slice-entry "@item-01-001")]}
           "calibration.json" {:items [entry]}}
          (fn [pack-ref]
            (let [result (oracle/evaluate-cut
                          root
                          {:projectId "loyly" :slice "01-booking-core"
                           :items [{:id "@item-01-001"}]}
                          {:oracle-pack-ref pack-ref})]
              (is (false? (:passed result)))
              (is (re-find #"grader digest mismatch.*grader.sh" (:stderr result))))))))))

(deftest oracle-rejects-stale-slice-semantics-for-a-selected-item
  (with-cut "Feature: Booking\n\n  Rule: One source\n\n    @item-01-001\n    Scenario: Book a slot\n"
    (fn [root]
      (with-oras-pack
        {"policy.json" {:version 1 :minimumCalibrationFixtures 2}
         "project.json" {:projectId "loyly" :artifacts (project-artifacts root)}
         "slice.json" {:sliceId "01-booking-core"
                       :items [(slice-entry "@item-01-001" "Stale name" "One source")]}
         "calibration.json" {:items [(calibration-entry "@item-01-001")]}}
        (fn [pack-ref]
          (let [result (oracle/evaluate-cut
                        root
                        {:projectId "loyly" :slice "01-booking-core"
                         :items [{:id "@item-01-001"}]}
                        {:oracle-pack-ref pack-ref})]
            (is (false? (:passed result)))
            (is (re-find #"slice semantics.*@item-01-001.*pinned model contract"
                         (:stderr result)))))))))

(deftest oracle-executes-runnable-fixtures-instead-of-trusting-declarations
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (with-oras-pack
        {"policy.json" {:version 1 :minimumCalibrationFixtures 2}
         "project.json" {:projectId "loyly" :artifacts (project-artifacts root)}
         "slice.json" {:sliceId "01-booking-core" :items [(slice-entry "@item-01-001")]}
         "calibration.json" {:items [(calibration-entry "@item-01-001")]}
         "fixture-server.mjs"
         (str "import http from 'node:http';\n"
              "const server=http.createServer((_q,r)=>{r.end('pass')});\n"
              "server.listen(0,'127.0.0.1',()=>console.log('http://127.0.0.1:'+server.address().port));\n")}
        (fn [pack-ref]
          (let [result (oracle/evaluate-cut
                        root
                        {:projectId "loyly" :slice "01-booking-core"
                         :items [{:id "@item-01-001"}]}
                        {:oracle-pack-ref pack-ref
                         :fixture-runner #(fixture-result % (constantly "pass"))})]
            (is (false? (:passed result)))
            (is (re-find #"calibration fixture.*uncarried.*expected fail.*observed pass"
                         (:stderr result)))))))))

(deftest runnable-oracle-calibration-fails-closed-without-gondolin
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (with-oras-pack
        {"policy.json" {:version 1}
         "project.json" {:projectId "loyly" :artifacts (project-artifacts root)}
         "slice.json" {:sliceId "01-booking-core" :items [(slice-entry "@item-01-001")]}
         "calibration.json" {:items [(calibration-entry "@item-01-001")]}}
        (fn [pack-ref]
          (let [result (oracle/evaluate-cut
                        root
                        {:projectId "loyly" :slice "01-booking-core"
                         :items [{:id "@item-01-001"}]}
                        {:oracle-pack-ref pack-ref})]
            (is (false? (:passed result)))
            (is (re-find #"Gondolin fixture runner is required" (:stderr result)))))))))

(deftest oracle-rejects-a-declaration-only-pack-without-runnable-fixtures
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (with-oras-pack
        {"policy.json" {:version 1 :minimumCalibrationFixtures 2}
         "project.json" {:projectId "loyly" :artifacts (project-artifacts root)}
         "slice.json" {:sliceId "01-booking-core" :items [(slice-entry "@item-01-001")]}
         "calibration.json" {:items [(calibration-entry "@item-01-001")]}}
        false
        (fn [pack-ref]
          (let [result (oracle/evaluate-cut
                        root
                        {:projectId "loyly" :slice "01-booking-core"
                         :items [{:id "@item-01-001"}]}
                        {:oracle-pack-ref pack-ref})]
            (is (false? (:passed result)))
            (is (re-find #"runnable fixture server layer" (:stderr result)))))))))

(deftest oracle-rejects-inverted-carried-and-uncarried-expectations
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (let [entry (update (calibration-entry "@item-01-001") :fixtures
                          (fn [fixtures]
                            (mapv #(assoc % :expected "fail") fixtures)))]
        (with-oras-pack
          {"policy.json" {:version 1 :minimumCalibrationFixtures 2}
           "project.json" {:projectId "loyly" :artifacts (project-artifacts root)}
           "slice.json" {:sliceId "01-booking-core" :items [(slice-entry "@item-01-001")]}
           "calibration.json" {:items [entry]}
           "fixture-server.mjs"
           (str "import http from 'node:http';\n"
                "const server=http.createServer((_q,r)=>{r.end('fail')});\n"
                "server.listen(0,'127.0.0.1',()=>console.log('http://127.0.0.1:'+server.address().port));\n")}
          (fn [pack-ref]
            (let [result (oracle/evaluate-cut
                          root
                          {:projectId "loyly" :slice "01-booking-core"
                           :items [{:id "@item-01-001"}]}
                          {:oracle-pack-ref pack-ref})]
              (is (false? (:passed result)))
              (is (re-find #"carried fixture.*must expect pass" (:stderr result))))))))))

(deftest pinned-model-is-the-native-oracle-contract
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (let [result (oracle/evaluate-cut root {:projectId "loyly"
                                              :items [{:id "@item-01-001"}]})]
        (is (= true (:passed result)))
        (is (= ["@item-01-001"] (:item-ids result)))
        (is (= 5 (count (:artifacts result))))
        (is (re-matches #"[0-9a-f]{64}" (:oracle-digest result)))))))

(deftest oracle-digest-covers-every-project-model-artifact
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (let [selection {:projectId "loyly" :items [{:id "@item-01-001"}]}
            first-digest (:oracle-digest (oracle/evaluate-cut root selection))]
        (spit (io/file root "model" "loyly" "tokens.json") "changed\n")
        (let [second-digest (:oracle-digest (oracle/evaluate-cut root selection))]
          (is (not= first-digest second-digest)))))))

(deftest native-oracle-rejects-duplicate-item-identities
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: First\n\n  @item-01-001\n  Scenario: Second\n"
    (fn [root]
      (let [result (oracle/evaluate-cut root {:projectId "loyly"
                                              :items [{:id "@item-01-001"}]})]
        (is (= false (:passed result)))
        (is (re-find #"duplicate item id @item-01-001" (:stderr result)))))))

(deftest native-oracle-rejects-missing-selected-items
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Existing identity\n"
    (fn [root]
      (let [result (oracle/evaluate-cut root {:projectId "loyly"
                                              :items [{:id "@item-01-999"}]})]
        (is (= false (:passed result)))
        (is (re-find #"unknown items: @item-01-999" (:stderr result)))))))

(deftest native-oracle-rejects-unstamped-scenarios
  (with-cut "Feature: Booking\n\n  Scenario: Missing identity\n"
    (fn [root]
      (let [result (oracle/evaluate-cut root {:projectId "loyly"
                                              :items [{:id "@item-01-001"}]})]
        (is (= false (:passed result)))
        (is (re-find #"unstamped scenario" (:stderr result)))))))
