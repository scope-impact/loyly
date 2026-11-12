(ns runner.mailbox-worker
  "Suite-only stdio adapter. Files carry bytes, never runner facts. Each peer's
   Clojure handler writes its contracted handoffs to its real SQLite Grain store."
  (:require [runner.standin :as standin] [runner.util :as u] [clojure.java.io :as io]))
(def roots (atom #{}))
(def seen (atom #{}))
(def tasks (atom {}))
(defn alive? [pid]
  (try (let [h (.orElse (java.lang.ProcessHandle/of (Long/parseLong (str pid))) nil)] (and h (.isAlive h))) (catch Throwable _ false)))
(defn exit! [pid]
  (when-let [h (.orElse (java.lang.ProcessHandle/of (Long/parseLong (str pid))) nil)]
    (with-open [children (.descendants h)]
      (.forEach children (reify java.util.function.Consumer (accept [_ child] (.destroyForcibly ^java.lang.ProcessHandle child)))))
    (.destroyForcibly h)))
(defn serve-http! [port pid token]
  (with-open [listener (java.net.ServerSocket. (int port))]
    (.setSoTimeout listener 100)
    (while (and @token (alive? pid))
      (try (with-open [client (.accept listener) out (io/writer (.getOutputStream client))]
             (.write out "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nsauna") (.flush out))
           (catch java.net.SocketTimeoutException _)))))
(defn handle-peer! [dir token]
  (let [{:keys [context env port pid]} (u/decode (slurp (io/file dir "hello.json")))
        environment (into {} (map (fn [[k v]] [(name k) v])) env)
        state (atom {})]
    (try
      (if (= context "fixture-http")
        (serve-http! port pid token)
        (loop [position 0 idle 0]
          (when (and @token (alive? pid) (< idle 12000))
            (let [request (io/file dir (str "request-" position ".json"))]
              (if (.isFile request)
                (let [out (java.io.StringWriter.)]
                  (binding [*in* (java.io.StringReader. (slurp request)) *out* out standin/state state standin/*environment* environment]
                    (standin/-main context))
                  (u/put (str dir "/response-" position ".tmp") (str out))
                  (java.nio.file.Files/move (.toPath (io/file dir (str "response-" position ".tmp"))) (.toPath (io/file dir (str "response-" position ".json"))) (make-array java.nio.file.CopyOption 0))
                  (recur (inc position) 0))
                (do (Thread/sleep 10) (recur position (inc idle))))))))
      (catch Throwable t
        (when @token (if (:disconnect (ex-data t)) (exit! pid) (.printStackTrace t System/err)))))))
(defn warm! [directory]
  (let [script (u/put (str directory "/script.json") "{}")]
    (doseq [context ["specification" "cycle-preparation" "grader-lifecycle" "regeneration-cycle" "measurement"]]
      (let [method ({"specification" "specification/stamped-items" "cycle-preparation" "cycle-preparation/source-cuts" "grader-lifecycle" "grader-lifecycle/instrument-goldens" "regeneration-cycle" "regeneration-cycle/cycle-progress" "measurement" "measurement/checklist"} context)
            request {:jsonrpc "2.0" :id "warm" :method method :params (if (= context "measurement") {:cut-id "warm"} {})}]
        (binding [*in* (java.io.StringReader. (u/encode request)) *out* (java.io.StringWriter.)
                  standin/state (atom {}) standin/*environment* {"RUNNER_TEST_SCRIPT" script "LOYLY_GRAIN_STORE" (str directory "/" context ".sqlite")}]
          (standin/-main context))))))
(defn -main [control]
  (warm! (u/mkdir (str control "/warm")))
  (u/put (str control "/ready") "ready")
  (try
    (while (not (.exists (io/file control "stop")))
      (let [current (try (set (clojure.edn/read-string (slurp (io/file control "roots.edn")))) (catch Throwable _ #{}))]
        (doseq [[key task] @tasks :when (not (current (:root task)))]
          (reset! (:token task) false) (future-cancel (:future task)) (swap! tasks dissoc key))
        (doseq [root current dir (.listFiles (io/file root))
                :let [key (.getPath dir)]
                :when (and (.isFile (io/file dir "hello.json")) (not (@seen key)))]
          (swap! seen conj key)
          (let [token (atom true) task (future (handle-peer! key token))]
            (swap! tasks assoc key {:root root :token token :future task}))))
      (Thread/sleep 50))
    (finally
      (doseq [[_ task] @tasks] (reset! (:token task) false) (future-cancel (:future task)))
      (shutdown-agents))))
