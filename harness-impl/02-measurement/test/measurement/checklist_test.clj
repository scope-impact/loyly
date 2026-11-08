(ns measurement.checklist-test
  "Rule: The checklist is derived from the stamped scenarios at a pinned source cut, never written."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [measurement.support :as s]))

(use-fixtures :each s/with-product)

(deftest item-02-001-one-stamped-item-per-stamped-scenario
  (s/pin! "K-3")
  (s/stamp! "03-001" "03-002" "04-001")
  (let [r (s/derive! "K-3" ["03-001" "03-002" "04-001"])
        [e] (s/events r)]
    (is (= ["measurement/checklist-derived"] (s/event-types r)))
    (is (= ["03-001" "03-002" "04-001"] (mapv #(get % "item-id") (get e "items"))))
    (is (= 3 (get e "total")))
    (let [c (s/checklist "K-3")]
      (is (= 3 (get c "total")))
      (is (= ["03-001" "03-002" "04-001"] (mapv #(get % "item-id") (get c "items"))))
      (is (= (get e "checklist-digest") (get c "checklist-digest")))
      (testing "the digest is the digest of those three stamped items"
        (s/pin! "K-4")
        (s/pin! "K-5")
        (let [same (first (s/events (s/derive! "K-4" ["03-001" "03-002" "04-001"])))
              other (first (s/events (s/derive! "K-5" ["03-001" "03-002"])))]
          (is (= (get e "checklist-digest") (get same "checklist-digest")))
          (is (not= (get e "checklist-digest") (get other "checklist-digest"))))))))

(deftest item-02-002-the-spec-is-read-as-the-stamper-reads-it
  (s/pin! "K-3")
  (s/stamp! "03-001" "03-002")
  (let [src ".pi/plan/03-x/spec.feature"
        r (s/derive! "K-3" [(s/item "03-002" :rule nil :source (str src ":3"))
                            (s/item "03-001" :rule "Stamping" :source (str src ":6") :title "a padded title")])
        items (get (s/checklist "K-3") "items")]
    (is (s/result r))
    (is (= "03-002" (get (first items) "item-id")))
    (is (nil? (get (first items) "rule")))
    (is (= {"item-id" "03-001" "rule" "Stamping" "source" (str src ":6") "title" "a padded title"}
           (select-keys (second items) ["item-id" "rule" "source" "title"])))
    (testing "never sorted by item identity"
      (is (= ["03-002" "03-001"] (mapv #(get % "item-id") items))))))

(deftest item-02-003-a-skipped-slice-is-on-record
  (s/pin! "K-3")
  (s/stamp! "05-001" "05-002")
  (let [r (s/derive! "K-3" ["05-001" "05-002"]
                     :skipped [{"slice" "03" "status" "planned"} {"slice" "04" "status" "backlog"}])
        c (s/checklist "K-3")]
    (is (s/result r))
    (is (= ["05-001" "05-002"] (mapv #(get % "item-id") (get c "items"))))
    (is (= [{"slice" "03" "status" "planned"} {"slice" "04" "status" "backlog"}]
           (get c "skipped-slices")
           (get (first (s/events r)) "skipped-slices")))))

(deftest item-02-004-every-unstamped-scenario-is-listed
  (s/pin! "K-3")
  (s/stamp! "01-001")
  (let [unstamped [{"source" ".pi/plan/01-a/spec.feature:12" "title" "unstamped one"}
                   {"source" ".pi/plan/02-b/spec.feature:30" "title" "unstamped two"}]
        r (s/derive! "K-3" ["01-001"] :unstamped unstamped)]
    (is (= "unstamped-scenarios" (s/refusal r)))
    (is (= -32000 (get-in r ["error" "code"])))
    (is (= unstamped (get-in r ["error" "data" "unstamped"])))
    (is (re-find #"specification/stamp-slice" (get-in r ["error" "message"])))
    (is (nil? (s/checklist "K-3")))))

(deftest item-02-005-an-empty-checklist-is-refused
  (s/pin! "K-3")
  (let [r (s/derive! "K-3" [] :skipped [{"slice" "03" "status" "planned"} {"slice" "04" "status" "backlog"}])]
    (is (= "empty-checklist" (s/refusal r)))
    (is (nil? (s/checklist "K-3")))))

(deftest item-02-006-an-identity-never-handed-out-is-refused
  (s/pin! "K-3")
  (s/stamp! "03-001")
  (let [r (s/derive! "K-3" ["03-001" "03-007"])]
    (is (= "item-never-stamped" (s/refusal r)))
    (is (= ["03-007"] (get-in r ["error" "data" "item-ids"])))
    (is (nil? (s/checklist "K-3")))))

(deftest item-02-007-an-empty-title-is-refused
  (s/pin! "K-3")
  (s/stamp! "03-001")
  (let [r (s/derive! "K-3" [(s/item "03-001" :title "")])]
    (is (= "empty-title" (s/refusal r)))
    (is (nil? (s/checklist "K-3")))))

(deftest item-02-033-a-cut-never-pinned-is-refused
  (s/stamp! "03-001")
  (let [r (s/derive! "K-9" ["03-001"])]
    (is (= "cut-not-pinned" (s/refusal r)))
    (is (nil? (s/checklist "K-9")))))

(deftest item-02-034-an-identity-carried-twice-is-refused
  (s/pin! "K-3")
  (s/stamp! "03-001")
  (let [r (s/derive! "K-3" [(s/item "03-001") (s/item "03-001" :source ".pi/plan/03/spec.feature:20")])]
    (is (= "item-twice" (s/refusal r)))
    (is (= ["03-001"] (get-in r ["error" "data" "item-ids"])))
    (is (nil? (s/checklist "K-3")))))

(deftest item-02-035-a-source-cut-has-one-checklist
  (s/pin! "K-3")
  (s/stamp! "03-001" "03-002" "03-003")
  (is (s/result (s/derive! "K-3" ["03-001" "03-002" "03-003"])))
  (let [r (s/derive! "K-3" ["03-001"])]
    (is (= "checklist-already-derived" (s/refusal r)))
    (is (= 3 (get (s/checklist "K-3") "total")))))
