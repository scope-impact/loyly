(ns loyly.pi-harness.core.evidence.otel-evidence
  "OTLP JSON projection of an already-persisted invariant evaluation."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- sha256 [value]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest (.getBytes (str value) "UTF-8"))))))

(defn- attribute [key value]
  {:key key :value {:stringValue (str value)}})

(defn- safe-filename [value]
  (str/replace value #"[^A-Za-z0-9._-]" "_"))

(defn write-evaluation!
  [results-root {:keys [evaluation-id evidence-digest result] :as evaluation}]
  (when-not (and (string? evaluation-id) (not (str/blank? evaluation-id))
                 (string? evidence-digest) (not (str/blank? evidence-digest))
                 (contains? #{"pass" "fail" "broken"} result))
    (throw (ex-info "OTel projection requires a persisted evaluation identity, evidence digest, and verdict"
                    {:evaluation evaluation})))
  (let [directory (io/file results-root)
        _ (.mkdirs directory)
        digest (sha256 evaluation-id)
        now (str (* 1000000 (System/currentTimeMillis)))
        span {:traceId (subs digest 0 32)
              :spanId (subs (sha256 (str evaluation-id "|result")) 0 16)
              :name "loyly.invariant.evaluation"
              :kind 1
              :startTimeUnixNano now
              :endTimeUnixNano now
              :attributes [(attribute "loyly.evaluation.id" evaluation-id)
                           (attribute "loyly.evidence.digest" evidence-digest)
                           (attribute "loyly.evaluation.verdict" result)
                           (attribute "loyly.cycle.id" (:cycle-id evaluation))
                           (attribute "loyly.item.id" (:item-id evaluation))
                           (attribute "loyly.subject.digest" (:subject-digest evaluation))
                           (attribute "loyly.oracle.digest" (:oracle-digest evaluation))
                           (attribute "loyly.producer" (:producer evaluation))]
              :status {:code (if (= "pass" result) 1 2)}}
        payload {:resourceSpans
                 [{:resource
                   {:attributes [(attribute "service.name" "loyly-pi-harness")]}
                   :scopeSpans
                   [{:scope {:name "loyly.pi-harness.evidence-projection"
                             :version "1"}
                     :spans [span]}]}]}
        output (io/file directory (str (safe-filename evaluation-id) ".otlp.json"))]
    (spit output (json/write-str payload))
    (.getAbsolutePath output)))
