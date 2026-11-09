(ns loyly.cycle-runner.repo
  "The project repository at the cut (contract ProjectRepository), the
   handed-in harness configuration, the plan and its slices, and the reading
   of a spec file: its scenarios, their item tags, their Rules and digests."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.cycle-runner.util :as u]))

;; ---------------------------------------------------------------------------
;; git

(defn git [repo & args]
  (u/run! (into ["git" "-C" (str repo)] args) {:timeout-ms 120000}))

(defn git-ok [repo & args]
  (let [{:keys [exit out]} (apply git repo args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) " failed: " out) {:exit exit :out out})))
    out))

(defn git-bytes
  "The bytes of `git show <commit>:<path>`, or nil when it names nothing."
  ^bytes [repo commit p]
  (let [pb (doto (ProcessBuilder. ^java.util.List ["git" "-C" (str repo) "show" (str commit ":" p)])
             (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD))
        proc (.start pb)
        bs (.readAllBytes (.getInputStream proc))]
    (when (zero? (.waitFor proc)) bs)))

(defn git-text [repo commit p]
  (some-> (git-bytes repo commit p) (String. "UTF-8")))

(defn commit?
  "is_commit: git cat-file -e <commit>^{commit}."
  [repo commit]
  (and (some? repo) (u/exists? repo)
       (zero? (:exit (git repo "cat-file" "-e" (str commit "^{commit}"))))))

(defn tree-of [repo commit]
  (str/trim (git-ok repo "rev-parse" (str commit "^{tree}"))))

(defn ls-tree
  "Entries of a tree at the commit: [{:mode :type :sha :path}]."
  [repo commit & paths]
  (let [out (apply git-ok repo "ls-tree" commit "--" paths)]
    (vec (for [line (str/split-lines out)
               :when (not (str/blank? line))
               :let [[meta p] (str/split line #"\t" 2)
                     [mode type sha] (str/split meta #"\s+")]]
           {:mode mode :type type :sha sha :path p}))))

(defn mode-at [repo commit p]
  (:mode (first (ls-tree repo commit p))))

;; ---------------------------------------------------------------------------
;; The harness configuration: legacy config.sh, KEY=value lines.

(defn- unquote-value [v]
  (let [v (str/trim v)]
    (if (and (>= (count v) 2)
             (or (and (str/starts-with? v "\"") (str/ends-with? v "\""))
                 (and (str/starts-with? v "'") (str/ends-with? v "'"))))
      (subs v 1 (dec (count v)))
      v)))

(defn parse-config [text]
  (into {}
        (for [line (str/split-lines (or text ""))
              :let [line (str/trim line)
                    line (if (str/starts-with? line "export ") (str/trim (subs line 7)) line)]
              :when (and (not (str/blank? line)) (not (str/starts-with? line "#")))
              :let [[k v] (str/split line #"=" 2)]
              :when (and v (re-matches #"[A-Za-z_][A-Za-z0-9_]*" k))]
          [k (unquote-value v)])))

(defn words [s] (vec (remove str/blank? (str/split (str/trim (or s "")) #"\s+"))))

(defn config-of
  "The configuration as the runner reads it."
  [text]
  (let [m (parse-config text)
        seam (let [s (str/trim (get m "APP_SEAM" ""))] (if (= s "stdio") "stdio" "http"))]
    {:app-dir (get m "APP_DIR")
     :port (some-> (get m "PORT") str/trim not-empty parse-long)
     :plan-dir (get m "PLAN_DIR")
     :app-marker (get m "APP_MARKER")
     :app-probe (get m "APP_PROBE")
     :fenced-paths (words (get m "FENCED_PATHS"))
     :app-seam seam
     :plan-slices (words (get m "PLAN_SLICES"))}))

;; ---------------------------------------------------------------------------
;; Reading a spec file

(def ^:private heading-re #"^\s*(Feature|Rule|Background|Scenario Outline|Scenario Template|Scenario|Example)\s*:(.*)$")
(def ^:private scenario-kinds #{"Scenario" "Scenario Outline" "Scenario Template" "Example"})
(def item-tag-re #"@item-(\d+-\d{3,})")

(defn- tag-line? [line] (str/starts-with? (str/triml line) "@"))

(defn- heading [line]
  (when-let [[_ kind title] (re-matches heading-re line)]
    {:kind kind :title (str/trim title)}))

(defn read-spec
  "The scenarios of a spec file's text, in file order: heading line (from 1),
   title, item identity (from its tag lines), Rule title, scenario digest;
   the empty-title headings; the Feature's tags."
  [text]
  (let [lines (vec (str/split-lines (or text "")))
        n (count lines)
        boundary? (fn [i] (let [l (nth lines i)] (or (heading l) (tag-line? l))))
        digest-of (fn [i]
                    (let [end (or (first (filter boundary? (range (inc i) n))) n)
                          body (subvec lines i end)
                          body (vec (reverse (drop-while str/blank? (reverse body))))]
                      (u/sha256-of-text (str/join "\n" body))))
        tags-above (fn [i]
                     (loop [j (dec i) acc []]
                       (if (and (>= j 0) (tag-line? (nth lines j)))
                         (recur (dec j) (into acc (words (nth lines j))))
                         acc)))]
    (loop [i 0 rule nil feature-tags [] scenarios [] empties []]
      (if (>= i n)
        {:scenarios scenarios :empty-title-lines empties :feature-tags feature-tags}
        (let [l (nth lines i)
              h (heading l)]
          (cond
            (nil? h) (recur (inc i) rule feature-tags scenarios empties)
            (= "Feature" (:kind h)) (recur (inc i) nil (tags-above i) scenarios empties)
            (= "Rule" (:kind h)) (recur (inc i) (:title h) feature-tags scenarios empties)
            (scenario-kinds (:kind h))
            (if (str/blank? (:title h))
              (recur (inc i) rule feature-tags scenarios (conj empties (inc i)))
              (let [tags (tags-above i)
                    item (some #(second (re-matches item-tag-re %)) tags)]
                (recur (inc i) rule feature-tags
                       (conj scenarios {:line (inc i) :title (:title h) :item-id item
                                        :rule rule :digest (digest-of i)})
                       empties)))
            :else (recur (inc i) rule feature-tags scenarios empties)))))))

(defn strip-item-tags
  "The text with the @item-NN-SSS tags of its tag lines removed: a tag line
   left empty goes; comments and other tags stay."
  [text]
  (let [trailing-newline? (str/ends-with? (or text "") "\n")
        out (for [line (str/split-lines (or text ""))
                  :let [stripped (if (tag-line? line)
                                   (let [indent (re-find #"^\s*" line)
                                         kept (remove #(re-matches item-tag-re %) (words line))]
                                     (when (seq kept) (str indent (str/join " " kept))))
                                   line)]
                  :when (some? stripped)]
              stripped)]
    (str (str/join "\n" out) (when trailing-newline? "\n"))))

;; ---------------------------------------------------------------------------
;; The plan and its slices

(defn- normalize [p]
  (loop [parts (str/split p #"/") acc []]
    (if-let [x (first parts)]
      (cond
        (or (= x "") (= x ".")) (recur (rest parts) acc)
        (= x "..") (recur (rest parts) (if (seq acc) (pop acc) acc))
        :else (recur (rest parts) (conj acc x)))
      (str/join "/" acc))))

(defn- slice-number [name] (second (re-matches #"^(\d+)-.*" name)))

(defn- same-number? [a b]
  (let [pa (parse-long a) pb (parse-long b)]
    (if (and pa pb) (= pa pb) (= a b))))

(defn plan-slices
  "The slices of PLAN_DIR at the commit, in slice order, each read at its
   target when it is a symbolic link; only the selected ones."
  [repo commit {:keys [plan-dir plan-slices]}]
  (let [plan-dir (normalize (or plan-dir ""))
        entries (if (str/blank? plan-dir) [] (ls-tree repo commit (str plan-dir "/")))]
    (->> entries
         (keep (fn [{:keys [mode type sha path]}]
                 (let [name (last (str/split path #"/"))
                       number (slice-number name)]
                   (when number
                     (let [target (cond
                                    (= mode "120000")
                                    (let [link (str/trim (git-ok repo "cat-file" "-p" sha))]
                                      (normalize (str plan-dir "/" link)))
                                    (= type "tree") (str plan-dir "/" name)
                                    :else nil)
                           text (when target (git-text repo commit (str target "/spec.feature")))]
                       (when text
                         {:name name :number number
                          :source (str plan-dir "/" name "/spec.feature")
                          :target (str target "/spec.feature")
                          :text text}))))))
         (sort-by (juxt #(or (parse-long (:number %)) Long/MAX_VALUE) :name))
         (filter (fn [s] (or (empty? plan-slices)
                             (some #(same-number? % (:number s)) plan-slices))))
         vec)))

(defn slice-status [reading]
  (some #({"@planned" "planned" "@backlog" "backlog"} %) (:feature-tags reading)))

(defn checklist-reading
  "Legacy gen_checklist's reading of the selected slices: the stamped items,
   the unstamped scenarios and the slices skipped by status."
  [slices]
  (reduce
   (fn [acc {:keys [name number source text]}]
     (let [reading (read-spec text)
           status (slice-status reading)]
       (if status
         (update acc :skipped-slices conj {:slice number :status status})
         (reduce (fn [acc {:keys [line title item-id rule digest]}]
                   (if item-id
                     (update acc :items conj {:item-id item-id :scenario-digest digest :rule rule
                                              :source (str source ":" line) :title title})
                     (update acc :unstamped conj {:source (str source ":" line) :title title})))
                 acc (:scenarios reading)))))
   {:items [] :unstamped [] :skipped-slices []}
   slices))

(defn scenario-digests
  "The cut's scenario digests, keyed by item identity (@item-NN-SSS) and by title."
  [slices]
  (into {}
        (for [{:keys [text]} slices
              {:keys [title item-id digest]} (:scenarios (read-spec text))
              k (cond-> [title] item-id (conj (str "@item-" item-id)))]
          [k digest])))
