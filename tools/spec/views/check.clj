;; Check the built page the way a reader sees it (called by check.sh).
;;   bb check.clj static <page.html> <mmd-dir>   every view is there and not empty, ids are unique,
;;       every internal #link names an id, no Clojure value leaked into the text; and, against
;;       spec v2 itself (.pi/spec, read here, not through extract.clj): every context has its view
;;       and its card, every handoff edge (producer | event | consumer) is drawn, and the item
;;       count shown is the number of distinct @item tags of the spec.feature files; the C4 view
;;       is linked from home and shows the three C4 views of .pi/architecture.md (C4, below), in
;;       order, each with its Mermaid diagram. Writes each
;;       distinct Mermaid diagram to <mmd-dir>; prints the top-level views for the browser check.
;;   bb check.clj dom <view-id> < dumped-dom      headless Chrome's DOM at #<view-id>: that view
;;       is the one shown, no JavaScript error, and its every Mermaid block drawn without error.
(require '[clojure.string :as str] '[clojure.java.io :as io] '[clojure.edn :as edn] '[clojure.java.shell :as sh])
(defn views [html]   ; [[id chunk] …], each view up to the next one or the footer
  (let [starts (re-seq #"<div id=\"([^\"]+)\" class=\"view\">" html)
        idx (map #(str/index-of html (first %)) starts)
        end (or (str/index-of html "<footer") (count html))]
    (map (fn [[_ id] a b] [id (subs html a b)]) starts idx (concat (rest idx) [end]))))
(defn text-of [chunk] (-> chunk (str/replace #"(?s)<div class=\"bar\">.*?</nav>" "") (str/replace #"<[^>]+>" "") str/trim))
(defn unescape [s] (-> s (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&quot;" "\"") (str/replace "&amp;" "&")))
(defn fail [& xs] (binding [*out* *err*] (apply println xs)) (System/exit 1))
(def C4 ["Level 1: System Context" "Level 2: Container" "Level 3: Component (cycle runner)"])   ; .pi/architecture.md
(defn spec-v2 [page]   ; what the page must show, read from .pi/spec directly
  (let [root (str/trim (:out (sh/sh "git" "rev-parse" "--show-toplevel" :dir (.getParent (io/file page)))))
        dir (io/file root ".pi/spec")
        ctxs (sort (for [d (.listFiles dir) :when (.isDirectory (io/file d "allium"))] (.getName d)))
        frags (fn [c] (for [f (.listFiles (io/file dir c "event-model")) :when (str/ends-with? (.getName f) ".edn")] f))
        kws (fn [c] (set (for [f (frags c) :when (not= "handoffs.edn" (.getName f)) x (tree-seq coll? seq (edn/read-string (slurp f))) :when (keyword? x)] x)))
        edges (set (for [p ctxs :let [h (io/file dir p "event-model" "handoffs.edn")] :when (.exists h)
                         e (mapcat (comp keys :events) (vals (edn/read-string (slurp h))))
                         c ctxs :when (and (not= c p) ((kws c) e))]
                     (str p "|" (namespace e) "/" (name e) "|" c)))
        items (set (for [c ctxs :let [f (io/file dir c "spec.feature")] :when (.exists f)
                         l (str/split-lines (slurp f)) :when (str/starts-with? (str/trim l) "@")
                         [_ i] (re-seq #"@item-(\d+-\d+)" l)] i))]
    {:ctxs ctxs :edges edges :items (count items)}))
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
      (let [{:keys [ctxs edges items]} (spec-v2 a)
            home (or (second (first (filter #(= "home" (first %)) vs))) "")
            shown-edges (set (map second (re-seq #"data-edge=\"([^\"]+)\"" home)))
            cards (set (map second (re-seq #"data-context=\"([^\"]+)\"" home)))
            n-items (some-> (re-find #"data-count=\"items\">(\d+)<" home) second parse-long)
            n-ctx (some-> (re-find #"data-count=\"contexts\">(\d+)<" home) second parse-long)]
        (doseq [need (concat ["home" "review" "c4"] (map #(str "ctx-" %) ctxs))] (when-not (some #(= need (first %)) vs) (fail "missing view" need)))
        (when (not= (set ctxs) cards) (fail "context cards" (sort cards) "but .pi/spec has" ctxs))
        (when (not= n-ctx (count ctxs)) (fail "the page counts" n-ctx "contexts, .pi/spec has" (count ctxs)))
        (when (seq (remove shown-edges edges)) (fail "handoff edges missing from the page:" (str/join " " (sort (remove shown-edges edges)))))
        (when (seq (remove edges shown-edges)) (fail "handoff edges the spec does not have:" (str/join " " (sort (remove edges shown-edges)))))
        (when (not= n-items items) (fail "the page counts" n-items "items, the spec.feature files stamp" items))
        (println (str "spec v2: " (count ctxs) " contexts, each with its view and card; " (count edges) " handoff edges drawn; " items " items, as stamped"))
        (let [c4 (or (second (first (filter #(= "c4" (first %)) vs))) "")
              secs (map second (re-seq #"(?s)<section data-c4=\"([^\"]+)\">.*?<pre class=\"mermaid\">" c4))]
          (when-not (str/includes? home "href=\"#c4\"") (fail "home does not link the C4 view"))
          (when (not= C4 secs) (fail "C4 views" (vec secs) "but the page must show" C4))
          (when (not= 3 (count (re-seq #"<pre class=\"mermaid\">" c4))) (fail "the C4 view must hold 3 diagrams"))
          (println (str "C4: " (count secs) " views, each with its diagram: " (str/join "; " secs)))))
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
          drawn (count (re-seq #"<pre class=\"mermaid\"[^>]*data-processed=\"true\"" chunk))
          bad (count (re-seq #"Syntax error in text" chunk))]
      (if (and (= shown a) (nil? err) (= n drawn) (zero? bad))
        (println n)
        (fail (str "chrome #" a ": view shown " shown ", " drawn " of " n " diagrams drawn, " bad " syntax errors, js error: " (or err "-")))))))
