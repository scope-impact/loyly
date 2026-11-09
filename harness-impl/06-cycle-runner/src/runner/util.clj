(ns runner.util
  (:require [clojure.java.io :as io] [clojure.string :as str] [clojure.data.json :as json] [clojure.walk :as walk])
  (:import [java.util.concurrent TimeUnit] [java.security MessageDigest] [java.time Instant] [java.util Date]))
(defn path [& xs] (.getAbsolutePath (apply io/file xs)))
(defn mkdir [p] (.mkdirs (io/file p)) p)
(defn put [p x] (io/make-parents p) (spit p x) p)
(defn normalize [x]
  (walk/postwalk (fn [v] (cond (instance? Date v) (str (.toInstant ^Date v))
                               (instance? java.time.temporal.TemporalAccessor v) (str v)
                               (keyword? v) (subs (str v) 1) :else v)) x))
(defn encode [x] (json/write-str (normalize x)))
(defn decode [s] (json/read-str s :key-fn keyword))
(defn json-file [p x] (put p (encode x)))
(defn read-json [p] (decode (slurp p)))
(defn sha [x] (let [b (if (bytes? x) x (.getBytes (str x) "UTF-8"))] (format "%064x" (java.math.BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") b)))))
(defn digest [x] (str "sha256:" (sha x)))
(defn tree-digest [dir]
  (digest (apply str (for [f (sort-by #(.getPath %) (filter #(.isFile %) (file-seq (io/file dir))))]
                      (str (str/replace (subs (.getPath f) (inc (count (str dir)))) "\\" "/") "\t" (sha (java.nio.file.Files/readAllBytes (.toPath f))) "\n")))))
(defn destroy [^Process p]
  (doseq [h (reverse (vec (.toList (.descendants (.toHandle p)))))] (.destroyForcibly ^java.lang.ProcessHandle h))
  (.destroyForcibly p))
(defn launch [args cwd env clean?]
  (let [pb (ProcessBuilder. ^java.util.List (mapv str args))]
    (when cwd (.directory pb (io/file cwd)))
    (when clean? (doseq [k (vec (.keySet (.environment pb))) :when (or (str/starts-with? k "LOYLY_") (= k "GATE_STORE"))] (.remove (.environment pb) k)))
    (doseq [[k v] env] (if (nil? v) (.remove (.environment pb) (name k)) (.put (.environment pb) (name k) (str v))))
    (.redirectErrorStream pb true)
    (.start pb)))
(defn run
  ([args cwd] (run args cwd {} false 300))
  ([args cwd env clean? seconds]
   (let [p (launch args cwd env clean?) out (future (slurp (.getInputStream p)))
         done (.waitFor p (long (* seconds 1000)) TimeUnit/MILLISECONDS)]
     (when-not done (destroy p))
     {:exit (if done (.exitValue p) -1) :timeout (not done) :out (deref out 3000 "")})))
(defn command [s extra cwd env clean? seconds]
  (when (str/blank? s) (throw (ex-info "handed-in command empty" {:reason "effect-could-not-run"})))
  ;; The handed-in command is shell syntax; positional arguments stay quoted by bash.
  (let [program (:out (run ["bash" "-c" (str "command -v -- " (first (str/split s #"\s+")))] cwd))]
    (when (str/blank? program) (throw (ex-info (str "command cannot run: " s) {:reason "effect-could-not-run"}))))
  (run (into ["bash" "-c" (str "exec " s " \"$@\"") "runner-command"] extra) cwd env clean? seconds))
(defn git [repo & args] (run (into ["git" "-c" "safe.directory=*" "-C" (str repo)] args) nil))
(defn git! [repo & args] (let [r (apply git repo args)] (when-not (zero? (:exit r)) (throw (ex-info (:out r) {:reason "effect-could-not-run"}))) (str/trimr (:out r))))
(defn copy [src dst] (mkdir dst) (let [r (run ["cp" "-a" (str src "/.") dst] nil)] (when-not (zero? (:exit r)) (throw (ex-info (:out r) {})))) dst)
(defn remove-tree [p] (run ["rm" "-rf" "--" p] nil))
(defn refuse
  ([code] (refuse code {}))
  ([code data] (throw (ex-info code {:rpc-code -32000 :data (assoc data :refusal code)}))))
(defn fault [code message] (throw (ex-info message {:rpc-code code})))
(defn command-file [s extra cwd seconds file]
  (when (or (str/blank? s) (not (zero? (:exit (run ["bash" "-c" (str "command -v -- " (first (str/split s #"\s+")))] cwd)))))
    (throw (ex-info (str "command cannot run: " s) {:reason "effect-could-not-run"})))
  (io/make-parents file)
  (let [pb (ProcessBuilder. ^java.util.List (into ["bash" "-c" (str "exec " s " \"$@\"") "runner-command"] extra))
        _ (.directory pb (io/file cwd)) _ (.redirectErrorStream pb true) _ (.redirectOutput pb (io/file file))
        p (.start pb) done (.waitFor p (long (* seconds 1000)) TimeUnit/MILLISECONDS)]
    (when-not done (destroy p))
    {:exit (if done (.exitValue p) -1) :timeout (not done) :out (if (.isFile (io/file file)) (slurp file) "")}))
