(ns loyly.seam
  "The open grader's hands: the only things its researcher (Phase-1 sandbox code, and the :code
   nodes it emits) can do outside ORC. They are ORC tools: `call-tool` is the context's
   :call-tool-fn, and the researcher node binds them by name (:mcp-tools).

     read-spec       {path}                one file under the context's spec dir, or a listing
                                           of a directory there; nothing outside it
     start-candidate {env files}           start the candidate's start.sh, in a fresh directory
                                           under the run's working directory, with a fresh
                                           LOYLY_GRAIN_STORE there. FILES {name content} are
                                           written into that directory first; ${DIR} in an ENV
                                           value is that directory. Returns {:handle :dir :store}
     write-file      {handle name content} (re)write one file in a started candidate's directory
     seam-call       {handle request timeout-ms}
                                           send one JSON-RPC request (a map, or a JSON line) and
                                           read the response line; {:response :line}, or
                                           {:error} on timeout or exit
     stop-candidate  {handle}              stop it; {:exit :stderr-tail}

   The candidate's start.sh path is the host's, set once per run (configure!); no tool takes a
   path into the room, so nothing here reads the candidate's source."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io BufferedReader File)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def tool-names ["read-spec" "start-candidate" "write-file" "seam-call" "stop-candidate"])

(def tool-arg-specs
  {"read-spec" ["path"]
   "start-candidate" ["env" "files"]
   "write-file" ["handle" "name" "content"]
   "seam-call" ["handle" "request" "timeout-ms"]
   "stop-candidate" ["handle"]})

(defonce ^:private config (atom nil))
(defonce ^:private candidates (atom {}))

(defn configure!
  "SPEC-DIR, START (the candidate's start.sh) and WORK-DIR for this grading run."
  [spec-dir start work-dir]
  (reset! config {:spec-dir (.getCanonicalFile (io/file spec-dir))
                  :start (str start)
                  :work-dir (doto (io/file work-dir) .mkdirs)
                  :starts 0}))

(defn- arg [args k] (or (get args k) (get args (name k))))

(defn- under? [^File root ^File f]
  (let [r (str (.getCanonicalPath root) File/separator)]
    (or (= (.getCanonicalPath root) (.getCanonicalPath f))
        (str/starts-with? (.getCanonicalPath f) r))))

(defn read-spec [args]
  (let [root (:spec-dir @config)
        f (io/file root (or (arg args :path) "."))]
    (cond
      (not (under? root f)) {:error "only files under the context's spec dir can be read"}
      (.isDirectory f) {:entries (vec (sort (map #(cond-> (.getName ^File %) (.isDirectory ^File %) (str "/"))
                                                 (.listFiles f))))}
      (.isFile f) {:content (slurp f :encoding "UTF-8")}
      :else {:error (str "no such file: " (arg args :path))})))

(defn- write-into [^File dir nm content]
  (let [f (io/file dir (str nm))]
    (if (and (under? dir f) (not= (.getCanonicalPath dir) (.getCanonicalPath f)))
      (do (io/make-parents f) (spit f (str content) :encoding "UTF-8") (str f))
      (throw (ex-info (str "a file name must stay inside the candidate's directory: " nm) {})))))

(defn start-candidate [args]
  (let [{:keys [start work-dir]} (swap! config update :starts inc)
        n (:starts @config)
        dir (doto (io/file work-dir (format "start-%02d" n)) .mkdirs)
        store (str (io/file dir "grain.sqlite"))
        _ (doseq [[nm content] (arg args :files)] (write-into dir (name nm) content))
        pb (ProcessBuilder. ["bash" start])
        env (.environment pb)]
    (doseq [k (filter #(str/starts-with? % "LOYLY_") (vec (keys env)))] (.remove env k))
    (doseq [[k v] (arg args :env)]
      (.put env (name k) (str/replace (str v) "${DIR}" (str dir))))
    (.put env "LOYLY_GRAIN_STORE" store)
    (.directory pb dir)
    (.redirectError pb (io/file dir "stderr.log"))
    (let [p (.start pb)
          lines (LinkedBlockingQueue.)
          rdr (BufferedReader. (io/reader (.getInputStream p) :encoding "UTF-8"))
          handle (format "c%02d" n)]
      (doto (Thread. #(try (loop [] (when-let [l (.readLine rdr)] (.put lines l) (recur)))
                           (catch Exception _ nil)))
        (.setDaemon true) .start)
      (swap! candidates assoc handle {:process p :lines lines :dir dir
                                      :in (io/writer (.getOutputStream p) :encoding "UTF-8")})
      {:handle handle :dir (str dir) :store store})))

(defn- candidate [args]
  (or (get @candidates (str (arg args :handle)))
      (throw (ex-info (str "no started candidate " (pr-str (arg args :handle))) {}))))

(defn write-file [args]
  {:path (write-into (:dir (candidate args)) (arg args :name) (arg args :content))})

(defn seam-call [args]
  (let [{:keys [^Process process ^LinkedBlockingQueue lines ^java.io.Writer in]} (candidate args)
        req (arg args :request)
        line (if (string? req) (str/trim req) (json/write-str req))
        timeout-ms (long (or (arg args :timeout-ms) 120000))]
    (if-not (.isAlive process)
      {:error (str "the candidate has exited (" (.exitValue process) ")")}
      (do (.write in (str line "\n")) (.flush in)
          (if-let [resp (.poll lines timeout-ms TimeUnit/MILLISECONDS)]
            {:response (try (json/read-str resp :key-fn keyword) (catch Exception _ nil))
             :line resp}
            {:error (str "no response line within " timeout-ms " ms"
                         (when-not (.isAlive process) " (the candidate exited)"))})))))

(defn- stop! [{:keys [^Process process dir]}]
  (run! #(.destroyForcibly ^java.lang.ProcessHandle %) (iterator-seq (.iterator (.descendants (.toHandle process)))))
  (.destroy process)
  (when-not (.waitFor process 10 TimeUnit/SECONDS) (.destroyForcibly process) (.waitFor process))
  (let [e (let [f (io/file dir "stderr.log")] (if (.isFile f) (slurp f) ""))]
    {:exit (.exitValue process) :stderr-tail (subs e (max 0 (- (count e) 800)))}))

(defn stop-candidate [args]
  (let [c (candidate args)]
    (swap! candidates dissoc (str (arg args :handle)))
    (stop! c)))

(defn stop-all! []
  (doseq [[h c] @candidates] (swap! candidates dissoc h) (stop! c)))

(defn call-tool
  "ORC's :call-tool-fn: (tool-name args) -> result; the 3-arity takes ORC's tool-context."
  ([tool args] (call-tool tool args nil))
  ([tool args _]
   (let [args (if (map? args) args {})]
     (case (str tool)
       "read-spec" (read-spec args)
       "start-candidate" (start-candidate args)
       "write-file" (write-file args)
       "seam-call" (seam-call args)
       "stop-candidate" (stop-candidate args)
       {:error (str "no such tool: " tool)}))))
