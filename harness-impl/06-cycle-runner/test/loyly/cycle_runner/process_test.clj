(ns loyly.cycle-runner.process-test
  "start.sh as the gate starts it: a fresh store, the seam on stdin and stdout,
   the identity check played by hand, then the store read directly."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.harness :as h]
            [loyly.cycle-runner.model :as model]
            [loyly.cycle-runner.standins :as standins]
            [loyly.cycle-runner.util :as u])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.util.concurrent TimeUnit]))

(defn- launch [env]
  (let [pb (ProcessBuilder. ^java.util.List [(str h/home "/start.sh")])
        m (.environment pb)]
    (doseq [[k v] env] (.put m k v))
    (.redirectError pb (java.lang.ProcessBuilder$Redirect/appendTo (io/file (str (h/tmp-root) "/start.stderr"))))
    (let [p (.start pb)]
      {:p p
       :in (OutputStreamWriter. (.getOutputStream p) "UTF-8")
       :out (BufferedReader. (InputStreamReader. (.getInputStream p) "UTF-8"))})))

(defn- ask [{:keys [in out]} id method params]
  (.write in (str (u/->json {:jsonrpc "2.0" :id id :method method :params params}) "\n"))
  (.flush in)
  (let [line (.readLine out)]
    (is (some? line))
    (u/<-json line)))

(defn- read-store [path]
  (let [s (es/start {:conn {:type :sqlite :database-file path} :event-pubsub nil :logger nil})]
    (try (vec (mapcat #(into [] (es/read s {:tenant-id %})) (keys (es/tenants s))))
         (finally (es/stop s)))))

(def model-events (get-in @model/model [:cycle-runner :events]))

(deftest item-06-005-006-033-start-sh-speaks-the-seam-and-its-store-holds-orc-runs
  (h/with-product
    (let [store (u/abs-path (str (h/tmp-root) "/gate-" (random-uuid) ".sqlite"))
          w (h/world! "process" {})
          w-exit (h/world! "process-exit" {:exiting model/contexts})
          t0 (System/currentTimeMillis)
          base (merge (:env w) {"LOYLY_GRAIN_STORE" store})
          ;; the gate hands everything in through the environment at launch; the
          ;; three requests below differ only in what each world names, so the
          ;; exiting contexts are a second contexts file read at each start
          proc (launch (assoc base "LOYLY_CONTEXTS" (get-in w-exit [:env "LOYLY_CONTEXTS"])))]
      (try
        (testing "a hex string that is no commit is refused before the contexts are asked"
          (let [r (ask proc 1 "cycle-runner/start-run"
                       {:run-id "R-0" :cycle-id "C-0" :cut-id "K-0" :commit "0123456789abcdef0123456789abcdef01234567"
                        :selection-id "s" :budget h/budget :grader-digests ["r-1"]})]
            (is (= 1 (:id r)))
            (is (= "not-a-commit" (get-in r [:error :data :refusal])))))
        (testing "five contexts that exit at once are named"
          (let [r (ask proc 2 "cycle-runner/start-run"
                       {:run-id "R-1" :cycle-id "C-1" :cut-id "K-1" :commit (:commit w)
                        :selection-id "s" :budget h/budget :grader-digests ["r-1"]})]
            (is (= "contexts-not-responding" (get-in r [:error :data :refusal])))
            (is (= (set model/contexts) (set (get-in r [:error :data :contexts]))))))
        (finally
          (.close ^java.io.Writer (:in proc))
          (.waitFor ^Process (:p proc) 60 TimeUnit/SECONDS)))
      (testing "a run whose Specification exits when asked to stamp halts at the stamp"
        (swap! h/script assoc-in ["specification" "specification/stamp-slice"] :exit)
        (let [proc (launch base)]
          (try
            (let [r (ask proc "a" "cycle-runner/start-run"
                         {:run-id "R-1" :cycle-id "C-1" :cut-id "K-1" :commit (:commit w)
                          :selection-id "s" :budget h/budget :grader-digests ["r-1"]})
                  run (get-in (ask proc "b" "cycle-runner/run-progress" {:run-id "R-1"}) [:result :run])
                  internal (ask proc "c" "cycle-runner/record-effect" {})]
              (is (= "a" (:id r)))
              (is (= "cycle-runner/run-started" (get-in r [:result :events 0 :type])))
              (is (= "halted" (:status run)))
              (is (= {:effect "stamp" :reason "context-did-not-respond"} (select-keys (:halt run) [:effect :reason])))
              (is (= [] (:effect-results run)))
              (is (= -32601 (get-in internal [:error :code])))
              (is (= 0 (h/launch-count (:builder-dir w)))))
            (finally
              (.close ^java.io.Writer (:in proc))
              (.waitFor ^Process (:p proc) 60 TimeUnit/SECONDS)))))
      (testing "the store holds the model's events, each written inside a successful ORC run"
        (let [events (read-store store)
              types (map :event/type events)
              ours (filter #(= "cycle-runner" (namespace (:event/type %))) events)
              sheets (into {} (for [e events :when (= :sheet/sheet-created (:event/type e))] [(:sheet-id e) (:name e)]))]
          (is (seq ours))
          (is (every? #{"cycle-runner" "sheet" "grain"} (map namespace types)) (pr-str (distinct types)))
          (doseq [e ours
                  :let [schema (get-in model-events [(:event/type e) :schema])
                        fields (set (remove #(= "event" (namespace %)) (keys e)))
                        declared (set (model/event-fields schema))]]
            (is schema (str (:event/type e)))
            (is (set/subset? fields declared) (str (:event/type e) " " fields))
            (is (malli.core/validate schema (into {} (filter (comp fields key)) e)) (str (:event/type e))))
          (testing "each tick completes once and is traced once"
            (let [started (filter #(= :sheet/tree-tick-started (:event/type %)) events)
                  done (group-by :tick-id (filter #(= :sheet/tree-tick-completed (:event/type %)) events))
                  traced (group-by :trace-id (filter #(= :sheet/execution-traced (:event/type %)) events))]
              (is (seq started))
              (doseq [s started]
                (is (= 1 (count (get done (:tick-id s)))))
                (is (= 1 (count (get traced (:tick-id s))))))))
          (testing "each of our events lies inside a run of a flow's workflow that succeeded"
            (let [indexed (map-indexed vector events)]
              (doseq [[i e] indexed :when (= "cycle-runner" (namespace (:event/type e)))]
                (let [[si start] (last (filter (fn [[j x]] (and (< j i) (= :sheet/tree-tick-started (:event/type x)))) indexed))
                      [ci end] (first (filter (fn [[j x]] (and (> j i) (= :sheet/tree-tick-completed (:event/type x))
                                                               (= (:tick-id x) (:tick-id start)))) indexed))]
                  (is (some? end))
                  (is (= :success (:root-status end)))
                  (is (contains? (set loyly.cycle-runner.orc/flows) (get sheets (:sheet-id start))))))))
          (is (some #(= :grain/todo-processor-checkpoint %) types))))
      (is (< (- (System/currentTimeMillis) t0) 120000)))))
