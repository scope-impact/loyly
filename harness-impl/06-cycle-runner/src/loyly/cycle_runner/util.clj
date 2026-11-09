(ns loyly.cycle-runner.util
  "Small shared tools: JSON, digests, files, processes."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file Files LinkOption Path Paths StandardCopyOption]
           [java.nio.file.attribute PosixFilePermission]
           [java.security MessageDigest]
           [java.time Instant OffsetDateTime ZoneOffset]
           [java.util Date UUID]
           [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; Diagnostics go to stderr, never stdout.

(defn log [& xs]
  (binding [*out* *err*]
    (println (str/join " " (cons "[cycle-runner]" xs)))
    (flush)))

;; ---------------------------------------------------------------------------
;; JSON

(defn- json-value [_k v]
  (cond
    (instance? Instant v) (str v)
    (instance? OffsetDateTime v) (str (.toInstant ^OffsetDateTime v))
    (instance? Date v) (str (.toInstant ^Date v))
    (instance? UUID v) (str v)
    (keyword? v) (if (namespace v) (str (namespace v) "/" (name v)) (name v))
    (set? v) (vec v)
    :else v))

(defn- json-key [k]
  (if (keyword? k)
    (if (namespace k) (str (namespace k) "/" (name k)) (name k))
    (str k)))

(defn ->json ^String [x]
  (json/write-str x :value-fn json-value :key-fn json-key :escape-slash false :escape-unicode false))

(defn <-json [^String s]
  (json/read-str s :key-fn keyword))

;; ---------------------------------------------------------------------------
;; Digests

(defn sha256-hex ^String [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md bs)))))

(defn utf8 ^bytes [^String s] (.getBytes s "UTF-8"))

(defn sha256-of-text [^String s] (str "sha256:" (sha256-hex (utf8 s))))

(defn concat-bytes ^bytes [& arrays]
  (let [out (java.io.ByteArrayOutputStream.)]
    (doseq [^bytes a arrays :when a] (.write out a))
    (.toByteArray out)))

;; ---------------------------------------------------------------------------
;; Files

(defn path ^Path [& parts]
  (Paths/get (str (first parts)) (into-array String (map str (rest parts)))))

(defn abs-path ^String [p]
  (str (.normalize (.toAbsolutePath (path p)))))

(defn mkdirs! [p] (.mkdirs (io/file (str p))) (str p))

(defn exists? [p] (.exists (io/file (str p))))

(defn read-bytes ^bytes [p] (Files/readAllBytes (path p)))

(defn spit-utf8 [p ^String s]
  (io/make-parents (io/file (str p)))
  (spit (io/file (str p)) s :encoding "UTF-8"))

(defn- make-writable! [^File f]
  (try (.setWritable f true true) (catch Exception _ nil)))

(defn delete-tree! [p]
  (let [f (io/file (str p))]
    (when (or (.exists f) (Files/isSymbolicLink (.toPath f)))
      (if (and (.isDirectory f) (not (Files/isSymbolicLink (.toPath f))))
        (do (make-writable! f)
            (doseq [c (.listFiles f)] (delete-tree! c))
            (.delete f))
        (do (make-writable! (.getParentFile f)) (.delete f))))))

(defn copy-tree!
  "Copy a directory tree (files, directories, symbolic links as links),
   keeping executable bits. `skip?` is called with each path relative to
   `from` and skips it when true."
  ([from to] (copy-tree! from to (constantly false)))
  ([from to skip?]
   (let [src (.normalize (.toAbsolutePath (path from)))
         dst (.normalize (.toAbsolutePath (path to)))]
     (Files/createDirectories dst (make-array java.nio.file.attribute.FileAttribute 0))
     (with-open [s (Files/walk src (make-array java.nio.file.FileVisitOption 0))]
       (doseq [^Path p (iterator-seq (.iterator s))
               :let [rel (.relativize src p)
                     rel-s (str rel)]
               :when (not (str/blank? rel-s))
               :when (not (skip? rel-s))]
         (let [target (.resolve dst rel)]
           (cond
             (Files/isSymbolicLink p)
             (do (Files/createDirectories (.getParent target) (make-array java.nio.file.attribute.FileAttribute 0))
                 (Files/deleteIfExists target)
                 (Files/createSymbolicLink target (Files/readSymbolicLink p)
                                           (make-array java.nio.file.attribute.FileAttribute 0)))
             (Files/isDirectory p (make-array LinkOption 0))
             (Files/createDirectories target (make-array java.nio.file.attribute.FileAttribute 0))
             :else
             (do (Files/createDirectories (.getParent target) (make-array java.nio.file.attribute.FileAttribute 0))
                 (Files/copy p target ^"[Ljava.nio.file.CopyOption;"
                             (into-array java.nio.file.CopyOption
                                         [StandardCopyOption/REPLACE_EXISTING
                                          StandardCopyOption/COPY_ATTRIBUTES
                                          LinkOption/NOFOLLOW_LINKS])))))))
     (str dst))))

(defn regular-files
  "Every file of a tree that is not a directory, as paths relative to `root`,
   sorted by code point. Symbolic links count as files."
  [root]
  (let [r (.normalize (.toAbsolutePath (path root)))]
    (if-not (Files/isDirectory r (make-array LinkOption 0))
      []
      (with-open [s (Files/walk r (make-array java.nio.file.FileVisitOption 0))]
        (->> (iterator-seq (.iterator s))
             (remove #(and (Files/isDirectory ^Path % (make-array LinkOption 0))
                           (not (Files/isSymbolicLink ^Path %))))
             (map #(str (.relativize r ^Path %)))
             (remove str/blank?)
             sort
             vec)))))

(defn tree-digest
  "\"sha256:\" and the SHA-256 of one line per file, <path> TAB <sha256 hex of
   its bytes> LF, by path."
  [root]
  (let [r (.toAbsolutePath (path root))
        lines (for [rel (regular-files root)
                    :let [p (.resolve r ^String rel)
                          bs (if (Files/isSymbolicLink p)
                               (utf8 (str (Files/readSymbolicLink p)))
                               (Files/readAllBytes p))]]
                (str rel "\t" (sha256-hex bs) "\n"))]
    (sha256-of-text (apply str lines))))

(defn set-read-only-tree! [root]
  (let [r (.toAbsolutePath (path root))]
    (when (Files/exists r (make-array LinkOption 0))
      (with-open [s (Files/walk r (make-array java.nio.file.FileVisitOption 0))]
        (doseq [^Path p (reverse (vec (iterator-seq (.iterator s))))
                :when (not (Files/isSymbolicLink p))]
          (.setWritable (.toFile p) false false))))))

(defn executable? [p]
  (let [f (io/file (str p))]
    (and (.isFile f) (.canExecute f))))

(defn chmod-exec! [p]
  (.setExecutable (io/file (str p)) true false))

;; ---------------------------------------------------------------------------
;; Environment

(defn strip-env
  "The environment without any variable whose name starts LOYLY_ and without
   GATE_STORE."
  [env]
  (into {} (remove (fn [[k _]] (or (str/starts-with? k "LOYLY_") (= k "GATE_STORE")))) env))

;; ---------------------------------------------------------------------------
;; Processes

(defn shell-argv
  "argv running a handed-in command line (shell words) with extra arguments
   appended, each passed as one argument."
  [cmd-line args]
  (into ["/bin/sh" "-c" (str cmd-line " \"$@\"") "sh"] args))

(defn command-program
  "The program a command line names: its first word."
  [cmd-line]
  (first (remove str/blank? (str/split (str/trim (or cmd-line "")) #"\s+"))))

(defn program-runnable?
  "Whether the program a command line names can be started: a path (resolved
   against `dir`) that is an executable file, or a name found executable on
   the PATH of `env`."
  [cmd-line dir env]
  (let [prog (command-program cmd-line)]
    (cond
      (str/blank? prog) false
      (str/includes? prog "/")
      (let [f (io/file prog)
            f (if (.isAbsolute f) f (io/file (str dir) prog))]
        (executable? f))
      :else
      (boolean (some #(executable? (io/file % prog))
                     (str/split (or (get env "PATH") "") #":"))))))

(defn- destroy-tree! [^Process p]
  (try
    (let [handle (.toHandle p)
          kids (vec (iterator-seq (.iterator (.descendants handle))))]
      (doseq [^java.lang.ProcessHandle k (reverse kids)] (.destroyForcibly k))
      (.destroyForcibly p))
    (catch Exception _ nil)))

(defn kill-tree! [^Process p] (when p (destroy-tree! p)))

(defn- builder ^ProcessBuilder [argv {:keys [dir env]}]
  (let [pb (ProcessBuilder. ^java.util.List (mapv str argv))]
    (when dir (.directory pb (io/file (str dir))))
    (when env
      (let [m (.environment pb)]
        (.clear m)
        (doseq [[k v] env] (.put m (str k) (str v)))))
    (.redirectInput pb (java.lang.ProcessBuilder$Redirect/from (io/file "/dev/null")))
    pb))

(defn run!
  "Run argv to its end, or until `timeout-ms` passed (then its tree is
   stopped). Returns {:exit n :out text :timed-out? bool}; stdout and stderr
   merged."
  [argv {:keys [timeout-ms out-file] :as opts}]
  (let [pb (doto (builder argv opts) (.redirectErrorStream true))
        _ (when out-file
            (io/make-parents (io/file (str out-file)))
            (.redirectOutput pb (java.lang.ProcessBuilder$Redirect/to (io/file (str out-file)))))
        p (.start pb)
        out (if out-file
              (delay (try (slurp (io/file (str out-file)) :encoding "UTF-8") (catch Exception _ "")))
              (future (slurp (.getInputStream p) :encoding "UTF-8")))
        finished? (if timeout-ms
                    (.waitFor p (long timeout-ms) TimeUnit/MILLISECONDS)
                    (do (.waitFor p) true))]
    (when-not finished? (destroy-tree! p) (.waitFor p 5 TimeUnit/SECONDS))
    {:exit (if finished? (.exitValue p) -1)
     :out (if out-file (force out) (or (deref out 5000 nil) ""))
     :timed-out? (not finished?)}))

(defn spawn!
  "Start argv in the background, its output discarded."
  ^Process [argv opts]
  (let [pb (builder argv opts)]
    (.redirectOutput pb java.lang.ProcessBuilder$Redirect/DISCARD)
    (.redirectError pb java.lang.ProcessBuilder$Redirect/DISCARD)
    (.start pb)))

(defn now ^Instant [] (Instant/now))

(defn last-line [text]
  (last (remove str/blank? (str/split-lines (or text "")))))

(defn safe-name [s]
  (str/replace (str s) #"[^A-Za-z0-9._-]" "_"))
