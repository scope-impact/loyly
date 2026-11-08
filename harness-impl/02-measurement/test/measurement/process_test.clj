(ns measurement.process-test
  "The gate's run, played end to end: start.sh on a fresh store file, the identity
   check over stdin within thirty seconds, then the store read directly."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [measurement.store-check :as store-check])
  (:import [java.io BufferedReader File InputStreamReader OutputStreamWriter]
           [java.util.concurrent TimeUnit]))

(defn- request [id method params]
  (json/write-str {"jsonrpc" "2.0" "id" id "method" method "params" params}))

(deftest the-gate-s-identity-check-through-start-sh
  (let [store (File/createTempFile "measurement-gate" ".db")
        _ (.delete store)
        pb (doto (ProcessBuilder. ^java.util.List [(.getCanonicalPath (io/file "start.sh"))])
             (.directory (io/file "/"))
             (.redirectError (java.lang.ProcessBuilder$Redirect/appendTo (File/createTempFile "measurement-gate" ".log"))))
        _ (.put (.environment pb) "LOYLY_GRAIN_STORE" (.getPath store))
        started (System/currentTimeMillis)
        proc (.start pb)
        in (OutputStreamWriter. (.getOutputStream proc) "UTF-8")
        out (BufferedReader. (InputStreamReader. (.getInputStream proc) "UTF-8"))
        send! (fn [line] (.write in (str line "\n")) (.flush in))
        lines [(request 1 "specification/scenario-stamped"
                        {"item-id" "02-001" "slice" "02" "source" ".pi/plan/02-measurement/spec.feature:12"
                         "line" 12 "title" "The checklist holds one stamped item per stamped scenario"
                         "stamped-at" "2026-11-07T10:00:00Z"})
               (request 2 "cycle-preparation/source-cut-pinned"
                        {"cut-id" "K-1" "commit" "0123456789abcdef0123456789abcdef01234567"
                         "tree-digest" "tree-1" "pinned-at" "2026-11-07T10:00:01Z"})
               (request 3 "measurement/derive-checklist"
                        {"cut-id" "K-1" "selection-id" "whole-plan"
                         "items" [{"item-id" "02-001" "scenario-digest" "sd-1" "rule" nil
                                   "source" ".pi/plan/02-measurement/spec.feature:12"
                                   "title" "The checklist holds one stamped item per stamped scenario"}]
                         "unstamped" [] "skipped-slices" []})
               (request 4 "measurement/derive-checklist"
                        {"cut-id" "K-404" "selection-id" "whole-plan"
                         "items" [{"item-id" "02-001" "scenario-digest" "sd-1" "rule" nil
                                   "source" "x:1" "title" "t"}]
                         "unstamped" [] "skipped-slices" []})
               (request 5 "measurement/checklist" {"cut-id" "K-1"})]
        _ (run! send! lines)
        responses (vec (for [_ lines] (some-> (.readLine out) json/read-str)))
        elapsed (- (System/currentTimeMillis) started)]
    (.close in)
    (testing "the process exits when stdin closes, and wrote nothing else to stdout"
      (is (.waitFor proc 60 TimeUnit/SECONDS))
      (is (nil? (.readLine out))))
    (testing "identity proved by domain behaviour within thirty seconds"
      (is (< elapsed 30000) (str "took " elapsed " ms"))
      (is (= [1 2 3 4 5] (mapv #(get % "id") responses)))
      (is (= "measurement/checklist-derived" (get-in responses [2 "result" "events" 0 "type"])))
      (is (= "cut-not-pinned" (get-in responses [3 "error" "data" "refusal"])))
      (is (= ["02-001"] (mapv #(get % "item-id") (get-in responses [4 "result" "checklist" "items"])))))
    (testing "the store, read directly, passes the gate's reading"
      (let [es (es/start {:conn {:type :sqlite :database-file (.getPath store)}})
            tenants (keys (es/tenants es))]
        (try
          (is (= 1 (count tenants)) "ORC shares the product's one tenant")
          (let [events (store-check/read-events es (first tenants))]
            (is (some #(= :measurement/checklist-derived (:event/type %)) events))
            (is (some #(= :grain/todo-processor-checkpoint (:event/type %)) events))
            (is (some #(= :sheet/tree-tick-completed (:event/type %)) events))
            (is (empty? (store-check/check-events events))))
          (finally (es/stop es)))))
    (doseq [suffix ["" "-wal" "-shm"]] (.delete (File. (str (.getPath store) suffix))))))
