(ns loyly.pi-harness.core.oracle.oci-oracle
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def artifact-type "application/vnd.loyly.product-oracle.v1")

(def catalog-type
  "application/vnd.loyly.product-oracle.catalog.v1+json")

(def fixture-server-type
  "application/vnd.loyly.oracle.fixture-server.v1+javascript")

(def grader-file-type
  "application/vnd.loyly.oracle.grader-file.v1")

(def otel-capture-policy-type
  "application/vnd.loyly.oracle.otel-capture-policy.v1+json")

(def required-metadata-layer-types
  [catalog-type
   "application/vnd.loyly.oracle.fixture-server.v1+javascript"
   otel-capture-policy-type])

(defn- local-oci-layout-ref? [pack-ref]
  (let [layout-path (-> pack-ref
                        (str/replace #"@sha256:[0-9a-f]{64}$" "")
                        (str/replace #":[^/]+$" ""))]
    (.isFile (io/file layout-path "oci-layout"))))

(defn- oras-ref-arguments [pack-ref]
  (cond-> []
    (local-oci-layout-ref? pack-ref) (conj "--oci-layout")
    true (conj pack-ref)))

(defn- valid-layer-types? [actual-types]
  (and (= required-metadata-layer-types
          (subvec actual-types 0 (min (count actual-types)
                                      (count required-metadata-layer-types))))
       (< (count required-metadata-layer-types) (count actual-types))
       (let [payload-types (drop (count required-metadata-layer-types) actual-types)]
         (and (some #{grader-file-type} payload-types)
              (every? #{grader-file-type} payload-types)))))

(defn- run-oras [& arguments]
  (let [result (apply shell/sh "oras" arguments)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "ORAS oracle pack command failed: " (str/trim (:err result)))
                      {:arguments arguments :result result})))
    (:out result)))

(defn- delete-tree! [root]
  (doseq [file (reverse (file-seq root))]
    (when-not (.delete file)
      (throw (ex-info "could not remove temporary ORAS oracle pack output"
                      {:path (.getPath file)})))))

(defn resolve-pack [pack-ref]
  (let [descriptor (json/read-str
                    (apply run-oras
                           (concat ["manifest" "fetch"]
                                   (oras-ref-arguments pack-ref)
                                   ["--descriptor"]))
                    :key-fn keyword)
        manifest (json/read-str
                  (apply run-oras
                         (concat ["manifest" "fetch"]
                                 (oras-ref-arguments pack-ref)))
                  :key-fn keyword)
        directory (.toFile (java.nio.file.Files/createTempDirectory
                            "loyly-oracle-pack-pull-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (when-not (= artifact-type (:artifactType manifest))
        (throw (ex-info "OCI oracle pack has the wrong artifact type"
                        {:expected artifact-type :actual (:artifactType manifest)})))
      (let [layers (:layers manifest)
            actual-types (mapv :mediaType layers)]
        (when-not (valid-layer-types? actual-types)
          (throw (ex-info "OCI oracle pack must contain ordered metadata, a runnable fixture server layer, and grader file layers"
                          {:expected-prefix required-metadata-layer-types
                           :grader-layer-type grader-file-type
                           :actual actual-types})))
        (apply run-oras
               (concat ["pull"]
                       (oras-ref-arguments pack-ref)
                       ["--output" (.getPath directory) "--no-tty"]))
        {:digest (:digest descriptor)
         :layers
         (mapv (fn [layer]
                 (let [title (get-in layer [:annotations :org.opencontainers.image.title])
                       file (io/file directory title)]
                   (when-not (and (string? title) (.isFile file))
                     (throw (ex-info "OCI oracle layer has no extractable titled payload"
                                     {:layer layer})))
                   (cond-> {:media-type (:mediaType layer)
                            :digest (:digest layer)
                            :size (:size layer)
                            :title title
                            :content (cond
                               (= fixture-server-type (:mediaType layer))
                               (slurp file)

                               (= grader-file-type (:mediaType layer))
                               (.encodeToString (java.util.Base64/getEncoder)
                                                (java.nio.file.Files/readAllBytes (.toPath file)))

                               :else
                               (json/read-str (slurp file) :key-fn keyword))}
                     (= otel-capture-policy-type (:mediaType layer))
                     (assoc :raw-content
                            (.encodeToString (java.util.Base64/getEncoder)
                                             (java.nio.file.Files/readAllBytes (.toPath file)))))))
               layers)})
      (finally
        (delete-tree! directory)))))

(defn layer-content [pack media-type]
  (:content (some #(when (= media-type (:media-type %)) %) (:layers pack))))

(defn- grader-relative-path [title]
  (when (and (string? title) (str/starts-with? title "grader/"))
    (let [relative (subs title (count "grader/"))
          path (java.nio.file.Paths/get relative (make-array String 0))]
      (when (and (not (str/blank? relative))
                 (not (.isAbsolute path))
                 (not-any? #(= ".." (str %)) path))
        relative))))

(defn- selected-paths-digest [selected-paths]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        value (str/join "\n" (sort selected-paths))]
    (format "%064x" (BigInteger. 1 (.digest digest (.getBytes value "UTF-8"))))))

(defn materialize-grader!
  ([pack cache-root] (materialize-grader! pack cache-root nil))
  ([pack cache-root selected-paths]
   (when-not cache-root
     (throw (ex-info "OCI oracle cache root is required to materialize its grader" {})))
   (let [pack-root (io/file cache-root (str/replace (:digest pack) #"^sha256:" ""))
         root (.getCanonicalFile
               (if selected-paths
                 (io/file pack-root (selected-paths-digest selected-paths) "grader")
                 (io/file pack-root "grader")))
         grader-layers (filter #(= grader-file-type (:media-type %)) (:layers pack))]
     (.mkdirs root)
     (doseq [{:keys [title content] :as layer} grader-layers
             :let [relative (grader-relative-path title)]
             :when (or (nil? selected-paths) (contains? selected-paths relative))]
       (let [target (when relative (.getCanonicalFile (io/file root relative)))]
         (when-not (and target (.startsWith (.toPath target) (.toPath root)))
           (throw (ex-info "OCI grader layer title must be a safe path below grader/"
                           {:layer (dissoc layer :content)})))
         (some-> target .getParentFile .mkdirs)
         (java.nio.file.Files/write (.toPath target)
                                    (.decode (java.util.Base64/getDecoder) ^String content)
                                    (make-array java.nio.file.OpenOption 0))))
     (.getPath root))))

(defn- observability-relative-path [title]
  (when (and (string? title) (str/starts-with? title "runtime-observability/"))
    (let [relative (subs title (count "runtime-observability/"))
          path (java.nio.file.Paths/get relative (make-array String 0))]
      (when (and (not (str/blank? relative))
                 (not (.isAbsolute path))
                 (not-any? #(= ".." (str %)) path))
        relative))))

(defn materialize-observability! [pack cache-root]
  (when-not cache-root
    (throw (ex-info "OCI oracle cache root is required to materialize observability policy" {})))
  (let [root (.getCanonicalFile
              (io/file cache-root (str/replace (:digest pack) #"^sha256:" "") "runtime-observability"))
        policy-layers (filter #(= otel-capture-policy-type (:media-type %)) (:layers pack))]
    (.mkdirs root)
    (doseq [{:keys [title content raw-content] :as layer} policy-layers]
      (let [relative (observability-relative-path title)
            target (when relative (.getCanonicalFile (io/file root relative)))]
        (when-not (and target (.startsWith (.toPath target) (.toPath root)))
          (throw (ex-info "OCI observability layer title must be a safe path below runtime-observability/"
                          {:layer (dissoc layer :content)})))
        (some-> target .getParentFile .mkdirs)
        (java.nio.file.Files/write (.toPath target)
                                   (.decode (java.util.Base64/getDecoder)
                                            ^String (or raw-content content))
                                   (make-array java.nio.file.OpenOption 0))))
    (.getPath root)))

(defn- file-digest [file]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [input (io/input-stream file)]
      (let [buffer (byte-array 8192)]
        (loop []
          (let [n (.read input buffer)]
            (when (pos? n)
              (.update digest buffer 0 n)
              (recur))))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn- validate-calibration-result! [{:keys [itemId role expected]} result]
  (let [observation (try
                      (first (json/read-str (:stdout result) :key-fn keyword))
                      (catch Exception _ nil))
        observed (some-> (:verdict observation) str/lower-case)
        exit-matches? (if (= expected "pass")
                        (zero? (:exitCode result))
                        (not (zero? (:exitCode result))))]
    (when-not (and (= itemId (:itemId result))
                   (= role (:role result))
                   (#{"pass" "fail"} expected)
                   (= expected observed)
                   exit-matches?)
      (throw (ex-info (str "calibration fixture " itemId " " role
                           " expected " expected " but observed "
                           (or observed "unreadable"))
                      {:item-id itemId :role role :expected expected
                       :observed observed :result result})))
    {:item-id itemId :role role :expected expected :observed observed}))

(defn validate-pack!
  ([pack selection contract]
   (validate-pack! pack selection contract {}))
  ([pack selection contract {:keys [grader-root fixture-runner]}]
  (let [catalog (layer-content pack catalog-type)
        expected-project (:projectId selection)
        selected-items (mapv #(if (map? %) % {:id %}) (:items selection))
        selected-item-ids (mapv :id selected-items)
        contract-by-item (into {} (map (juxt :id identity)) (:items contract))
        entries (:entries catalog)
        entry-counts (frequencies (map :itemId entries))
        duplicate-entries (->> entry-counts (keep (fn [[item-id n]] (when (> n 1) item-id))) sort vec)
        entry-by-item (into {} (map (juxt :itemId identity)) entries)
        missing-items (sort (remove entry-by-item selected-item-ids))
        selected-entries (mapv entry-by-item selected-item-ids)
        minimum-fixtures (or (:minimumCalibrationFixtures catalog) 2)
        grader-artifacts (:graderArtifacts catalog)
        fixture-server (layer-content pack fixture-server-type)
        capture-policy (layer-content pack otel-capture-policy-type)
        calibration-cases (atom [])]
    (when-not (and (= 1 (:version catalog))
                   (integer? minimum-fixtures)
                   (<= 2 minimum-fixtures))
      (throw (ex-info "OCI product catalogue requires version 1 and at least two calibration fixtures"
                      {:catalog catalog})))
    (when (seq duplicate-entries)
      (throw (ex-info (str "OCI product catalogue contains duplicate item "
                           (str/join ", " duplicate-entries))
                      {:duplicates duplicate-entries})))
    (when (and capture-policy
               (not (and (= 1 (:version capture-policy))
                         (= "opentelemetry" (:standard capture-policy))
                         (= ["traces" "metrics" "profiles"] (:signals capture-policy))
                         (= "otlp-json" (:format capture-policy))
                         (false? (:authoritativeGrade capture-policy)))))
      (throw (ex-info "OCI oracle requires a non-authoritative OpenTelemetry capture policy"
                      {:capture-policy capture-policy})))
    (when-not (= expected-project (:projectId catalog))
      (throw (ex-info (str "OCI product catalogue names project " (:projectId catalog)
                           " but the selection names " expected-project)
                      {:catalog catalog :selection selection})))
    (when (seq missing-items)
      (throw (ex-info (str "selected item " (str/join ", " missing-items)
                           " is absent from the OCI product catalogue")
                      {:catalog catalog :selection selection})))
    (when grader-root
      (let [root (.getCanonicalFile (io/file grader-root))]
        (doseq [{:keys [path digest] :as artifact} grader-artifacts]
          (let [file (.getCanonicalFile (io/file root path))]
            (when-not (and (string? path)
                           (re-matches #"[0-9a-f]{64}" (or digest ""))
                           (.startsWith (.toPath file) (.toPath root))
                           (.isFile file))
              (throw (ex-info "grader dependency is unavailable or has invalid metadata"
                              {:artifact artifact})))
            (let [actual (file-digest file)]
              (when-not (= digest actual)
                (throw (ex-info (str "grader dependency digest mismatch for " path)
                                {:artifact artifact :actual actual}))))))))
    (doseq [selection-item selected-items]
      (let [item-id (:id selection-item)
            contract-item (get contract-by-item item-id)
            entry (get entry-by-item item-id)
            scenario-digest (:scenario-digest contract-item)
            _ (when-not (= scenario-digest (:scenario-digest selection-item))
                (throw (ex-info (str "approved scenario digest for " item-id
                                     " does not match the pinned cut")
                                {:selection-item selection-item :contract-item contract-item})))
            _ (when-not (= scenario-digest (:scenarioDigest entry))
                (throw (ex-info (str "OCI product catalogue scenario digest for " item-id
                                     " does not match the approved selection")
                                {:expected scenario-digest :actual (:scenarioDigest entry)})))
            {:keys [grader fixtures]} entry
            roles (set (map :role fixtures))]
        (when-not (and (string? (:path grader))
                       (re-matches #"[0-9a-f]{64}" (or (:digest grader) "")))
          (throw (ex-info (str "selected item " item-id
                               " has no digest-bound independent grader")
                          {:item-id item-id :grader grader})))
        (when-not (and (<= minimum-fixtures (count fixtures))
                       (contains? roles "carried")
                       (contains? roles "uncarried"))
          (throw (ex-info (str "selected item " item-id
                               " requires carried and uncarried calibration fixtures")
                          {:item-id item-id :fixtures fixtures})))
        (doseq [{:keys [role expected] :as fixture} fixtures]
          (let [required-expected (case role
                                    "carried" "pass"
                                    "uncarried" "fail"
                                    nil)]
            (when-not (= required-expected expected)
              (throw (ex-info (str role " fixture for " item-id " must expect "
                                   (or required-expected "a defined verdict"))
                              {:item-id item-id :fixture fixture})))))
        (when grader-root
          (let [root (.getCanonicalFile (io/file grader-root))
                file (.getCanonicalFile (io/file root (:path grader)))]
            (when-not (and (.startsWith (.toPath file) (.toPath root)) (.isFile file))
              (throw (ex-info (str "grader path is unavailable or escapes its root: "
                                   (:path grader))
                              {:item-id item-id :grader grader})))
            (let [actual (file-digest file)]
              (when-not (= (:digest grader) actual)
                (throw (ex-info (str "grader digest mismatch for " (:path grader))
                                {:item-id item-id :expected (:digest grader)
                                 :actual actual}))))
            (when fixture-server
              (doseq [{:keys [role expected]} fixtures]
                (swap! calibration-cases conj
                       {:itemId item-id :role role :expected expected
                        :graderPath (:path grader)})))))))
    (when (and fixture-server (seq @calibration-cases) (nil? fixture-runner))
      (throw (ex-info "Gondolin fixture runner is required for runnable oracle calibration" {})))
    (let [runner-result (when (seq @calibration-cases)
                          (fixture-runner {:fixtureSource fixture-server
                                           :graderRoot (.getPath (.getCanonicalFile (io/file grader-root)))
                                           :cases (mapv #(dissoc % :expected) @calibration-cases)}))
          _ (when (and runner-result (not= "gondolin" (:backend runner-result)))
              (throw (ex-info "oracle calibration must execute in Gondolin"
                              {:backend (:backend runner-result)})))
          actual-by-case (into {} (map (juxt (juxt :itemId :role) identity)) (:cases runner-result))
          expected-case-keys (set (map (juxt :itemId :role) @calibration-cases))
          actual-case-keys (set (map (juxt :itemId :role) (:cases runner-result)))
          _ (when-not (and (= expected-case-keys actual-case-keys)
                           (= (count expected-case-keys) (count (:cases runner-result))))
              (throw (ex-info "Gondolin oracle calibration returned a missing, duplicate, or unknown case"
                              {:expected expected-case-keys :actual actual-case-keys})))
          calibration-results (mapv (fn [expected]
                                      (assoc (validate-calibration-result!
                                              expected
                                              (get actual-by-case [(:itemId expected) (:role expected)]))
                                             :backend "gondolin"))
                                    @calibration-cases)]
      (assoc pack
             :selected-item-ids selected-item-ids
             :selected-entries selected-entries
             :calibration-results calibration-results)))))
