(ns runner.mailbox
  "Lifecycle of the suite-only peer worker; the test driver never opens SQLite."
  (:require [runner.util :as u] [clojure.java.io :as io] [clojure.string :as str]))
(def roots (atom #{}))
(def worker (atom nil))
(defn java-command []
  ["java" "-Xmx256m" "-XX:ActiveProcessorCount=2" "-XX:+UseSerialGC" "-XX:TieredStopAtLevel=1"
   "-cp" (str/join java.io.File/pathSeparator (map u/abs (str/split (System/getProperty "java.class.path") (re-pattern java.io.File/pathSeparator)))) "clojure.main"])
(defn publish! []
  (when-let [w @worker]
    (locking roots
      (let [from (io/file (:control w) "roots.tmp") to (io/file (:control w) "roots.edn")]
        (spit from (pr-str @roots))
        (java.nio.file.Files/move (.toPath from) (.toPath to) (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))))
(defn start! []
  (let [control (u/mkdir (str "target/peer-worker/" (random-uuid)))
        _ (u/put (str control "/roots.edn") "[]")
        p (u/launch (into (java-command) ["-m" "runner.mailbox-worker" control]) nil {} false)
        logs (future (with-open [in (.getErrorStream p) out (io/output-stream (str control "/stderr.log"))] (io/copy in out)))
        output (future (with-open [in (.getInputStream p) out (io/output-stream (str control "/stdout.log"))] (io/copy in out)))]
    (reset! worker {:control control :process p :logs logs :output output})
    (loop [i 0]
      (when-not (.isFile (io/file control "ready"))
        (when (or (> i 6000) (not (.isAlive p))) (throw (ex-info (str "Fixture peer worker did not boot: " control) {})))
        (Thread/sleep 50) (recur (inc i))))))
(defn register! [root] (let [dir (u/mkdir (str root "/mailboxes"))] (swap! roots conj dir) (publish!) dir))
(defn stop! [root] (swap! roots disj (str root "/mailboxes")) (publish!))
(defn shutdown! []
  (when-let [w @worker]
    (u/put (str (:control w) "/stop") "stop")
    (when-not (.waitFor ^Process (:process w) 10 java.util.concurrent.TimeUnit/SECONDS) (u/stop-process (:process w)))
    (reset! worker nil)))
