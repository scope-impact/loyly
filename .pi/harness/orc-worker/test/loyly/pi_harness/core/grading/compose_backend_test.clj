(ns loyly.pi-harness.core.grading.compose-backend-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [loyly.pi-harness.core.grading.grader-commands :as grader-commands]
            [loyly.pi-harness.core.build.project-profile :as project-profile]
            [loyly.pi-harness.core.build.stack-conformance :as stack-conformance]
            [loyly.pi-harness.core.grading.compose :as compose]))

(deftest evidence-compose-overlay-is-valid-json-with-literal-paths
  (is (not (str/includes? grader-commands/evidence-compose-json "\\/")))
  (is (= "${HARNESS_EVIDENCE_ROOT}"
         (get-in (json/read-str grader-commands/evidence-compose-json :key-fn keyword)
                 [:services :app :volumes 0 :source]))))

(deftest verification-rejects-published-host-ports
  (let [evidence {:compose-config {:exitCode 0
                                   :stdout "{\"services\":{\"app\":{\"healthcheck\":{\"test\":[\"CMD\",\"true\"]},\"ports\":[{\"published\":\"8080\"}]},\"test\":{}}}"
                                   :stderr ""}
                  :compose-services {:exitCode 0 :stdout "app\ntest\n" :stderr ""}
                  :compose-test {:exitCode 0 :stdout "tests passed" :stderr ""}
                  :compose-up {:exitCode 0 :stdout "started" :stderr ""}
                  :compose-logs {:exitCode 0 :stdout "app ready" :stderr ""}
                  :compose-down {:exitCode 0 :stdout "removed" :stderr ""}}
        result (compose/interpret-verification evidence)]
    (is (false? (:passed result)))
    (is (re-find #"must not publish host ports" (:stderr result)))))

(deftest instrument-command-is-derived-from-project-and-selected-items
  (let [command (-> (grader-commands/commands
                     "instrument" "gondolin"
                     {:project-id "another-project"
                      :item-ids ["@item-alpha-001" "@item-beta-002"]})
                    first
                    :command)]
    (is (str/includes? command "/grader/runtime-conformance.py"))
    (is (str/includes? command "/workspace/model/another-project/runtime-evidence-contract.json"))
    (is (str/includes? command "--item @item-alpha-001"))
    (is (str/includes? command "--item @item-beta-002"))
    (is (not (str/includes? command "01-007")))))

(deftest instrument-command-rejects-a-project-path-segment
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"safe project identity"
       (grader-commands/commands "instrument" "gondolin"
                                 {:project-id ".." :item-ids ["@item-alpha-001"]}))))

(deftest project-runtime-profile-is-derived-from-conventional-files
  (let [project-root ".."
        profile (project-profile/load! project-root "loyly")]
    (is (= "model/loyly" (:modelRoot profile)))
    (is (= "loyly-black-box" (:graderProducer profile)))
    (is (= "harness/loyly/gondolin.json" (:gondolinVmConfig profile)))
    (is (= "harness/loyly/Dockerfile.rust" (get-in profile [:stackDockerfiles :rust])))
    (is (= "booking-core-01-008.sh"
           (project-profile/grader-script
            (project-profile/resolved project-root {:projectId "loyly" :componentId "booking-core"})
            "@item-01-008")))))

(deftest behavioral-grader-is-selected-by-project-and-item
  (is (= "booking-core-01-008.sh"
         (project-profile/grader-script
          {:itemIdPrefix "@item-"
           :itemGraderScript "booking-core-{itemSuffix}.sh"}
          "@item-01-008"))))

(deftest gondolin-loads-the-pinned-target-runtime-before-compose
  (let [command (-> (grader-commands/commands
                     "compose" "gondolin"
                     {:runtime-image-archive "rust-1.91-alpine.tar"})
                    first :command)]
    (is (str/includes? command
                       "docker load -i /runtime-images/rust-1.91-alpine.tar"))
    (is (not (str/includes? command "unsupported offline base image")))))

(deftest gondolin-node-fallback-imports-only-transitive-runtime-libraries
  (is (str/includes? grader-commands/gondolin-runtime
                     "for executable in /bin/* /usr/bin/node /usr/bin/dirname"))
  (is (str/includes? grader-commands/gondolin-runtime
                     "tar -ch bin tmp usr/bin/node usr/bin/dirname $libs")))

(deftest independent-grading-starts-the-candidate-through-its-compose-container
  (let [commands (grader-commands/commands
                  "independent" "gondolin"
                  {:grader-script "booking-core-01-007.sh"
                   :item-ids ["@item-01-007"]
                   :runtime-image-archive "rust-1.91-alpine.tar"})
        by-name (into {} (map (juxt :name :command) commands))]
    (is (str/includes? (get by-name "independent-runtime")
                       "docker load -i /runtime-images/rust-1.91-alpine.tar"))
    (is (str/includes? (get by-name "independent-up") "docker compose"))
    (is (str/includes? (get by-name "independent-up") "docker inspect"))
    (is (str/includes? (get by-name "independent-observe-start") "otelcol-contrib"))
    (is (str/includes? (get by-name "independent-observe-start") "otelcol-ebpf-profiler"))
    (is (str/includes? (get by-name "independent-observe-start") "OTEL_EBPF_OPEN_PORT=8080"))
    (is (str/includes? (get by-name "independent-observe-start") "/usr/local/bin/obi"))
    (is (not (str/includes? (get by-name "independent-observe-start") "bpftrace")))
    (is (not (str/includes? (get by-name "independent-up") "./impl/start.sh")))
    (is (str/includes? (get by-name "independent-grade")
                       "/tmp/pi-harness-independent-base-url"))
    (is (str/includes? (get by-name "independent-observe-stop") "kill -INT"))
    (is (< (.indexOf (mapv :name commands) "independent-observe-start")
           (.indexOf (mapv :name commands) "independent-grade")))
    (is (< (.indexOf (mapv :name commands) "independent-grade")
           (.indexOf (mapv :name commands) "independent-observe-stop")))))

(deftest independent-grading-scopes-candidate-runtime-evidence-to-the-item
  (let [command (-> (grader-commands/commands
                     "independent" "gondolin"
                     {:grader-script "booking-core-01-007.sh"
                      :item-ids ["@item-01-007"]
                      :runtime-image-archive "rust-1.91-alpine.tar"})
                    (->> (filter #(= "independent-up" (:name %))))
                    first
                    :command)]
    (is (str/includes? command
                       "-f /tmp/pi-harness-independent-evidence-compose.json"))
    (is (str/includes? command
                       "OTEL_FILE_EXPORT_DIR\\\":\\\"/evidence/otel/independent/@item-01-007"))))

(deftest requested-rust-stack-rejects-a-node-substitute
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-stack-conformance-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        impl (doto (java.io.File. root "impl") .mkdirs)]
    (try
      (spit (java.io.File. impl "Dockerfile") "FROM node:22-alpine\n")
      (spit (java.io.File. impl "server.js") "console.log('substitute')\n")
      (let [result (stack-conformance/check (.getAbsolutePath root) "rust")]
        (is (false? (:passed result)))
        (is (str/includes? (:stderr result) "pi-harness/rust:1.91-web"))
        (is (str/includes? (:stderr result) "requested rust stack")))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest requested-rust-stack-accepts-rust-source-and-pinned-base
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "loyly-stack-conformance-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        impl (doto (java.io.File. root "impl") .mkdirs)]
    (try
      (spit (java.io.File. impl "Dockerfile") "# generated\nFROM pi-harness/rust:1.91-web\n")
      (spit (java.io.File. impl "main.rs") "fn main() {}\n")
      (is (true? (:passed (stack-conformance/check (.getAbsolutePath root) "rust"))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest node-stack-loads-the-real-base-image-archive
  ;; The faked node base (docker import of the VM's node binary) has no npm, unlike the
  ;; real image the builder targets, so node grading loads the real archive instead.
  (let [stack (project-profile/stack-requirements "typescript")]
    (is (= "node:22-alpine" (:base stack)))
    (is (= "node-22-alpine.tar" (:archive stack)))
    (is (str/ends-with? (#'grader-commands/runtime-command "gondolin" (:archive stack))
                        "docker load -i /runtime-images/node-22-alpine.tar"))))
