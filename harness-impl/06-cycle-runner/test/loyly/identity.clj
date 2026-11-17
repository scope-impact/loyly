(ns loyly.identity (:require [loyly.test-runner :as f] [loyly.domain :as d] [clojure.java.io :as io] [clojure.data.json :as json] [clojure.java.shell :as sh] [clojure.string :as str]))
(defn -main [& _]
  (let [_ (reset! f/model-control {:reply (fn [request default]
            (if (str/includes? request "[[ ## code ## ]]")
              "[[ ## reasoning ## ]]\nRead the checklist item and run its candidate.\n[[ ## code ## ]]\n(let [item (get-input :item)] (when-not (string? (:item-id item)) (/ 1 0)) (let [r (shell {\"command\" \"sh impl/start.sh\"})] (println (str \"FINAL\" \"_ANSWER: \" (pr-str {:evidence (str (:item-id item) (pr-str r))})))))\n[[ ## completed ## ]]"
              default))})
        server (f/model-server) initial (f/fixture)
        repo (get-in initial [:env "LOYLY_PROJECT_REPO"])
        _ (spit (str repo "/plan/01-demo/spec.feature") "Feature: Example\n  @item-01-001\n  Scenario: One real item\n    Given the fixture\n")
        _ (sh/sh "git" "-C" repo "add" ".")
        _ (sh/sh "git" "-C" repo "-c" "user.name=Fixture" "-c" "user.email=fixture@example.invalid" "commit" "-qm" "one item")
        fixture (assoc initial :commit (str/trim (:out (sh/sh "git" "-C" repo "rev-parse" "HEAD")))) root (:root fixture)
        env (assoc (:env fixture) "LOYLY_MODEL_API_BASE" (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1") "LOYLY_GRAIN_STORE" (str root "/identity.sqlite"))
        pb (ProcessBuilder. ^java.util.List [(.getAbsolutePath (io/file "start.sh"))])
        _ (.putAll (.environment pb) env) _ (.redirectError pb (io/file root "stderr.log"))
        started (System/nanoTime) process (.start pb) writer (io/writer (.getOutputStream process)) reader (io/reader (.getInputStream process))
        request (fn [id method params]
          (.write writer (str (json/write-str {:jsonrpc "2.0" :id id :method method :params params}) "\n")) (.flush writer)
          (let [line (deref (future (.readLine ^java.io.BufferedReader reader)) 120000 ::timeout)]
            (assert (and (string? line) (seq line)) (str "No response " line))
            (let [r (json/read-str line :key-fn keyword)] (assert (= id (:id r))) (println "RESPONSE" id (pr-str r)) r)))]
    (try
      (assert (= "not-a-commit" (get-in (request 1 "cycle-runner/start-run" (assoc (f/params fixture "invalid") :commit (apply str (repeat 40 "d")))) [:error :data :refusal])))
      (let [config (get env "LOYLY_HARNESS_CONFIG") original (slurp config)]
        (spit config (str original "BUILDER_IMAGE=missing-image\n"))
        (assert (= "builder-image-missing" (get-in (request 2 "cycle-runner/start-run" (f/params fixture "image")) [:error :data :refusal])))
        (spit config original))
      (assert (:result (request 3 "cycle-runner/start-run" (f/params fixture "rubric"))))
      (let [run (get-in (request 4 "cycle-runner/run-progress" {:run-id "R-rubric"}) [:result :run])]
        (assert (= ["stamp" "pin" "checklist"] (mapv :effect (:effect-results run))))
        (assert (= "grader" (get-in run [:halt :effect]))))
      (println "INITIAL IDENTITY SECONDS" (/ (- (System/nanoTime) started) 1e9))
      (assert (:result (request 5 "cycle-runner/start-run" (assoc (f/params fixture "approved") :grader-digests [(d/digest (str "cycle-runner/grade.v1\n" (slurp (get env "LOYLY_GRADER_RUBRIC"))))]))))
      (let [run (get-in (request 6 "cycle-runner/run-progress" {:run-id "R-approved"}) [:result :run])]
        (assert (= "passed" (:status run))) (assert (= 1 (count (:scores run))))
        (assert (every? (set (map :effect (:effect-results run))) ["self-check" "fence" "regenerate" "file-verdicts" "file-score"])))
      (println "IDENTITY PASSED" root "seconds" (/ (- (System/nanoTime) started) 1e9))
      (finally (.close writer) (.destroy process) (.waitFor process) (.stop server 0) (shutdown-agents)))))
