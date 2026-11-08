(ns loyly.cycle-preparation.spec-test
  "The accepted scenarios of .pi/spec/cycle-preparation/spec.feature, each
   driven through the public seam from a fresh store, named by its @item id."
  (:require [clojure.test :refer [deftest is testing]]
            [loyly.cycle-preparation.candidate :as c]))

(def c1 "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51")
(def c2 "9b07e2d4c8a1f6035e9d2b7c4a0f8e1d6b3c5a92")
(def not-a-commit "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef")

(defn- pin! [cand cut-id commit tree-digest]
  (c/call! cand "cycle-preparation/pin-source-cut"
           {"cut-id" cut-id "commit" commit "tree-digest" tree-digest}))

(defn- cuts [cand]
  (get-in (c/call! cand "cycle-preparation/source-cuts" {}) ["result" "cuts"]))

(defn- cuts-named [cand cut-id]
  (filterv #(= cut-id (get % "cut-id")) (cuts cand)))

(defn- refusal [resp] (get-in resp ["error" "data" "refusal"]))

(deftest item-05-001-the-operator-pins-a-source-cut
  (testing "@item-05-001 The operator pins a source cut"
    ;; Given commit c1 of the project repository has the tree digest "t-81"
    (c/with-candidate [cand _ {c1 "t-81"}]
      ;; When the operator pins source cut K-3 at c1 with the tree digest "t-81"
      (let [resp (pin! cand "K-3" c1 "t-81")
            events (get-in resp ["result" "events"])]
        ;; Then source cut K-3 is pinned at c1 with the tree digest "t-81"
        (is (nil? (get resp "error")) (pr-str resp))
        (is (= 1 (count events)))
        (let [e (first events)]
          (is (= "cycle-preparation/source-cut-pinned" (get e "type")))
          (is (= #{"type" "cut-id" "commit" "tree-digest" "pinned-at"} (set (keys e))))
          (is (= ["K-3" c1 "t-81"] [(get e "cut-id") (get e "commit") (get e "tree-digest")]))
          (is (some? (java.time.Instant/parse (get e "pinned-at")))))
        ;; And the pinned source cuts show source cut K-3 with that commit and tree digest
        (let [shown (cuts-named cand "K-3")]
          (is (= 1 (count shown)))
          (is (= [c1 "t-81"] [(get (first shown) "commit") (get (first shown) "tree-digest")]))
          (is (= (get (first events) "pinned-at") (get (first shown) "pinned-at"))))))))

(deftest item-05-002-a-pinned-source-cut-is-never-pinned-again
  (testing "@item-05-002 A pinned source cut is never pinned again"
    (c/with-candidate [cand _ {c1 "t-81" c2 "t-82"}]
      ;; Given source cut K-3 was pinned at c1 with the tree digest "t-81"
      (is (nil? (get (pin! cand "K-3" c1 "t-81") "error")))
      ;; And commit c2 has the tree digest "t-82"
      ;; When the operator pins source cut K-3 at c2 with the tree digest "t-82"
      (let [resp (pin! cand "K-3" c2 "t-82")]
        ;; Then the pinning is refused
        (is (= -32000 (get-in resp ["error" "code"])) (pr-str resp))
        (is (= "cut-already-pinned" (refusal resp)))
        (is (nil? (get resp "result"))))
      ;; Pinning it again with the same content records nothing either.
      (is (= "cut-already-pinned" (refusal (pin! cand "K-3" c1 "t-81"))))
      ;; And the pinned source cuts hold one source cut K-3, at c1 with the tree digest "t-81"
      (let [shown (cuts-named cand "K-3")]
        (is (= 1 (count shown)))
        (is (= [c1 "t-81"] [(get (first shown) "commit") (get (first shown) "tree-digest")]))))))

(deftest item-05-003-a-source-cut-of-something-that-is-not-a-commit-is-refused
  (testing "@item-05-003 A source cut of something that is not a commit is refused"
    ;; Given "deadbeef..." is no commit of the project repository
    (c/with-candidate [cand _ {c1 "t-81"}]
      ;; When the operator pins source cut K-4 at "deadbeef..." with the tree digest "t-81"
      (let [resp (pin! cand "K-4" not-a-commit "t-81")]
        ;; Then the pinning is refused
        (is (= -32000 (get-in resp ["error" "code"])) (pr-str resp))
        (is (= "not-a-commit" (refusal resp))))
      ;; And no source cut K-4 is pinned
      (is (= [] (cuts-named cand "K-4")))
      (is (= [] (cuts cand))))))

(deftest item-05-004-a-source-cut-whose-tree-digest-is-not-its-commits-tree-is-refused
  (testing "@item-05-004 A source cut whose tree digest is not its commit's tree is refused"
    ;; Given commit c1 of the project repository has the tree digest "t-81"
    (c/with-candidate [cand _ {c1 "t-81"}]
      ;; When the operator pins source cut K-4 at commit c1 with the tree digest "t-99"
      (let [resp (pin! cand "K-4" c1 "t-99")]
        ;; Then the pinning is refused
        (is (= -32000 (get-in resp ["error" "code"])) (pr-str resp))
        (is (= "tree-digest-not-the-commits" (refusal resp))))
      ;; And no source cut K-4 is pinned
      (is (= [] (cuts-named cand "K-4")))
      (is (= [] (cuts cand))))))
