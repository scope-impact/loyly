(ns loyly.identity
  (:require [loyly.spine :as spine] [clojure.data.json :as json] [clojure.java.io :as io]))
(defn rpc [writer reader id method params]
  (.write writer (str (json/write-str {:jsonrpc "2.0" :id id :method (str "cycle-runner/" method) :params params}) "\n"))
  (.flush writer)
  (let [line (.readLine reader) reply (json/read-str line :key-fn keyword)]
    (assert (= id (:id reply)) line) reply))
(defn -main [& _]
  (let [root (.getCanonicalFile (.toFile (java.nio.file.Files/createTempDirectory (.toPath (io/file ".")) "identity-" (make-array java.nio.file.attribute.FileAttribute 0))))
        server (spine/model)]
    (try
      (let [{:keys [params env]} (spine/fixture root server)
            config (get env "LOYLY_HARNESS_CONFIG") original (slurp config)
            pb (ProcessBuilder. ["./start.sh"]) _ (.putAll (.environment pb) (assoc env "LOYLY_GRAIN_STORE" (str (io/file root "fresh.sqlite")) "LOYLY_BUILDER_IMAGES" (str root)))
            _ (.redirectError pb java.lang.ProcessBuilder$Redirect/INHERIT) proc (.start pb)]
        (try
          (with-open [w (io/writer (.getOutputStream proc)) r (io/reader (.getInputStream proc))]
            (let [send (partial rpc w r)]
              (assert (= "not-a-commit" (get-in (send 1 "start-run" (assoc params :commit (apply str (repeat 40 "f")))) [:error :data :refusal])))
              (spit config (str original "BUILDER_IMAGE=absent\n"))
              (assert (= "builder-image-missing" (get-in (send 2 "start-run" params) [:error :data :refusal])))
              (spit config original)
              (send 3 "start-run" (assoc params :grader-digests ["other"]))
              (let [run (get-in (send 4 "run-progress" {:run-id "R1"}) [:result :run])]
                (assert (= ["stamp" "pin" "checklist"] (mapv :effect (:effect-results run))))
                (assert (= ["grader" "refused-before-the-cycle"] ((juxt :effect :reason) (:halt run)))))
              (send 5 "start-run" (assoc params :run-id "R2" :cycle-id "C2" :cut-id "K2"))
              (let [run (get-in (send 6 "run-progress" {:run-id "R2"}) [:result :run])]
                (assert (= "passed" (:status run)) (str run))
                (assert (= [{:item-id "01-001" :verdict "pass" :reason "booked"}] (:scores run)))
                (assert (= 11 (count (:effect-results run))))
                (println "Fresh-store identity passed. Both initial refusals, grader halt, eleven effects and judge score verified."))))
          (finally (.destroy proc) (.waitFor proc))))
      (finally (.stop server 0) (doseq [f (reverse (file-seq root))] (.delete f)) (shutdown-agents)))))
