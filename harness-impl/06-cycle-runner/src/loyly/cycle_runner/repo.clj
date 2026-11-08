(ns loyly.cycle-runner.repo
  "The project repository at the cut, read with git, and the harness configuration (legacy
   config.sh as-is: KEY=value lines)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.cycle-runner.gherkin :as gherkin]
            [loyly.cycle-runner.util :as u]))

(defn git-env []
  (into {} (remove (fn [[k _]] (str/starts-with? k "LOYLY_"))) (System/getenv)))

(defn git [repo & args]
  (u/run-process (into ["git"] args) {:dir repo :env (git-env) :timeout-ms 600000}))

(defn git-out [repo & args]
  (let [{:keys [exit out]} (apply git repo args)]
    (when (zero? exit) out)))

(defn is-commit? [repo commit]
  (and (some? repo) (.isDirectory (io/file repo))
       (zero? (:exit (git repo "cat-file" "-e" (str commit "^{commit}"))))))

(defn tree-of [repo commit]
  (some-> (git-out repo "rev-parse" (str commit "^{tree}")) str/trim))

(defn show
  "A file's text at the commit, or nil."
  [repo commit path]
  (git-out repo "show" (str commit ":" path)))

(defn show-bytes [repo commit path]
  (let [{:keys [exit out]} (git repo "cat-file" "-e" (str commit ":" path))]
    (when (zero? exit)
      (let [p (u/start-process ["git" "show" (str commit ":" path)] {:dir repo :env (git-env)})]
        (.close (.getOutputStream p))
        (let [bs (.readAllBytes (.getInputStream p))]
          (.waitFor p)
          bs)))))

;; ---------------------------------------------------------------------------
;; The configuration
;; ---------------------------------------------------------------------------

(defn- unquote-value [v]
  (let [v (str/trim (str/replace v #"\s+#.*$" ""))]
    (cond
      (and (>= (count v) 2) (str/starts-with? v "\"") (str/ends-with? v "\"")) (subs v 1 (dec (count v)))
      (and (>= (count v) 2) (str/starts-with? v "'") (str/ends-with? v "'")) (subs v 1 (dec (count v)))
      :else v)))

(defn parse-config
  "The KEY=value lines of a legacy config.sh, unindented assignments only."
  [text]
  (let [kv (into {}
                 (keep (fn [line]
                         (when-let [[_ k v] (re-matches #"^([A-Z_][A-Z0-9_]*)=(.*)$" line)]
                           [k (unquote-value v)])))
                 (str/split-lines (or text "")))]
    {:app-dir (get kv "APP_DIR")
     :port (some-> (get kv "PORT") str/trim parse-long)
     :plan-dir (get kv "PLAN_DIR")
     :app-marker (get kv "APP_MARKER")
     :app-probe (get kv "APP_PROBE")
     :fenced-paths (vec (remove str/blank? (str/split (or (get kv "FENCED_PATHS") "") #"\s+")))
     :boot-budget-seconds 10
     :boot-polls 20}))

;; ---------------------------------------------------------------------------
;; The plan at the cut
;; ---------------------------------------------------------------------------

(defn- normalize [path]
  (str (.normalize (.toPath (io/file path)))))

(defn- ls-tree [repo commit dir]
  (->> (u/lines (git-out repo "ls-tree" commit (str (str/replace dir #"/+$" "") "/")))
       (keep (fn [l]
               (when-let [[_ mode type _ path] (re-matches #"^(\d+) (\w+) (\w+)\t(.*)$" l)]
                 {:mode mode :type type :path path})))))

(defn- slice-status [{:keys [feature-tags]}]
  (cond (some #{"@backlog"} feature-tags) "backlog"
        (some #{"@planned"} feature-tags) "planned"))

(defn plan
  "Each slice of the plan at the cut, in slice order: its folder name, slice number, the
   spec file's path at the cut, its text, reading and status (planned or backlog skip it)."
  [repo commit plan-dir]
  (->> (ls-tree repo commit plan-dir)
       (keep (fn [{:keys [mode type path]}]
               (let [folder (last (str/split path #"/"))
                     spec-dir (cond
                                (= type "tree") path
                                (= mode "120000") (normalize (str (.getParent (io/file path)) "/"
                                                                  (str/trim (show repo commit path))))
                                :else nil)
                     spec-path (when spec-dir (str spec-dir "/spec.feature"))
                     text (when spec-path (show repo commit spec-path))]
                 (when text
                   (let [reading (gherkin/read-feature text)]
                     {:folder folder
                      :slice (first (str/split folder #"-"))
                      :source (str (str/replace path #"/+$" "") "/spec.feature")
                      :spec-path spec-path
                      :text text
                      :reading reading
                      :status (slice-status reading)})))))
       (sort-by :folder)
       vec))
