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
;; its longest chain of predecessors. Contexts are bands (swimlanes); the layout below orders
;; them. Unhappy events (the config's :story :unhappy pattern) sit in a sub-row below the happy ones.
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
;; Time runs left to right, one narrow column per rank, so a storm of about fourteen columns fits a
;; desktop page (1180px) at about its natural size; sticky text wraps to four short lines, the
;; trigger under it (who, then which command) to four. Each band has a header row (its name, its slice), its happy rows, then a
;; lighter sub-row of its unhappy events. The orchestrator (the config's :story :orchestrator, the
;; context that only relays) is a thin band at the top. The other bands are ordered so the handoffs
;; between them span the fewest bands (ties keep the order of their first event). A handoff arrow is
;; an orthogonal connector: its vertical runs keep to the gutters between columns and its
;; horizontal runs to a free row or the gap between two bands, so no arrow runs over a sticky.
(def COL 84) (def SW 76) (def SH 60) (def OSH 36) (def X0 10) (def TOP 30) (def BH 24) (def OROWH 44) (def BGAP 8) (def PAD 6)
(def ORCH (some-> (get-in cfg [:story :orchestrator]) name))
(defn- esc [s] (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))
(defn- f [x] (let [r (Math/round (* 10.0 (double x)))] (if (zero? (mod r 10)) (str (quot r 10)) (str (/ r 10.0)))))
(defn- wrap [s n max-lines]
  (let [ls (reduce (fn [ls w] (let [l (peek ls)] (if (and l (<= (+ (count l) 1 (count w)) n)) (conj (pop ls) (str l " " w)) (conj (or ls []) w)))) [] (str/split (str/trim s) #"\s+"))]
    (if (> (count ls) max-lines) (conj (vec (take (dec max-lines) ls)) (let [l (nth ls (dec max-lines))] (str (subs l 0 (min (count l) (- n 1))) "…"))) ls)))
(defn- clip [s n] (if (> (count s) n) (str (subs s 0 (dec n)) "…") s))

(def STORY-DARK "--ss-page:#121715;--ss-lane:#1a211e;--ss-lane2:#161c19;--ss-unrow:#211a12;--ss-line:#2e3833;--ss-ink:#e3e9e5;--ss-muted:#98a59f;--ss-evt:#e58a35;--ss-evt-ink:#1f1002;--ss-un:#4a3115;--ss-un-ink:#fcd8a6;--ss-un-line:#e58a35;--ss-arrow:#b195f0;--ss-chg:#5cc9b0;--ss-chg-ink:#0b1f1a;--ss-new:#f07ab3")
(def STORY-CSS
  (str ".story{--ss-page:#f4f5f2;--ss-lane:#ffffff;--ss-lane2:#f7f8f5;--ss-unrow:#fdf6ec;--ss-line:#d6dcd7;--ss-ink:#1c231f;--ss-muted:#5f6b65;--ss-evt:#f6a04d;--ss-evt-ink:#341b02;--ss-un:#fde3c2;--ss-un-ink:#5c3409;--ss-un-line:#c4741f;--ss-arrow:#6b3fc4;--ss-chg:#0e6b5c;--ss-chg-ink:#ffffff;--ss-new:#b4236b;font-family:'Instrument Sans',system-ui,-apple-system,'Segoe UI',sans-serif}"
       "@media (prefers-color-scheme:dark){:root:not([data-theme=\"light\"]) .story,.story:root{" STORY-DARK "}}"
       ":root[data-theme=\"dark\"] .story{" STORY-DARK "}"
       ".story .bg{fill:var(--ss-page)}.story .lane{fill:var(--ss-lane);stroke:var(--ss-line)}.story .lane.alt{fill:var(--ss-lane2)}.story .unrow{fill:var(--ss-unrow)}"
       ".story .lane-h{paint-order:stroke;stroke:var(--ss-lane);stroke-width:5px;stroke-linejoin:round}.story .lane-h.alt{stroke:var(--ss-lane2)}"
       ".story .lane-t{fill:var(--ss-ink);font-weight:600;font-size:12.5px}.story .lane-s{fill:var(--ss-muted);font-size:11px}.story a:hover .lane-t{text-decoration:underline}"
       ".story .time{fill:var(--ss-muted);font-size:11px;letter-spacing:.06em}.story .tick{stroke:var(--ss-line);opacity:.6}"
       ".story .sk{fill:var(--ss-evt)}.story .sk-t{fill:var(--ss-evt-ink);font-size:11px;font-weight:500}"
       ".story .un .sk{fill:var(--ss-un);stroke:var(--ss-un-line);stroke-dasharray:4 3}.story .un .sk-t{fill:var(--ss-un-ink)}"
       ".story .hint{fill:var(--ss-muted);font-size:10px}.story .arrow{fill:none;stroke:var(--ss-arrow);stroke-width:1.5;opacity:.8}.story .ah{fill:var(--ss-arrow)}"
       ".story .chg{fill:var(--ss-chg)}.story .chg.new{fill:var(--ss-new)}.story .chg-t{fill:var(--ss-chg-ink);font-size:8.5px;font-weight:700;letter-spacing:.03em}"
       ".story .marked .sk{stroke:var(--ss-chg);stroke-width:2.5;stroke-dasharray:none}.story a:hover .sk,.story a:focus .sk{stroke:var(--ss-ink);stroke-width:2}"))

(defn- hint-lines [e]   ; who triggers the event, then which command: up to four lines under the sticky
  (let [ls (concat (some-> (:actor e) (wrap 15 2)) (some-> (:cmd e) (wrap 15 4)))]
    (if (> (count ls) 4) (conj (vec (take 3 ls)) (clip (str (nth ls 3) "…") 15)) ls)))

(defn- perms [xs] (if (empty? xs) [()] (for [x xs p (perms (remove #{x} xs))] (cons x p))))
(defn- band-order
  "The bands, orchestrator first, then the order (of every permutation, for up to seven bands) whose
  cross-band order pairs span the fewest bands; `groups` comes in first-event order, which breaks ties."
  [groups edges]
  (let [orch (filter #(= ORCH (first %)) groups) others (remove #(= ORCH (first %)) groups)
        cx (fn [k] (:ctx (SE-BY k)))
        xe (for [[a b] edges :when (not= (cx a) (cx b))] [(cx a) (cx b)])
        cost (fn [order] (let [ix (zipmap (concat (map first orch) order) (range))] (reduce + (for [[a b] xe] (Math/abs (long (- (ix a) (ix b))))))))
        best (if (<= (count others) 7) (first (sort-by cost (perms (map first others)))) (map first others))
        by (into {} others)]
    (concat orch (for [c best] [c (by c)]))))

(defn- rounded [pts]   ; an orthogonal polyline as a path with rounded corners
  (let [pts (reduce (fn [v p] (if (= p (peek v)) v (conj v p))) [] pts)
        sg (fn [v] (cond (pos? v) 1 (neg? v) -1 :else 0))]
    (str "M" (f (ffirst pts)) "," (f (second (first pts)))
         (str/join (for [[[ax ay] [bx by] [cx cy]] (partition 3 1 pts)
                         :let [k (min 6 (/ (+ (Math/abs (double (- bx ax))) (Math/abs (double (- by ay)))) 2) (/ (+ (Math/abs (double (- cx bx))) (Math/abs (double (- cy by)))) 2))]]
                     (str " L" (f (- bx (* k (sg (- bx ax))))) "," (f (- by (* k (sg (- by ay)))))
                          " Q" (f bx) "," (f by) " " (f (+ bx (* k (sg (- cx bx))))) "," (f (+ by (* k (sg (- cy by))))))))
         " L" (f (first (peek pts))) "," (f (second (peek pts))))))

(defn story-svg
  "The event storm as one SVG string. `standalone?`: with the XML namespace and a page background,
  for dist/story.svg."
  [standalone?]
  (let [{:keys [rank edges]} ORDER
        ncol (inc (apply max 0 (vals rank)))
        first-col (fn [[c es]] [(apply min (map (comp rank :key) (or (seq (remove unhappy? es)) es))) (.indexOf (mapv :id CTXS) c)])
        lanes (band-order (sort-by first-col (group-by :ctx SE)) edges)
        ;; rows inside a band: happy events first, then the unhappy sub-row(s), stacked per column
        placed (for [[c es] lanes]
                 (let [slots (reduce (fn [m e] (update m [(rank (:key e)) (unhappy? e)] (fnil conj []) e)) {} (sort-by (juxt unhappy? :name) es))
                       happy (apply max 1 (for [[[_ u] v] slots :when (not u)] (count v)))
                       un (apply max 0 (for [[[_ u] v] slots :when u] (count v)))
                       orch? (= c ORCH)]
                   {:ctx c :orch? orch? :happy happy :un un
                    :rh (if orch? OROWH (+ SH 18 (* 11.5 (apply max 0 (map (comp count hint-lines) es))))) :sh (if orch? OSH SH)
                    :at (into {} (for [[[col u] v] slots [i e] (map-indexed vector v)] [(:key e) [col (if u (+ happy i) i)]]))}))
        band-h (fn [l] (+ BH (* (:rh l) (+ (:happy l) (:un l))) PAD))
        ys (vec (reductions + TOP (map #(+ (band-h %) BGAP) placed)))
        W (+ (* 2 X0) (* COL ncol)) H (+ (peek ys) (- BGAP) 6)
        band (into {} (for [[l y0] (map vector placed ys)] [(:ctx l) (assoc l :y0 y0 :y1 (+ y0 (band-h l)))]))
        pos (into {} (for [[l y0] (map vector placed ys) [k [col row]] (:at l)]
                       [k {:col col :row row :ctx (:ctx l) :sh (:sh l) :x (+ X0 (* col COL) (/ (- COL SW) 2.0)) :y (+ y0 BH (* row (:rh l)))}]))
        occ (set (for [[_ {:keys [ctx row col]}] pos] [ctx row col]))
        gx (fn [c] (+ X0 (* c COL)))   ; the gutter left of column c
        ctxs (into {} (map (juxt :id :c) CTXS))
        ;; handoff routes: each a list of points, with the gutters its vertical runs use
        routes (for [[a b] edges :let [A (pos a) B (pos b)] :when (and A B (not= (:ctx A) (:ctx B)))]
                 (let [c1 (:col A) c2 (:col B) ym1 (+ (:y A) (/ (:sh A) 2.0)) ym2 (+ (:y B) (/ (:sh B) 2.0))
                       S [(+ (:x A) SW) ym1] T [(dec (:x B)) ym2]
                       between (range (inc c1) c2)
                       free? (fn [{:keys [ctx row]}] (not-any? #(occ [ctx row %]) between))]
                   (cond
                     (or (empty? between) (free? A)) {:a a :b b :pts [S [:g2 ym1] [:g2 ym2] T] :g2 [(gx c2) [:t b]]}
                     (free? B) {:a a :b b :pts [S [:g1 ym1] [:g1 ym2] T] :g1 [(gx (inc c1)) [:s a]]}
                     :else (let [bb (band (:ctx B)) yc (if (> (:y0 bb) (:y A)) (- (:y0 bb) (/ BGAP 2.0)) (+ (:y1 bb) (/ BGAP 2.0)))]
                             {:a a :b b :pts [S [:g1 ym1] [:g1 yc] [:g2 yc] [:g2 ym2] T] :g1 [(gx (inc c1)) [:s a]] :g2 [(gx c2) [:t b]]}))))
        ;; several arrows in one gutter, from different sources or to different targets, sit apart
        owners (reduce (fn [m [g o]] (update m g #(if (some #{o} %) % (conj (or % []) o)))) {} (mapcat (juxt :g1 :g2) routes))
        shift (fn [[g o]] (+ g (nth [0 -3 3 -1.5 1.5] (mod (.indexOf ^java.util.List (owners g) o) 5))))]
    (str "<svg" (when standalone? " xmlns=\"http://www.w3.org/2000/svg\"") " class=\"story\" viewBox=\"0 0 " W " " (f H) "\" width=\"" W "\" height=\"" (f H) "\" style=\"--story-w:" W "px\" role=\"img\" aria-labelledby=\"story-title\">"
         "<title id=\"story-title\">" (esc (str (:title world) ": the event storm, " (count SE) " domain events in time order across " (count lanes) " bounded contexts")) "</title>"
         "<style>" STORY-CSS "</style>"
         "<defs><marker id=\"story-ah\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"6\" markerHeight=\"6\" orient=\"auto\"><path class=\"ah\" d=\"M0,0 L10,5 L0,10 z\"/></marker></defs>"
         (when standalone? (str "<rect class=\"bg\" x=\"0\" y=\"0\" width=\"" W "\" height=\"" (f H) "\"/>"))
         "<text class=\"time\" x=\"" X0 "\" y=\"20\">TIME →</text>"
         (str/join (for [i (range 1 ncol)] (str "<line class=\"tick\" x1=\"" (gx i) "\" y1=\"" (- TOP 4) "\" x2=\"" (gx i) "\" y2=\"" (f (- H 4)) "\" stroke-dasharray=\"2 4\"/>")))
         (str/join
           (for [[i l] (map-indexed vector placed) :let [{:keys [y0 y1]} (band (:ctx l))]]
             (str "<a href=\"#ctx-" (esc (:ctx l)) "\" data-lane=\"" (esc (:ctx l)) "\"" (when (:orch? l) " data-orchestrator=\"true\"") ">"
                  "<rect class=\"lane" (when (odd? i) " alt") "\" x=\"4\" y=\"" (f y0) "\" width=\"" (- W 8) "\" height=\"" (f (- y1 y0)) "\" rx=\"8\"/>"
                  (when (pos? (:un l)) (str "<rect class=\"unrow\" x=\"5\" y=\"" (f (+ y0 BH (* (:happy l) (:rh l)) -5)) "\" width=\"" (- W 10) "\" height=\"" (f (+ (* (:un l) (:rh l)) PAD 4)) "\" rx=\"6\"/>"))
                  "</a>")))
         (str/join
           (for [{:keys [a b pts g1 g2]} routes]
             (str "<path class=\"arrow\" data-arrow=\"" (esc a) "|" (esc b) "\" d=\""
                  (rounded (for [[x y] pts] [(case x :g1 (shift g1) :g2 (shift g2) x) y])) "\" marker-end=\"url(#story-ah)\"/>")))
         (str/join   ; band headers above the arrows, haloed, so an arrow passing a header leaves it legible
           (for [[i l] (map-indexed vector placed) :let [c (ctxs (:ctx l)) y0 (:y0 (band (:ctx l)))]]
             (str "<a href=\"#ctx-" (esc (:ctx l)) "\"><text class=\"lane-h" (when (odd? i) " alt") "\" x=\"" (+ X0 2) "\" y=\"" (f (+ y0 16)) "\">"
                  "<tspan class=\"lane-t\">" (esc (human (:ctx l))) "</tspan>"
                  (when (:accepted c) (str "<tspan class=\"lane-s\"> · slice " (esc (:n c)) " · open its event model</tspan>"))
                  "</text></a>")))
         (str/join
           (for [e SE :let [{:keys [x y sh ctx]} (pos (:key e)) orch? (= ctx ORCH)
                            lines (if orch? (wrap (:name e) 12 2) (wrap (:name e) 12 4)) m (:mark e)
                            hint (when-not orch? (hint-lines e))
                            cx (+ x (/ SW 2.0))]]
             (str "<a href=\"" (esc (:href e)) "\" class=\"" (str/join " " (remove nil? ["ev" (when (unhappy? e) "un") (when m "marked")])) "\" data-event=\"" (esc (:key e)) "\"" (when m (str " data-change=\"" (name m) "\"")) ">"
                  "<title>" (esc (str (:name e) (when (:desc e) (str ": " (:desc e))) (let [t (str/join " · " (remove nil? [(:actor e) (:cmd e)]))] (when (seq t) (str " (" t ")"))))) "</title>"
                  "<rect class=\"sk\" x=\"" (f x) "\" y=\"" (f y) "\" width=\"" SW "\" height=\"" sh "\" rx=\"2\"/>"
                  (str/join (map-indexed (fn [j t] (str "<text class=\"sk-t\" x=\"" (f cx) "\" y=\"" (f (+ y (/ sh 2.0) (- (* 6.25 (dec (count lines)))) 4 (* j 12.5))) "\" text-anchor=\"middle\">" (esc t) "</text>")) lines))
                  (str/join (map-indexed (fn [j t] (str "<text class=\"hint\" x=\"" (f cx) "\" y=\"" (f (+ y sh 13 (* j 11.5))) "\" text-anchor=\"middle\">" (esc t) "</text>")) hint))
                  (when m (str "<rect class=\"chg " (name m) "\" x=\"" (f (+ x SW -46)) "\" y=\"" (f (- y 7)) "\" width=\"46\" height=\"14\" rx=\"7\"/><text class=\"chg-t\" x=\"" (f (+ x SW -23)) "\" y=\"" (f (+ y 3)) "\" text-anchor=\"middle\">" (if (= m :new) "NEW" "CHANGED") "</text>"))
                  "</a>")))
         "</svg>")))

(def UNORDERED   ; events no flow, handoff or :after pair places after another (bar the first)
  (let [has-pred (set (map second (:edges ORDER)))] (vec (sort (remove has-pred (map :key SE))))))
(println "story: unordered (no predecessor):" (str/join " " UNORDERED))
(println "story:" (count SE) "events," (inc (apply max 0 (vals (:rank ORDER)))) "columns," (count (:edges ORDER)) "order pairs")
