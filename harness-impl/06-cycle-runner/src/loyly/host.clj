(ns loyly.host
  (:require [clojure.string :as str] [clojure.edn :as edn] [clojure.java.io :as io] [loyly.plan :as plan] [loyly.domain :as d])
  (:import [java.util.concurrent TimeUnit] [java.nio.file Files LinkOption] [java.security MessageDigest]))
(defn child [base path]
  (let [root (.normalize (.toAbsolutePath (.toPath (io/file base))))
        target (.normalize (.resolve root (str path)))]
    (when (or (= root target) (not (.startsWith target root)) (not (.startsWith (.toPath (.getCanonicalFile (.toFile target))) (.toPath (.getCanonicalFile (.toFile root))))))
      (throw (ex-info "Path outside run scope" {:path path})))
    (.toFile target)))
(def ^:dynamic *on-start* nil)
(defn run [dir seconds env args]
  (let [b (ProcessBuilder. ^java.util.List (mapv str args))]
    (.directory b (io/file dir))
    (.redirectErrorStream b true)
    (.putAll (.environment b) env)
    (let [p (.start b) read-output (future (slurp (.getInputStream p)))]
      (when *on-start* (*on-start* p))
      (when-not (.waitFor p seconds TimeUnit/SECONDS)
        (with-open [children (.descendants p)]
          (doseq [c (reverse (vec (.toArray children)))] (.destroyForcibly ^java.lang.ProcessHandle c)))
        (.destroyForcibly p)
        (throw (ex-info "Stage timed out" {:budget true})))
      {:exit (.exitValue p) :out (deref read-output 1000 "")})))
(defn script [dir seconds env command & args]
  (when (str/blank? command) (throw (ex-info "Command missing" {})))
  (let [[program & opts] (str/split command #"\s+")]
    (run dir seconds env (concat [program] opts args))))
(defn checked [dir args]
  (let [result (run dir 60 {} args)]
    (when-not (zero? (:exit result)) (throw (ex-info (:out result) {:command (vec args)})))
    (str/trim (:out result))))
(defn file-hex [file]
  (.formatHex (java.util.HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (Files/readAllBytes (.toPath file)))))
(defn tree-digest [root]
  (with-open [paths (Files/walk (.toPath root) (make-array java.nio.file.FileVisitOption 0))]
    (let [base (.toPath root)
          files (sort-by str (filter #(Files/isRegularFile % (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                                    (iterator-seq (.iterator paths))))]
      (when (seq files)
        (d/digest (apply str (for [p files] (str (.relativize base p) "\t" (file-hex (.toFile p)) "\n"))))))))
(defn clone [repo commit room]
  (io/make-parents room)
  (checked (.getParentFile room) ["git" "clone" "--no-local" "--no-checkout" repo (.getAbsolutePath room)])
  (checked room ["git" "checkout" "--detach" commit]))
(defn fence [repo commit room paths]
  (let [stage (io/file (.getParentFile room) "fence-staging")
        _ (clone repo commit stage)
        git #(checked stage (into ["git"] %))
        unmatched (vec (remove #(seq (git ["ls-files" "--" %])) paths))]
    (doseq [p paths :when (not (some #{p} unmatched))]
      (child stage p)
      (git ["rm" "-rf" "--" p]))
    (let [tree (git ["write-tree"])
          root (git ["-c" "user.name=Cycle runner" "-c" "user.email=runner@localhost" "commit-tree" tree "-m" "Fenced cut"])]
      (git ["update-ref" "refs/heads/fenced" root])
      (checked (.getParentFile room) ["git" "clone" "--depth" "1" "--single-branch" "-b" "fenced" (str "file://" (.getRawPath (.toURI stage))) (.getAbsolutePath room)])
      (checked room ["git" "remote" "remove" "origin"])
      (let [git #(checked room (into ["git"] %))
            objects (map #(second (str/split % #" " 2)) (str/split-lines (git ["rev-list" "--objects" "--all"])))
            tracked (set (str/split-lines (git ["ls-files"])))]
        {:clean-room-commit (git ["rev-parse" "HEAD"]) :fenced-paths paths :unmatched-paths unmatched
         :root-commits (count (str/split-lines (git ["rev-list" "--max-parents=0" "--all"])))
         :commits (parse-long (git ["rev-list" "--count" "--all"]))
         :paths-left-in-tree (vec (filter #(some (fn [f] (or (= f %) (str/starts-with? f (str % "/")))) tracked) paths))
         :paths-reachable-in-object-graph (vec (filter #(some (fn [f] (and f (or (= f %) (str/starts-with? f (str % "/"))))) objects) paths))}))))
(defn copy! [from to]
  (checked (.getParentFile to) ["cp" "-R" (.getAbsolutePath from) (.getAbsolutePath to)]))

(defn builder! [directory attempt repo seconds session command args]
  (let [owner (io/file directory (str "builder-owner-" attempt ".edn")) clock (io/file directory (str "builder-clock-" attempt))
        _ (.createNewFile clock)
        left (- seconds (quot (- (System/currentTimeMillis) (.lastModified clock)) 1000))]
    (when (.isFile owner)
      (let [{:keys [pid started identity]} (edn/read-string (slurp owner))
            previous (.orElse (java.lang.ProcessHandle/of pid) nil)]
        (when (and previous (= identity session) (= started (str (.orElse (.startInstant (.info previous)) nil))))
          (with-open [desc (.descendants previous)]
            (doseq [p (reverse (vec (.toArray desc)))] (.destroyForcibly ^java.lang.ProcessHandle p)))
          (.destroyForcibly previous)
          (.get (.onExit previous) 3 TimeUnit/SECONDS))))
    (when-not (pos? left) (throw (ex-info "Regenerate budget exhausted" {:budget true})))
    (binding [*on-start* (fn [^Process p]
                           (spit owner (pr-str {:pid (.pid p) :started (str (.orElse (.startInstant (.info p)) nil)) :identity session})))]
      (try (apply script repo left {"LOYLY_BUILDER_SESSION_ID" session} command args)
           (finally (.delete owner))))))
