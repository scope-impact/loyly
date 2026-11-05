(ns hjson
  "JSON in the one dialect the harness speaks, plus the small pieces of plumbing
  every tool in it needs.

  The writer reproduces a one-space-indented pretty printer with non-ASCII written
  through literally: that byte shape is contract (harness/spec.feature @harness-012),
  so it is spelled out here rather than delegated to a library whose defaults could
  move underneath it.

  The reader is hand written for one reason: a scored item is the checklist item's
  keys IN THE CHECKLIST'S OWN ORDER (@harness-029), so parsing has to preserve the
  order the keys arrived in. Objects come back as array maps, which are ordered."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- writing

(def ^:private escapes
  {\\ "\\\\", \" "\\\"", \backspace "\\b", \formfeed "\\f",
   \newline "\\n", \return "\\r", \tab "\\t"})

(defn- write-string [s]
  (let [sb (StringBuilder. "\"")]
    (doseq [c (str s)]
      (.append sb (or (escapes c)
                      (if (< (int c) 0x20)
                        (format "\\u%04x" (int c))
                        c))))
    (.append sb "\"")
    (str sb)))

(defn- indent [n] (str/join (repeat n " ")))

(defn write
  "Render `v` as JSON text, one space of indent per level, no trailing newline."
  ([v] (write v 0))
  ([v level]
   (cond
     (nil? v)     "null"
     (true? v)    "true"
     (false? v)   "false"
     (string? v)  (write-string v)
     (keyword? v) (write-string (name v))
     (number? v)  (str v)
     (map? v)     (if (empty? v)
                    "{}"
                    (str "{\n"
                         (str/join ",\n"
                                   (for [[k val] v]
                                     (str (indent (inc level))
                                          (write-string (if (keyword? k) (name k) (str k)))
                                          ": "
                                          (write val (inc level)))))
                         "\n" (indent level) "}"))
     (sequential? v) (if (empty? v)
                       "[]"
                       (str "[\n"
                            (str/join ",\n"
                                      (for [x v]
                                        (str (indent (inc level)) (write x (inc level)))))
                            "\n" (indent level) "]"))
     :else (throw (ex-info (str "cannot render " (pr-str v) " as JSON") {})))))

;; ---------------------------------------------------------------- reading

(defn- fail [s i msg]
  (throw (ex-info (str msg " at position " i) {:json/detail (str msg " at position " i)})))

(declare read-value)

(defn- skip-ws [^String s i]
  (loop [i i]
    (if (and (< i (count s)) (Character/isWhitespace (.charAt s i)))
      (recur (inc i))
      i)))

(defn- read-string-lit [^String s i]
  (loop [i (inc i) sb (StringBuilder.)]
    (when (>= i (count s)) (fail s i "unterminated string"))
    (let [c (.charAt s i)]
      (cond
        (= c \") [(str sb) (inc i)]
        (= c \\)
        (let [e (when (< (inc i) (count s)) (.charAt s (inc i)))]
          (case e
            \" (recur (+ i 2) (.append sb \"))
            \\ (recur (+ i 2) (.append sb \\))
            \/ (recur (+ i 2) (.append sb \/))
            \b (recur (+ i 2) (.append sb \backspace))
            \f (recur (+ i 2) (.append sb \formfeed))
            \n (recur (+ i 2) (.append sb \newline))
            \r (recur (+ i 2) (.append sb \return))
            \t (recur (+ i 2) (.append sb \tab))
            \u (if (< (+ i 5) (count s))
                 (recur (+ i 6)
                        (.append sb (char (Integer/parseInt (subs s (+ i 2) (+ i 6)) 16))))
                 (fail s i "truncated \\u escape"))
            (fail s i (str "invalid escape " (pr-str e)))))
        :else (recur (inc i) (.append sb c))))))

(defn- read-number [^String s i]
  (let [end (loop [j i]
              (if (and (< j (count s))
                       (re-matches #"[-+0-9.eE]" (str (.charAt s j))))
                (recur (inc j))
                j))
        text (subs s i end)]
    (when (= i end) (fail s i (str "unexpected character '" (.charAt s i) "'")))
    (if (re-matches #"-?\d+" text)
      [(Long/parseLong text) end]
      (try [(Double/parseDouble text) end]
           (catch Exception _ (fail s i (str "invalid number " (pr-str text))))))))

(defn- read-seq
  "Shared body of array and object reading: `close` ends it, `element` reads one."
  [^String s i close element]
  (loop [i (skip-ws s (inc i)) acc []]
    (when (>= i (count s)) (fail s i "unexpected end of input"))
    (if (= (.charAt s i) close)
      [acc (inc i)]
      (let [[parts i] (element s i)
            i (skip-ws s i)]
        (when (>= i (count s)) (fail s i "unexpected end of input"))
        (condp = (.charAt s i)
          \,    (recur (skip-ws s (inc i)) (into acc parts))
          close [(into acc parts) (inc i)]
          (fail s i (str "unexpected character '" (.charAt s i) "'")))))))

(defn- read-pair [^String s i]
  (when-not (= (.charAt s i) \") (fail s i "expected a key"))
  (let [[k i] (read-string-lit s i)
        i (skip-ws s i)]
    (when-not (and (< i (count s)) (= (.charAt s i) \:)) (fail s i "expected ':'"))
    (let [[v i] (read-value s (skip-ws s (inc i)))]
      [[k v] i])))

(defn- read-value [^String s i]
  (when (>= i (count s)) (fail s i "unexpected end of input"))
  (let [c (.charAt s i)]
    (cond
      (= c \{) (let [[kvs i] (read-seq s i \} read-pair)] [(apply array-map kvs) i])
      (= c \[) (let [[xs i] (read-seq s i \] (fn [s i] (let [[v i] (read-value s i)] [[v] i])))] [xs i])
      (= c \") (read-string-lit s i)
      (and (= c \t) (= "true" (subs s i (min (count s) (+ i 4)))))   [true (+ i 4)]
      (and (= c \f) (= "false" (subs s i (min (count s) (+ i 5)))))  [false (+ i 5)]
      (and (= c \n) (= "null" (subs s i (min (count s) (+ i 4)))))   [nil (+ i 4)]
      :else (read-number s i))))

(defn read-json
  "Parse JSON text. Throws ex-info carrying :json/detail on malformed input."
  [^String s]
  (let [[v i] (read-value s (skip-ws s 0))
        i (skip-ws s i)]
    (when (< i (count s))
      (fail s i (str "trailing data '" (.charAt s i) "'")))
    v))

(defn detail
  "The parser's own words about a failure, for the one message that carries them."
  [e]
  (or (:json/detail (ex-data e)) (.getMessage e)))

(defn type-name
  "The Python type names, which @harness-035 fixes as contract."
  [v]
  (cond (map? v) "dict" (string? v) "str" (nil? v) "NoneType"
        (or (true? v) (false? v)) "bool" (number? v) "int" :else "list"))

;; ---------------------------------------------------------------- plumbing

(defn root
  "The repository root: the parent of the harness/ directory holding this script,
  resolved through symlinks from the script's own location and never from the
  working directory (harness/spec.feature, INVOCATION)."
  [script]
  (.getParentFile (.getParentFile (.getCanonicalFile (io/file script)))))

(defn rel
  "`f` as a path relative to `root`, with forward slashes."
  [root f]
  (str/replace (subs (.getPath (io/file f)) (inc (count (.getPath (io/file root)))))
               java.io.File/separator "/"))

(defn plan-path
  "The spec root relative to `root`: the PLAN_DIR that root's harness/config.sh declares,
  or docs/plan when it declares none. Read from the file, not the environment, so the tools
  and modules.sh always resolve the same tree and a parent shell cannot leak its own."
  [root]
  (let [config (io/file root "harness" "config.sh")
        declared (when (.isFile config)
                   (some #(second (re-find #"^\s*PLAN_DIR=[\"']?([^\"'\s#]+)" %))
                         (str/split-lines (slurp config :encoding "UTF-8"))))]
    (or (not-empty declared) "docs/plan")))

(defn spec-files
  "Every <PLAN_DIR>/[0-9]*/spec.feature under `root`, sorted by path as text."
  [root]
  (->> (or (seq (.listFiles (io/file root (plan-path root)))) [])
       (filter #(.isDirectory %))
       (filter #(re-find #"^[0-9]" (.getName %)))
       (map #(io/file % "spec.feature"))
       (filter #(.isFile %))
       (sort-by #(rel root %))))

(defn plan-dir [root] (.getPath (io/file root (plan-path root))))

(defn emit
  "Write text to a stream as UTF-8, whatever the ambient locale says."
  [stream ^String s]
  (let [b (.getBytes s "UTF-8")]
    (.write ^java.io.OutputStream stream b 0 (alength b))
    (.flush ^java.io.OutputStream stream)))

(defn out [s] (emit System/out s))
(defn err [s] (emit System/err s))

(defn refuse
  "A refusal: the message to stderr with one trailing newline, then exit 1."
  [msg]
  (err (str msg "\n"))
  (System/exit 1))

(defn spit-utf8
  "Write `text` to `f`, creating missing parent directories."
  [f ^String text]
  (let [f (io/file f)]
    (when-let [p (.getParentFile f)] (.mkdirs p))
    (with-open [w (io/writer f :encoding "UTF-8")]
      (.write w text))))

(defn ralign [s w]
  (let [s (str s)]
    (str (str/join (repeat (max 0 (- w (count s))) " ")) s)))
