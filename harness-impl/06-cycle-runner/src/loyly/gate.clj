(ns loyly.gate
  (:require [clojure.java.io :as io] [clojure.string :as str] [loyly.host :as host] [loyly.plan :as plan])
  (:import [java.net URL HttpURLConnection]))
(defn stripped [args]
  (concat ["env"] (mapcat #(vector "-u" %) (filter #(or (= "GATE_STORE" %) (str/starts-with? % "LOYLY_")) (keys (System/getenv)))) args))
(defn attempt [room timeout args] (host/run room timeout {} (stripped args)))
(defn last-line [s] (last (remove str/blank? (map str/trim (str/split-lines s)))))
(defn result [exit out polls seconds & [poll]]
  {:exit exit :out (str "BOOT " polls " " seconds "\n" (when poll (str "ANSWERED " poll "\n")) out "\n")})
(defn fetch [url]
  (try (let [c ^HttpURLConnection (.openConnection (URL. url))]
         (try (.setConnectTimeout c 250) (.setReadTimeout c 250)
              (when (< (.getResponseCode c) 400) (slurp (.getInputStream c)))
              (finally (.disconnect c))))
       (catch Exception _ nil)))
(defn http [gate start probe cfg seconds]
  (let [port (cfg "PORT") url (str "http://127.0.0.1:" port)
        child (.start (doto (ProcessBuilder. (mapv str (stripped [start port])))
                        (.directory (io/file gate)) (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD)
                        (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)))]
    (try
      (loop [poll 1]
        (if-let [page (fetch url)]
          (if (str/includes? (str/lower-case page) (str/lower-case (cfg "APP_MARKER")))
            (let [r (attempt gate seconds [probe url])]
              (result (:exit r) (str (when-not (zero? (:exit r)) "identity-probe-failed ") (last-line (:out r))) 20 10 poll))
            (result 1 "identity-probe-failed page lacks marker" 20 10))
          (if (< poll 20) (do (Thread/sleep 500) (recur (inc poll))) (result 1 "boot-budget-exhausted no answer" 20 10))))
      (finally (with-open [desc (.descendants child)] (doseq [p (.toArray desc)] (.destroyForcibly ^java.lang.ProcessHandle p))) (.destroyForcibly child)))))
(defn host-gate [{:keys [gate config env p]} evidence seconds]
  (let [app (host/child gate (config "APP_DIR")) test (io/file app "test.sh") start (io/file app "start.sh")
        stdio? (= "stdio" (config "APP_SEAM")) polls (if stdio? 1 20) boot (if stdio? 30 10)
        repo (env "LOYLY_PROJECT_REPO") probe-path (config "APP_PROBE")
        probe (if stdio? (io/file evidence "identity-probe") (host/child gate probe-path))
        probe? (str/starts-with? (plan/git repo "ls-tree" (:commit p) "--" probe-path) "100755 ")]
    (when (and stdio? probe?) (spit probe (plan/git repo "show" (str (:commit p) ":" probe-path))) (.setExecutable probe true))
    (cond
      (not (and (.canExecute test) (.canExecute start))) (result 1 "contract-missing missing suite or launch entrypoint" polls boot)
      (not (.canExecute probe)) (result 1 "identity-probe-missing missing executable probe" polls boot)
      (and (not stdio?) (some? (fetch (str "http://127.0.0.1:" (config "PORT"))))) (result 1 "seam-answered-before-boot port already answered" polls boot)
      :else
      (let [suite (attempt gate seconds [test])]
        (cond (not (zero? (:exit suite))) (result 1 (str "suite-red " (last-line (:out suite))) polls boot)
              (not stdio?) (http gate start probe config seconds)
              :else (let [store (str (io/file evidence "events.sqlite"))
                          probe-result (attempt gate seconds [(str "GATE_STORE=" store) probe start "30"])
                          check (when (zero? (:exit probe-result))
                                  (let [command (env "LOYLY_STORE_CHECK_CMD")]
                                    (when (str/blank? command) (throw (ex-info "Store check command missing" {})))
                                    (let [[ex & args] (str/split command #"\s+")]
                                      (when-not (.canExecute (io/file ex)) (throw (ex-info "Store check command unavailable" {:command ex})))
                                      (attempt repo seconds (concat [ex] args [store])))))
                          failure (if (zero? (:exit probe-result)) (when-not (zero? (:exit check)) check) probe-result)]
                      (if failure (result 1 (str "identity-probe-failed " (last-line (:out failure))) polls boot)
                          (result 0 (last-line (:out probe-result)) polls boot 1))))))))
