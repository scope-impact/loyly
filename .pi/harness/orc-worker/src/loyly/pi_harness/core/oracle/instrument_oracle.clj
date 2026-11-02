(ns loyly.pi-harness.core.oracle.instrument-oracle
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def artifact-type "application/vnd.loyly.instrument-oracle.v1")

(def metadata-layer-types
  ["application/vnd.loyly.instrument-oracle.policy.v1+json"
   "application/vnd.loyly.instrument-oracle.instruments.v1+json"
   "application/vnd.loyly.instrument-oracle.fixtures.v1+json"])

(def golden-layer-type "application/vnd.loyly.allure-golden.v1+json")

(defn- run-oras [& arguments]
  (let [result (apply shell/sh "oras" arguments)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "ORAS instrument oracle command failed: "
                           (str/trim (:err result)))
                      {:arguments arguments :result result})))
    (:out result)))

(defn- delete-tree! [root]
  (doseq [file (reverse (file-seq root))]
    (when-not (.delete file)
      (throw (ex-info "could not remove temporary instrument oracle output"
                      {:path (.getPath file)})))))

(defn- file-sha256 [file]
  (with-open [input (io/input-stream file)]
    (let [digest (java.security.MessageDigest/getInstance "SHA-256")
          buffer (byte-array 8192)]
      (loop []
        (let [read (.read input buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur))))
      (format "%064x" (BigInteger. 1 (.digest digest))))))

(defn- value-sha256 [value]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (format "%064x"
            (BigInteger. 1 (.digest digest (.getBytes (str value) "UTF-8"))))))

(defn instrument-digest [artifacts]
  (str "sha256:"
       (value-sha256
        (apply str
               (map (fn [{:keys [path digest]}]
                      (str path "\u0000" digest "\n"))
                    (sort-by :path artifacts))))))

(defn- verify-artifact! [root instrument-id {:keys [path digest] :as artifact}]
  (let [file (.getCanonicalFile (io/file root path))
        relative (.relativize (.toPath root) (.toPath file))]
    (when (or (.isAbsolute relative)
              (str/starts-with? (str relative) "..")
              (not (.isFile file)))
      (throw (ex-info "instrument oracle names an unavailable instrument file"
                      {:instrument-id instrument-id :artifact artifact
                       :project-root (.getPath root)})))
    (let [actual (str "sha256:" (file-sha256 file))]
      (when-not (= digest actual)
        (throw (ex-info (str "instrument oracle digest mismatch for " instrument-id
                             " artifact " path)
                        {:instrument-id instrument-id :artifact artifact :actual actual}))))))

(defn- root-relative-path [root file]
  (-> (.relativize (.toPath root) (.toPath (.getCanonicalFile file)))
      str
      (str/replace "\\" "/")))

(defn- discovered-artifacts [root instrument-id roots files]
  (let [directories (map #(.getCanonicalFile (io/file root %)) roots)
        explicit-files (map #(.getCanonicalFile (io/file root %)) files)]
    (doseq [directory directories]
      (let [relative (root-relative-path root directory)]
        (when (or (str/starts-with? relative "..") (not (.isDirectory directory)))
          (throw (ex-info "instrument oracle names an unavailable source root"
                          {:instrument-id instrument-id :root relative})))))
    (doseq [file explicit-files]
      (let [relative (root-relative-path root file)]
        (when (or (str/starts-with? relative "..") (not (.isFile file)))
          (throw (ex-info "instrument oracle names an unavailable instrument file"
                          {:instrument-id instrument-id :path relative})))))
    (->> (concat (mapcat file-seq directories) explicit-files)
         (filter #(.isFile %))
         (map (fn [file]
                {:path (root-relative-path root file)
                 :digest (str "sha256:" (file-sha256 file))}))
         (sort-by :path)
         vec)))

(defn discover-instrument-digest [project-root roots files]
  (let [root (.getCanonicalFile (io/file project-root))]
    (instrument-digest
     (discovered-artifacts root "discovery" roots files))))

(defn verify-instrument-files! [pack project-root]
  (let [root (.getCanonicalFile (io/file project-root))]
    (doseq [{:keys [id path digest artifacts roots files] :as instrument} (:instruments pack)]
      (cond
        (seq artifacts)
        (do
          (doseq [artifact artifacts]
            (verify-artifact! root id artifact))
          (let [actual (instrument-digest artifacts)]
            (when-not (= digest actual)
              (throw (ex-info (str "instrument oracle aggregate digest mismatch for " id)
                              {:instrument instrument :actual actual})))))

        (or (seq roots) (seq files))
        (let [actual-artifacts (discovered-artifacts root id roots files)
              actual (instrument-digest actual-artifacts)]
          (when-not (= digest actual)
            (throw (ex-info (str "instrument oracle aggregate digest mismatch for " id)
                            {:instrument instrument :actual actual
                             :artifacts actual-artifacts}))))

        :else
        (verify-artifact! root id {:path path :digest digest})))
    pack))

(defn- layer-file [directory layer]
  (let [title (get-in layer [:annotations :org.opencontainers.image.title])
        file (io/file directory title)]
    (when-not (and (string? title) (.isFile file))
      (throw (ex-info "instrument oracle layer has no extractable titled payload"
                      {:layer layer})))
    file))

(defn validate!
  ([pack] (validate! pack #{}))
  ([{:keys [policy instruments fixtures goldens] :as pack} required-instrument-ids]
   (when-not (= 1 (:version policy))
     (throw (ex-info "instrument oracle policy version must be 1" {:policy policy})))
   (let [minimum (:minimumFixturesPerInstrument policy)
         instrument-ids (mapv :id instruments)
         instrument-id-set (set instrument-ids)
         missing (sort (remove instrument-id-set required-instrument-ids))
         fixture-ids (mapv :id fixtures)
         fixture-id-set (set fixture-ids)
         golden-fixture-ids (mapv #(get-in % [:labels :fixture_id]) goldens)]
     (when (seq missing)
       (throw (ex-info (str "instrument oracle is missing required instruments: "
                            (str/join ", " missing))
                       {:required required-instrument-ids :actual instrument-id-set})))
     (when-not (and (integer? minimum) (<= 2 minimum))
       (throw (ex-info "instrument oracle requires at least two fixtures per instrument"
                       {:minimum minimum})))
     (when-not (= (count instrument-ids) (count instrument-id-set))
       (throw (ex-info "instrument oracle contains duplicate instrument identities"
                       {:instrument-ids instrument-ids})))
     (when-not (= (count fixture-ids) (count fixture-id-set))
       (throw (ex-info "instrument oracle contains duplicate fixture identities"
                       {:fixture-ids fixture-ids})))
     (doseq [{:keys [id digest]} instruments]
       (when-not (and (string? id)
                      (re-matches #"sha256:[0-9a-f]{64}" (or digest "")))
         (throw (ex-info "instrument oracle instrument has an invalid identity or digest"
                         {:instrument {:id id :digest digest}})))
       (let [calibration (filter #(= id (:instrumentId %)) fixtures)
             roles (set (map :role calibration))]
         (when-not (and (<= minimum (count calibration))
                        (contains? roles "carried")
                        (contains? roles "uncarried"))
           (throw (ex-info (str "instrument " id
                                " must have carried and uncarried calibration fixtures")
                           {:instrument-id id :fixtures calibration})))))
     (doseq [fixture fixtures]
       (when-not (contains? instrument-id-set (:instrumentId fixture))
         (throw (ex-info "instrument oracle fixture names an unknown instrument"
                         {:fixture fixture}))))
     (when-not (and (= (count fixture-ids) (count golden-fixture-ids))
                    (= fixture-id-set (set golden-fixture-ids)))
       (throw (ex-info "instrument oracle must contain exactly one Allure golden per fixture"
                       {:fixture-ids fixture-id-set
                        :golden-fixture-ids golden-fixture-ids})))
     (doseq [golden goldens]
       (let [fixture-id (get-in golden [:labels :fixture_id])
             instrument-id (get-in golden [:labels :instrument_id])
             fixture (some #(when (= fixture-id (:id %)) %) fixtures)]
         (when-not (and (= 1 (:schemaVersion golden))
                        (contains? #{"passed" "failed" "broken"} (:status golden))
                        (= instrument-id (:instrumentId fixture)))
           (throw (ex-info "instrument oracle contains an invalid normalized Allure golden"
                           {:golden golden :fixture fixture})))))
     pack)))

(defn resolve-pack [pack-ref]
  (let [descriptor (json/read-str
                    (run-oras "manifest" "fetch" "--oci-layout" pack-ref "--descriptor")
                    :key-fn keyword)
        manifest (json/read-str
                  (run-oras "manifest" "fetch" "--oci-layout" pack-ref)
                  :key-fn keyword)
        layers (:layers manifest)
        layer-types (mapv :mediaType layers)
        directory (.toFile (java.nio.file.Files/createTempDirectory
                            "loyly-instrument-oracle-pull-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (when-not (= artifact-type (:artifactType manifest))
        (throw (ex-info "OCI artifact is not a Löyly instrument oracle"
                        {:expected artifact-type :actual (:artifactType manifest)})))
      (when-not (= metadata-layer-types (subvec layer-types 0 (min 3 (count layer-types))))
        (throw (ex-info "instrument oracle metadata layers are missing or out of order"
                        {:expected metadata-layer-types :actual layer-types})))
      (when-not (and (> (count layer-types) 3)
                     (every? #(= golden-layer-type %) (drop 3 layer-types)))
        (throw (ex-info "instrument oracle must contain at least one Allure golden layer"
                        {:actual layer-types})))
      (run-oras "pull" "--oci-layout" pack-ref "--output" (.getPath directory) "--no-tty")
      (let [[policy-layer instruments-layer fixtures-layer & golden-layers] layers
            read-layer #(json/read-str (slurp (layer-file directory %)) :key-fn keyword)]
        (validate!
         {:digest (:digest descriptor)
          :policy (read-layer policy-layer)
          :instruments (:instruments (read-layer instruments-layer))
          :fixtures (:fixtures (read-layer fixtures-layer))
          :goldens (mapv read-layer golden-layers)
          :layers layers}))
      (finally
        (delete-tree! directory)))))
