;; Shared by every view of the event-model page: names, stickies, the page frame, Mermaid.
;; render.clj loads it into its own namespace and into each context's (see context.clj); the
;; loader defines `app?` and `threads` first.
(require '[clojure.string :as str])

;; ---------- names ----------
(defn short [k] (last (str/split (str k) #"/")))
(defn ns-of [k] (when (str/includes? (str k) "/") (first (str/split (str k) #"/"))))
(defn last-seg [s] (last (str/split (str s) #"[./]")))
(def WORDS {"orc" "ORC" "pi" "Pi" "rpc" "RPC" "json" "JSON" "id" "id" "gepa" "GEPA" "otel" "OTel" "vm" "VM" "ui" "UI"})
(defn human [n]
  (let [w (->> (str/split (str/replace (last-seg n) #"([a-z0-9])([A-Z])" "$1 $2") #"[-_ ]+")
               (remove str/blank?) (map #(or (WORDS (str/lower-case %)) (str/lower-case %))))]
    (if (empty? w) ""
        (str (if (WORDS (str/lower-case (first w))) (first w) (str/capitalize (first w)))
             (when (next w) (str " " (str/join " " (rest w))))))))
(defn kebab [n] (str/lower-case (str/replace (str n) #"([a-z0-9])([A-Z])" "$1-$2")))
(defn snake [n] (str/lower-case (str/replace (str n) #"([a-z0-9])([A-Z])" "$1_$2")))
(defn plural [n w] (str n " " w (when (not= n 1) "s")))
(defn ordered-groups [f xs] (for [k (distinct (map f xs))] [k (filter #(= k (f %)) xs)]))

;; ---------- components ----------
(defn st [kind lbl & [sub href]]
  (let [inner (list lbl (when (seq sub) [:small sub]))]
    (if href [:a {:class ["st" kind] :href (str "#" href)} inner] [:span {:class ["st" kind]} inner])))
(defn kw-class [w] (get {"Given" "given" "When" "when" "Then" "then"} w "and"))
(defn tier-chip [tier] (when tier [:span {:class ["tier" tier] :title "build tier"} tier]))
(defn muted [& xs] [:span.muted xs])
(defn code [x] [:code x])
(defn review
  "App mode only: buttons the MCP app wires by data attribute, and the open-thread badge of `target`."
  [target & actions]
  (when app?
    [:span.review (for [a actions] [:button.rv {:type "button" :data-review-action a :data-review-target target} (str/capitalize a)])
     [:span.threads {:data-threads-for target} (get threads target)]]))

;; ---------- Mermaid ----------
(defn mq [t] (-> (str t) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "#quot;")))
(defn nid [t] (str "n_" (str/replace (str t) #"[^A-Za-z0-9]" "_")))
(defn mm-block [src note]
  (list [:div.mm [:button.size {:type "button" :aria-pressed "false"} "Actual size"] [:pre.mermaid src]]
        (when note [:p.c4-note note])))

;; ---------- page frame ----------
(def legend
  [:div.legend (for [[c t] [["--scr" "screen / actor"] ["--cmd" "command"] ["--evt" "event"] ["--fail" "refusal (requires)"] ["--rm" "read model / query"] ["--proc" "policy / timer"]]]
                 [:span [:i {:style {:background (str "var(" c ")")}}] t])])
(defn crumbs [parts]
  [:nav.crumbs {:aria-label "Where you are"}
   (concat (for [[t h] (butlast parts)] (list [:a {:href (str "#" h)} t] [:span.sep "›"]))
           [[:span.here {:aria-current "page"} (first (last parts))]])])
(defn view [id parts & body]
  [:div.view {:id id}
   [:div.bar [:div.left (crumbs parts) [:a.toplink {:href "#review"} "Review"]] legend]
   [:main body]])
(defn stats [& xs] [:div.stats (for [x xs :when x] [:span x])])
(defn n-of [n w] (list [:b n] " " w))
(defn qlist [xs] [:ul.qlist (for [x xs] [:li x])])
(defn none [& xs] [:div.none xs])
(def home-crumb ["Löyly spec v2" "home"])
