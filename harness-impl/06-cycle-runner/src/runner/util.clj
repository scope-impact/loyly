(ns runner.util
  (:require [clojure.java.io :as io] [clojure.string :as str] [clojure.data.json :as json])
  (:import [java.security MessageDigest] [java.util Date] [java.time Instant]
           [java.util.concurrent TimeUnit]))
(defn now [] (Instant/now))
(defn abs [p] (.getCanonicalPath (io/file p)))
(defn mkdir [p] (.mkdirs (io/file p)) (abs p))
(defn put [p text] (io/make-parents p) (spit p text) (abs p))
(defn hex [b] (apply str (map #(format "%02x" (bit-and 255 %)) b)))
(defn sha [x] (str "sha256:" (hex (.digest (MessageDigest/getInstance "SHA-256") (if (string? x) (.getBytes ^String x "UTF-8") x)))))
(defn bytes-of [p] (java.nio.file.Files/readAllBytes (.toPath (io/file p))))
(defn digest [dir]
  (let [root (.toPath (io/file dir))]
    (sha (apply str (for [f (sort-by #(.toString (.relativize root (.toPath %))) (filter #(.isFile %) (file-seq (io/file dir))))]
                     (str (.toString (.relativize root (.toPath f))) "\t" (subs (sha (bytes-of f)) 7) "\n"))))))
(defn wire [x]
  (cond (instance? Date x) (str (Instant/ofEpochMilli (.getTime ^Date x)))
        (instance? Instant x) (str x)
        (instance? java.time.OffsetDateTime x) (str (.toInstant ^java.time.OffsetDateTime x))
        (instance? java.time.ZonedDateTime x) (str (.toInstant ^java.time.ZonedDateTime x))
        (keyword? x) (subs (str x) 1)
        (map? x) (into {} (map (fn [[k v]] [k (wire v)])) x)
        (set? x) (mapv wire x)
        (sequential? x) (mapv wire x)
        :else x))
(defn encode [x] (json/write-str (wire x)))
(defn decode [s] (json/read-str s :key-fn keyword))
(defn stop-process [^Process p]
  (when p
    (try (with-open [s (.descendants (.toHandle p))] (.forEach s (reify java.util.function.Consumer (accept [_ h] (.destroyForcibly ^java.lang.ProcessHandle h))))) (catch Throwable _))
    (.destroyForcibly p)))
(def launch-lock (Object.))
(defn launch [args cwd env clean?]
  (let [pb (ProcessBuilder. ^java.util.List (mapv str args)) e (.environment pb)]
    (when cwd (.directory pb (io/file cwd)))
    (when clean? (doseq [k (vec (.keySet e)) :when (or (str/starts-with? k "LOYLY_") (= k "GATE_STORE"))] (.remove e k)))
    (doseq [[k v] env :when v] (.put e (name k) (str v)))
    (locking launch-lock (.start pb))))
(defn exec
  ([args cwd] (exec args cwd {} false 300000))
  ([args cwd env clean? ms]
   (let [p (launch args cwd env clean?)
         out (future (slurp (.getInputStream p))) err (future (slurp (.getErrorStream p)))
         ended (.waitFor p (long ms) TimeUnit/MILLISECONDS)]
     (when-not ended (stop-process p))
     {:exit (when ended (.exitValue p)) :timeout (not ended) :out @out :err @err})))
(defn shell [cmd args cwd env clean? ms]
  (when (str/blank? cmd) (throw (ex-info "Command is not handed in" {})))
  (exec (into ["sh" "-c" (str "exec " cmd " \"$@\"") "runner"] args) cwd env clean? ms))
(defn git [repo & args]
  (let [r (exec (into ["git" "-c" "safe.directory=*" "-c" "gc.auto=0" "-c" "maintenance.auto=false" "-C" repo] args) nil)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "git " (str/join " " args) ": " (:err r)) r)))
    (:out r)))
(defn config [path]
  (into {} (keep (fn [line] (when-let [[_ k v] (re-matches #"\s*([A-Z_]+)=(.*)" line)]
                            [(keyword k) (let [v (str/trim v)] (if (and (> (count v) 1) (#{\" \'} (first v)) (= (first v) (last v))) (subs v 1 (dec (count v))) v))])))
        (str/split-lines (slurp path))))
(defn remove-tree [p] (doseq [f (reverse (file-seq (io/file p)))] (io/delete-file f true)))
(defn copy-tree [from to]
  (mkdir to)
  (doseq [f (.listFiles (io/file from))]
    (let [dest (io/file to (.getName f))]
      (cond (.isDirectory f) (copy-tree f dest)
            :else (java.nio.file.Files/copy (.toPath f) (.toPath dest) (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING java.nio.file.StandardCopyOption/COPY_ATTRIBUTES])))))
  (abs to))
(defn readonly [dir]
  (doseq [f (file-seq (io/file dir))] (.setWritable f false false)))
