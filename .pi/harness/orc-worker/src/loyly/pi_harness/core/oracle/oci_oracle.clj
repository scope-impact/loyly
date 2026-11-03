(ns loyly.pi-harness.core.oracle.oci-oracle
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def artifact-type "application/vnd.loyly.oracle.pack.v1")

(def fixture-server-type
  "application/vnd.loyly.oracle.fixture-server.v1+javascript")

(def grader-file-type
  "application/vnd.loyly.oracle.grader-file.v1")

(def otel-capture-policy-type
  "application/vnd.loyly.oracle.otel-capture-policy.v1+json")

(def required-metadata-layer-types
  ["application/vnd.loyly.oracle.policy.v1+json"
   "application/vnd.loyly.oracle.project.v1+json"
   "application/vnd.loyly.oracle.slice.v1+json"
   "application/vnd.loyly.oracle.calibration.v1+json"
   "application/vnd.loyly.oracle.fixture-server.v1+javascript"
   otel-capture-policy-type])

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
                    (run-oras "manifest" "fetch" "--oci-layout" pack-ref "--descriptor")
                    :key-fn keyword)
        manifest (json/read-str
                  (run-oras "manifest" "fetch" "--oci-layout" pack-ref)
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
        (run-oras "pull" "--oci-layout" pack-ref "--output" (.getPath directory) "--no-tty")
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

(defn materialize-grader! [pack cache-root]
  (when-not cache-root
    (throw (ex-info "OCI oracle cache root is required to materialize its grader" {})))
  (let [root (.getCanonicalFile
              (io/file cache-root (str/replace (:digest pack) #"^sha256:" "") "grader"))
        grader-layers (filter #(= grader-file-type (:media-type %)) (:layers pack))]
    (.mkdirs root)
    (doseq [{:keys [title content] :as layer} grader-layers]
      (let [relative (grader-relative-path title)
            target (when relative (.getCanonicalFile (io/file root relative)))]
        (when-not (and target (.startsWith (.toPath target) (.toPath root)))
          (throw (ex-info "OCI grader layer title must be a safe path below grader/"
                          {:layer (dissoc layer :content)})))
        (some-> target .getParentFile .mkdirs)
        (java.nio.file.Files/write (.toPath target)
                                   (.decode (java.util.Base64/getDecoder) ^String content)
                                   (make-array java.nio.file.OpenOption 0))))
    (.getPath root)))

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
  (let [policy (layer-content pack "application/vnd.loyly.oracle.policy.v1+json")
        project (layer-content pack "application/vnd.loyly.oracle.project.v1+json")
        slice (layer-content pack "application/vnd.loyly.oracle.slice.v1+json")
        calibration (layer-content pack "application/vnd.loyly.oracle.calibration.v1+json")
        expected-project (:projectId selection)
        selected-item-ids (->> (:items selection)
                               (map #(if (map? %) (:id %) %))
                               set)
        packed-item-ids (->> (:items slice)
                             (map #(if (map? %) (:id %) %))
                             set)
        missing-items (sort (remove packed-item-ids selected-item-ids))
        contract-by-item (into {} (map (juxt :id identity)) (:items contract))
        slice-by-item (into {} (keep #(when (map? %) [(:id %) %])) (:items slice))
        minimum-fixtures (or (:minimumCalibrationFixtures policy) 2)
        calibration-by-item (into {} (map (juxt :itemId identity)) (:items calibration))
        grader-artifacts (:graderArtifacts calibration)
        fixture-server (layer-content pack fixture-server-type)
        capture-policy (layer-content pack otel-capture-policy-type)
        calibration-cases (atom [])]
    (when-not (and (= 1 (:version policy))
                   (integer? minimum-fixtures)
                   (<= 2 minimum-fixtures))
      (throw (ex-info "OCI oracle policy requires version 1 and at least two calibration fixtures"
                      {:policy policy})))
    (when-not (and (= 1 (:version capture-policy))
                   (= "opentelemetry" (:standard capture-policy))
                   (= ["traces" "metrics" "profiles"] (:signals capture-policy))
                   (= "otlp-json" (:format capture-policy))
                   (false? (:authoritativeGrade capture-policy)))
      (throw (ex-info "OCI oracle requires a non-authoritative OpenTelemetry capture policy"
                      {:capture-policy capture-policy})))
    (when-not (= expected-project (:projectId project))
      (throw (ex-info (str "OCI oracle project layer names " (:projectId project)
                           " but the selection names " expected-project)
                      {:project-layer project :selection selection})))
    (when-not (= (:artifacts contract) (:artifacts project))
      (throw (ex-info "OCI oracle project artifacts do not match the pinned model contract"
                      {:expected (:artifacts contract)
                       :actual (:artifacts project)})))
    (when (and (:slice selection) (not= (:slice selection) (:sliceId slice)))
      (throw (ex-info (str "OCI oracle slice layer names " (:sliceId slice)
                           " but the selection names " (:slice selection))
                      {:slice-layer slice :selection selection})))
    (when (seq missing-items)
      (throw (ex-info (str "selected item " (str/join ", " missing-items)
                           " is absent from the OCI oracle slice layer")
                      {:slice-layer slice :selection selection})))
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
    (doseq [item-id selected-item-ids]
      (let [expected-semantics (select-keys (get contract-by-item item-id) [:id :name :rule])
            actual-semantics (select-keys (get slice-by-item item-id) [:id :name :rule])
            _ (when-not (= expected-semantics actual-semantics)
                (throw (ex-info (str "OCI oracle slice semantics for " item-id
                                     " do not match the pinned model contract")
                                {:expected expected-semantics :actual actual-semantics})))
            {:keys [grader fixtures] :as item-calibration} (get calibration-by-item item-id)
            roles (set (map :role fixtures))]
        (when-not item-calibration
          (throw (ex-info (str "selected item " item-id
                               " has no OCI oracle calibration entry")
                          {:item-id item-id :calibration calibration})))
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
      (assoc pack :calibration-results calibration-results)))))
