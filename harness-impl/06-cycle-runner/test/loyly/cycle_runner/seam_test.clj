(ns loyly.cycle-runner.seam-test
  "The public seam itself, as the gate drives it: start.sh speaking JSON-RPC over stdio, the
   identity check by domain behaviour, and the runner's own Grain store read directly."
  (:require [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-v3.interface :as es]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.fixtures :as f]
            [loyly.cycle-runner.model :as model]
            [loyly.cycle-runner.standins :as standins]
            [loyly.cycle-runner.util :as u]
            [malli.core :as m])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; The runner's store, read directly, as the gate reads it
;; ---------------------------------------------------------------------------

(defn- closed [schema]
  (m/walk (m/schema schema)
          (m/schema-walker (fn [s] (if (= :map (m/type s))
                                     (let [p (m/properties s)]
                                       (if (contains? p :closed) s
                                           (m/-set-properties s (assoc p :closed true))))
                                     s)))))

(defn store-events [path]
  (let [s (es/start {:conn {:type :sqlite :database-file path}})]
    (try
      (vec (mapcat (fn [t] (into [] (es/read s {:tenant-id t})))
                   (keys (es/tenants s))))
      (finally (es/stop s)))))

(def command-of
  {:cycle-runner/run-started :cycle-runner/start-run
   :cycle-runner/effect-recorded :cycle-runner/record-effect
   :cycle-runner/handoff-relayed :cycle-runner/relay-handoff
   :cycle-runner/verdicts-handed-in :cycle-runner/hand-in-verdicts
   :cycle-runner/run-concluded :cycle-runner/conclude-run
   :cycle-runner/run-halted :cycle-runner/halt-run})

(defn store-findings
  "What the gate would find wrong with the runner's store."
  [path]
  (let [evs (store-events path)
        model-events (get-in (model/area-map) [:events])
        sheet-name (into {} (keep (fn [e] (when (= :sheet/sheet-created (:event/type e))
                                            [(:sheet-id e) (:name e)])))
                         evs)
        findings (atom [])
        open (atom {})
        completed (atom {})]
    (doseq [e evs]
      (let [t (:event/type e)]
        (cond
          (= "cycle-runner" (namespace t))
          (let [schema (get-in model-events [t :schema])
                body (into {} (remove (fn [[k _]] (= "event" (namespace k)))) e)]
            (when-not (and schema (m/validate (closed schema) body))
              (swap! findings conj {:event t :problem :fields :body body}))
            (let [cmd (command-of t)
                  flows (set (map #(str (namespace %) "/" (name %)) (model/flow-of-command cmd)))
                  inside (filter #(flows (sheet-name (:sheet-id %))) (vals @open))]
              (when (empty? inside)
                (swap! findings conj {:event t :problem :outside-its-workflow}))))

          (= :sheet/tree-tick-started t) (swap! open assoc (:tick-id e) e)
          (= :sheet/tree-tick-completed t)
          (do (swap! completed update (:tick-id e) (fnil inc 0))
              (when-not (= :success (:root-status e))
                (swap! findings conj {:event t :problem :not-success :tick (:tick-id e) :root-status (:root-status e)}))
              (swap! open dissoc (:tick-id e)))
          (#{"sheet" "grain"} (namespace t)) nil
          :else (swap! findings conj {:event t :problem :unknown-type}))))
    (doseq [[tick n] @completed :when (not= 1 n)]
      (swap! findings conj {:tick tick :problem :completed-times :n n}))
    (let [traced (frequencies (keep #(when (= :sheet/execution-traced (:event/type %)) (:trace-id %)) evs))]
      (doseq [[tick _] @completed :when (not= 1 (get traced tick 0))]
        (swap! findings conj {:tick tick :problem :traced-times :n (get traced tick 0)})))
    (when-not (some #(= :grain/todo-processor-checkpoint (:event/type %)) evs)
      (swap! findings conj {:problem :no-checkpoints}))
    {:findings @findings
     :types (frequencies (map :event/type evs))}))

(deftest the-store-holds-only-model-events-each-written-inside-its-workflow
  (testing "a run to its verdicts, a passed run, a halted and a refused start, in one store"
    (let [w (f/world)]
      (f/with-runner [sys w]
        (f/start! sys w)
        (f/hand-in! sys "R-1" (f/verdicts-file! w "v.json" "[{\"id\":\"01-001\",\"verdict\":\"pass\"}]") "r-1")
        (is (= "passed" (:status (f/progress sys "R-1"))))
        (reset! standins/scripts {"measurement" {:exit-on #{"measurement/derive-checklist"}}})
        (f/start! sys w :run-id "R-2" :cycle-id "C-2")
        (is (= "halted" (:status (f/progress sys "R-2"))))
        (Thread/sleep 1500)
        (let [{:keys [findings types]} (store-findings (:test/store sys))]
          (is (empty? findings) (pr-str (take 5 findings)))
          (is (pos? (get types :cycle-runner/run-concluded 0)))
          (is (pos? (get types :cycle-runner/run-halted 0)))
          (is (pos? (get types :sheet/tree-tick-completed 0))))))))

;; ---------------------------------------------------------------------------
;; start.sh over stdio
;; ---------------------------------------------------------------------------

(defn- launch [env]
  (let [pb (ProcessBuilder. ["./start.sh"])
        e (.environment pb)]
    (.directory pb (io/file "."))
    (doseq [[k v] env] (.put e k v))
    (.redirectError pb (java.lang.ProcessBuilder$Redirect/appendTo (io/file (f/temp-dir "seam-err") "stderr.log")))
    (let [p (.start pb)]
      {:p p
       :in (OutputStreamWriter. (.getOutputStream p))
       :out (BufferedReader. (InputStreamReader. (.getInputStream p)))})))

(defn- rpc [{:keys [in ^BufferedReader out]} id method params]
  (.write in (str (u/->json {:jsonrpc "2.0" :id id :method method :params params}) "\n"))
  (.flush in)
  (u/parse-json (.readLine out)))

(deftest the-gates-identity-check-over-start-sh
  (let [w (f/world)
        base (:base w)
        commit (get-in w [:repo :commit])
        marker (str base "/self-check-ran")
        exiting (into {} (map (fn [c] [c "#!/bin/bash\nexit 0\n"]))
                      ["specification" "cycle-preparation" "grader-lifecycle" "regeneration-cycle" "measurement"])
        all-exit (standins/write-contexts! (f/temp-dir "exit-ctx") exiting)
        store (str (f/temp-dir "seam-store") "/fresh.sqlite")
        env (assoc (:env w)
                   "LOYLY_GRAIN_STORE" store
                   "LOYLY_SELF_CHECK_CMD" (f/self-check! base (str "touch " marker "\nexit 0")))
        t0 (System/currentTimeMillis)
        proc (launch (assoc env "LOYLY_CONTEXTS" all-exit))]
    (try
      (testing "a hex string that is no commit is refused before the contexts are asked"
        (standins/reset-scripts! {})
        (let [r (rpc proc 1 "cycle-runner/start-run" (f/start-params "abcdefabcdefabcdefabcdefabcdefabcdefabcd"))]
          (is (= 1 (:id r)))
          (is (= "not-a-commit" (f/refusal r)) (pr-str r))
          (is (< (- (System/currentTimeMillis) t0) 30000) "within thirty seconds of start.sh launching")))
      (testing "contexts that exit at once refuse the start, naming all five"
        (let [r (rpc proc 2 "cycle-runner/start-run" (f/start-params commit))]
          (is (= "contexts-not-responding" (f/refusal r)))
          (is (= ["specification" "cycle-preparation" "grader-lifecycle" "regeneration-cycle" "measurement"]
                 (get-in r [:error :data :contexts])))))
      (testing "unknown methods, the processor's commands among them"
        (is (= -32601 (get-in (rpc proc 3 "cycle-runner/record-effect" {}) [:error :code])))
        (is (= -32602 (get-in (rpc proc 4 "cycle-runner/start-run" {:run-id 5}) [:error :code]))))
      (finally
        (.close (:in proc))
        (.waitFor ^Process (:p proc) 30 TimeUnit/SECONDS)
        (u/kill-tree! (:p proc))))
    (testing "a run whose Specification exits when asked to stamp halts at the stamp"
      (standins/reset-scripts! {"specification" {:exit-on #{"specification/stamp-slice"}}})
      (let [proc (launch (assoc env "LOYLY_GRAIN_STORE" (str (f/temp-dir "seam-store") "/fresh.sqlite")))]
        (try
          (let [r (rpc proc 1 "cycle-runner/start-run" (f/start-params commit))
                run (get-in (rpc proc 2 "cycle-runner/run-progress" {:run-id "R-1"}) [:result :run])]
            (is (= "cycle-runner/run-started" (get-in r [:result :events 0 :type])) (pr-str r))
            (is (= "halted" (:status run)))
            (is (= {:effect "stamp" :reason "context-did-not-respond"} (select-keys (:halt run) [:effect :reason])))
            (is (= [] (:effect-results run)))
            (is (not (.exists (io/file marker))) "the self-check never ran")
            (is (empty? (f/builder-log (:builder w))) "the builder never ran"))
          (finally
            (.close (:in proc))
            (.waitFor ^Process (:p proc) 30 TimeUnit/SECONDS)
            (u/kill-tree! (:p proc))))))))
