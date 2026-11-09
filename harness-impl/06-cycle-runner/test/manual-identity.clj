#!/usr/bin/env bb
(ns manual-identity)
(require '[clojure.java.io :as io] '[cheshire.core :as json] '[babashka.process :refer [shell]])
(def root (.getCanonicalPath (io/file (System/getProperty "user.dir"))))
(def dir (str root "/work/manual-" (random-uuid)))
(def repo (str dir "/project"))
(defn put [p s] (io/make-parents p) (spit p s) p)
(defn script [p s] (put p (str "#!/bin/sh\nset -eu\n" s "\n")) (shell "chmod" "+x" p) p)
(defn git [& xs] (apply shell {:out :string :err :string} "git" "-c" "safe.directory=*" "-C" repo xs))
(.mkdirs (io/file repo))
(put (str repo "/plan/01-a/spec.feature") "Feature: identity\n  @item-01-001\n  Scenario: a scenario\n    Given a fixture\n")
(script (str repo "/probe.sh") "exit 0")
(git "init" "-q") (git "add" ".") (git "-c" "user.name=Identity" "-c" "user.email=identity@local" "commit" "-qm" "fixture")
(def commit (clojure.string/trim (:out (git "rev-parse" "HEAD"))))
(def phase (put (str dir "/phase") "exit"))
(def context-program
  (put (str dir "/context.clj")
       (str "(require '[cheshire.core :as j] '[clojure.java.io :as io])\n"
            "(doseq [line (line-seq (io/reader *in*))] (let [r (j/parse-string line true) mode (slurp \"" phase "\")] "
            "(when (or (= mode \"exit\") (= (:method r) \"specification/stamp-slice\")) (System/exit 0)) "
            "(println (j/generate-string {:jsonrpc \"2.0\" :id (:id r) :result {}})) (flush)))")))
(def names ["specification" "cycle-preparation" "grader-lifecycle" "regeneration-cycle" "measurement"])
(def contexts (put (str dir "/contexts.json") (json/generate-string (into {} (for [n names] [n (script (str dir "/" n ".sh") (str "exec bb '" context-program "'"))])))))
(def config (put (str dir "/config.sh") "APP_DIR=out\nPLAN_DIR=plan\nAPP_SEAM=stdio\nAPP_PROBE=probe.sh\nFENCED_PATHS=old\n"))
(def self-check (script (str dir "/self-check.sh") (str "touch '" dir "/self-check-ran'")))
(def builder (script (str dir "/builder.sh") (str "touch '" dir "/builder-ran'")))
(def store (str dir "/fresh.sqlite"))
(def pb (ProcessBuilder. [(str root "/start.sh")]))
(doseq [[k v] {"LOYLY_PROJECT_REPO" repo "LOYLY_RUNS_DIR" (str dir "/runs") "LOYLY_HARNESS_CONFIG" config
               "LOYLY_CONTEXTS" contexts "LOYLY_SELF_CHECK_CMD" self-check "LOYLY_BUILDER_CMD" builder
               "LOYLY_GRAIN_STORE" store "LOYLY_BUILDER_INSTRUCTION" (put (str dir "/instruction.md") "Identity fixture")}] (.put (.environment pb) k v))
(.redirectError pb (io/file (str dir "/stderr")))
(def began (System/nanoTime))
(def process (.start pb))
(def writer (io/writer (.getOutputStream process)))
(def reader (io/reader (.getInputStream process)))
(defn call [id method params]
  (let [request {:jsonrpc "2.0" :id id :method method :params params}]
    (.write writer (str (json/generate-string request) "\n")) (.flush writer)
    (let [line (.readLine reader) response (json/parse-string line true)]
      (spit (str dir "/transcript.jsonl") (str (json/generate-string request) "\n" line "\n") :append true)
      (assert (= id (:id response))) (println line) response)))
(def params {:run-id "identity" :cycle-id "cycle" :cut-id "cut" :selection-id "selection" :commit commit
             :grader-digests ["approved"] :budget {:gate-attempt-limit 3 :max-outer-cycles 1 :max-continuations 0
               :stage-timeouts-seconds {:fence 30 :regenerate 30 :gate 30 :score 30}}})
(try
  (assert (= "not-a-commit" (get-in (call 1 "cycle-runner/start-run" (assoc params :commit (apply str (repeat 40 "d")))) [:error :data :refusal])))
  (assert (= (set names) (set (get-in (call 2 "cycle-runner/start-run" params) [:error :data :contexts]))))
  (spit phase "stamp")
  (assert (seq (get-in (call 3 "cycle-runner/start-run" params) [:result :events])))
  (let [r (get-in (call 4 "cycle-runner/run-progress" {:run-id "identity"}) [:result :run])]
    (assert (= "halted" (:status r))) (assert (= "stamp" (get-in r [:halt :effect])))
    (assert (= "context-did-not-respond" (get-in r [:halt :reason]))) (assert (empty? (:effect-results r))))
  (assert (not (.exists (io/file dir "self-check-ran")))) (assert (not (.exists (io/file dir "builder-ran"))))
  (let [seconds (/ (- (System/nanoTime) began) 1e9)] (assert (< seconds 30)) (println "Identity effects confirmed in" seconds "seconds."))
  (.close writer) (.waitFor process 10000 java.util.concurrent.TimeUnit/MILLISECONDS)
  (println "Fresh store:" store)
  (spit (str root "/work/manual-store-path") store)
  (finally (.destroyForcibly process)))
