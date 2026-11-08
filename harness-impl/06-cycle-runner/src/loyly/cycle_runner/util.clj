(ns loyly.cycle-runner.util
  "Small helpers: JSON, digests, files, processes, time. Nothing here knows the domain."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Path Paths]
           [java.nio.file.attribute PosixFilePermission]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util Date]
           [java.util.concurrent TimeUnit]))

(defn log [& xs]
  (binding [*out* *err*]
    (println (apply str "[cycle-runner] " xs))
    (flush)))

;; ---------------------------------------------------------------------------
;; JSON
;; ---------------------------------------------------------------------------

(defn- json-value [_k v]
  (cond
    (instance? Instant v) (str v)
    (instance? Date v) (str (.toInstant ^Date v))
    (uuid? v) (str v)
    (set? v) (vec v)
    :else v))

(defn- json-key [k]
  (if (keyword? k)
    (if (namespace k) (str (namespace k) "/" (name k)) (name k))
    (str k)))

(defn ->json ^String [x]
  (json/write-str x :value-fn json-value :key-fn json-key :escape-slash false))

(defn parse-json
  "Parse JSON text, keys as keywords. Throws on malformed text."
  [^String s]
  (json/read-str s :key-fn keyword))

;; ---------------------------------------------------------------------------
;; Digests
;; ---------------------------------------------------------------------------

(defn sha256-hex ^String [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-256")
        d (.digest md bs)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn sha256-of-string [^String s] (str "sha256:" (sha256-hex (utf8 s))))

(defn file-bytes ^bytes [f] (Files/readAllBytes (.toPath (io/file f))))

(defn sha256-of-file [f] (str "sha256:" (sha256-hex (file-bytes f))))

;; ---------------------------------------------------------------------------
;; Files
;; ---------------------------------------------------------------------------

(defn now ^Instant [] (Instant/now))

(defn mkdirs [f] (.mkdirs (io/file f)) (io/file f))

(defn abs-path ^String [f] (.getAbsolutePath (io/file f)))

(defn symlink? [^File f]
  (Files/isSymbolicLink (.toPath f)))

(defn delete-tree! [f]
  (let [f (io/file f)]
    (when (or (.exists f) (symlink? f))
      ;; Read-only directories (a repair room's mounts) must be made writable first.
      (when (and (.isDirectory f) (not (symlink? f)))
        (.setWritable f true)
        (doseq [c (.listFiles f)] (delete-tree! c)))
      (.delete f))))

(defn files-under
  "Every regular file under dir, as paths relative to dir with '/' separators, sorted by
   code point."
  [dir]
  (let [root (.toPath (io/file dir))]
    (if-not (.isDirectory (io/file dir))
      []
      (->> (file-seq (io/file dir))
           (filter #(.isFile ^File %))
           (map #(str/replace (str (.relativize root (.toPath ^File %))) File/separator "/"))
           sort
           vec))))

(defn copy-tree!
  "Copy src to dst (which must not exist), keeping executable bits and symlinks."
  [src dst]
  (let [src (io/file src) dst (io/file dst)]
    (cond
      (symlink? src)
      (Files/createSymbolicLink (.toPath dst) (Files/readSymbolicLink (.toPath src))
                                (make-array java.nio.file.attribute.FileAttribute 0))

      (.isDirectory src)
      (do (.mkdirs dst)
          (doseq [c (.listFiles src)]
            (copy-tree! c (io/file dst (.getName ^File c)))))

      :else
      (do (some-> (.getParentFile dst) .mkdirs)
          (Files/copy (.toPath src) (.toPath dst)
                      ^"[Ljava.nio.file.CopyOption;"
                      (into-array java.nio.file.CopyOption
                                  [java.nio.file.StandardCopyOption/COPY_ATTRIBUTES]))))))

(defn make-read-only!
  "Remove every write permission under f (files and directories)."
  [f]
  (let [f (io/file f)]
    (when (and (.isDirectory f) (not (symlink? f)))
      (doseq [c (.listFiles f)] (make-read-only! c)))
    (when-not (symlink? f)
      (let [p (.toPath f)
            perms (java.util.HashSet. (Files/getPosixFilePermissions p (make-array LinkOption 0)))]
        (.removeAll perms #{PosixFilePermission/OWNER_WRITE PosixFilePermission/GROUP_WRITE
                            PosixFilePermission/OTHERS_WRITE})
        (Files/setPosixFilePermissions p perms)))))

(defn executable? [f]
  (let [f (io/file f)] (and (.isFile f) (.canExecute f))))

;; ---------------------------------------------------------------------------
;; Processes
;; ---------------------------------------------------------------------------

(defn kill-tree!
  "Destroy a process and every descendant it still has."
  [^Process p]
  (when p
    (let [h (.toHandle p)
          kids (vec (iterator-seq (.iterator (.descendants h))))]
      (doseq [^java.lang.ProcessHandle k kids] (.destroyForcibly k))
      (.destroyForcibly p)
      (.waitFor p 5 TimeUnit/SECONDS))))

(defn- set-env! [^ProcessBuilder pb env]
  (let [e (.environment pb)]
    (.clear e)
    (doseq [[k v] env :when (some? v)] (.put e (str k) (str v)))))

(defn start-process
  "Start argv in dir with exactly env (a map). out: a File for stdout+stderr, or :pipe."
  ^Process [argv {:keys [dir env out err]}]
  (let [pb (ProcessBuilder. ^java.util.List (mapv str argv))]
    (when dir (.directory pb (io/file dir)))
    (set-env! pb env)
    (cond
      (instance? File out) (do (.redirectOutput pb (java.lang.ProcessBuilder$Redirect/appendTo out))
                               (.redirectErrorStream pb true))
      (= out :discard) (do (.redirectOutput pb java.lang.ProcessBuilder$Redirect/DISCARD)
                           (.redirectError pb java.lang.ProcessBuilder$Redirect/DISCARD))
      :else nil)
    (when (instance? File err)
      (.redirectError pb (java.lang.ProcessBuilder$Redirect/appendTo err)))
    (.start pb)))

(defn run-process
  "Run argv to completion (or until timeout-ms, then the tree is destroyed). stdin is
   closed. Returns {:exit n :out text :timed-out? bool}; stdout and stderr merged."
  [argv {:keys [dir env timeout-ms]}]
  (let [tmp (File/createTempFile "loyly-cr" ".out")
        p (start-process argv {:dir dir :env env :out tmp})]
    (.close (.getOutputStream p))
    (let [done? (if timeout-ms
                  (.waitFor p (long timeout-ms) TimeUnit/MILLISECONDS)
                  (do (.waitFor p) true))]
      (when-not done? (kill-tree! p))
      (let [out (slurp tmp)]
        (.delete tmp)
        {:exit (if done? (.exitValue p) -1) :out out :timed-out? (not done?)}))))

(defn sh
  "Run a command (argv) and return trimmed stdout on exit 0, else nil."
  [argv {:keys [dir env]}]
  (let [{:keys [exit out]} (run-process argv {:dir dir :env env :timeout-ms 600000})]
    (when (zero? exit) out)))

(defn lines [s] (if (str/blank? s) [] (str/split-lines s)))

(defn last-line
  "The last non-blank line of text, trimmed, or \"\"."
  [s]
  (or (some-> (last (remove str/blank? (lines s))) str/trim) ""))
