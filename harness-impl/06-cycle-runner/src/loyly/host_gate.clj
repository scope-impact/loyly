(ns loyly.host-gate (:require [loyly.process :as proc] [loyly.effects :as effects] [clojure.string :as str] [clojure.java.io :as io]))
(defn answer [url]
  (try (let [conn ^java.net.HttpURLConnection (.openConnection (.toURL (java.net.URI/create url)))]
    (.setConnectTimeout conn 200) (.setReadTimeout conn 200)
    (try (when (< (.getResponseCode conn) 400) (slurp (.getInputStream conn))) (finally (.disconnect conn))))
    (catch Exception _ nil)))
(defn perform [env p conf in evidence]
  (let [gate (:gate-room in) app (str gate "/" (get conf "APP_DIR")) stdio (= "stdio" (get conf "APP_SEAM"))
        start (str app "/start.sh") test (str app "/test.sh") probe-path (get conf "APP_PROBE")
        executable (str/starts-with? (effects/git env "ls-tree" (:commit p) probe-path) "100755")
        base {:boot-polls (if stdio 1 20) :boot-budget-seconds (if stdio 30 10) :identity-probe-present executable :seam-answered-before-boot false}
        fail (fn [reason detail] (assoc base :outcome "failed" :reason reason :detail detail))
        timeout (get-in p [:budget :stage-timeouts-seconds :gate]) url (str "http://127.0.0.1:" (get conf "PORT"))]
    (cond
      (not (and (.canExecute (io/file start)) (.canExecute (io/file test)))) (fail "contract-missing" "test.sh and start.sh must be executable")
      (not executable) (fail "identity-probe-failed" "The identity probe is absent or not executable at the cut")
      (and (not stdio) (answer url)) (assoc (fail "boot-budget-exhausted" (str url " answered before boot")) :seam-answered-before-boot true)
      :else
      (let [suite (proc/clean-run! gate [test] timeout {})]
        (spit (str evidence "/suite.txt") (str (:out suite) (:err suite)))
        (cond
          (:timeout suite) {:timeout true}
          (not (zero? (:exit suite))) (fail "suite-red" (str (:out suite) (:err suite)))
          (re-find #"(?i)(?:ran\s+0\s+tests|0\s+tests?\s+(?:discovered|found))" (:out suite)) (fail "suite-discovery-empty" (:out suite))
          stdio
          (let [probe (str (.getParent (io/file gate)) "/identity-probe-" (:attempt in) ".sh") store (str evidence "/events.sqlite")
                _ (spit probe (effects/cut-file env p probe-path)) _ (.setExecutable (io/file probe) true)
                result (proc/clean-run! gate [probe start "30"] timeout {"GATE_STORE" store})]
            (spit (str evidence "/probe.txt") (str (:out result) (:err result)))
            (if-not (zero? (:exit result)) (fail "identity-probe-failed" (str (:out result) (:err result)))
              (let [cmd (get env "LOYLY_STORE_CHECK_CMD")]
                (when (str/blank? cmd) (throw (ex-info "LOYLY_STORE_CHECK_CMD is missing" {})))
                (let [check (proc/clean-run! (get env "LOYLY_PROJECT_REPO") ["sh" "-c" (str "exec " cmd " \"$1\"") "loyly" store] timeout {})]
                  (spit (str evidence "/store.txt") (str (:out check) (:err check)))
                  (if (zero? (:exit check)) (assoc base :outcome "passed" :answered-on-poll 1 :identity-probe-result (str/trim (:out result)) :detail "")
                    (fail "identity-probe-failed" (str (:out check) (:err check))))))))
          :else
          (let [pb (proc/clean-builder gate [start (get conf "PORT")] {})
                _ (.redirectOutput pb java.lang.ProcessBuilder$Redirect/DISCARD) _ (.redirectError pb java.lang.ProcessBuilder$Redirect/DISCARD)
                server (.start pb)]
            (try
              (loop [poll 1]
                (if-let [body (answer url)]
                  (if-not (str/includes? (str/lower-case body) (str/lower-case (get conf "APP_MARKER" ""))) (fail "identity-probe-failed" "Page does not contain the configured marker")
                    (let [r (proc/clean-run! gate [(str gate "/" probe-path) url] timeout {})]
                      (spit (str evidence "/probe.txt") (str (:out r) (:err r)))
                      (if (zero? (:exit r)) (assoc base :outcome "passed" :answered-on-poll poll :identity-probe-result (str/trim (:out r)) :detail "")
                        (fail "identity-probe-failed" (str (:out r) (:err r))))))
                  (if (= poll 20) (fail "boot-budget-exhausted" "No answer within 20 polls") (do (Thread/sleep 500) (recur (inc poll))))))
              (finally (proc/stop! server)))))))))
