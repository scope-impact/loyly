(ns loyly.spine
  (:require [clojure.data.json :as json] [clojure.java.io :as io] [clojure.string :as str]
            [clojure.test :refer [deftest is testing]] [loyly.main :as main] [loyly.domain :as d] [loyly.host :as host])
  (:import [com.sun.net.httpserver HttpServer HttpHandler] [java.net InetSocketAddress]))
(defn model ([] (model identity)) ([transform]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/" (reify HttpHandler (handle [_ exchange]
      (let [req (json/read-str (slurp (.getRequestBody exchange)) :key-fn keyword)
            msgs (str/join "\n" (map :content (:messages req)))
            marker (cond (str/includes? msgs "[[ ## level ## ]]") "[[ ## reasoning ## ]]\nbooked\n[[ ## level ## ]]\n2"
                         (str/includes? msgs "[[ ## code ## ]]") "[[ ## reasoning ## ]]\nObserve\n[[ ## code ## ]]\n(final! {:evidence \"fixture answered\"})"
                         :else "[[ ## builder-instruction ## ]]\nBuild the fixture")
            bytes (.getBytes (json/write-str {:id "fixture" :object "chat.completion" :model (:model req)
                                              :choices [{:index 0 :finish_reason "stop" :message {:role "assistant" :content (str ((transform {:marker marker :request req :messages msgs}) :marker) "\n[[ ## completed ## ]]")}}]}) "UTF-8")]
        (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
        (.sendResponseHeaders exchange 200 (alength bytes))
        (with-open [os (.getResponseBody exchange)] (.write os bytes))))))
    (.start server) server)))
(defn file! [root path contents executable?]
  (let [f (io/file root path)] (io/make-parents f) (spit f contents)
    (when executable? (.setExecutable f true)) (.getAbsolutePath f)))
(defn fixture [root server]
  (let [repo (io/file root "repo") _ (.mkdir repo)
        config (file! root "config" "PLAN_DIR=plan\nAPP_DIR=impl\nFENCED_PATHS=impl\nAPP_PROBE=probe\n" false)
        rubric (file! root "rubric" "Require observed evidence." false)
        builder (file! root "builder" "#!/bin/sh\nmkdir -p \"$1/impl\"\nprintf '#!/bin/sh\\necho candidate\\n' > \"$1/impl/start.sh\"\nchmod +x \"$1/impl/start.sh\"\n" true)
        gate (file! root "gate" "#!/bin/sh\nset -eu\ntest ! -e \"$1/impl\"\ntest -x \"$2/start.sh\"\necho 'BOOT 1 30'\necho 'ANSWERED 1'\n\"$2/start.sh\"\n" true)]
    (file! repo "plan/01-fixture/spec.feature" "Feature: Answer\n  @item-01-001\n  Scenario: A fixture answers\n    Then it answers over the seam\n" false)
    (file! repo "plan/01-fixture/seam.md" "Run impl/start.sh and observe candidate." false)
    (file! repo "impl/old" "previous candidate" false)
    (file! repo "probe" "#!/bin/sh\necho fixture\n" true)
    (doseq [args [["init" "-q"] ["add" "."] ["-c" "user.name=Fixture" "-c" "user.email=fixture@example.invalid" "commit" "-qm" "fixture"]]]
      (host/checked repo (into ["git"] args)))
    {:env {"LOYLY_PROJECT_REPO" (str repo) "LOYLY_HARNESS_CONFIG" config "LOYLY_GRADER_RUBRIC" rubric
           "LOYLY_RUNS_DIR" (str (io/file root "runs")) "LOYLY_GATE_CMD" gate "LOYLY_BUILDER_HOST" builder
           "LOYLY_SELF_CHECK_CMD" (file! root "check" "#!/bin/sh\necho passed\n" true)
           "LOYLY_MODEL_API_BASE" (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1")
           "LOYLY_RENDER_MODEL" "renderer" "LOYLY_GRADER_MODEL" "grader" "LOYLY_BUILDER_INSTRUCTION" "Render this build."
           "LOYLY_GRADER_IMAGE" (or (System/getenv "LOYLY_GRADER_IMAGE") "/Users/sathish.narayanan/Documents/git/loyly-spec-v2/.pi/harness/.state/assets/builder-clojure")}
     :params {:run-id "R1" :cut-id "K1" :cycle-id "C1" :selection-id "selection" :grader-digests [(d/digest (str "cycle-runner/grade.v1\n" (slurp rubric)))]
              :commit (host/checked repo ["git" "rev-parse" "HEAD"])
              :budget {:gate-attempt-limit 1 :max-outer-cycles 1 :max-continuations 0
                       :stage-timeouts-seconds {:fence 30 :regenerate 30 :gate 30 :score 70}}}}))
(defn call [system method params]
  (main/respond system {:jsonrpc "2.0" :id 1 :method (str "cycle-runner/" method) :params params}))
(deftest approved-cycle
  (testing "@item-06-001"
    (let [root (.getCanonicalFile (.toFile (java.nio.file.Files/createTempDirectory (.toPath (io/file ".")) "spine-" (make-array java.nio.file.attribute.FileAttribute 0))))
          server (model)]
      (try
        (let [{:keys [env params]} (fixture root server) system (main/open! (str (io/file root "grain")) (constantly env))]
          (try
            (let [response (call system "start-run" params)
                  progress (get-in (call system "run-progress" {:run-id "R1"}) [:result :run])]
              (is (nil? (:error response)) (str (:error response)))
              (is (= "passed" (:status progress)) (str (:halt progress)))
              (is (= ["stamp" "pin" "checklist" "self-check" "fence" "regenerate" "install" "self-check" "gate" "file-verdicts" "file-score"]
                     (mapv :effect (:effect-results progress))))
                        (testing "@item-06-059"
                (let [life (d/rows system :sheet/tree-tick-started :sheet/node-execution-started :sheet/node-execution-completed :cycle-runner/effect-recorded)
                      nodes (into {} (mapcat #(get-in % [:execution-snapshot :nodes-by-id]) (filter #(= :sheet/tree-tick-started (:event/type %)) life)))
                      checked (reduce (fn [{:keys [active] :as acc} e]
                                        (case (:event/type e)
                                          :sheet/node-execution-started (update acc :active conj [(:tick-id e) (:node-id e)])
                                          :sheet/node-execution-completed (update acc :active disj [(:tick-id e) (:node-id e)])
                                          :cycle-runner/effect-recorded (do (is (some (fn [[_ n]] (= (:effect e) (first (str/split (get-in nodes [n :name]) #":")))) active))
                                                                          (update acc :count inc))
                                          acc)) {:active #{} :count 0} life)]
                  (is (= 11 (:count checked)))
                  (let [ticks (map :tick-id (filter #(= :sheet/tree-tick-started (:event/type %)) life))]
                    (doseq [type [:sheet/tree-tick-completed :sheet/execution-traced]]
                      (is (= (frequencies ticks) (frequencies (map (if (= type :sheet/execution-traced) :trace-id :tick-id) (d/rows system type)))))))
                  (is (seq (d/rows system :grain/todo-processor-checkpoint))))))
            (finally (main/close! system))))
        (finally (.stop server 0) (doseq [f (reverse (file-seq root))] (.delete f)))))))
(deftest filed-verdicts-and-allure
  (let [mode (atom :mixed)
        transform (fn [{:keys [marker messages] :as packet}]
                    (if (and (str/includes? marker "## level ##") (str/includes? messages "01-002"))
                      (assoc packet :marker (if (= :mixed @mode) "[[ ## reasoning ## ]]\nno receipt\n[[ ## level ## ]]\n1"
                                                "[[ ## level ## ]]\n2")) packet))
        root (.getCanonicalFile (.toFile (java.nio.file.Files/createTempDirectory (.toPath (io/file ".")) "score-" (make-array java.nio.file.attribute.FileAttribute 0))))
        server (model transform)]
    (try
      (let [{:keys [params env]} (fixture root server) repo (env "LOYLY_PROJECT_REPO")
            spec (io/file repo "plan/01-fixture/spec.feature")
            _ (spit spec "  @item-01-002\n  Scenario: A receipt is returned\n    Then the receipt is visible\n" :append true)
            _ (host/checked repo ["git" "-c" "user.name=Fixture" "-c" "user.email=fixture@example.invalid" "commit" "-qam" "Second scenario"])
            commit (host/checked repo ["git" "rev-parse" "HEAD"])
            sys (main/open! (str (io/file root "store")) (constantly env))]
        (try
          (doseq [[mode-value id] [[:mixed "mixed"] [:unreached "unreached"]]]
            (reset! mode mode-value)
            (let [p (assoc params :commit commit :run-id id :cycle-id id :cut-id id)
                  reply (call sys "start-run" p)
                  run (get-in (call sys "run-progress" {:run-id id}) [:result :run])
                  receipt (first (:events (last (filter #(= "file-score" (:effect %)) (:effect-results run)))))
                  items (:items receipt)]
              (is (nil? (:error reply)) (str reply))
              (is (= "passed" (:status run)) (str (:halt run)))
              (is (= [1 2] ((juxt :passes :total) receipt)))
              (if (= mode-value :mixed)
                (testing "@item-06-064"
                  (is (= [["01-001" "pass" "booked"] ["01-002" "fail" "no receipt"]] (mapv (juxt :item-id :verdict :evidence) items)))
                  (let [root-id (get-in run [:tick :tick-id])
                        children (for [tick (d/rows sys :sheet/tree-tick-started)
                                       :when (and (= root-id (str (:parent-tick-id tick))) (= "cycle-runner/grade.v1" (get-in tick [:execution-snapshot :sheet-name])))]
                                   (:tick-id tick))
                        scores (filter #((set children) (:tick-id %)) (d/rows sys :judge/score-emitted))]
                    (is (= 2 (count (set children)) (count (set (map :tick-id scores)))))
                    (is (= [["checklist/01-001" 1.0] ["checklist/01-002" 0.0]] (mapv (juxt :judge-name :score) scores)))))
                (testing "@item-06-067 @item-06-074"
                  (is (= 1 (:unreached receipt)))
                  (is (= ["01-002" "fail" "unreached"] ((juxt :item-id :verdict :reason) (second items))))
                  (is (= ["01-001"] (mapv :item-id (:scores run))))
                  (let [files (.listFiles (io/file (env "LOYLY_RUNS_DIR") id "allure-results"))
                        reports (into {} (map (fn [f] (let [r (json/read-str (slurp f) :key-fn keyword)]
                                                       [(get-in r [:labels 0 :value]) r]))) files)]
                    (is (= 2 (count files) (count reports)))
                    (doseq [{:keys [item-id evidence verdict]} items]
                      (let [report (reports (str "item-" item-id))]
                        (is (= "tag" (get-in report [:labels 0 :name])))
                        (is (uuid? (parse-uuid (:uuid report))))
                        (is (= item-id (:historyId report) (:testCaseId report)))
                        (is (= evidence (:description report)))
                        (is (= (if (= "pass" verdict) "passed" "failed") (:status report)))))
                    (is (= "unreached" (get-in reports ["item-01-002" :statusDetails :message]))))))))
          (finally (main/close! sys))))
      (finally (.stop server 0) (doseq [f (reverse (file-seq root))] (.delete f))))))
