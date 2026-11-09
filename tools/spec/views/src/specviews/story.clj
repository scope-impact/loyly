;; L0: the event storm, one time-ordered board of every context's domain events, drawn as a
;; static SVG at build time. Loaded by render.clj after the contexts; defines `story-svg`.
;;
;; The order is derived from the event models, never written here:
;;   1. each flow's events in step order (event a, then event b);
;;   2. the config's :story :after pairs, the order the event models do not hold (an orchestrator
;;      that reads stores instead of consuming events);
;;   3. a handoff: an event of one context, folded by a read model of another that folds only
;;      handoffs, comes before the events that context's commands reading that read model
;;      produce; and an event a policy listens to comes before the events of the commands it
;;      issues.
;; 1 and 2 are added first; each pair of 3 only when it closes no loop. Each event's column is
;; its longest chain of predecessors. Contexts are bands (swimlanes), ordered by their first
;; event. Unhappy events (the config's :story :unhappy pattern) sit below the happy ones.
(require '[clojure.string :as str] '[clojure.set :as set])

(def SE (vec (mapcat #(in-ctx (:id %) 'story-events) CTXS)))   ; [{:key :ctx :name …} …]
(def SE-BY (into {} (map (juxt :key identity) SE)))

(defn- flow-pairs []
  (for [c (:contexts world) [_ a] (:areas c) [_ f] (:flows a)
        :let [evs (distinct (for [[ak an zk zn] (:steps f) [k n] [[ak an] [zk zn]] :when (and (= k "event") (SE-BY n))] n))]
        [x y] (partition 2 1 evs)] [x y]))
(defn- cfg-pairs [] (for [[a b] (get-in cfg [:story :after]) :let [a (str (symbol a)) b (str (symbol b))] :when (and (SE-BY a) (SE-BY b))] [a b]))
(defn- handoff-pairs []
  (let [blocks (for [c (:contexts world) [_ a] (:areas c)] a)
        produced (fn [cmd] (for [a blocks [ck c] (:commands a) :when (= ck cmd) e (:produces c)] e))]
    (concat
      (for [a blocks [rk r] (:read-models a) :when (every? #(not= (ns-of %) (ns-of rk)) (:consumes r))   ; a read model folding only handoffs
            e (:consumes r) :when (SE-BY e)
            [ck c] (:commands a) :when (some #{rk} (:reads c)) f (:produces c) :when (SE-BY f)] [e f])
      (for [a blocks k [:todo-processors] [_ p] (get a k) e (:subscribes p) :when (SE-BY e) cmd (:produces p) f (produced cmd) :when (SE-BY f)] [e f]))))

(defn- reaches? [g a b]   ; is b reachable from a?
  (loop [seen #{} todo [a]]
    (if-let [x (peek todo)]
      (cond (= x b) true (seen x) (recur seen (pop todo)) :else (recur (conj seen x) (into (pop todo) (g x))))
      false)))
(def ORDER
  (let [add (fn [[g used] [a b]] (if (or (= a b) (some #{b} (g a)) (reaches? g b a)) [g used] [(update g a (fnil conj []) b) (conj used [a b])]))
        [g used] (reduce add [{} []] (distinct (concat (flow-pairs) (cfg-pairs))))
        [g used] (reduce add [g used] (distinct (handoff-pairs)))
        preds (reduce (fn [m [a b]] (update m b (fnil conj #{}) a)) {} used)
        rank (memoize (fn rank [k] (if-let [ps (seq (preds k))] (inc (apply max (map rank ps))) 0)))]
    {:rank (into {} (for [e SE] [(:key e) (rank (:key e))])) :edges used}))

(def UNHAPPY (re-pattern (get-in cfg [:story :unhappy] "$^")))
(defn unhappy? [e] (boolean (re-find UNHAPPY (short (:key e)))))

;; ---------- layout ----------
(def COL 156) (def SW 132) (def SH 66) (def HEAD 176) (def TOP 44) (def ROWH 104) (def PAD 14)
(defn- esc [s] (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))
(defn- wrap [s n max-lines]
  (let [ls (reduce (fn [ls w] (let [l (peek ls)] (if (and l (<= (+ (count l) 1 (count w)) n)) (conj (pop ls) (str l " " w)) (conj (or ls []) w)))) [] (str/split s #"\s+"))]
    (if (> (count ls) max-lines) (conj (vec (take (dec max-lines) ls)) (let [l (nth ls (dec max-lines))] (str (subs l 0 (min (count l) (- n 1))) "…"))) ls)))
(defn- clip [s n] (if (> (count s) n) (str (subs s 0 (dec n)) "…") s))

(def STORY-CSS
  (str ".story{--ss-page:#f4f5f2;--ss-lane:#ffffff;--ss-lane2:#f7f8f5;--ss-line:#d6dcd7;--ss-ink:#1c231f;--ss-muted:#5f6b65;--ss-evt:#f6a04d;--ss-evt-ink:#341b02;--ss-un:#fde3c2;--ss-un-ink:#5c3409;--ss-un-line:#c4741f;--ss-arrow:#6b3fc4;--ss-chg:#0e6b5c;--ss-chg-ink:#ffffff;--ss-new:#b4236b;font-family:'Instrument Sans',system-ui,-apple-system,'Segoe UI',sans-serif}"
       "@media (prefers-color-scheme:dark){:root:not([data-theme=\"light\"]) .story,.story:root{--ss-page:#121715;--ss-lane:#1a211e;--ss-lane2:#161c19;--ss-line:#2e3833;--ss-ink:#e3e9e5;--ss-muted:#98a59f;--ss-evt:#e58a35;--ss-evt-ink:#1f1002;--ss-un:#4a3115;--ss-un-ink:#fcd8a6;--ss-un-line:#e58a35;--ss-arrow:#b195f0;--ss-chg:#5cc9b0;--ss-chg-ink:#0b1f1a;--ss-new:#f07ab3}}"
       ":root[data-theme=\"dark\"] .story{--ss-page:#121715;--ss-lane:#1a211e;--ss-lane2:#161c19;--ss-line:#2e3833;--ss-ink:#e3e9e5;--ss-muted:#98a59f;--ss-evt:#e58a35;--ss-evt-ink:#1f1002;--ss-un:#4a3115;--ss-un-ink:#fcd8a6;--ss-un-line:#e58a35;--ss-arrow:#b195f0;--ss-chg:#5cc9b0;--ss-chg-ink:#0b1f1a;--ss-new:#f07ab3}"
       ".story .bg{fill:var(--ss-page)}.story .lane{fill:var(--ss-lane);stroke:var(--ss-line)}.story .lane.alt{fill:var(--ss-lane2)}"
       ".story .lane-t{fill:var(--ss-ink);font-weight:600;font-size:14px}.story .lane-s{fill:var(--ss-muted);font-size:11px}"
       ".story .time{fill:var(--ss-muted);font-size:11px;letter-spacing:.06em}.story .tick{stroke:var(--ss-line)}"
       ".story .sk{fill:var(--ss-evt)}.story .sk-t{fill:var(--ss-evt-ink);font-size:12.5px;font-weight:500}"
       ".story .un .sk{fill:var(--ss-un);stroke:var(--ss-un-line);stroke-dasharray:4 3}.story .un .sk-t{fill:var(--ss-un-ink);font-size:12px}"
       ".story .hint{fill:var(--ss-muted);font-size:10.5px}.story .arrow{fill:none;stroke:var(--ss-arrow);stroke-width:1.6;opacity:.75}.story .ah{fill:var(--ss-arrow)}"
       ".story .chg{fill:var(--ss-chg)}.story .chg.new{fill:var(--ss-new)}.story .chg-t{fill:var(--ss-chg-ink);font-size:9.5px;font-weight:700;letter-spacing:.04em}"
       ".story .marked .sk{stroke:var(--ss-chg);stroke-width:2.5;stroke-dasharray:none}.story a:hover .sk,.story a:focus .sk{stroke:var(--ss-ink);stroke-width:2}"))

(defn story-svg
  "The event storm as one SVG string. `standalone?`: with the XML namespace and a page background,
  for dist/story.svg."
  [standalone?]
  (let [{:keys [rank edges]} ORDER
        ncol (inc (apply max 0 (vals rank)))
        lanes (->> (group-by :ctx SE) (sort-by (fn [[c es]] [(apply min (map (comp rank :key) (or (seq (remove unhappy? es)) es))) (.indexOf (mapv :id CTXS) c)])))
        ;; rows inside a lane: happy events first, then unhappy, stacked per column
        placed (for [[c es] lanes]
                 (let [slots (reduce (fn [m e] (let [col (rank (:key e)) k [col (unhappy? e)]] (update m k (fnil conj []) e))) {} (sort-by (juxt unhappy? :name) es))
                       happy-rows (apply max 1 (for [[[_ u] v] slots :when (not u)] (count v)))
                       un-rows (apply max 0 (for [[[_ u] v] slots :when u] (count v)))]
                   {:ctx c :rows (+ happy-rows un-rows)
                    :at (into {} (for [[[col u] v] slots [i e] (map-indexed vector v)] [(:key e) [col (if u (+ happy-rows i) i)]]))}))
        ys (reductions + TOP (map #(+ (* ROWH (:rows %)) (* 2 PAD)) placed))
        W (+ HEAD (* COL ncol) PAD) H (+ (last ys) PAD)
        pos (into {} (for [[l y0] (map vector placed ys) [k [col row]] (:at l)]
                       [k [(+ HEAD (* col COL) (/ (- COL SW) 2)) (+ y0 PAD (* row ROWH))]]))
        ctxs (into {} (map (juxt :id :c) CTXS))]
    (str "<svg" (when standalone? " xmlns=\"http://www.w3.org/2000/svg\"") " class=\"story\" viewBox=\"0 0 " W " " H "\" width=\"" W "\" height=\"" H "\" role=\"img\" aria-labelledby=\"story-title\">"
         "<title id=\"story-title\">" (esc (str (:title world) ": the event storm, " (count SE) " domain events in time order across " (count lanes) " bounded contexts")) "</title>"
         "<style>" STORY-CSS "</style>"
         "<defs><marker id=\"story-ah\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"6\" markerHeight=\"6\" orient=\"auto\"><path class=\"ah\" d=\"M0,0 L10,5 L0,10 z\"/></marker></defs>"
         (when standalone? (str "<rect class=\"bg\" x=\"0\" y=\"0\" width=\"" W "\" height=\"" H "\"/>"))
         "<text class=\"time\" x=\"" HEAD "\" y=\"22\">TIME →</text>"
         (str/join (for [i (range ncol)] (str "<line class=\"tick\" x1=\"" (+ HEAD (* i COL)) "\" y1=\"30\" x2=\"" (+ HEAD (* i COL)) "\" y2=\"" (- H PAD) "\" stroke-dasharray=\"2 4\"/>")))
         (str/join
           (for [[i [l y0]] (map-indexed vector (map vector placed ys)) :let [c (ctxs (:ctx l)) h (+ (* ROWH (:rows l)) (* 2 PAD) -6)]]
             (str "<a href=\"#ctx-" (esc (:ctx l)) "\" data-lane=\"" (esc (:ctx l)) "\">"
                  "<rect class=\"lane" (when (odd? i) " alt") "\" x=\"4\" y=\"" y0 "\" width=\"" (- W 8) "\" height=\"" h "\" rx=\"8\"/>"
                  (str/join (map-indexed (fn [j t] (str "<text class=\"lane-t\" x=\"18\" y=\"" (+ y0 26 (* j 17)) "\">" (esc t) "</text>")) (wrap (human (:ctx l)) 17 2)))
                  (when (:accepted c) (str "<text class=\"lane-s\" x=\"18\" y=\"" (+ y0 (if (> (count (wrap (human (:ctx l)) 17 2)) 1) 61 44)) "\">slice " (esc (:n c)) " · open its event model</text>"))
                  "</a>")))
         (str/join
           (for [[a b] edges :let [[x1 y1] (pos a) [x2 y2] (pos b)] :when (and x1 x2 (not= (:ctx (SE-BY a)) (:ctx (SE-BY b))))]
             (let [sx (+ x1 SW) sy (+ y1 (/ SH 2)) ex x2 ey (+ y2 (/ SH 2)) dx (max 30 (/ (- ex sx) 2))]
               (str "<path class=\"arrow\" data-arrow=\"" (esc a) "|" (esc b) "\" d=\"M" sx "," sy " C" (+ sx dx) "," sy " " (- ex dx) "," ey " " (- ex 3) "," ey "\" marker-end=\"url(#story-ah)\"/>"))))
         (str/join
           (for [e SE :let [[x y] (pos (:key e)) lines (wrap (:name e) 18 3) m (:mark e)
                            hint (clip (str/join " · " (remove nil? [(:actor e) (:cmd e)])) 26)]]
             (str "<a href=\"" (esc (:href e)) "\" class=\"" (str/join " " (remove nil? ["ev" (when (unhappy? e) "un") (when m "marked")])) "\" data-event=\"" (esc (:key e)) "\"" (when m (str " data-change=\"" (name m) "\"")) ">"
                  "<title>" (esc (str (:name e) (when (:desc e) (str ": " (:desc e))))) "</title>"
                  "<rect class=\"sk\" x=\"" x "\" y=\"" y "\" width=\"" SW "\" height=\"" SH "\" rx=\"2\"/>"
                  (str/join (map-indexed (fn [j t] (str "<text class=\"sk-t\" x=\"" (+ x (/ SW 2)) "\" y=\"" (+ y (- (/ SH 2) (* 7.5 (dec (count lines)))) 4 (* j 15)) "\" text-anchor=\"middle\">" (esc t) "</text>")) lines))
                  (when (seq hint) (str "<text class=\"hint\" x=\"" (+ x (/ SW 2)) "\" y=\"" (+ y SH 15) "\" text-anchor=\"middle\">" (esc hint) "</text>"))
                  (when m (str "<rect class=\"chg " (name m) "\" x=\"" (+ x SW -46) "\" y=\"" (- y 8) "\" width=\"52\" height=\"16\" rx=\"8\"/><text class=\"chg-t\" x=\"" (+ x SW -20) "\" y=\"" (+ y 3.5) "\" text-anchor=\"middle\">" (if (= m :new) "NEW" "CHANGED") "</text>"))
                  "</a>")))
         "</svg>")))

(def UNORDERED   ; events no flow, handoff or :after pair places after another (bar the first)
  (let [has-pred (set (map second (:edges ORDER)))] (vec (sort (remove has-pred (map :key SE))))))
(println "story: unordered (no predecessor):" (str/join " " UNORDERED))
(println "story:" (count SE) "events," (inc (apply max 0 (vals (:rank ORDER)))) "columns," (count (:edges ORDER)) "order pairs")
