(ns loyly.pi-harness.core.oracle.oracle-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.core.oracle.model-resolver :as model-resolver]
            [loyly.pi-harness.core.oracle.oci-oracle :as oci-oracle]
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

(def oracle-layer-types
  [["catalog.json" "application/vnd.loyly.product-oracle.catalog.v1+json"]])

(def fixture-server-layer
  ["fixture-server.mjs" "application/vnd.loyly.oracle.fixture-server.v1+javascript"])

(def otel-capture-policy-layer
  ["runtime-observability/capture-policy.json"
   "application/vnd.loyly.oracle.otel-capture-policy.v1+json"])

(def grader-layer
  ["grader/grader.sh" "application/vnd.loyly.oracle.grader-file.v1"])

(defn- oracle-layer [title media-type]
  {:mediaType media-type
   :digest (str "sha256:" (apply str (repeat 64 "a")))
   :size 1
   :annotations {:org.opencontainers.image.title title}})

(deftest registry-artifact-resolves-a-product-oracle
  (let [pack-ref (str "ghcr.io/scope-impact/loyly/product-oracle@sha256:"
                      (apply str (repeat 64 "b")))
        layer-types (conj oracle-layer-types fixture-server-layer
                          otel-capture-policy-layer grader-layer)
        layers (mapv (fn [[title media-type]] (oracle-layer title media-type)) layer-types)
        descriptor {:digest (str "sha256:" (apply str (repeat 64 "b")))}
        manifest {:artifactType oci-oracle/artifact-type :layers layers}]
    (with-redefs [shell/sh
                  (fn [& arguments]
                    (let [arguments (vec arguments)]
                      (cond
                        (some #{"--oci-layout"} arguments)
                        {:exit 1 :out "" :err "registry reference used local OCI layout mode"}

                        (= "--descriptor" (last arguments))
                        {:exit 0 :out (json/write-str descriptor) :err ""}

                        (= "manifest" (second arguments))
                        {:exit 0 :out (json/write-str manifest) :err ""}

                        (= "pull" (second arguments))
                        (let [output-index (.indexOf arguments "--output")
                              output (io/file (get arguments (inc output-index)))]
                          (doseq [[title media-type] layer-types]
                            (let [file (io/file output title)]
                              (some-> file .getParentFile .mkdirs)
                              (spit file
                                    (if (contains? #{oci-oracle/fixture-server-type
                                                     oci-oracle/grader-file-type}
                                                   media-type)
                                      "payload"
                                      (json/write-str {:version 1})))))
                          {:exit 0 :out "" :err ""})

                        :else
                        {:exit 1 :out "" :err (str "unexpected ORAS arguments " arguments)})))]
      (let [pack (oci-oracle/resolve-pack pack-ref)]
        (is (= (:digest descriptor) (:digest pack)))
        (is (= (mapv first layer-types) (mapv :title (:layers pack))))))))

(def test-fixture-server-source
  (str "import http from 'node:http';\n"
       "const role=process.argv[3];\n"
       "const server=http.createServer((_q,r)=>{r.end(role==='carried'?'pass':'fail')});\n"
       "server.listen(0,'127.0.0.1',()=>console.log('http://127.0.0.1:'+server.address().port));\n"))

(defn scenario-digest [root item-id]
  (->> (:items (model-resolver/resolve-contract
                root {:projectId "loyly" :items [{:id item-id}]}))
       (some #(when (= item-id (:id %)) (:scenario-digest %)))))

(defn selection-for [root item-id]
  {:projectId "loyly"
   :slice "01-booking-core"
   :items [{:id item-id :scenario-digest (scenario-digest root item-id)}]})

(defn catalog-entry
  ([root item-id] (catalog-entry root item-id ["carried" "uncarried"]))
  ([root item-id roles]
   {:itemId item-id
    :scenarioDigest (scenario-digest root item-id)
    :grader {:path "grader.sh"
             :digest (value-digest test-grader-source)}
    :fixtures (mapv (fn [role] {:role role :expected (if (= role "carried") "pass" "fail")})
                    roles)}))

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

(deftest whole-system-catalogue-selects-only-the-approved-item
  (let [scenario-digest (apply str (repeat 64 "1"))
        selected {:id "@item-01-001" :scenario-digest scenario-digest}
        pack {:digest (str "sha256:" (apply str (repeat 64 "b")))
              :layers [{:media-type "application/vnd.loyly.product-oracle.catalog.v1+json"
                        :content {:version 1
                                  :projectId "loyly"
                                  :minimumCalibrationFixtures 2
                                  :entries [{:itemId "@item-01-001"
                                             :scenarioDigest scenario-digest
                                             :grader {:path "grader.sh"
                                                      :digest (value-digest test-grader-source)}
                                             :fixtures [{:role "carried" :expected "pass"}
                                                        {:role "uncarried" :expected "fail"}]}
                                            {:itemId "@item-99-999"
                                             :scenarioDigest (apply str (repeat 64 "9"))
                                             :grader {:path "other.sh"
                                                      :digest (apply str (repeat 64 "9"))}
                                             :fixtures [{:role "carried" :expected "pass"}
                                                        {:role "uncarried" :expected "fail"}]}]}}]}
        result (oci-oracle/validate-pack!
                pack
                {:projectId "loyly" :items [selected]}
                {:items [selected] :artifacts []})]
    (is (= ["@item-01-001"] (:selected-item-ids result)))
    (is (= 1 (count (:selected-entries result))))))

(deftest only-selected-grader-files-are-materialized
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-selected-graders-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        encode #(.encodeToString (java.util.Base64/getEncoder) (.getBytes % "UTF-8"))
        pack {:digest (str "sha256:" (apply str (repeat 64 "b")))
              :layers [{:media-type oci-oracle/grader-file-type
                        :title "grader/selected.sh" :content (encode "selected")}
                       {:media-type oci-oracle/grader-file-type
                        :title "grader/unselected.sh" :content (encode "unselected")}] }]
    (try
      (let [grader-root (oci-oracle/materialize-grader!
                         pack root #{"selected.sh"})]
        (is (.isFile (io/file grader-root "selected.sh")))
        (is (not (.exists (io/file grader-root "unselected.sh")))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(defn with-oras-pack
  ([layers f] (with-oras-pack layers true f))
  ([layers runnable? f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-oracle-pack-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        source (io/file root "source")
        layout (io/file root "layout")
        layer-types (cond-> oracle-layer-types
                      runnable? (conj fixture-server-layer otel-capture-policy-layer)
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
                (json/write-str
                 (get layers name
                      (case name
                        "catalog.json"
                        {:version 1 :projectId "loyly"
                         :minimumCalibrationFixtures 2 :entries []}

                        "runtime-observability/capture-policy.json"
                        {:version 1
                         :standard "opentelemetry"
                         :target {:openPorts [8080]}
                         :signals ["traces" "metrics" "profiles"]
                         :format "otlp-json"
                         :purpose "diagnostic"
                         :authoritativeGrade false}
                        {}))))))
      (let [result (apply shell/sh
                          (concat ["oras" "push" "--oci-layout" (str layout ":accepted")
                                   "--artifact-type" oci-oracle/artifact-type]
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
        {"catalog.json" {:version 1 :projectId "loyly"
                         :minimumCalibrationFixtures 2
                         :entries [(catalog-entry root "@item-01-001")]}}
        (fn [pack-ref]
          (let [oracle-cache (io/file root ".pi" "harness" ".state" "oracles")
                result (oracle/evaluate-cut
                        root
                        (selection-for root "@item-01-001")
                        {:oracle-pack-ref pack-ref
                         :oracle-cache-root oracle-cache
                         :fixture-runner calibrated-fixture-runner})]
            (is (true? (:passed result)))
            (is (re-matches #"sha256:[0-9a-f]{64}" (:oracle-pack-digest result)))
            (is (= (mapv second (conj oracle-layer-types fixture-server-layer
                                      otel-capture-policy-layer grader-layer))
                   (mapv :media-type (:oracle-layers result))))
            (is (.isFile (io/file (:grader-root result) "grader.sh")))
            (is (.isFile (io/file (:observability-root result) "capture-policy.json")))
            (let [layer (some #(when (= "runtime-observability/capture-policy.json" (:title %)) %)
                              (:oracle-layers result))]
              (is (= (str "sha256:" (file-digest
                                      (io/file (:observability-root result) "capture-policy.json")))
                     (:digest layer))))
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
        {"catalog.json" {:version 1 :projectId "another-project"
                         :minimumCalibrationFixtures 2
                         :entries [(catalog-entry root "@item-01-001")]}}
        (fn [pack-ref]
          (let [result (oracle/evaluate-cut
                        root
                        (selection-for root "@item-01-001")
                        {:oracle-pack-ref pack-ref})]
            (is (false? (:passed result)))
            (is (re-find #"catalogue names project.*another-project.*loyly" (:stderr result)))))))))

(deftest oracle-rejects-a-pack-that-omits-a-selected-item
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (with-oras-pack
        {"catalog.json" {:version 1 :projectId "loyly"
                         :minimumCalibrationFixtures 2
                         :entries [{:itemId "@item-01-999"
                                    :scenarioDigest (apply str (repeat 64 "9"))
                                    :grader {:path "grader.sh"
                                             :digest (value-digest test-grader-source)}
                                    :fixtures [{:role "carried" :expected "pass"}
                                               {:role "uncarried" :expected "fail"}]}]}}
        (fn [pack-ref]
          (let [result (oracle/evaluate-cut
                        root
                        (selection-for root "@item-01-001")
                        {:oracle-pack-ref pack-ref})]
            (is (false? (:passed result)))
            (is (re-find #"selected item.*@item-01-001.*product catalogue"
                         (:stderr result)))))))))

(deftest product-oracle-is-not-bound-to-unrelated-model-files
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (with-oras-pack
        {"catalog.json" {:version 1 :projectId "loyly"
                         :minimumCalibrationFixtures 2
                         :entries [(catalog-entry root "@item-01-001")]}}
        (fn [pack-ref]
          (spit (io/file root "model" "loyly" "tokens.json") "unrelated change\n")
          (let [result (oracle/evaluate-cut
                        root
                        (selection-for root "@item-01-001")
                        {:oracle-pack-ref pack-ref
                         :fixture-runner calibrated-fixture-runner})]
            (is (true? (:passed result)))))))))

(deftest oracle-rejects-an-item-without-both-calibration-roles
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (with-oras-pack
        {"catalog.json" {:version 1 :projectId "loyly"
                         :minimumCalibrationFixtures 2
                         :entries [(catalog-entry root "@item-01-001" ["carried"])]}}
        (fn [pack-ref]
          (let [result (oracle/evaluate-cut
                        root
                        (selection-for root "@item-01-001")
                        {:oracle-pack-ref pack-ref})]
            (is (false? (:passed result)))
            (is (re-find #"@item-01-001.*carried and uncarried calibration fixtures"
                         (:stderr result)))))))))

(deftest oracle-rejects-a-pack-with-a-stale-grader-digest
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (let [entry (assoc-in (catalog-entry root "@item-01-001")
                            [:grader :digest]
                            (apply str (repeat 64 "0")))]
        (with-oras-pack
          {"catalog.json" {:version 1 :projectId "loyly"
                           :minimumCalibrationFixtures 2 :entries [entry]}}
          (fn [pack-ref]
            (let [result (oracle/evaluate-cut
                          root
                          (selection-for root "@item-01-001")
                          {:oracle-pack-ref pack-ref})]
              (is (false? (:passed result)))
              (is (re-find #"grader digest mismatch.*grader.sh" (:stderr result))))))))))

(deftest oracle-rejects-a-stale-scenario-digest-for-a-selected-item
  (with-cut "Feature: Booking\n\n  Rule: One source\n\n    @item-01-001\n    Scenario: Book a slot\n"
    (fn [root]
      (with-oras-pack
        {"catalog.json" {:version 1 :projectId "loyly"
                         :minimumCalibrationFixtures 2
                         :entries [(assoc (catalog-entry root "@item-01-001")
                                          :scenarioDigest (apply str (repeat 64 "0")))]}}
        (fn [pack-ref]
          (let [result (oracle/evaluate-cut
                        root
                        (selection-for root "@item-01-001")
                        {:oracle-pack-ref pack-ref})]
            (is (false? (:passed result)))
            (is (re-find #"catalogue scenario digest.*@item-01-001.*approved selection"
                         (:stderr result)))))))))

(deftest oracle-executes-runnable-fixtures-instead-of-trusting-declarations
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (with-oras-pack
        {"catalog.json" {:version 1 :projectId "loyly"
                         :minimumCalibrationFixtures 2
                         :entries [(catalog-entry root "@item-01-001")]}
         "fixture-server.mjs"
         (str "import http from 'node:http';\n"
              "const server=http.createServer((_q,r)=>{r.end('pass')});\n"
              "server.listen(0,'127.0.0.1',()=>console.log('http://127.0.0.1:'+server.address().port));\n")}
        (fn [pack-ref]
          (let [result (oracle/evaluate-cut
                        root
                        (selection-for root "@item-01-001")
                        {:oracle-pack-ref pack-ref
                         :fixture-runner #(fixture-result % (constantly "pass"))})]
            (is (false? (:passed result)))
            (is (re-find #"calibration fixture.*uncarried.*expected fail.*observed pass"
                         (:stderr result)))))))))

(deftest runnable-oracle-calibration-fails-closed-without-gondolin
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (with-oras-pack
        {"catalog.json" {:version 1 :projectId "loyly"
                         :minimumCalibrationFixtures 2
                         :entries [(catalog-entry root "@item-01-001")]}}
        (fn [pack-ref]
          (let [result (oracle/evaluate-cut
                        root
                        (selection-for root "@item-01-001")
                        {:oracle-pack-ref pack-ref})]
            (is (false? (:passed result)))
            (is (re-find #"Gondolin fixture runner is required" (:stderr result)))))))))

(deftest oracle-rejects-a-declaration-only-pack-without-runnable-fixtures
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (with-oras-pack
        {"catalog.json" {:version 1 :projectId "loyly"
                         :minimumCalibrationFixtures 2
                         :entries [(catalog-entry root "@item-01-001")]}}
        false
        (fn [pack-ref]
          (let [result (oracle/evaluate-cut
                        root
                        (selection-for root "@item-01-001")
                        {:oracle-pack-ref pack-ref})]
            (is (false? (:passed result)))
            (is (re-find #"runnable fixture server layer" (:stderr result)))))))))

(deftest oracle-rejects-inverted-carried-and-uncarried-expectations
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (let [entry (update (catalog-entry root "@item-01-001") :fixtures
                          (fn [fixtures]
                            (mapv #(assoc % :expected "fail") fixtures)))]
        (with-oras-pack
          {"catalog.json" {:version 1 :projectId "loyly"
                           :minimumCalibrationFixtures 2 :entries [entry]}
           "fixture-server.mjs"
           (str "import http from 'node:http';\n"
                "const server=http.createServer((_q,r)=>{r.end('fail')});\n"
                "server.listen(0,'127.0.0.1',()=>console.log('http://127.0.0.1:'+server.address().port));\n")}
          (fn [pack-ref]
            (let [result (oracle/evaluate-cut
                          root
                          (selection-for root "@item-01-001")
                          {:oracle-pack-ref pack-ref})]
              (is (false? (:passed result)))
              (is (re-find #"carried fixture.*must expect pass" (:stderr result))))))))))

(deftest measured-oracle-evaluation-requires-a-pinned-product-catalogue
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (let [result (oracle/evaluate-cut root (selection-for root "@item-01-001"))]
        (is (false? (:passed result)))
        (is (re-find #"pinned product oracle is required for measured regeneration"
                     (:stderr result)))))))

(deftest scenario-digest-does-not-cover-unrelated-project-model-artifacts
  (with-cut "Feature: Booking\n\n  @item-01-001\n  Scenario: Book a slot\n"
    (fn [root]
      (let [first-digest (scenario-digest root "@item-01-001")]
        (spit (io/file root "model" "loyly" "tokens.json") "changed\n")
        (is (= first-digest (scenario-digest root "@item-01-001")))))))

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
