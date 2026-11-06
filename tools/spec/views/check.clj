;; Check the built page the way a reader sees it (called by check.sh).
;;   bb check.clj static <page.html> <mmd-dir>   every view is there and not empty, ids are unique,
;;       every internal #link names an id, no Clojure value leaked into the text; writes each
;;       distinct Mermaid diagram to <mmd-dir>; prints the top-level views for the browser check.
;;   bb check.clj dom <view-id> < dumped-dom      headless Chrome's DOM at #<view-id>: that view
;;       is the one shown, no JavaScript error, and its every Mermaid block drawn without error.
(require '[clojure.string :as str] '[clojure.java.io :as io])
(defn views [html]   ; [[id chunk] …], each view up to the next one or the footer
  (let [starts (re-seq #"<div id=\"([^\"]+)\" class=\"view\">" html)
        idx (map #(str/index-of html (first %)) starts)
        end (or (str/index-of html "<footer") (count html))]
    (map (fn [[_ id] a b] [id (subs html a b)]) starts idx (concat (rest idx) [end]))))
(defn text-of [chunk] (-> chunk (str/replace #"(?s)<div class=\"bar\">.*?</nav>" "") (str/replace #"<[^>]+>" "") str/trim))
(defn unescape [s] (-> s (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&quot;" "\"") (str/replace "&amp;" "&")))
(defn fail [& xs] (binding [*out* *err*] (apply println xs)) (System/exit 1))
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
      (doseq [need ["home" "ctx" "review"]] (when-not (some #(= need (first %)) vs) (fail "missing view" need)))
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
