(ns cycle-preparation.test-runner
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [cycle-preparation.product :as product]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-model-validator.interface :as guard]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.read-model-processor-v2.interface :as rm]
            [malli.core :as m]))

(def c1 "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51")
(def c2 "9b07e2d4c8a1f6035e9d2b7c4a0f8e1d6b3c5a92")
(def missing "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
(def pin "cycle-preparation/pin-source-cut")
(def cuts "cycle-preparation/source-cuts")
(defn params [id commit tree] {:cut-id id :commit commit :tree-digest tree})

(defn verify-store! [file]
  (let [store (es/start {:conn {:type :sqlite :database-file (.getAbsolutePath file)}})]
    (try
      (let [tenants (es/tenants store)
            _ (is (= 1 (count tenants)))
            events (into [] (es/read store {:tenant-id (first (keys tenants))}))
            domain (filter #(= :cycle-preparation/source-cut-pinned (:event/type %)) events)
            indexed (map-indexed vector events)
            starts (filter #(= :sheet/tree-tick-started (:event/type (second %))) indexed)
            ends (filter #(= :sheet/tree-tick-completed (:event/type (second %))) indexed)
            traces (filter #(= :sheet/execution-traced (:event/type %)) events)
            area (:cycle-preparation (product/load-model))
            workflow-sheets (set (map :sheet-id (filter #(and (= :sheet/sheet-created (:event/type %))
                                                    (= "cycle-preparation/cut-to-fence" (:name %))) events)))]
        (is (every? #(or (= :cycle-preparation/source-cut-pinned (:event/type %))
                        (#{"sheet" "grain"} (namespace (:event/type %)))) events))
        (is (= (count starts) (count ends) (count traces)))
        (when (seq starts)
          (is (seq (filter #(= :grain/todo-processor-checkpoint (:event/type %)) events))))
        (doseq [[_ start] starts]
          (is (= 1 (count (filter #(= (:tick-id start) (:tick-id (second %))) ends))))
          (is (= 1 (count (filter #(= (:tick-id start) (:trace-id %)) traces)))))
        (doseq [[i event] indexed :when (= :cycle-preparation/source-cut-pinned (:event/type event))]
          (let [body (into {} (remove #(= "event" (namespace (key %))) event))]
            (is (= #{:cut-id :commit :tree-digest :pinned-at} (set (keys body))))
            (is (m/validate (get-in area [:events (:event/type event) :schema]) body)))
          (is (some (fn [[begin start]]
                      (and (< begin i) (workflow-sheets (:sheet-id start))
                           (some (fn [[end terminal]]
                                   (and (> end i) (= (:tick-id start) (:tick-id terminal))
                                        (= :success (:root-status terminal)))) ends))) starts)))
        (is (= (count domain) (count (distinct (map :cut-id domain))))))
      (finally (es/stop store)))))

(defn with-product
  ([f] (with-product (io/file ".test-state" (str (random-uuid))) f))
  ([dir f]
  (let [_ (.mkdirs dir)
        facts (io/file dir "commits.json")
        store (io/file dir "store.sqlite")
        _ (spit facts (json/write-str {c1 "t-81" c2 "t-82"}))
        pb (ProcessBuilder. ^java.util.List ["./start.sh"])
        _ (doto (.environment pb)
            (.put "LOYLY_GRAIN_STORE" (.getAbsolutePath store))
            (.put "LOYLY_PROJECT_COMMITS" (.getAbsolutePath facts)))
        _ (.redirectError pb (io/file dir "stderr.log"))
        p (.start pb)
        writer (io/writer (.getOutputStream p))
        reader (io/reader (.getInputStream p))
        n (atom 0)
        line! (fn [line]
                (.write writer (str line "\n")) (.flush writer)
                (let [response (future (.readLine ^java.io.BufferedReader reader))
                      text (deref response 30000 ::timeout)]
                  (when (= text ::timeout) (.destroyForcibly p) (throw (ex-info "Response timeout" {})))
                  (when-not text (throw (ex-info (str "Process exited: " (slurp (io/file dir "stderr.log"))) {})))
                  (json/read-str text :key-fn keyword)))
        rpc (fn [method parameters]
              (let [id (swap! n inc)
                    response (line! (json/write-str {:jsonrpc "2.0" :id id :method method :params parameters}))]
                (is (= id (:id response)))
                response))]
    (try (f rpc facts line! store)
         (finally (.close writer)
                  (when-not (.waitFor p 15 java.util.concurrent.TimeUnit/SECONDS) (.destroyForcibly p))
                  (.close reader)
                  (is (= 0 (.exitValue p)))
                  (verify-store! store))))))

(deftest item-05-001
  (testing "@item-05-001 The operator pins a source cut"
    (with-product
      (fn [rpc _ _ _]
        (is (= [] (get-in (rpc cuts {}) [:result :cuts])))
        (let [p (params "K-3" c1 "t-81")
              response (rpc pin p)
              event (first (get-in response [:result :events]))
              cut (dissoc event :type)]
          (is (= "cycle-preparation/source-cut-pinned" (:type event)))
          (is (= p (select-keys event (keys p))))
          (is (string? (:pinned-at event)))
          (is (= #{:type :cut-id :commit :tree-digest :pinned-at} (set (keys event))))
          (is (= [cut] (get-in (rpc cuts {}) [:result :cuts]))))))))

(deftest item-05-002
  (testing "@item-05-002 A pinned source cut is never pinned again"
    (with-product
      (fn [rpc _ _ _]
        (rpc pin (params "K-3" c1 "t-81"))
        (let [before (rpc cuts {})]
          (doseq [p [(params "K-3" c2 "t-82") (params "K-3" c1 "t-81")
                     (params "K-3" missing "wrong")]]
            (let [r (rpc pin p)]
              (is (= -32000 (get-in r [:error :code])))
              (is (= "cut-already-pinned" (get-in r [:error :data :refusal])))))
          (is (= (:result before) (:result (rpc cuts {})))))))))

(deftest item-05-003
  (testing "@item-05-003 A source cut of something that is not a commit is refused"
    (with-product
      (fn [rpc facts _ _]
        (let [r (rpc pin (params "K-4" missing "t-81"))]
          (is (= -32000 (get-in r [:error :code])))
          (is (= "not-a-commit" (get-in r [:error :data :refusal])))
          (is (= [] (get-in (rpc cuts {}) [:result :cuts]))))
        ;; Facts are handed in anew for every command, including disappearance.
        (spit facts (json/write-str {missing "t-new"}))
        (is (= 1 (count (get-in (rpc pin (params "K-4" missing "t-new")) [:result :events]))))
        (is (= "not-a-commit" (get-in (rpc pin (params "K-5" c1 "t-81")) [:error :data :refusal])))
        (.delete facts)
        (is (= "not-a-commit" (get-in (rpc pin (params "K-6" c2 "t-82")) [:error :data :refusal])))))))

(deftest item-05-004
  (testing "@item-05-004 A source cut whose tree digest is not its commit's tree is refused"
    (with-product
      (fn [rpc _ _ _]
        (let [r (rpc pin (params "K-4" c1 "t-99"))]
          (is (= -32000 (get-in r [:error :code])))
          (is (= "tree-digest-not-the-commits" (get-in r [:error :data :refusal])))
          (is (= [] (get-in (rpc cuts {}) [:result :cuts]))))
        (is (= 1 (count (get-in (rpc pin (params "K-4" c1 "t-81")) [:result :events]))))))))

(deftest transport-and-ordering
  (with-product
    (fn [rpc _ line! _]
      (is (= -32700 (get-in (line! "{broken") [:error :code])))
      (is (= -32601 (get-in (rpc "unknown" {}) [:error :code])))
      (doseq [p [(params "short" "abc" "x") (params "uppercase" (.toUpperCase c1) "x")
                 (params nil c1 "x") (params "nil" c1 nil) {} []]]
        (is (= -32602 (get-in (rpc pin p) [:error :code]))))
      (is (= [] (get-in (rpc cuts {}) [:result :cuts])))
      (rpc pin (params "a" c1 "t-81"))
      (rpc pin (params "b" c2 "t-82"))
      (is (= ["b" "a"] (mapv :cut-id (get-in (rpc cuts {}) [:result :cuts])))))))

(deftest persistence
  (let [dir (io/file ".test-state" (str (random-uuid)))
        expected (atom nil)]
    (with-product dir
      (fn [rpc _ _ _]
        (rpc pin (params "persistent" c1 "t-81"))
        (reset! expected (:result (rpc cuts {})))))
    (with-product dir
      (fn [rpc _ _ _]
        (is (= @expected (:result (rpc cuts {}))))
        (is (= "cut-already-pinned"
               (get-in (rpc pin (params "persistent" c2 "t-82")) [:error :data :refusal])))
        (is (= @expected (:result (rpc cuts {}))))))))

(deftest boot-guard-refuses-runtime-drift
  (let [model (product/load-model)
        area (:cycle-preparation model)]
    (product/register-blocks! area)
    (is (:valid? (guard/verify-or-throw! {:model model})))
    (let [original @cp/command-registry*]
      (try
        (swap! cp/command-registry* assoc-in
               [:cycle-preparation/pin-source-cut :grain.event-model/produces] #{})
        (is (thrown? clojure.lang.ExceptionInfo (guard/verify-or-throw! {:model model})))
        (finally (reset! cp/command-registry* original))))
    (let [original @rm/read-model-registry*]
      (try
        (swap! rm/read-model-registry* assoc-in [:cycle-preparation/source-cuts :schema] [:map])
        (is (thrown? clojure.lang.ExceptionInfo (guard/verify-or-throw! {:model model})))
        (finally (reset! rm/read-model-registry* original))))))

(defn -main [& _]
  (let [r (run-tests 'cycle-preparation.test-runner)]
    (shutdown-agents)
    (System/exit (if (or (zero? (:test r)) (pos? (+ (:fail r) (:error r)))) 1 0))))
