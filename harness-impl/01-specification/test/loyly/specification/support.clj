(ns loyly.specification.support
  "Drives the product through its seam: JSON-RPC request lines in, response
   lines out, against a fresh product on a fresh store, with spec files in a
   fresh folder under target/."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.specification.rpc :as rpc]
            [loyly.specification.system :as system]))

(def ^:dynamic *product* nil)
(def ^:dynamic *root* nil)

(defn work-dir ^java.io.File []
  (let [d (io/file "target" "test-work" (str (random-uuid)))]
    (.mkdirs d)
    (.getAbsoluteFile d)))

(defmacro with-product
  "A fresh product (in-memory store) and a fresh folder for spec files."
  [& body]
  `(let [p# (system/start {})]
     (try
       (binding [*product* p# *root* (work-dir)]
         ~@body)
       (finally (system/stop p#)))))

(def ^:private ids (atom 0))

(defn call
  "Send one request line through the seam; the decoded response."
  [method params]
  (let [id (swap! ids inc)
        line (json/write-str {"jsonrpc" "2.0" "id" id "method" method "params" params})
        response (json/read-str (rpc/handle-line *product* line))]
    (assert (= id (get response "id")) "the response carries the request's id")
    response))

(defn path
  "The absolute path of a file under this test's folder."
  [rel]
  (str (io/file *root* rel)))

(defn write-spec!
  "Write spec lines to `rel` (e.g. \"plan/03-gate/spec.feature\"); line n of
   `lines` is line n of the file."
  [rel lines]
  (let [f (io/file *root* rel)]
    (.mkdirs (.getParentFile f))
    (spit f (str (str/join "\n" lines) "\n"))
    (str f)))

(defn spec-lines [rel]
  (str/split-lines (slurp (io/file *root* rel))))

(defn stamp
  "Stamp `slice` from `rel`, offering `unstamped` [[line title] ...] and
   `empty-lines`."
  ([slice rel unstamped] (stamp slice rel unstamped []))
  ([slice rel unstamped empty-lines]
   (call "specification/stamp-slice"
         {"slice" slice
          "source" (path rel)
          "unstamped" (mapv (fn [[l t]] {"line" l "title" t}) unstamped)
          "empty-title-lines" (vec empty-lines)})))

(defn events [response]
  (get-in response ["result" "events"]))

(defn stamped-events [response]
  (filterv #(= "specification/scenario-stamped" (get % "type")) (events response)))

(defn item-ids [response]
  (mapv #(get % "item-id") (stamped-events response)))

(defn slices
  ([] (get-in (call "specification/stamped-items" {}) ["result" "slices"]))
  ([slice] (get-in (call "specification/stamped-items" {"slice" slice}) ["result" "slices"])))

(defn slice-entry [slice] (first (slices slice)))

(defn next-number [slice] (get (slice-entry slice) "next-number"))

(defn refusal [response] (get-in response ["error" "data" "refusal"]))

(defn heading-tagged?
  "Is the line directly above the heading on `heading-line` (1-based, in the
   current file) the tag `tag`?"
  [rel heading-line tag]
  (= tag (str/trim (nth (spec-lines rel) (- heading-line 2)))))

(defn padded
  "Spec lines with the given {line text} placed at those 1-based lines and
   filler elsewhere, `n` lines long."
  [n placed]
  (mapv (fn [i] (get placed i (if (= i 1) "Feature: gate" ""))) (range 1 (inc n))))
