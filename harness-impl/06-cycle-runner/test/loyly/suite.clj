(ns loyly.suite
  (:require [loyly.spine] [loyly.paths] [loyly.lifecycle] [clojure.test :refer [deftest is testing run-tests]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [loyly.main :as main]))
(def params {:run-id "R1" :cycle-id "C1" :cut-id "K1" :selection-id "S1"
             :grader-digests ["unapproved"]
             :budget {:gate-attempt-limit 1 :max-continuations 0 :max-outer-cycles 1
                      :stage-timeouts-seconds {:fence 5 :regenerate 10 :gate 10 :score 30}}})
(defn request [system method params]
  (main/respond system {:jsonrpc "2.0" :id 1 :method method :params params}))
(deftest start-requires-real-inputs
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory (.toPath (io/file ".")) "suite-" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo (io/file dir "repo") config (io/file dir "config")
        marker (io/file dir "builder-ran") host (io/file dir "builder")
        git (fn [& args] (apply sh/sh "git" "-C" (str repo) args))]
    (try
      (.mkdir repo)
      (git "init" "-q")
      (git "-c" "user.name=Fixture" "-c" "user.email=fixture@example.invalid" "commit" "-q" "--allow-empty" "-m" "fixture")
      (spit host (str "#!/bin/sh\ntouch '" (.getAbsolutePath marker) "'\n"))
      (.setExecutable host true)
      (spit config "BUILDER_IMAGE=missing\n")
      (let [system (main/open! (str (io/file dir "store"))
                               (fn [] {"LOYLY_PROJECT_REPO" (str repo) "LOYLY_HARNESS_CONFIG" (str config)
                                       "LOYLY_BUILDER_IMAGES" (str dir) "LOYLY_BUILDER_HOST" (str host)}))]
        (try
          (doseq [[item commit expected] [["@item-06-005" (apply str (repeat 40 "f")) "not-a-commit"]
                                           ["@item-06-050" (.trim (:out (git "rev-parse" "HEAD"))) "builder-image-missing"]]]
            (testing item
              (let [reply (request system "cycle-runner/start-run" (assoc params :commit commit))]
                (is (= -32000 (get-in reply [:error :code])))
                (is (= expected (get-in reply [:error :data :refusal])))
                (is (nil? (:result (request system "cycle-runner/run-progress" {:run-id "R1"}))))
                (is (false? (.exists marker))))))
          (finally (main/close! system))))
      (finally (doseq [f (reverse (file-seq dir))] (.delete f))))))
(defn -main [& _]
  (let [{:keys [test fail error]} (run-tests 'loyly.suite 'loyly.spine 'loyly.paths 'loyly.lifecycle)]
    (shutdown-agents)
    (System/exit (if (and (pos? test) (zero? (+ fail error))) 0 1))))
