(ns loyly.pi-harness.core.oracle.harness-snapshot
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

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
    (format "%064x" (BigInteger. 1 (.digest digest (.getBytes (str value) "UTF-8"))))))

(defn- relative-path [root file]
  (-> (.relativize (.toPath root) (.toPath (.getCanonicalFile file)))
      str (str/replace "\\" "/")))

(defn- files-under [root relative]
  (let [file (.getCanonicalFile (io/file root relative))
        path (relative-path root file)]
    (when (or (str/starts-with? path "..") (not (.exists file)))
      (throw (ex-info "harness snapshot input is unavailable" {:path relative})))
    (if (.isDirectory file)
      (filter #(.isFile %) (file-seq file))
      [file])))

(defn- artifacts [root definition]
  (let [declared (concat (mapcat #(files-under root %) (:roots definition))
                         (mapcat #(files-under root %) (:files definition)))
        runtime-manifests (->> (file-seq (io/file root ".pi/harness/.state/assets"))
                               (filter #(and (.isFile %) (= "manifest.json" (.getName %)))))]
    (->> (concat declared runtime-manifests)
         (map (fn [file] {:path (relative-path root file)
                          :digest (str "sha256:" (file-sha256 file))}))
         distinct
         (sort-by :path)
         vec)))

(defn- golden [file]
  (json/read-str (slurp file) :key-fn keyword))

(defn build! [project-root]
  (let [root (.getCanonicalFile (io/file project-root))
        definition (json/read-str
                    (slurp (io/file root ".pi/harness/config/harness-snapshot.json"))
                    :key-fn keyword)
        artifacts (artifacts root definition)
        manifest-digest (str "sha256:" (value-sha256 (json/write-str artifacts)))
        revision-result (shell/sh "git" "rev-parse" "HEAD" :dir (.getPath root))
        revision (str/trim (:out revision-result))
        golden-root (io/file root ".pi/harness/test/fixtures/instrument-oracle")
        goldens (->> (.listFiles golden-root)
                     (filter #(str/ends-with? (.getName %) ".golden.json"))
                     (sort-by #(.getName %))
                     (mapv golden))
        fixtures (mapv (fn [value]
                         {:id (get-in value [:labels :fixture_id])
                          :instrumentId (:snapshotId definition)
                          :role (get-in value [:parameters 0 :value])})
                       goldens)]
    (when-not (and (zero? (:exit revision-result))
                   (re-matches #"[0-9a-f]{40}" revision))
      (throw (ex-info "harness snapshot requires a committed revision"
                      {:result revision-result})))
    {:harness-revision revision
     :manifest-digest manifest-digest
     :artifacts artifacts
     :components (:components definition)
     :fixtures fixtures
     :goldens goldens}))

(defn validate! [{:keys [manifest-digest artifacts fixtures goldens] :as snapshot}]
  (when-not (re-matches #"sha256:[0-9a-f]{64}" (or manifest-digest ""))
    (throw (ex-info "harness snapshot has no valid manifest digest" {})))
  (when (empty? artifacts)
    (throw (ex-info "harness snapshot contains no bound artifacts" {})))
  (let [roles (set (map :role fixtures))
        fixture-ids (set (map :id fixtures))
        golden-ids (set (map #(get-in % [:labels :fixture_id]) goldens))]
    (when-not (= #{"carried" "uncarried"} roles)
      (throw (ex-info "harness snapshot requires carried and uncarried fixtures" {:roles roles})))
    (when-not (= fixture-ids golden-ids)
      (throw (ex-info "harness snapshot fixtures and goldens differ" {}))))
  snapshot)
