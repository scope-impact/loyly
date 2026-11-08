(ns loyly.grader-lifecycle.support
  "Drives the candidate through its public seam: JSON-RPC lines in, response
   lines out, on a fresh Grain store and a scenario-digests file per test."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [clojure.data.json :as json]
            [loyly.grader-lifecycle.seam :as seam]
            [loyly.grader-lifecycle.system :as system])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *sys* nil)
(def ^:dynamic *dir* nil)

(defn temp-dir []
  (.toFile (Files/createTempDirectory "loyly-gl-test" (make-array FileAttribute 0))))

(defn digests-file ^File [] (File. ^File *dir* "scenario-digests.json"))

(defn set-digests!
  "The issuer hands in the current scenario digests."
  [m]
  (spit (digests-file) (json/write-str m)))

(defn delete-digests! [] (.delete (digests-file)))

(defn with-system* [f]
  (let [dir (temp-dir)
        store (File. dir "store.db")
        sys (system/start {:store-path (str store)
                           :digests-path (str (File. dir "scenario-digests.json"))})]
    (try
      (binding [*sys* sys *dir* dir]
        (f))
      (finally
        (system/stop sys)))))

(def ids (atom 0))

(defn rpc
  "Sends one request line through the seam; returns the decoded response."
  ([method params]
   (rpc {"jsonrpc" "2.0" "id" (swap! ids inc) "method" method "params" params}))
  ([req]
   (let [line (if (string? req) req (json/write-str req))
         resp-line (json/write-str (seam/handle-line *sys* line))]
     (json/read-str resp-line))))

(defn write! [params] (rpc "grader-lifecycle/write-instrument-golden" params))
(defn move! [params] (rpc "grader-lifecycle/move-instrument-golden" params))
(defn goldens [] (get-in (rpc "grader-lifecycle/instrument-goldens" {}) ["result" "goldens"]))

(defn golden [golden-id]
  (first (filter #(= golden-id (get % "golden-id")) (goldens))))

(defn refusal [resp] (get-in resp ["error" "data" "refusal"]))
(defn error-code [resp] (get-in resp ["error" "code"]))

(defn store-events
  "Every event of the tenant, read from the store directly."
  []
  (vec (es/read (:event-store *sys*) {:tenant-id system/tenant-id})))

(defn model-events []
  (filterv #(= "grader-lifecycle" (namespace (:event/type %))) (store-events)))
