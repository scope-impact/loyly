(ns loyly.lifecycle
  (:require [clojure.data.json :as json] [clojure.java.io :as io] [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.main :as main] [loyly.paths :as paths] [loyly.spine :as spine] [loyly.host :as host]))
(defn bounded [seconds check]
  (let [deadline (+ (System/nanoTime) (* 1000000000 seconds))]
    (loop []
      (or (check)
          (when (< (System/nanoTime) deadline)
            (Thread/sleep 75)
            (recur))))))
(deftest isolated-copy
  (testing "@item-06-062"
    (let [cwd (.getCanonicalFile (io/file ".")) copy (System/getenv "LOYLY_TEST_CANDIDATE")]
      (is (some? copy) "test.sh must launch its JVM in the standalone copy")
      (when copy
        (is (= (.getPath cwd) copy))
        (is (= (.getCanonicalFile (io/file cwd "src/loyly/main.clj"))
               (.getCanonicalFile (io/file (.toURI (io/resource "loyly/main.clj"))))))
        (doseq [parent (take-while some? (iterate #(when % (.getParentFile %)) cwd))]
          (is (false? (.exists (io/file parent ".pi"))) (str "The copy can see a .pi directory at " parent)))
        (paths/with-case (fn [{:keys [p repo] :as c}]
          (is (not (.startsWith (.toPath cwd) (.toPath repo))))
          (let [src (paths/prepare-builder c) [cut port] (paths/http-fixture c src "sauna")
                run (paths/start c (assoc p :commit cut))]
            (is (= "passed" (:status run)))
            (is (= "regeneration-cycle/gate-attempt-passed" (:type (paths/first-event run "gate"))))
            (is (= 1 (:passes (paths/first-event run "file-score")))))))))))
(defn terminate-builder [pid]
  (when-let [builder (.orElse (java.lang.ProcessHandle/of pid) nil)]
    (with-open [children (.descendants builder)]
      (doseq [child (reverse (vec (.toArray children)))] (.destroyForcibly ^java.lang.ProcessHandle child)))
    (.destroyForcibly builder)
    (.get (.onExit builder) 5 java.util.concurrent.TimeUnit/SECONDS)))
(deftest reopen-builder-session
  (testing "@item-06-070"
    (let [dir (.getCanonicalFile (.toFile (java.nio.file.Files/createTempDirectory (.toPath (io/file ".")) "restart-" (make-array java.nio.file.attribute.FileAttribute 0))))
          server (spine/model) systems (atom [])]
      (try
        (let [{:keys [params env]} (spine/fixture dir server)
              b (env "LOYLY_BUILDER_HOST") normal (slurp b)
              launches (io/file dir "launches") store (str (io/file dir "events.sqlite"))
              boot (fn [] (let [s (main/open! store (constantly env))] (swap! systems conj s) s))
              _ (spit b (str "#!/bin/sh\nset -eu\nprintf '%s %s\\n' \"$$\" \"$LOYLY_BUILDER_SESSION_ID\" >> '" launches "'\n"
                             "if test \"$(wc -l < '" launches "' | tr -d ' ')\" = 1; then sleep 30; fi\n" normal))
              p (assoc-in params [:budget :stage-timeouts-seconds :regenerate] 90)
              before (boot) request (future (spine/call before "start-run" p))]
          (is (bounded 15 #(and (.exists launches) (seq (slurp launches)))))
          (Thread/sleep 5000)
          (let [before-run (get-in (spine/call before "run-progress" {:run-id "R1"}) [:result :run])
                [[pid session]] (map #(str/split % #" ") (str/split-lines (slurp launches)))]
            (is (= "running" (:status before-run)))
            (is (= "regenerate" (:name (last (get-in before-run [:tick :nodes])))))
            (main/close! before) (reset! systems [])
            (future-cancel request)
            (terminate-builder (parse-long pid))
            (let [after (boot) result (bounded 65 #(let [r (get-in (spine/call after "run-progress" {:run-id "R1"}) [:result :run])]
                                                      (when (#{"passed" "failed" "halted"} (:status r)) r)))]
              (is (= "passed" (:status result)) (pr-str (:halt result)))
              (is (= (get-in before-run [:tick :tick-id]) (get-in result [:tick :tick-id])))
              (is (= [session session] (mapv #(second (str/split % #" ")) (str/split-lines (slurp launches)))))
              (is (= ["stamp" "pin" "checklist" "self-check" "fence" "regenerate" "install" "self-check" "gate" "file-verdicts" "file-score"] (paths/names result)))
              (is (= 1 (:passes (paths/first-event result "file-score")))))))
        (finally
          (doseq [s @systems] (main/close! s))
          (.stop server 0)
          (host/checked "." ["rm" "-rf" dir]))))))
