(ns loyly.pi-harness.evidence-manifest
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def artifact-schema
  [:map
   [:path :string]
   [:sha256 :string]
   [:bytes :int]
   [:media-type :string]
   [:producer :string]
   [:kind :string]
   [:item-id {:optional true} :string]])

(def manifest-schema
  [:map
   [:version :int]
   [:cycle-id :string]
   [:project-id :string]
   [:cut-digest :string]
   [:subject-digest :string]
   [:oracle-digest :string]
   [:digest :string]
   [:artifacts [:vector artifact-schema]]])

(defn- digest-stream [input]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        buffer (byte-array 8192)]
    (loop []
      (let [read (.read input buffer)]
        (when (pos? read)
          (.update digest buffer 0 read)
          (recur))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn- file-digest [file]
  (with-open [input (io/input-stream file)]
    (digest-stream input)))

(defn- value-digest [value]
  (with-open [input (io/input-stream (.getBytes (pr-str value) "UTF-8"))]
    (digest-stream input)))

(defn- relative-path [root file]
  (-> (.relativize (.toPath (io/file root)) (.toPath file))
      str
      (str/replace "\\" "/")))

(defn- item-id [path]
  (some-> (re-find #"(?:^|/)(@?item-\d+-\d+)(?:/|$)" path)
          second
          (str/replace #"^item-" "@item-")))

(defn- classify [path]
  (let [lower (str/lower-case path)
        producer (cond
                   (str/starts-with? lower "playwright/") "playwright"
                   (str/starts-with? lower "otel/") "opentelemetry"
                   (str/starts-with? lower "compose/") "docker-compose"
                   (str/starts-with? lower "independent/") "independent-grader"
                   :else "project")
        kind (cond
               (and (= producer "playwright") (str/ends-with? lower ".zip")) "trace"
               (re-find #"\.(png|jpe?g)$" lower) "screenshot"
               (str/ends-with? lower ".webm") "video"
               (and (= producer "opentelemetry") (str/includes? lower "metric")) "metrics"
               (and (= producer "opentelemetry") (str/includes? lower "trace")) "trace"
               (and (= producer "opentelemetry") (str/includes? lower "log")) "logs"
               (= producer "opentelemetry") "telemetry"
               (str/ends-with? lower ".xml") "test-report"
               (str/ends-with? lower ".result.json") "command-result"
               (or (str/ends-with? lower ".stdout.txt")
                   (str/ends-with? lower ".stderr.txt")) "command-output"
               :else "artifact")
        media-type (cond
                     (str/ends-with? lower ".json") "application/json"
                     (str/ends-with? lower ".xml") "application/xml"
                     (str/ends-with? lower ".zip") "application/zip"
                     (str/ends-with? lower ".png") "image/png"
                     (re-find #"\.jpe?g$" lower) "image/jpeg"
                     (str/ends-with? lower ".webm") "video/webm"
                     :else "text/plain")]
    {:producer producer :kind kind :media-type media-type}))

(defn- artifact [root file]
  (let [path (relative-path root file)]
    (cond-> (merge {:path path
                    :sha256 (file-digest file)
                    :bytes (.length file)}
                   (classify path))
      (item-id path) (assoc :item-id (item-id path)))))

(defn seal! [root metadata]
  (let [directory (io/file root)
        _ (.mkdirs directory)
        artifacts (->> (file-seq directory)
                       (filter #(.isFile %))
                       (remove #(let [path (relative-path directory %)]
                                  (or (= path "manifest.json")
                                      (str/starts-with? path "allure-results/"))))
                       (map #(artifact directory %))
                       (sort-by :path)
                       vec)
        unsigned (assoc (select-keys metadata
                                     [:cycle-id :project-id :cut-digest
                                      :subject-digest :oracle-digest])
                        :version 1
                        :artifacts artifacts)
        manifest (assoc unsigned :digest (value-digest unsigned))]
    (spit (io/file directory "manifest.json") (json/write-str manifest))
    manifest))
