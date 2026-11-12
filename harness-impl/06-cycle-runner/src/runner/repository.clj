(ns runner.repository
  (:require [runner.util :as u] [clojure.string :as str] [clojure.java.io :as io]))
(defn read-at [repo commit path] (u/git repo "show" (str commit ":" path)))
(defn strip-items [text]
  (str/join "\n" (keep (fn [line]
                         (if (str/starts-with? (str/triml line) "@")
                           (let [[tags comment] (str/split line #"#" 2)
                                 s (str/replace tags #"@item-[0-9]+-[0-9]+\b[ \t]*" "")
                                 s (if comment (str s "#" comment) s)]
                             (when-not (str/blank? s) (str/trimr s)))
                           line)) (str/split text #"\n" -1))))
(defn reading [text source]
  (let [lines (vec (str/split text #"\n" -1))]
    (loop [i 0 rule nil tags [] items [] empty [] scenarios []]
      (if (>= i (count lines)) {:items items :empty-title-lines empty :unstamped scenarios}
        (let [line (lines i) t (str/trim line)
              heading (re-matches #"(Feature|Rule|Scenario Outline|Scenario|Background|Examples):\s*(.*)" t)
              title (nth heading 2 nil)
              empty (if (and heading (str/blank? title)) (conj empty (inc i)) empty)]
          (cond
            (str/starts-with? t "#") (recur (inc i) rule tags items empty scenarios)
            (str/starts-with? t "@") (recur (inc i) rule (into tags (map second (re-seq #"@item-([0-9]+-[0-9]+)\b" (first (str/split t #"#" 2))))) items empty scenarios)
            (and heading (= "Rule" (second heading))) (recur (inc i) title [] items empty scenarios)
            (and heading (#{"Scenario" "Scenario Outline"} (second heading)))
            (let [end (or (first (filter (fn [j] (let [s (str/trim (lines j))] (or (str/starts-with? s "@") (re-find #"^(Feature|Rule|Scenario Outline|Scenario|Background|Examples):" s)))) (range (inc i) (count lines)))) (count lines))
                  body (reverse (drop-while str/blank? (reverse (subvec lines i end))))
                  item {:item-id (first tags) :scenario-digest (u/sha (str/join "\n" body)) :rule rule :source (str source ":" (inc i)) :title title}]
              (recur (inc i) rule [] (conj items item) empty (if (and (empty? tags) (not (str/blank? title))) (conj scenarios {:line (inc i) :title title}) scenarios)))
            :else (recur (inc i) rule (if (str/blank? t) tags []) items empty scenarios)))))))
(defn slices [repo commit config]
  (let [plan (:PLAN_DIR config) selected (set (str/split (str/trim (:PLAN_SLICES config "")) #"\s+"))
        entries (str/split-lines (u/git repo "ls-tree" (str commit ":" plan)))]
    (vec (for [entry entries
               :let [[_ mode type hash name] (re-matches #"([0-9]+) (\S+) (\S+)\t(.*)" entry)
                     number (second (re-find #"^([0-9]+)-" name))]
               :when (and number (or (= selected #{""}) (selected number)))
               :let [path (str plan "/" name)
                     target (if (= mode "120000") (.toString (.normalize (.resolve (java.nio.file.Paths/get plan (make-array String 0)) (str/trim (read-at repo commit path))))) path)
                     source (str path "/spec.feature")
                     text (read-at repo commit (str target "/spec.feature"))
                     status (some-> (re-find #"(?im)^\s*(?:#\s*)?status:\s*(planned|backlog)\b" text) second)]]
           (merge {:name name :slice number :text text :source source :status status} (reading text source))))))
(defn stamp-copy [dir slice]
  (let [source (u/put (str dir "/stamping/" (:name slice) "/spec.feature") (strip-items (:text slice)))
        read (reading (slurp source) source)]
    {:slice (:slice slice) :source source :unstamped (:unstamped read) :empty-title-lines (:empty-title-lines read)}))
(defn checklist [slices cut selection]
  {:cut-id cut :selection-id selection
   :items (vec (mapcat #(filter :item-id (:items %)) (remove :status slices)))
   :unstamped (vec (mapcat #(for [item (:items %) :when (nil? (:item-id item))] (select-keys item [:source :title])) (remove :status slices)))
   :skipped-slices (mapv #(select-keys % [:slice :status]) (filter :status slices))})
(defn git! [path & args] (apply u/git path (concat ["-c" "user.name=Cycle runner" "-c" "user.email=runner@localhost"] args)))
(defn fence! [repo commit dir paths]
  (let [clone (u/exec ["git" "-c" "safe.directory=*" "clone" "--depth" "1" "--no-hardlinks" "--no-checkout" (str "file://" (u/abs repo)) dir] nil)]
    (when-not (zero? (:exit clone)) (throw (ex-info (:err clone) clone))))
  (git! dir "fetch" "--depth" "1" "origin" commit)
  (git! dir "checkout" "--detach" commit)
  (let [unmatched (filterv (fn [p] (empty? (str/trim (git! dir "ls-tree" "-r" "--name-only" "HEAD" "--" p)))) paths)]
    (doseq [p paths] (git! dir "rm" "-rf" "--ignore-unmatch" "--" p))
    (let [tree (str/trim (git! dir "write-tree"))
          root (str/trim (:out (u/exec ["git" "-c" "safe.directory=*" "-c" "user.name=Cycle runner" "-c" "user.email=runner@localhost" "-C" dir "commit-tree" tree "-m" "Fenced source cut"] nil)))]
      (git! dir "checkout" "--orphan" "clean-room")
      (git! dir "reset" "--hard" root)
      (doseq [ref (str/split-lines (git! dir "for-each-ref" "--format=%(refname)")) :when (not= ref "refs/heads/clean-room")] (git! dir "update-ref" "-d" ref))
      (git! dir "remote" "remove" "origin")
      (io/delete-file (io/file dir ".git/shallow") true)
      (git! dir "reflog" "expire" "--expire=now" "--all")
      (git! dir "gc" "--prune=now")
      (let [treepaths (str/split-lines (git! dir "ls-tree" "-r" "--name-only" "HEAD"))
            objects (keep #(second (str/split % #" " 2)) (str/split-lines (git! dir "rev-list" "--objects" "--all")))
            present (fn [all] (filterv (fn [p] (let [p (str/replace p #"/$" "")] (some #(or (= p %) (str/starts-with? % (str p "/"))) all))) paths))]
        {:clean-room-commit root :fenced-paths paths :root-commits (count (str/split-lines (git! dir "rev-list" "--max-parents=0" "--all")))
         :commits (count (str/split-lines (git! dir "rev-list" "--all"))) :unmatched-paths unmatched :paths-left-in-tree (present treepaths) :paths-reachable-in-object-graph (present objects)}))))
(defn archive! [repo commit dir]
  (u/mkdir dir)
  (let [p (u/launch ["git" "-c" "safe.directory=*" "-C" repo "archive" commit] nil {} false)
        tar (u/launch ["tar" "-x" "-C" dir] nil {} false)]
    (with-open [input (.getInputStream p) output (.getOutputStream tar)] (io/copy input output))
    (when-not (and (zero? (.waitFor p)) (zero? (.waitFor tar))) (throw (ex-info "Could not archive cut" {}))))
  dir)
