(ns measurement.seam-test
  "The seam's transport and errors (seam.md), the handed-in handoffs, and the
   boot guard."
  (:require [ai.obney.grain.event-model.interface :as em]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [measurement.domain :as domain]
            [measurement.support :as s]
            [measurement.system :as system]))

(use-fixtures :each s/with-product)

(deftest transport-errors
  (testing "a line that is not JSON"
    (let [r (s/rpc-raw "{not json")]
      (is (= -32700 (get-in r ["error" "code"])))
      (is (contains? r "id"))))
  (testing "an unknown method"
    (is (= -32601 (get-in (s/rpc "measurement/no-such-method" {}) ["error" "code"]))))
  (testing "a request with no method"
    (is (= -32600 (get-in (s/rpc-raw (json/write-str {"jsonrpc" "2.0" "id" 9})) ["error" "code"]))))
  (testing "a notification gets no response"
    (is (nil? (s/rpc-raw (json/write-str {"jsonrpc" "2.0" "method" "measurement/checklist"
                                          "params" {"cut-id" "K-1"}})))))
  (testing "the response carries the request's id, a string id too"
    (is (= "abc" (get (s/rpc-raw (json/write-str {"jsonrpc" "2.0" "id" "abc" "method" "measurement/checklist"
                                                  "params" {"cut-id" "K-1"}}))
                      "id")))))

(deftest invalid-params-record-nothing
  (s/pin! "K-3")
  (s/stamp! "03-001")
  (testing "null for a field that is not [:maybe ...]"
    (let [r (s/rpc "measurement/derive-checklist"
                   {"cut-id" "K-3" "selection-id" nil "items" [(s/item "03-001")] "unstamped" [] "skipped-slices" []})]
      (is (= -32602 (get-in r ["error" "code"])))))
  (testing "a missing field"
    (is (= -32602 (get-in (s/rpc "measurement/file-score" {"cycle-id" "C-7"}) ["error" "code"]))))
  (testing "a refusal code outside the enum"
    (is (= -32602 (get-in (s/fail-stage! "C-7" "made-up" "x") ["error" "code"]))))
  (testing "a handoff that does not match its contract appends nothing"
    (let [r (s/rpc "cycle-preparation/source-cut-pinned"
                   {"cut-id" "K-4" "commit" "not-a-sha" "tree-digest" "t" "pinned-at" s/t0})]
      (is (= -32602 (get-in r ["error" "code"]))))
    (is (= "cut-not-pinned" (s/refusal (s/derive! "K-4" ["03-001"])))))
  (is (nil? (s/checklist "K-3"))))

(deftest handoffs-are-appended-exactly-as-given
  (let [params {"item-id" "03-001" "slice" "03" "source" ".pi/plan/03/spec.feature:6" "line" 6
                "title" "a title" "stamped-at" "2026-11-07T10:00:00Z"}
        r (s/rpc "specification/scenario-stamped" params)]
    (is (= [(assoc params "type" "specification/scenario-stamped")] (s/events r))))
  (testing "a handoff the fold already holds is still appended and echoed, and ignored"
    (s/gate! "C-7" "K-3" "D-1" 1)
    (is (s/result (s/gate! "C-7" "K-3" "D-9" 2)))
    (s/pin! "K-3")
    (assert (s/result (s/derive! "K-3" ["03-001"])))
    (is (= "not-the-gated-candidate"
           (s/refusal (s/file-verdicts! "C-7" "D-9" "G-1" [(s/verdict "03-001" "pass")]))))
    (is (s/result (s/file-verdicts! "C-7" "D-1" "G-1" [(s/verdict "03-001" "pass")])))))

(deftest rate-format
  (is (= "6.2%" (domain/rate 1 16)))
  (is (= "31.2%" (domain/rate 5 16)))
  (is (= "66.7%" (domain/rate 2 3)))
  (is (= "100.0%" (domain/rate 3 3)))
  (is (= "0.0%" (domain/rate 0 3)))
  (is (= "n/a" (domain/rate 0 0))))

(deftest start-refuses-a-model-apart-from-the-running-blocks
  (let [tampered (assoc-in (em/registered-model)
                           [:measurement :commands :measurement/file-score :schema]
                           [:map [:cycle-id :string]])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refusing to start"
                          (system/start {:model tampered}))))
  (let [missing (update-in (em/registered-model) [:measurement :queries] dissoc :measurement/scoreboard)]
    (is (thrown? clojure.lang.ExceptionInfo (system/start {:model missing})))))
