(ns loyly.cycle-preparation.candidate
  "Drives the candidate only through its public seam: start.sh as a process,
   JSON-RPC lines over stdin/stdout, the Grain store file it is handed, and the
   commits file LOYLY_PROJECT_COMMITS names. Also reads that store directly, as
   the gate does."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [ai.obney.grain.fressian-util.interface :as fressian])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter BufferedWriter File]
           [java.util.concurrent TimeUnit]))

(def ^:private here (.getCanonicalPath (File. ".")))

(defn temp-dir []
  (let [d (File/createTempFile "loyly-05-" "")]
    (.delete d) (.mkdirs d) d))

(defn write-commits!
  "The project repository's commits: {commit tree-digest}."
  [^File file commits]
  (spit file (json/write-str commits)))

(defn start!
  "Launch start.sh with a fresh store file and the given commits file.
   Returns a candidate map."
  [{:keys [commits-file store-file env]}]
  (let [pb (ProcessBuilder. ^java.util.List [(str here "/start.sh")])
        e (.environment pb)
        err-file (File/createTempFile "loyly-05-stderr-" ".log")]
    (.put e "LOYLY_GRAIN_STORE" (str store-file))
    (if commits-file
      (.put e "LOYLY_PROJECT_COMMITS" (str commits-file))
      (.remove e "LOYLY_PROJECT_COMMITS"))
    (doseq [[k v] env] (.put e k v))
    ;; Run from elsewhere: start.sh must work from any working directory.
    (.directory pb (io/file (System/getProperty "java.io.tmpdir")))
    (.redirectError pb err-file)
    (let [p (.start pb)]
      {:process p
       :in (BufferedWriter. (OutputStreamWriter. (.getOutputStream p) "UTF-8"))
       :out (BufferedReader. (InputStreamReader. (.getInputStream p) "UTF-8"))
       :err-file err-file
       :store-file store-file
       :next-id (atom 0)})))

(defn send-line!
  "Send one raw line; read one response line (parsed)."
  [{:keys [^BufferedWriter in ^BufferedReader out]} line]
  (.write in ^String line)
  (.write in "\n")
  (.flush in)
  (let [resp (.readLine out)]
    (when-not resp (throw (ex-info "candidate closed stdout" {})))
    (json/read-str resp)))

(defn call!
  "One JSON-RPC request; returns the parsed response (string keys)."
  [cand method params]
  (let [id (swap! (:next-id cand) inc)
        resp (send-line! cand (json/write-str {:jsonrpc "2.0" :id id :method method :params params}))]
    (assert (= id (get resp "id")) (str "response id mismatch: " resp))
    resp))

(defn stop! [{:keys [^Process process ^BufferedWriter in]}]
  (try (.close in) (catch Exception _))
  (when-not (.waitFor process 20 TimeUnit/SECONDS)
    (.destroy process)
    (.waitFor process 10 TimeUnit/SECONDS))
  (.exitValue process))

(defn stderr [{:keys [err-file]}] (slurp err-file))

(defmacro with-candidate
  "Start a candidate with a fresh store and a commits file holding `commits`;
   bind it to `sym` and the commits file to `commits-sym`."
  [[sym commits-sym commits] & body]
  `(let [dir# (temp-dir)
         ~commits-sym (io/file dir# "commits.json")
         _# (write-commits! ~commits-sym ~commits)
         ~sym (start! {:commits-file ~commits-sym :store-file (io/file dir# "store.db")})]
     (try ~@body
          (finally (stop! ~sym)))))

;; ---------------------------------------------------------------------------
;; The store, read directly
;; ---------------------------------------------------------------------------

(defn store-events
  "Every event in the SQLite store, in order, decoded."
  [store-file]
  (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname (str store-file)})]
    (mapv (fn [r] (assoc (fressian/decode (:events/data r))
                         :event/type (keyword (subs (:events/type r) 1))
                         :event/id (:events/id r)))
          (jdbc/execute! ds ["select id, type, data from events order by id"]))))
