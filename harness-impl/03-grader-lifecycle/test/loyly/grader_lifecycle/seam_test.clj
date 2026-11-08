(ns loyly.grader-lifecycle.seam-test
  "The seam's transport, its refusal order, the handed-in scenario digests, the
   store the gate reads and the boot guard."
  (:require [ai.obney.grain.event-model-validator.interface :as validator]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [loyly.grader-lifecycle.model :as model]
            [loyly.grader-lifecycle.support :as s]))

(use-fixtures :each s/with-system*)

(defn- write-params [golden-id sd]
  {"golden-id" golden-id "scenario" "sc" "scenario-digest" sd "path" "p" "digest" "g-1"})

(defn- move-params [golden-id m]
  (merge {"golden-id" golden-id "digest" "g-2" "reason" "wording-replaced"
          "additions-only" false "note" "n" "scenario-digest" "s-1"}
         m))

(deftest transport-errors
  (testing "a line that is not JSON"
    (let [resp (s/rpc "{not json")]
      (is (= -32700 (s/error-code resp)))
      (is (contains? resp "id"))
      (is (nil? (get resp "id")))))
  (testing "an unknown method carries the request id"
    (let [resp (s/rpc {"jsonrpc" "2.0" "id" "abc" "method" "grader-lifecycle/nope" "params" {}})]
      (is (= -32601 (s/error-code resp)))
      (is (= "abc" (get resp "id")))))
  (testing "params that do not match the schema record nothing"
    (s/set-digests! {"sc" "s-1"})
    (is (= -32602 (s/error-code (s/write! (dissoc (write-params "G" "s-1") "path")))))
    (is (= -32602 (s/error-code (s/write! (assoc (write-params "G" "s-1") "digest" nil)))))
    (is (= -32602 (s/error-code (s/write! (assoc (write-params "G" "s-1") "digest" 4)))))
    (is (= -32602 (s/error-code (s/move! (assoc (move-params "G" {}) "additions-only" "yes")))))
    (is (= -32602 (s/error-code (s/move! (assoc (move-params "G" {}) "note" nil)))))
    (is (empty? (s/model-events)))
    (is (= [] (s/goldens)))))

(deftest refusals-follow-the-allium-order
  (s/set-digests! {"sc" "s-1"})
  (is (contains? (s/write! (write-params "G" "s-1")) "result"))
  (testing "requires 1 before 4: an empty note for a golden never written"
    (is (= "no-such-golden" (s/refusal (s/move! (move-params "X" {"note" ""}))))))
  (testing "requires 2 before 4"
    (is (= "same-digest" (s/refusal (s/move! (move-params "G" {"digest" "g-1" "note" ""}))))))
  (testing "requires 3 before 4"
    (is (= "spec-grew-not-additions-only"
           (s/refusal (s/move! (move-params "G" {"reason" "spec-grew" "note" ""}))))))
  (testing "requires 4 before 5"
    (is (= "empty-note" (s/refusal (s/move! (move-params "G" {"note" "" "scenario-digest" "s-0"}))))))
  (testing "spec grew with an additions-only diff moves"
    (let [resp (s/move! (move-params "G" {"reason" "spec-grew" "additions-only" true}))]
      (is (= true (get-in resp ["result" "events" 0 "additions-only"])))))
  (testing "a write refused for its existence before its digest"
    (is (= "golden-exists" (s/refusal (s/write! (write-params "G" "s-9"))))))
  (is (= 2 (count (s/model-events)))))

(deftest scenario-digests-are-handed-in-and-read-at-every-command
  (testing "without the file the context is inert"
    (s/delete-digests!)
    (is (= "scenario-digest-mismatch" (s/refusal (s/write! (write-params "G" "s-1"))))))
  (testing "a scenario the file does not name has no digest"
    (s/set-digests! {"other" "s-1"})
    (is (= "scenario-digest-mismatch" (s/refusal (s/write! (write-params "G" "s-1"))))))
  (testing "the file is read again at the next command"
    (s/set-digests! {"sc" "s-1"})
    (is (contains? (s/write! (write-params "G" "s-1")) "result")))
  (testing "a move compares with the golden's recorded scenario, read now"
    (s/set-digests! {"sc" "s-2"})
    (is (= "stale-scenario-digest" (s/refusal (s/move! (move-params "G" {"scenario-digest" "s-1"})))))
    (is (contains? (s/move! (move-params "G" {"scenario-digest" "s-2"})) "result"))))

(deftest the-query-reports-goldens-in-the-order-written
  (is (= [] (s/goldens)))
  (s/set-digests! {"sc" "s-1"})
  (doseq [g ["G-b" "G-a" "G-c"]]
    (s/write! (write-params g "s-1")))
  (s/move! (move-params "G-a" {"digest" "g-2"}))
  (s/move! (move-params "G-a" {"digest" "g-3" "reason" "instrument-behavior-changed" "note" "m2"}))
  (let [gs (s/goldens)]
    (is (= ["G-b" "G-a" "G-c"] (map #(get % "golden-id") gs)))
    (is (every? #(= #{"golden-id" "path" "digest" "scenario-digest" "moves"} (set (keys %))) gs))
    (is (= [["g-1" "g-2" "wording-replaced" "n"] ["g-2" "g-3" "instrument-behavior-changed" "m2"]]
           (map (juxt #(get % "from-digest") #(get % "to-digest") #(get % "reason") #(get % "note"))
                (get (second gs) "moves"))))
    (is (= "g-3" (get (second gs) "digest")))))

(defn- run-of
  "The ORC run (tick) whose start..completion encloses the event at index i."
  [events i]
  (let [before (subvec events 0 i)
        start (last (filter #(= :sheet/tree-tick-started (:event/type %)) before))
        tick (:tick-id start)
        done (first (filter #(and (= :sheet/tree-tick-completed (:event/type %))
                                  (= tick (:tick-id %)))
                            (subvec events i)))]
    (when (and start done
               (not-any? #(and (= :sheet/tree-tick-completed (:event/type %)) (= tick (:tick-id %)))
                         before))
      {:start start :done done})))

(deftest the-store-holds-what-the-gate-reads
  (s/set-digests! {"sc" "s-1"})
  (s/write! (write-params "G" "s-1"))
  (s/move! (move-params "G" {}))
  (s/move! (move-params "G" {"note" ""}))
  (s/goldens)
  (let [events (s/store-events)
        model-types (set (keys (:events (model/area-map))))
        sheet-names (into {} (for [e events :when (= :sheet/sheet-created (:event/type e))]
                               [(:sheet-id e) (:name e)]))]
    (testing "every event is the model's, ORC's or Grain's"
      (is (every? #(or (contains? model-types (:event/type %))
                       (#{"sheet" "grain"} (namespace (:event/type %))))
                  events)))
    (testing "the model's events carry exactly their schema's fields"
      (doseq [e (filter #(contains? model-types (:event/type %)) events)]
        (is (= (set (model/event-fields (:event/type e)))
               (set (remove #(= "event" (namespace %)) (keys e)))))))
    (is (= 2 (count (filter #(contains? model-types (:event/type %)) events))))
    (testing "each command's event is written inside a successful run of its workflow"
      (doseq [i (range (count events))
              :when (contains? model-types (:event/type (nth events i)))]
        (let [{:keys [start done]} (run-of events i)]
          (is (some? start))
          (is (= "grader-lifecycle/golden-written-then-moved" (get sheet-names (:sheet-id start))))
          (is (= :success (:root-status done))))))
    (testing "each tick completes once and is traced once"
      (let [ticks (set (keep #(when (= :sheet/tree-tick-started (:event/type %)) (:tick-id %)) events))
            completed (frequencies (keep #(when (= :sheet/tree-tick-completed (:event/type %)) (:tick-id %)) events))
            traced (frequencies (keep #(when (= :sheet/execution-traced (:event/type %)) (:trace-id %)) events))]
        (is (= 3 (count ticks)))
        (is (every? #(= 1 (get completed %)) ticks))
        (is (every? #(= 1 (get traced %)) ticks))))
    (testing "the todo processors checkpoint"
      (is (some #(= :grain/todo-processor-checkpoint (:event/type %)) events)))))

(deftest the-boot-guard-refuses-a-model-apart-from-the-blocks
  (testing "the running blocks and the room's model agree"
    (is (:valid? (validator/verify-event-model!))))
  (testing "a model whose event schema differs is refused"
    (let [altered (assoc-in @model/model [:grader-lifecycle :events
                                          :grader-lifecycle/instrument-golden-written :schema]
                            [:map [:golden-id :string]])]
      (is (thrown? clojure.lang.ExceptionInfo (validator/verify-or-throw! {:model altered})))))
  (testing "a model missing a block that runs is refused"
    (let [altered (update-in @model/model [:grader-lifecycle :queries]
                             dissoc :grader-lifecycle/instrument-goldens)
          altered (update-in altered [:grader-lifecycle :screens :grader-lifecycle/instrument-goldens]
                             dissoc :queries)
          altered (update altered :grader-lifecycle dissoc :flows)]
      (is (thrown? clojure.lang.ExceptionInfo (validator/verify-or-throw! {:model altered}))))))
