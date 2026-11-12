(ns runner.gate
  (:require [runner.util :as u] [runner.repository :as repo]
            [clojure.java.io :as io] [clojure.string :as str]))
(defn last-line [s] (last (remove str/blank? (map str/trim (str/split-lines s)))))
(defn executable? [p] (and (.isFile (io/file p)) (.canExecute (io/file p))))
(defn probe [s]
  (let [{:keys [env params config]} s path (:APP_PROBE config)
        metadata (u/git (env "LOYLY_PROJECT_REPO") "ls-tree" (:commit params) "--" path)
        present (str/starts-with? metadata "100755")
        text (try (repo/read-at (env "LOYLY_PROJECT_REPO") (:commit params) path) (catch Throwable _ ""))]
    {:present present :text text :digest (u/sha text)}))
(defn harness-digest [s]
  (let [{:keys [env config]} s p (probe s)
        extra (if-not (str/blank? (env "LOYLY_GATE_CMD")) (env "LOYLY_GATE_CMD") (when (= "stdio" (:APP_SEAM config)) (env "LOYLY_STORE_CHECK_CMD")))]
    (u/sha (str (slurp (env "LOYLY_HARNESS_CONFIG")) (env "LOYLY_SELF_CHECK_CMD") (:text p) extra))))
(defn checked-command [cmd args cwd clean? ms env]
  (let [r (u/shell cmd args cwd env clean? ms)]
    ;; A program may itself return 126 or 127. Only the shell's exec error
    ;; says it could not be launched; a builder's exit is never a verdict.
    (when (and (#{126 127} (:exit r)) (str/blank? (:out r))
               (re-find #"(?i)(not found|no such file|not executable|permission denied)" (:err r)))
      (throw (ex-info (str "Command could not start: " cmd " " (:err r)) {:reason "effect-could-not-run"})))
    r))
(defn command-gate [s room candidate evidence ms]
  (let [r (checked-command ((:env s) "LOYLY_GATE_CMD") [room candidate evidence] ((:env s) "LOYLY_PROJECT_REPO") false ms {})
        lines (map str/trim (str/split-lines (:out r)))
        boot (some #(when-let [[_ polls seconds] (re-matches #"BOOT ([1-9][0-9]*) ([1-9][0-9]*)" %)] {:boot-polls (Long/parseLong polls) :boot-budget-seconds (Long/parseLong seconds)}) lines)
        poll (some #(when-let [[_ p] (re-matches #"ANSWERED ([1-9][0-9]*)" %)] (Long/parseLong p)) lines)
        last (last-line (:out r))
        [code text] (str/split (or last "") #"\s+" 2)
        defaults (or boot {:boot-polls 20 :boot-budget-seconds 10})]
    (u/put (str evidence "/command.stdout") (:out r))
    (cond
      (:timeout r) (throw (ex-info "Gate stage timeout" {:reason "budget" :stage "gate"}))
      (zero? (:exit r))
      (if (and boot poll (<= poll (:boot-polls boot)))
        (merge boot {:outcome "passed" :answered-on-poll poll :identity-probe-result last :detail (or last "")})
        (throw (ex-info "Gate command passed without valid BOOT and ANSWERED lines" {:reason "effect-could-not-run"})))
      (#{"contract-missing" "suite-discovery-empty" "suite-red" "boot-budget-exhausted" "identity-probe-failed"} code)
      (merge defaults {:outcome "failed" :reason code :detail (or text "")})
      (#{"seam-answered-before-boot" "identity-probe-missing"} code)
      (merge defaults {:outcome "failed" :reason (if (= code "seam-answered-before-boot") "boot-budget-exhausted" "identity-probe-failed")
                       :seam-answered-before-boot (= code "seam-answered-before-boot") :identity-probe-present (not= code "identity-probe-missing") :detail (or text "")})
      :else (throw (ex-info (or last "Gate command returned no result") {:reason "effect-could-not-run"})))))
(defn http-get [url & [any-status?]]
  (try (let [^java.net.HttpURLConnection c (.openConnection (.toURL (java.net.URI/create url)))]
         (.setConnectTimeout c 250) (.setReadTimeout c 250) (.setInstanceFollowRedirects c false)
         (try (when (or any-status? (< (.getResponseCode c) 400))
                (if any-status? (pos? (.getResponseCode c)) (slurp (.getInputStream c)))) (finally (.disconnect c))))
       (catch Throwable _ nil)))
(defn host-gate [s room evidence ms p]
  (let [{:keys [config env]} s
        stdio (= "stdio" (:APP_SEAM config))
        candidate (str room "/" (:APP_DIR config))
        test (str candidate "/test.sh") start (str candidate "/start.sh")
        url (when-not stdio (str "http://127.0.0.1:" (:PORT config)))
        budget (if stdio {:boot-polls 1 :boot-budget-seconds 30} {:boot-polls 20 :boot-budget-seconds 10})
        fail (fn [reason detail] (merge budget {:outcome "failed" :reason reason :detail (or detail "")}))
        capture (fn [name r] (u/put (str evidence "/" name) (str (:out r) (:err r))) r)]
    (cond
      (not (and (executable? test) (executable? start))) (fail "contract-missing" "test.sh and start.sh must be executable")
      (not (if stdio (:present p) (executable? (str room "/" (:APP_PROBE config)))))
      (assoc (fail "identity-probe-failed" "identity-probe-missing") :identity-probe-present false)
      (and (not stdio) (some? (http-get url true))) (assoc (fail "boot-budget-exhausted" "seam-answered-before-boot") :seam-answered-before-boot true)
      :else
      (let [suite (capture "suite.log" (u/exec [test] room {} true ms))]
        (cond
          (:timeout suite) (throw (ex-info "Gate stage timeout" {:reason "budget" :stage "gate"}))
          (not (zero? (:exit suite))) (fail "suite-red" (:out suite))
          stdio
          (let [probe-path (u/put (str (:dir s) "/identity-probe") (:text p))
                _ (.setExecutable (io/file probe-path) true false)
                store (str evidence "/events.sqlite")
                r (capture "probe.log" (u/exec [probe-path start "30"] room {"GATE_STORE" store} true ms))]
            (if-not (zero? (or (:exit r) 1)) (fail "identity-probe-failed" (:out r))
              (let [check (capture "store-check.log" (checked-command (env "LOYLY_STORE_CHECK_CMD") [store] (env "LOYLY_PROJECT_REPO") true ms {}))]
                (if (zero? (or (:exit check) 1))
                  (merge budget {:outcome "passed" :answered-on-poll 1 :identity-probe-result (str/trim (:out r)) :detail (:out r)})
                  (fail "identity-probe-failed" (:out check))))))
          :else
          (let [process (u/launch [start (:PORT config)] room {} true)
                drain-out (future (with-open [in (.getInputStream process)] (io/copy in (java.io.OutputStream/nullOutputStream))))
                drain-err (future (with-open [in (.getErrorStream process)] (io/copy in (java.io.OutputStream/nullOutputStream))))]
            (try
              (let [[poll page] (loop [i 1]
                                  (let [page (http-get url)]
                                    (cond page [i page] (>= i 20) [nil nil] :else (do (Thread/sleep 500) (recur (inc i))))))]
                (cond
                  (nil? poll) (fail "boot-budget-exhausted" "The candidate did not answer within 20 polls")
                  (not (str/includes? (str/lower-case page) (str/lower-case (:APP_MARKER config)))) (fail "identity-probe-failed" "The page does not carry the marker")
                  :else
                  (let [r (capture "probe.log" (u/exec [(str room "/" (:APP_PROBE config)) url] room {} true ms))]
                    (if (zero? (or (:exit r) 1))
                      (merge budget {:outcome "passed" :answered-on-poll poll :identity-probe-result (str/trim (:out r)) :detail (:out r)})
                      (fail "identity-probe-failed" (:out r))))))
              (finally (u/stop-process process) (future-cancel drain-out) (future-cancel drain-err)))))))))
(defn run! [s attempt digest room candidate evidence ms]
  (u/mkdir evidence)
  (let [p (probe s) command? (not (str/blank? ((:env s) "LOYLY_GATE_CMD")))
        result (if (and command? (not (:present p)))
                 {:outcome "failed" :reason "identity-probe-failed" :boot-polls 20 :boot-budget-seconds 10 :detail "identity-probe-missing" :identity-probe-present false}
                 (if command? (command-gate s room candidate evidence ms) (host-gate s room evidence ms p)))]
    (merge {:cycle-id (get-in s [:params :cycle-id]) :attempt attempt :candidate-digest digest
            :harness-digest (harness-digest s) :identity-probe-digest (:digest p) :identity-probe-present (:present p)
            :seam-answered-before-boot false :evidence-root evidence :grader-digests (get-in s [:params :grader-digests])}
           result)))
