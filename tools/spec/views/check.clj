;; Check the built pages the way a reader sees them (called by check.sh).
;;   bb check.clj static <page.html> <mmd-dir> <project.edn>   every view is there and not empty, ids
;;       are unique, every internal #link names an id, no Clojure value leaked into the text; and,
;;       against the specs themselves (read here from the config's spec root, not through the
;;       page's extractor): every context has its view and its card, every handoff edge
;;       (producer | event | consumer) is drawn on the architecture view, and the item count shown
;;       is the number of distinct stamped item tags; the C4 view shows the architecture file's
;;       "### Level N" views, in order, each with its Mermaid diagram. The drill-down levels: L0,
;;       the event storm on home and in story.svg, one sticky per event of the event models; L1,
;;       one swimlane board per flow on each context's view; L2, one spec card per Allium rule on
;;       each Allium file's view, each with its L3 "under the hood" folded away. review.html: one
;;       self-contained page (inline CSS and JS, Google Fonts and pinned CDN scripts only, a 2-4
;;       word title, in-page links only, under 16 MB). Writes each distinct Mermaid diagram to
;;       <mmd-dir>; prints the top-level views for the browser check.
;;   bb check.clj dom <view-id> < dumped-dom      headless Chrome's DOM at #<view-id>: that view
;;       is the one shown, no JavaScript error, and its every Mermaid block drawn without error
;;       (or deferred: folded under a closed "under the hood").
(require '[clojure.string :as str] '[clojure.java.io :as io] '[clojure.edn :as edn] '[clojure.java.shell :as sh])
(defn views [html]   ; [[id chunk] …], each view up to the next one or the footer
  (let [starts (re-seq #"<div id=\"([^\"]+)\" class=\"view\">" html)
        idx (map #(str/index-of html (first %)) starts)
        end (or (str/index-of html "<footer") (count html))]
    (map (fn [[_ id] a b] [id (subs html a b)]) starts idx (concat (rest idx) [end]))))
(defn text-of [chunk] (-> chunk (str/replace #"(?s)<div class=\"bar\">.*?</nav>" "") (str/replace #"<[^>]+>" "") str/trim))
(defn unescape [s] (-> s (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&quot;" "\"") (str/replace "&amp;" "&")))
(defn fail [& xs] (binding [*out* *err*] (apply println xs)) (System/exit 1))
(defn config [f]
  (let [f (io/file f) cfg (edn/read-string (slurp f)) dir (.getParentFile (.getAbsoluteFile f))]
    (assoc cfg :root (if (:root cfg) (.getCanonicalPath (.toFile (.resolve (.toPath dir) (str (:root cfg)))))
                         (str/trim (:out (sh/sh "git" "rev-parse" "--show-toplevel" :dir (str dir))))))))
(defn spec-of [cfg]   ; what the page must show, read from the spec root directly
  (let [root (:root cfg) F (:files cfg)
        dir (io/file root (:spec-root cfg))
        ctxs (sort (for [d (.listFiles dir) :when (.isDirectory (io/file d (:allium F)))
                         :when (or (= :discover (:contexts cfg :discover)) (some #{(.getName d)} (:contexts cfg)))] (.getName d)))
        frags (fn [c] (for [f (.listFiles (io/file dir c (:event-model F))) :when (str/ends-with? (.getName f) ".edn")] f))
        kws (fn [c] (set (for [f (frags c) :when (not= (:handoffs F) (.getName f)) x (tree-seq coll? seq (edn/read-string (slurp f))) :when (keyword? x)] x)))
        block-keys (fn [c k] (set (for [f (frags c) [_ am] (edn/read-string (slurp f)) bk (keys (get am k))] bk)))
        edges (set (for [p ctxs :let [h (io/file dir p (:event-model F) (:handoffs F))] :when (.exists h)
                         e (mapcat (comp keys :events) (vals (edn/read-string (slurp h))))
                         c ctxs :when (and (not= c p) ((kws c) e))]
                     (str p "|" (namespace e) "/" (name e) "|" c)))
        item-re (re-pattern (str (java.util.regex.Pattern/quote (:item-tag cfg)) "(\\d+-\\d+)"))
        items (set (for [c ctxs :let [f (io/file dir c (:spec F))] :when (.exists f)
                         l (str/split-lines (slurp f)) :when (str/starts-with? (str/trim l) "@")
                         [_ i] (re-seq item-re l)] i))
        arch (some-> (:architecture cfg) (#(io/file root %)) (#(when (.exists %) (slurp %))))]
    {:ctxs ctxs :edges edges :items (count items)
     :events (into {} (for [c ctxs] [c (count (filter #(= c (namespace %)) (block-keys c :events)))]))
     :flows (into {} (for [c ctxs] [c (count (block-keys c :flows))]))
     :rules (into {} (for [c ctxs f (.listFiles (io/file dir c (:allium F))) :when (str/ends-with? (.getName f) ".allium")]
                       [(str c "--" (str/replace (.getName f) ".allium" "")) (count (re-seq #"(?m)^rule \w+ \{" (slurp f)))]))
     :c4 (when arch (mapv second (re-seq #"(?s)### (Level \d: [^\n]+)\n.*?```mermaid\n" arch)))}))
(defn chunk-of [vs id] (or (second (first (filter #(= id (first %)) vs))) ""))
(defn check-review [f]
  (let [h (slurp f) title (second (re-find #"<title>([^<]*)</title>" h))
        scripts (map second (re-seq #"<script[^>]* src=\"([^\"]+)\"" h))
        links (map second (re-seq #"<link[^>]* href=\"([^\"]+)\"" h))
        hrefs (remove #(or (str/starts-with? % "#") (str/starts-with? % "https://fonts.")) (map second (re-seq #"<a[^>]* href=\"([^\"]*)\"" h)))]
    (when-not (<= 2 (count (str/split (str/trim (str title)) #"\s+")) 4) (fail "review.html: title" (pr-str title) "is not 2 to 4 words"))
    (when-let [bad (seq (remove #(re-find #"^https://(cdn\.jsdelivr\.net/npm/|cdnjs\.cloudflare\.com/)[^/]+@?\d+\.\d+\.\d+/" %) scripts))] (fail "review.html: scripts not from a pinned CDN:" bad))
    (when-let [bad (seq (remove #(str/starts-with? % "https://fonts.g") links))] (fail "review.html: external links:" bad))
    (when (seq hrefs) (fail "review.html: links leave the page:" (take 5 hrefs)))
    (when (re-find #"<link[^>]+rel=\"stylesheet\"[^>]+href=\"(?!https://fonts\.googleapis)" h) (fail "review.html: a stylesheet not from Google Fonts"))
    (when (> (.length (io/file f)) (* 16 1024 1024)) (fail "review.html: over 16 MB"))
    (println (str "review.html: self-contained, " (format "%.1f" (/ (.length (io/file f)) 1048576.0)) " MB, title \"" title "\", " (count scripts) " pinned script(s), "
                  (count (re-seq #"data-change=" h)) " event stickies and " (count (re-seq #"class=\"chg (?:new|changed)\"" h)) " marks of change"
                  (when (str/includes? h "id=\"changes\"") ", with the summary of what changed")))))
(let [[mode a b] *command-line-args*]
  (case mode
    "static"
    (let [html (slurp a) vs (views html) ids (map second (re-seq #" id=\"([^\"]+)\"" html))
          dup (for [[id n] (frequencies ids) :when (> n 1)] id)
          idset (set ids)
          links (distinct (map second (re-seq #"href=\"#([^\"]*)\"" html)))
          broken (remove idset links)
          empty-views (for [[id c] vs :when (str/blank? (text-of (or (second (re-find #"(?s)<main>(.*)</main>" c)) "")))] id)
          leaks (distinct (re-seq #"#object\[|clojure\.lang\.\w+|LazySeq|:read-models|:todo-processors" html))
          diagrams (distinct (map (comp unescape second) (re-seq #"(?s)<pre class=\"mermaid\">(.*?)</pre>" html)))]
      (let [cfg (config (nth *command-line-args* 3)) {:keys [ctxs edges items events flows rules c4]} (spec-of cfg)
            home (chunk-of vs "home") arch-v (chunk-of vs "c4")
            shown-edges (set (map second (re-seq #"data-edge=\"([^\"]+)\"" arch-v)))
            cards (set (map second (re-seq #"data-context=\"([^\"]+)\"" home)))
            n-items (some-> (re-find #"data-count=\"items\">(\d+)<" home) second parse-long)
            n-ctx (some-> (re-find #"data-count=\"contexts\">(\d+)<" home) second parse-long)
            dir (.getParentFile (io/file a))
            story (io/file dir (get-in cfg [:outputs :story] "story.svg"))
            evs-home (map second (re-seq #"data-event=\"([^\"]+)\"" home))]
        (doseq [need (concat ["home" "review"] (when (or (seq edges) (seq c4)) ["c4"]) (map #(str "ctx-" %) ctxs))] (when-not (some #(= need (first %)) vs) (fail "missing view" need)))
        (when (not= (set ctxs) cards) (fail "context cards" (sort cards) "but the spec root has" ctxs))
        (when (not= n-ctx (count ctxs)) (fail "the page counts" n-ctx "contexts, the spec root has" (count ctxs)))
        (when (seq (remove shown-edges edges)) (fail "handoff edges missing from the architecture view:" (str/join " " (sort (remove shown-edges edges)))))
        (when (seq (remove edges shown-edges)) (fail "handoff edges the spec does not have:" (str/join " " (sort (remove edges shown-edges)))))
        (when (not= n-items items) (fail "the page counts" n-items "items, the spec files stamp" items))
        (println (str "spec: " (count ctxs) " contexts, each with its view and card; " (count edges) " handoff edges drawn; " items " items, as stamped"))
        ;; L0: the event storm
        (when-not (str/includes? home "<svg class=\"story\"") (fail "L0: home has no event storm"))
        (when (not= (reduce + (vals events)) (count (distinct evs-home)) (count evs-home)) (fail "L0: the event storm shows" (count evs-home) "stickies, the event models define" (reduce + (vals events)) "events"))
        (when-not (.exists story) (fail "L0: no" (str story)))
        (let [sv (slurp story)]
          (when-not (and (str/starts-with? sv "<?xml") (str/includes? sv "xmlns=\"http://www.w3.org/2000/svg\"")) (fail "L0: story.svg is not a standalone SVG"))
          (when (not= (count evs-home) (count (re-seq #"data-event=" sv))) (fail "L0: story.svg and the page disagree")))
        (when-not (str/includes? home "href=\"#ctx-") (fail "L0: the event storm links no context"))
        (println (str "L0: the event storm, " (count evs-home) " stickies (every event), on home and in " (.getName story)))
        ;; L1: one swimlane board per flow
        (doseq [c ctxs :let [v (chunk-of vs (str "ctx-" c)) n (count (re-seq #"<div[^>]* class=\"lanes\"" v))]]
          (when (not= n (flows c)) (fail "L1: ctx-" c "shows" n "flow boards, its event model has" (flows c) "flows")))
        (println (str "L1: " (reduce + (vals flows)) " flows, each as swimlanes on its context's view"))
        ;; L2 and L3: a spec card per rule, its hood folded away
        (doseq [[m n] rules :let [v (chunk-of vs (str "mod-" m)) cs (filter #(str/includes? % "<details class=\"hood\">") (rest (str/split v #"<article[^>]* data-card=\"rule\"")))]]
          (when (not= n (count (re-seq #"data-card=\"rule\"" v))) (fail "L2: mod-" m "shows" (count (re-seq #"data-card=\"rule\"" v)) "rule cards, its Allium has" n "rules"))
          (when (not= n (count cs)) (fail "L3: mod-" m ": a rule card without its folded hood")))
        (println (str "L2: " (reduce + (vals rules)) " rule cards, each with its scenarios; L3: under the hood of each, folded"))
        ;; L2, as a reader reads it: the card above its hood names no source document or Allium
        ;; name, reads "Refused if" (never "Only if" over refusals), and folds all but its first example
        (let [cards (for [c (rest (str/split html #"<article")) :when (re-find #"^[^>]*data-card=\"" c)
                          :let [vis (first (str/split c #"<details class=\"hood\">"))]]
                      {:id (second (re-find #"^[^>]* id=\"([^\"]+)\"" c)) :rule? (re-find #"^[^>]*data-card=\"rule\"" c)
                       :text (unescape (str/replace vis #"<[^>]+>" " ")) :vis vis})
              refs (for [{:keys [id text]} cards :let [m (re-find #"\((?:[\w./-]+\.(?:md|allium|feature|edn|sh)|[a-z][\w-]*\.[A-Z]\w*)\b[^()]*\)" text)] :when m] (str id ": " m))
              rcards (filter :rule? cards)
              rows (for [{:keys [id text]} rcards :when (or (not (str/includes? text "Refused if")) (re-find #"Only if|It is refused when" text))] id)
              unfolded (for [{:keys [id vis]} cards :let [n (count (re-seq #"<div class=\"sc[ \"]" (first (str/split vis #"<details class=\"more-ex\">"))))] :when (> n 1)] id)
              folded (count (filter #(str/includes? (:vis %) "<details class=\"more-ex\">") cards))]
          (when (seq refs) (fail "L2: source references in a card's plain text:" (str/join "; " (take 5 refs))))
          (when (seq rows) (fail "L2: rule cards without a \"Refused if\" row, or still \"Only if\":" (str/join " " (take 5 rows))))
          (when (seq unfolded) (fail "L2: cards showing more than one example unfolded:" (str/join " " (take 5 unfolded))))
          (println (str "L2: " (count cards) " cards name no source document in their plain text; " (count rcards) " rule cards read \"Refused if\"; "
                        folded " fold their examples after the first")))
        (when (seq c4)
          (let [secs (map second (re-seq #"(?s)<section data-c4=\"([^\"]+)\">.*?<pre class=\"mermaid\">" arch-v))]
            (when-not (str/includes? home "href=\"#c4\"") (fail "home does not link the architecture view"))
            (when (not= c4 (vec secs)) (fail "C4 views" (vec secs) "but the architecture file has" c4))
            (println (str "C4: " (count secs) " views, each with its diagram: " (str/join "; " secs)))))
        (let [rv (io/file dir (get-in cfg [:outputs :review] "review.html"))]
          (if (.exists rv) (check-review rv) (fail "no" (str rv)))))
      (when (seq dup) (fail "duplicate ids:" (str/join " " (take 10 dup))))
      (when (seq broken) (fail "links to no id:" (str/join " " (map #(str "#" %) (take 10 broken)))))
      (when (seq empty-views) (fail "empty views:" (str/join " " empty-views)))
      (when (seq leaks) (fail "Clojure values in the page:" (str/join " " leaks)))
      (.mkdirs (io/file b))
      (doseq [[i d] (map-indexed vector diagrams)] (spit (str b "/" i ".mmd") d))
      (println (str "page: " (count vs) " views, " (count idset) " ids, " (count links) " internal links resolve, " (count diagrams) " distinct diagrams"))
      (println (str "top: " (str/join " " (for [[id] vs :when (not (re-find #"^blk-" id))] id)))))
    "dom"
    (let [d (slurp *in*) shown (second (re-find #"<html[^>]*data-view=\"([^\"]*)\"" d))
          err (second (re-find #"<html[^>]*data-js-error=\"([^\"]*)\"" d))
          chunk (or (second (first (filter #(= a (first %)) (views d)))) "")
          n (count (re-seq #"<pre class=\"mermaid\"" chunk))
          drawn (+ (count (re-seq #"<pre class=\"mermaid\"[^>]*data-processed=\"true\"" chunk)) (count (re-seq #"<pre class=\"mermaid\"[^>]*data-deferred" chunk)))
          bad (count (re-seq #"Syntax error in text" chunk))]
      (if (and (= shown a) (nil? err) (= n drawn) (zero? bad))
        (println n)
        (fail (str "chrome #" a ": view shown " shown ", " drawn " of " n " diagrams drawn, " bad " syntax errors, js error: " (or err "-")))))))
