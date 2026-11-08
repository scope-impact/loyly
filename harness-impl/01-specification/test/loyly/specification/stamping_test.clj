(ns loyly.specification.stamping-test
  "One test per stamped scenario of .pi/spec/specification/spec.feature, each
   driven through the seam and named by its @item id."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.specification.support :as s :refer [with-product stamp]]))

(def gate "plan/03-gate/spec.feature")

;; Rule: A slice's first stamping hands out its item identities from 1, in file order

(deftest item-01-001-first-stamping-hands-out-identities-in-file-order
  (with-product
    (is (empty? (s/slices "03")) "no scenario of slice 03 has ever been stamped")
    (s/write-spec! gate (s/padded 14 {6 "  Scenario: boots fresh"
                                      12 "  Scenario: refuses a busy seam"}))
    (let [r (stamp "03" gate [[6 "boots fresh"] [12 "refuses a busy seam"]])]
      (is (= ["03-001" "03-002"] (s/item-ids r)))
      (is (= [["boots fresh" 6] ["refuses a busy seam" 12]]
             (mapv (juxt #(get % "title") #(get % "line")) (s/stamped-events r))))
      ;; the identity is written into the spec file, above each heading
      (is (s/heading-tagged? gate 7 "@item-03-001"))
      (is (s/heading-tagged? gate 14 "@item-03-002"))
      (is (= "  @item-03-001" (nth (s/spec-lines gate) 5)) "with the heading's indentation")
      (is (= 3 (s/next-number "03")) "the next identity is @item-03-003"))))

(deftest item-01-002-slice-is-the-directory-name-up-to-its-first-dash
  (with-product
    (s/write-spec! gate (s/padded 3 {3 "Scenario: in the gate"}))
    (s/write-spec! "plan/notes/spec.feature" (s/padded 3 {3 "Scenario: in the notes"}))
    (let [notes-before (slurp (s/path "plan/notes/spec.feature"))]
      ;; the operator stamps every slice
      (is (= ["03-001"] (s/item-ids (stamp "03" gate [[3 "in the gate"]]))))
      (is (s/heading-tagged? gate 4 "@item-03-001"))
      (let [r (stamp "03" "plan/notes/spec.feature" [[3 "in the notes"]])]
        (is (= "no-spec-found" (s/refusal r)) "plan/notes holds no slice's spec"))
      (let [r (s/call "specification/stamp-slice"
                      {"slice" "notes" "source" (s/path "plan/notes/spec.feature")
                       "unstamped" [{"line" 3 "title" "in the notes"}] "empty-title-lines" []})]
        (is (= -32602 (get-in r ["error" "code"])) "\"notes\" is no slice"))
      (is (= notes-before (slurp (s/path "plan/notes/spec.feature"))) "its scenario is not stamped")
      (is (= ["03"] (mapv #(get % "slice") (s/slices)))))
    (testing "the slice is taken verbatim, up to the first dash"
      (s/write-spec! "plan/007-a-b/spec.feature" (s/padded 2 {2 "Scenario: verbatim"}))
      (is (= ["007-001"] (s/item-ids (stamp "007" "plan/007-a-b/spec.feature" [[2 "verbatim"]]))))
      (is (= "not-this-slice" (s/refusal (stamp "7" "plan/007-a-b/spec.feature" [])))))))

;; Rule: Stamping again continues the slice's numbering and never reuses a number

(deftest item-01-003-deleted-scenario-number-is-never-handed-out-again
  (with-product
    (s/write-spec! gate (s/padded 8 {2 "Scenario: one" 4 "Scenario: two" 6 "Scenario: three" 8 "Scenario: four"}))
    (is (= ["03-001" "03-002" "03-003" "03-004"]
           (s/item-ids (stamp "03" gate [[2 "one"] [4 "two"] [6 "three"] [8 "four"]]))))
    ;; the scenario stamped @item-03-004 is deleted; a new one is added
    (let [lines (vec (s/spec-lines gate))]
      (is (= ["@item-03-004" "Scenario: four"] (subvec lines 10 12)))
      (s/write-spec! gate (conj (subvec lines 0 10) "Scenario: refuses a second launch")))
    (let [before (s/spec-lines gate)
          r (stamp "03" gate [[11 "refuses a second launch"]])]
      (is (= ["03-005"] (s/item-ids r)))
      (is (= (count (filter #(str/starts-with? % "@item-03-00") before))
             3)
          "three identities were carried before")
      (is (= (concat (take 10 before) ["@item-03-005"] (drop 10 before)) (s/spec-lines gate))
          "no stamped item is renumbered")
      (is (= 6 (s/next-number "03"))))))

(deftest item-01-004-stamping-twice-changes-nothing
  (with-product
    (s/write-spec! gate (s/padded 4 {2 "Scenario: a" 4 "Scenario: b"}))
    (is (= ["03-001" "03-002"] (s/item-ids (stamp "03" gate [[2 "a"] [4 "b"]]))))
    (let [file-before (slurp (s/path gate))
          r (stamp "03" gate [])]
      (is (contains? r "result") "fully stamped is a success, not a refusal")
      (is (empty? (s/stamped-events r)) "no scenario is stamped")
      (is (= file-before (slurp (s/path gate)))))
    (let [ids (mapv #(get % "item-id") (get (s/slice-entry "03") "items"))]
      (is (= ["03-001" "03-002"] ids))
      (is (= ids (distinct ids)) "each identity names exactly one stamped item"))
    (testing "offering an already stamped scenario again is refused"
      (is (= "unstamped-mismatch" (s/refusal (stamp "03" gate [[3 "a"]])))))))

(deftest item-01-005-rewording-keeps-the-item-identity
  (with-product
    (s/write-spec! gate (s/padded 2 {2 "Scenario: boots fresh"}))
    (is (= ["03-001"] (s/item-ids (stamp "03" gate [[2 "boots fresh"]]))))
    (spit (s/path gate) (str/replace (slurp (s/path gate)) "boots fresh" "boots fresh from its launch entrypoint"))
    (let [r (stamp "03" gate [])]
      (is (empty? (s/stamped-events r)) "no scenario is stamped"))
    (is (= ["@item-03-001" "Scenario: boots fresh from its launch entrypoint"]
           (subvec (vec (s/spec-lines gate)) 1 3)))
    (is (= ["03-001"] (mapv #(get % "item-id") (get (s/slice-entry "03") "items"))))))

;; Rule: Stamping refuses a spec it cannot read as the slice's own

(deftest item-01-006-no-spec-found-for-a-slice-never-stamped
  (with-product
    (let [r (stamp "07" "plan/07-score/spec.feature" [])
          message (get-in r ["error" "message"])]
      (is (= -32000 (get-in r ["error" "code"])))
      (is (= "no-spec-found" (s/refusal r)))
      (is (str/includes? message "plan/07-score/spec.feature") "names where it looked")
      (is (not (str/includes? message "fully stamped"))))
    (is (empty? (s/slices "07")) "slice 07 has no stamped item")))

(deftest item-01-007-a-missing-spec-is-no-spec-found-never-fully-stamped
  (with-product
    (s/write-spec! gate (s/padded 4 {2 "Scenario: a" 4 "Scenario: b"}))
    (is (= ["03-001" "03-002"] (s/item-ids (stamp "03" gate [[2 "a"] [4 "b"]]))))
    (io/delete-file (s/path gate))
    (let [r (stamp "03" gate [])]
      (is (= "no-spec-found" (s/refusal r)))
      (is (not (contains? r "result")) "not reported fully stamped")
      (is (str/includes? (get-in r ["error" "message"]) "plan/03-gate/spec.feature"))
      (is (not (str/includes? (get-in r ["error" "message"]) "fully stamped"))))
    (is (= 3 (s/next-number "03")) "the next identity is still @item-03-003")))

(deftest item-01-008-only-the-files-unstamped-scenarios-in-file-order
  (testing "has never been stamped, offering \"b\" before \"a\""
    (with-product
      (s/write-spec! gate (s/padded 12 {6 "Scenario: a" 12 "Scenario: b"}))
      (let [before (slurp (s/path gate))
            r (stamp "03" gate [[12 "b"] [6 "a"]])]
        (is (= "unstamped-mismatch" (s/refusal r)))
        (is (= -32000 (get-in r ["error" "code"])))
        (is (= before (slurp (s/path gate))))
        (is (empty? (s/slices "03")) "no scenario of slice 03 is stamped by it"))))
  (testing "has stamped @item-03-001 as \"boots fresh\", offering it again with \"a\" and \"b\""
    (with-product
      (s/write-spec! gate (s/padded 3 {3 "Scenario: boots fresh"}))
      (is (= ["03-001"] (s/item-ids (stamp "03" gate [[3 "boots fresh"]]))))
      (s/write-spec! gate (s/padded 12 {3 "@item-03-001" 4 "Scenario: boots fresh"
                                        6 "Scenario: a" 12 "Scenario: b"}))
      (let [before (slurp (s/path gate))
            r (stamp "03" gate [[4 "boots fresh"] [6 "a"] [12 "b"]])]
        (is (= "unstamped-mismatch" (s/refusal r)))
        (is (= before (slurp (s/path gate))))
        (is (= ["03-001"] (mapv #(get % "item-id") (get (s/slice-entry "03") "items"))))
        (is (= 2 (s/next-number "03")))))))

;; Rule: An empty-title heading is not an item, and the stamper says so out loud

(deftest item-01-009-empty-title-heading-is-reported-not-stamped
  (with-product
    (s/write-spec! gate (s/padded 10 {6 "  Scenario: boots fresh" 9 "  Scenario:"}))
    (let [r (stamp "03" gate [[6 "boots fresh"]] [9])
          reported (filterv #(= "specification/empty-title-headings-reported" (get % "type")) (s/events r))]
      (is (= ["03-001"] (s/item-ids r)))
      (is (= 1 (count reported)))
      (is (= [9] (get (first reported) "lines")))
      (is (str/ends-with? (get (first reported) "source") "plan/03-gate/spec.feature"))
      (is (= "03" (get (first reported) "slice"))))
    (let [lines (s/spec-lines gate)]
      (is (= "  @item-03-001" (nth lines 5)))
      (is (= "  Scenario:" (nth lines 9)) "line 9 moved down by the tag above line 6")
      (is (not (str/starts-with? (str/trim (nth lines 8)) "@")) "line 9 is not stamped"))
    (is (= [9] (get (s/slice-entry "03") "empty-title-lines")))))

(deftest item-01-010-a-stamping-that-hides-an-empty-title-heading-is-refused
  (testing "has never been stamped"
    (with-product
      (s/write-spec! gate (s/padded 9 {6 "Scenario: one" 9 "Scenario:"}))
      (let [before (slurp (s/path gate))
            r (stamp "03" gate [[6 "one"]] [])]
        (is (= "empty-title-mismatch" (s/refusal r)))
        (is (= before (slurp (s/path gate))))
        (is (empty? (s/slices "03"))))))
  (testing "has stamped @item-03-001 and @item-03-002"
    (with-product
      (s/write-spec! gate (s/padded 4 {2 "Scenario: x" 4 "Scenario: y"}))
      (is (= ["03-001" "03-002"] (s/item-ids (stamp "03" gate [[2 "x"] [4 "y"]]))))
      (s/write-spec! gate (s/padded 9 {6 "Scenario: one" 9 "Scenario:"}))
      (let [before (slurp (s/path gate))
            r (stamp "03" gate [[6 "one"]] [])]
        (is (= "empty-title-mismatch" (s/refusal r)))
        (is (= before (slurp (s/path gate))))
        (is (= 3 (s/next-number "03")))
        (is (= 2 (count (get (s/slice-entry "03") "items"))))))))

;; Rule: One reading of Gherkin decides what is a scenario and what stamps it

(deftest item-01-011-outline-is-stamped-template-is-not
  (with-product
    (s/write-spec! gate (s/padded 14 {6 "Scenario Outline: an outline" 14 "Scenario Template: a template"}))
    (testing "a template is no scenario: offering it is refused"
      (is (= "unstamped-mismatch" (s/refusal (stamp "03" gate [[6 "an outline"] [14 "a template"]])))))
    (is (= ["03-001"] (s/item-ids (stamp "03" gate [[6 "an outline"]]))))
    (let [lines (s/spec-lines gate)]
      (is (= "@item-03-001" (nth lines 5)))
      (is (= "Scenario Template: a template" (nth lines 14)))
      (is (not (str/starts-with? (nth lines 13) "@")) "line 14 is not stamped"))))

(deftest item-01-012-whitespace-only-title-is-a-scenario
  (with-product
    (s/write-spec! gate (s/padded 6 {6 "Scenario:   "}))
    (testing "it is no empty-title heading"
      (is (= "unstamped-mismatch" (s/refusal (stamp "03" gate [] [6])))))
    (let [r (stamp "03" gate [[6 " "]])]
      (is (= ["03-001"] (s/item-ids r)))
      (is (= " " (get (first (s/stamped-events r)) "title"))))
    (is (= "@item-03-001" (nth (s/spec-lines gate) 5)))))

(deftest item-01-013-title-is-the-heading-title-trimmed
  (with-product
    (s/write-spec! gate (s/padded 6 {6 "    Scenario:   a padded title   "}))
    (testing "an untrimmed title is not the file's"
      (is (= "unstamped-mismatch" (s/refusal (stamp "03" gate [[6 "  a padded title   "]])))))
    (let [r (stamp "03" gate [[6 "a padded title"]])]
      (is (= ["03-001"] (s/item-ids r)))
      (is (= "a padded title" (get (first (s/stamped-events r)) "title"))))
    (is (= "a padded title" (get-in (s/slice-entry "03") ["items" 0 "title"])))))

(deftest item-01-014-item-tag-separated-by-a-blank-line-does-not-stamp
  (with-product
    (s/write-spec! gate (s/padded 2 {2 "Scenario: first"}))
    (is (= ["03-001"] (s/item-ids (stamp "03" gate [[2 "first"]]))))
    (s/write-spec! gate (s/padded 5 {3 "@item-03-001" 5 "Scenario: drifted away"}))
    (let [r (stamp "03" gate [[5 "drifted away"]])]
      (is (= ["03-002"] (s/item-ids r))))
    (is (= ["Feature: gate" "" "@item-03-001" "" "@item-03-002" "Scenario: drifted away"]
           (s/spec-lines gate)))))

(deftest item-01-015-other-tags-do-not-stamp
  (with-product
    (s/write-spec! gate (s/padded 6 {5 "  @wip @slow" 6 "  Scenario: has tags but no item tag"}))
    (let [r (stamp "03" gate [[6 "has tags but no item tag"]])]
      (is (= ["03-001"] (s/item-ids r))))
    (let [lines (s/spec-lines gate)]
      (is (= ["  @wip @slow" "  @item-03-001" "  Scenario: has tags but no item tag"]
             (subvec (vec lines) 4 7))
          "its tags @wip and @slow are kept"))))
