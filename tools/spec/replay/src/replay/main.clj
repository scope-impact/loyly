(ns replay.main
  "Build the replay page of one harness run from its Grain stores.
     bb -m replay.main <run-dir> <out.html> [--title <t>] [--spec <spec-root>]
   Reads <run-dir>/runner-store.sqlite (the cycle runner's store, ORC's workflow events in it)
   and every <run-dir>/runs/<run-id>/stores/<context>.sqlite (or runs/<run-id>/<context>.sqlite),
   with sqlite3, from a copy (the run dir is never touched); decodes
   each event body (Fressian) with replay.fressian. Groups the events into stickies (a domain
   event with the ORC tick that produced it and the Grain bookkeeping that committed it), maps
   the stages of the cycle onto them, scrubs the payloads for a public page, and writes one
   self-contained HTML file (page.css of tools/spec/views, page.js here)."
  (:require [replay.fressian :as fr] [clojure.string :as str] [clojure.edn :as edn]
            [clojure.java.io :as io] [babashka.fs :as fs] [babashka.process :as p]
            [cheshire.core :as json] [replicant.string :as r]))

;; ---------- the stage story: which owner event marks each stage ----------
(def STAGES
  [{:id "stamp" :name "Stamp" :types #{:specification/scenario-stamped}}
   {:id "pin" :name "Pin" :types #{:cycle-preparation/source-cut-pinned}}
   {:id "checklist" :name "Checklist" :types #{:measurement/checklist-derived}}
   {:id "self-check" :name "Self-check" :types #{:regeneration-cycle/harness-self-check-passed :regeneration-cycle/harness-self-check-failed}}
   {:id "fence" :name "Fence" :types #{:regeneration-cycle/clean-room-proved}}
   {:id "regenerate" :name "Regenerate" :types #{:regeneration-cycle/candidate-regenerated}}
   {:id "install" :name "Install" :types #{:regeneration-cycle/candidate-installed-in-gate-room}}
   {:id "gate" :name "Gate" :types #{:regeneration-cycle/gate-attempt-passed :regeneration-cycle/gate-attempt-failed :regeneration-cycle/gate-attempt-refused}}
   {:id "score" :name "Score" :types #{:measurement/item-verdicts-filed :measurement/measurement-filed :measurement/score-stage-failed}}
   {:id "scoreboard" :name "Scoreboard" :types #{:measurement/scoreboard-reported}}])
(def OUTCOME #{:regeneration-cycle/cycle-passed :regeneration-cycle/cycle-failed :cycle-runner/run-concluded :cycle-runner/run-halted})
(def RUNNER "cycle-runner")
(def TRIM 1200)   ; longest string kept whole in a payload

;; ---------- reading the stores ----------
(defn sh-out [& args] (let [r (apply p/sh args)] (when-not (zero? (:exit r)) (throw (ex-info (str "failed: " (str/join " " args) "\n" (:err r)) {}))) (:out r)))

(defn read-store [lane file]
  (let [sep "\u001f"
        q (str "select id, time, type, hex(data) from events order by time, id")
        ;; a WAL store is read from a copy (with its -wal and -shm, which sqlite may fold in), so the run dir is never touched
        tmp (fs/create-temp-dir {:prefix "replay-"})
        copy (fs/path tmp (fs/file-name file))
        _ (doseq [ext ["" "-wal" "-shm"] :let [f (str file ext)] :when (fs/exists? f)]
            (fs/copy f (str copy ext)))
        out (try (sh-out "sqlite3" "-separator" sep "-newline" "\u001e" (str copy) q)
                 (finally (fs/delete-tree tmp)))]
    (vec (for [row (str/split out #"\u001e") :when (not (str/blank? row))
               :let [[id t ty h] (str/split row (re-pattern sep))]]
           {:id id :time t :ms (.toEpochMilli (java.time.Instant/parse t)) :type (keyword (subs ty 1))
            :lane lane :d (fr/decode h) :bytes (quot (count h) 2)}))))

(defn find-stores [run-dir]
  (let [runner (fs/path run-dir "runner-store.sqlite")
        ;; the context stores sit in runs/<run-id>/stores/ or directly in runs/<run-id>/
        runs (sort-by #(fs/last-modified-time %)
                      (concat (fs/glob run-dir "runs/*/stores")
                              (filter #(seq (fs/glob % "*.sqlite")) (fs/glob run-dir "runs/*"))))
        dir (last runs)]
    (when-not (fs/exists? runner) (throw (ex-info (str "no runner store at " runner) {})))
    (when (> (count runs) 1) (binding [*out* *err*] (println "note: several runs/*/stores; using the newest," (str dir))))
    (into [[RUNNER runner]]
          (for [f (when dir (sort (fs/glob dir "*.sqlite")))] [(str/replace (fs/file-name f) #"\.sqlite$" "") f]))))

;; ---------- the event model: descriptions, and which command produces each event ----------
(defn event-model [spec-root]
  (into {}
        (for [d (when (fs/exists? spec-root) (fs/list-dir spec-root)) :when (fs/directory? (fs/path d "event-model"))
              f (fs/glob (fs/path d "event-model") "*.edn")
              [_ area] (edn/read-string (slurp (str f)))
              :let [cmds (:commands area)]
              [k ev] (:events area)]
          [k {:desc (:description ev)
              :cmd (some (fn [[ck c]] (when (contains? (set (:produces c)) k) ck)) cmds)}])))

;; ---------- scrubbing: the page is public ----------
(def SECRET-KEY #"(?i)(^|[-_])(token|secret|password|passwd|credentials?|api-?key|authorization|cookie|env|environment|transcript)s?($|[-_])")
(def TOKEN #"\b(sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+)")
(def HOST-PATH #"(?:/Users|/private|/tmp|/var/folders|/home|/Volumes)/[^\s\"'`,;()\[\]{}<>]*")

(defn scrubber [run-dir repo]
  (let [stats (atom {:paths 0 :keys #{} :tokens 0 :trimmed 0})
        base (str (fs/file-name run-dir))
        repo (str repo "/")
        path (fn [m]
               (swap! stats update :paths inc)
               (let [i (str/index-of m (str "/" base "/")) j (str/ends-with? m (str "/" base))]
                 (cond i (str "<run>/" (subs m (+ i (count base) 2)))
                       j "<run>"
                       (str/starts-with? m repo) (str "<repo>/" (subs m (count repo)))
                       :else (str "<host>/" (fs/file-name m)))))
        s (fn [x] (let [x (str/replace x HOST-PATH path)
                        x (str/replace x TOKEN (fn [_] (swap! stats update :tokens inc) "[redacted token]"))]
                    (if (> (count x) TRIM)
                      (do (swap! stats update :trimmed inc) (str (subs x 0 TRIM) " …[trimmed " (- (count x) TRIM) " characters]"))
                      x)))
        walk (fn walk [x]
               (cond (map? x) (into (sorted-map-by #(compare (str %1) (str %2)))
                                    (for [[k v] x :let [kn (if (keyword? k) (name k) (str k))]
                                          :when (or (not (re-find SECRET-KEY kn)) (do (swap! stats update :keys conj (str k)) false))]
                                      [(if (keyword? k) (subs (str k) 1) (str k)) (walk v)]))
                     (set? x) (mapv walk (sort-by str x))
                     (sequential? x) (mapv walk x)
                     (string? x) (s x)
                     (keyword? x) (str x)
                     (symbol? x) (str x)
                     (or (number? x) (boolean? x) (nil? x)) x
                     (bytes? x) (str "<" (count x) " bytes>")
                     :else (s (str x))))]
    {:walk walk :stats stats}))

;; ---------- grouping: one sticky per domain event burst, with its ORC tick and Grain commits ----------
(defn domain? [e] (not (#{"sheet" "grain"} (namespace (:type e)))))
(defn tick-of [e] (or (get-in e [:d :tick-id]) (some (fn [[k v]] (when (= k :tick) v)) (get-in e [:d :event/tags]))))
(defn sheet-of [e] (or (get-in e [:d :sheet-id]) (some (fn [[k v]] (when (= k :sheet) v)) (get-in e [:d :event/tags]))))

(defn burst-key [e] [(:type e) (get-in e [:d :effect]) (get-in e [:d :handoff-type]) (get-in e [:d :consumer])])
(defn what
  "What one sticky's lead event says beyond its type: the runner's effect, or the handoff it relayed."
  [d]
  (cond (:effect d) (str ": " (:effect d) (when (= "refused" (:outcome d)) " (refused)"))
        (:handoff-type d) (str ": " (name (keyword (:handoff-type d))) " → " (:consumer d))
        :else ""))

(defn group-lane
  "The stickies of one store. Returns [{:key :kind :type :evs [event ids] :main [ids] :tick [ids] :grain [ids]}]."
  [lane evs]
  (let [ticks (group-by tick-of (filter tick-of evs))
        win (into {} (for [[t es] ticks] [t [(apply min (map :ms es)) (apply max (map :ms es))]]))
        sheets (into {} (for [e evs :when (= :sheet/sheet-created (:type e))] [(get-in e [:d :sheet-id]) (get-in e [:d :name])]))
        tick-sheet (into {} (for [[t es] ticks] [t (some sheet-of es)]))
        ;; a domain event inside a tick's window belongs to that tick (the command it ran)
        dom-tick (into {} (for [e evs :when (domain? e)
                                :let [t (some (fn [[t [a b]]] (when (<= a (:ms e) b) t)) win)]]
                            [(:id e) t]))
        own? (fn [e] (or (= lane RUNNER) (= (namespace (:type e)) lane)))
        ;; bursts: consecutive domain events of one type in this store make one sticky
        doms (filter domain? evs)
        bursts (reduce (fn [acc e] (let [l (peek acc)]
                                     (if (and l (= (burst-key e) (burst-key (first l))) (< (- (:ms e) (:ms (peek l))) 2000)
                                              (= (own? e) (own? (first l))))
                                       (conj (pop acc) (conj l e)) (conj acc [e]))))
                       [] doms)
        items (vec (for [b bursts :let [e (first b) tks (distinct (keep (comp dom-tick :id) b))]]
                     {:kind (if (own? e) "event" "relay") :type (:type e) :what (what (:d e)) :main (mapv :id b) :ticks (vec tks)
                      :sheet (some-> (first tks) tick-sheet sheets)}))
        used (set (mapcat :ticks items))
        empty-ticks (for [[t es] ticks :when (not (used t))] {:kind "tick" :type :orc/tick :main [] :ticks [t] :sheet (sheets (tick-sheet t)) :ms (apply min (map :ms es))})
        defs (filter #(and (= "sheet" (namespace (:type %))) (not (tick-of %))) evs)
        def-item (when (seq defs) {:kind "orc-def" :type :orc/workflows-defined :main [] :ticks [] :defs (mapv :id defs)
                                   :sheet (str/join ", " (sort (vals sheets)))})
        items (concat items empty-ticks (when def-item [def-item]))
        by-id (into {} (map (juxt :id identity) evs))
        start (fn [it] (or (:ms it) (some-> (or (first (:main it)) (first (:defs it))) by-id :ms)))
        items (vec (sort-by (fn [it] [(start it) (:kind it)]) items))
        ;; every event lands in one sticky: its main events, its ticks' events, its definitions,
        ;; and the Grain bookkeeping (a tx names the events it committed; a checkpoint the event it ran on)
        owner (atom {})
        _ (doseq [[i it] (map-indexed vector items)]
            (doseq [id (concat (:main it) (:defs it) (mapcat #(map :id (ticks %)) (:ticks it)))] (swap! owner assoc id i)))
        _ (doseq [e evs :when (= :grain/tx (:type e)) :let [i (some @owner (get-in e [:d :event-ids]))] :when i] (swap! owner assoc (:id e) i))
        _ (doseq [e evs :when (not (@owner (:id e))) :let [i (@owner (get-in e [:d :triggered-by]))] :when i] (swap! owner assoc (:id e) i))
        ;; anything left joins the sticky before it in time (or the first)
        _ (reduce (fn [last-i e] (if-let [i (@owner (:id e))] i (do (swap! owner assoc (:id e) (or last-i 0)) last-i))) nil evs)]
    {:items items :owner @owner}))

;; ---------- the stage lines ----------
(defn dur [ms]
  (let [s (quot (max 0 ms) 1000)]
    (cond (< ms 1000) (str ms " ms") (< s 60) (str s " s") (< s 3600) (str (quot s 60) " min " (mod s 60) " s")
          :else (str (quot s 3600) " h " (quot (mod s 3600) 60) " min"))))
(defn human [k] (str/replace (name k) "-" " "))

(defn stage-line [id evs prev-ms]
  (let [d (map :d evs) f (first d) t (comp keyword name :type)
        by (fn [ty] (filter #(= ty (t %)) evs))]
    (case id
      "stamp" (str (count evs) " scenario" (when (not= 1 (count evs)) "s") " stamped"
                   (let [s (distinct (keep :slice d))] (when (seq s) (str " from slice " (str/join ", " s)))))
      "pin" (str "cut " (:cut-id f) " pinned at " (some-> (:commit f) (subs 0 7)))
      "checklist" (str (:total f (count (:items f))) " checklist items derived")
      "self-check" (str (if (seq (by :harness-self-check-failed)) "failed" "passed") " "
                        (str/join " and " (distinct (for [x d] (str/replace (str (:occasion x)) #"^before-" "before the ")))))
      "fence" (str "clean room proved; " (count (:fenced-paths f)) " paths fenced")
      "regenerate" (str "candidate regenerated" (when prev-ms (str " after " (dur (- (:ms (first evs)) prev-ms))))
                        (when (> (count evs) 1) (str ", " (count evs) " times")))
      "install" (str "installed in the gate room, attempt " (:attempt f))
      "gate" (let [l (last evs) a (get-in l [:d :attempt])]
               (str "gate " (str/replace (name (:type l)) #"^gate-attempt-" "") " on attempt " a
                    (when-let [p (get-in l [:d :answered-on-poll])] (str "; the candidate answered on poll " p))))
      "score" (if-let [m (last (by :measurement-filed))]
                (str (get-in m [:d :passes]) "/" (get-in m [:d :total]) " items pass"
                     (let [u (get-in m [:d :unreached])] (when (and u (pos? u)) (str ", " u " unreached"))))
                (if (seq (by :score-stage-failed)) "the score stage failed" "verdicts filed"))
      "scoreboard" (str "pass rate " (:rate f)
                        (if (:previous-cycle-id f)
                          (str " against " (:previous-cycle-id f))
                          "; no previous cycle to compare with")))))

;; ---------- the page ----------
(defn resource [f] (slurp (io/resource f)))

(defn page [{:keys [title lanes stages outcome items data stats run-id]}]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:title title]
    [:meta {:name "viewport" :content "width=device-width,initial-scale=1,viewport-fit=cover"}]
    [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
    [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin ""}]
    [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=Familjen+Grotesk:wght@500;600;700&family=Instrument+Sans:wght@400;500;600&family=JetBrains+Mono:wght@400;500&display=swap"}]
    [:style {:innerHTML (str (:views-css stats) "\n" (resource "replay/page.css"))}]]
   [:body
    [:div.wrap
     [:nav.bar [:div.crumbs [:a {:href "../index.html"} "Event model"] [:span.sep "›"] [:span.here "Replay: " run-id]]
      [:div.legend [:span [:i {:style {:background "var(--evt)"}}] "event"] [:span [:i.relay-i] "relayed copy"] [:span [:i {:style {:background "var(--scr)"}}] "ORC only"]]]
     [:header.head
      [:div.eyebrow "Harness run replay"]
      [:h1 title]
      [:p.outcome {:class (:class outcome)} [:b (:label outcome)] " · " (:duration outcome) (when (:score outcome) (list " · " (:score outcome)))]
      [:p.muted (:sentence outcome)]]
     [:section.story {:aria-label "The cycle, stage by stage"}
      [:ol.stages
       (for [[i s] (map-indexed vector stages)]
         [:li.stage {:id (str "stage-" (:id s)) :data-at (str (:at s -1)) :class (when-not (:at s) "unreached")}
          [:span.n (inc i)]
          [:div [:b (:name s)] [:span.line (or (:line s) "not reached")]]
          [:span.when (:when s)]])]]
     [:section.controls {:aria-label "Replay"}
      [:div.btns
       [:button#play {:type "button" :aria-label "Play"} "▶ Replay"]
       [:button#back {:type "button" :aria-label "Step back"} "◀"]
       [:button#step {:type "button" :aria-label "Step"} "▶|"]
       [:label.speed "Speed " [:select#speed (for [s ["1" "2" "4" "8" "16"]] [:option {:value s :selected (= s "4")} (str s "×")])]]]
      [:input#scrub {:type "range" :min "0" :max (str (dec (count (:events data)))) :value (str (dec (count (:events data)))) :aria-label "Position in the run"}]
      [:p#now.now {:aria-live "polite"} " "]]
     [:section.tracks-sec
      [:div.sec-h [:h2 "The events, lane by lane"]
       [:p.muted "One lane per store: the cycle runner and each context. A sticky is a Grain event (a burst of one type is one sticky); the ORC tick that issued its command and the Grain commits are folded under it. Click a sticky for its payload."]]
      [:div.tracks {:style {:--lanes (str (count lanes))}}
       [:div.track-hs (for [l lanes] [:div.track-h {:data-lane (:id l) :style {:--lane (str (:col l))}} (:name l) [:small (:count l) " events"]])]
       (for [[i it] (map-indexed vector items)]
         [:button.item {:type "button" :data-item (str i) :data-at (str (:at it)) :class [(:kind it) (when (:fail it) "fail")]
                        :style {:--lane (str (:col it))}}
          [:span.lane-tag (:lane-name it)]
          [:span.lb (:label it) (when (> (:n it) 1) [:span.x (str " ×" (:n it))])]
          [:small (:sub it)]])]]
     [:dialog#detail [:div.dlg]]
     [:footer.muted
      "Replayed from " (str/join ", " (for [l lanes] (str (:file l) " (" (:count l) ")"))) ": "
      (:total stats) " events, decoded from Fressian by tools/spec/replay. Payloads are scrubbed for publication: "
      (:paths stats) " host paths replaced with <run>, <repo> or <host> placeholders"
      (when (seq (:keys stats)) (str ", " (count (:keys stats)) " sensitive fields dropped (" (str/join ", " (:keys stats)) ")"))
      (when (pos? (:tokens stats)) (str ", " (:tokens stats) " tokens redacted"))
      (when (pos? (:trimmed stats)) (str ", " (:trimmed stats) " strings over " TRIM " characters trimmed (marked where trimmed)"))
      ". Built by tools/spec/replay/build.sh."]]
    [:script#data {:type "application/json" :innerHTML (str/replace (json/generate-string data) "</" "<\\/")}]
    [:script {:innerHTML (resource "replay/page.js")}]]])

(defn -main [& args]
  (let [[pos opts] (loop [a args pos [] o {}]
                     (cond (empty? a) [pos o]
                           (str/starts-with? (first a) "--") (recur (drop 2 a) pos (assoc o (keyword (subs (first a) 2)) (second a)))
                           :else (recur (rest a) (conj pos (first a)) o)))
        [run-dir out] pos
        _ (when-not (and run-dir out) (throw (ex-info "usage: build.sh <run-dir> <out.html> [--title <t>] [--spec <spec-root>]" {})))
        run-dir (str (fs/canonicalize run-dir))
        here (str (fs/parent (fs/parent (fs/parent (fs/absolutize (io/file (.toURI (io/resource "replay/main.clj"))))))))
        repo (str/trim (sh-out "git" "-C" here "rev-parse" "--show-toplevel"))
        spec-root (or (:spec opts) (str repo "/.pi/spec"))
        model (event-model spec-root)
        stores (find-stores run-dir)
        {:keys [walk stats]} (scrubber run-dir repo)
        lane-evs (for [[lane f] stores] [lane f (read-store lane f)])
        all (sort-by (juxt :ms :id) (mapcat #(nth % 2) lane-evs))
        idx (into {} (map-indexed (fn [i e] [[(:lane e) (:id e)] i]) all))
        ;; lanes: the runner first, then the contexts in the order of their first own event
        first-own (fn [[lane _ evs]] (or (some #(when (and (domain? %) (= (namespace (:type %)) lane)) (:ms %)) evs) Long/MAX_VALUE))
        lane-evs (into [(first lane-evs)] (sort-by first-own (rest lane-evs)))
        lanes (vec (for [[i [lane f evs]] (map-indexed vector lane-evs)]
                     {:id lane :col (inc i) :name (if (= lane RUNNER) "cycle runner" (human lane)) :count (count evs)
                      :file (str (fs/relativize run-dir f))}))
        col (into {} (map (juxt :id :col) lanes))
        grouped (for [[lane _ evs] lane-evs] [lane evs (group-lane lane evs)])
        by-key (into {} (for [e all] [[(:lane e) (:id e)] e]))
        t0 (:ms (first all))
        items (->> (for [[lane evs {:keys [items owner]}] grouped
                         [li it] (map-indexed vector items)
                         :let [mine (sort-by idx (for [e evs :when (= li (owner (:id e)))] [lane (:id e)]))
                               main (map #(by-key [lane %]) (:main it))
                               lead (if (seq main) (idx [lane (:id (first main))]) (idx (first mine)))
                               ty (:type it)
                               m (model ty)]]
                     (merge it
                            {:lane lane :lane-name (:name (first (filter #(= lane (:id %)) lanes))) :col (col lane)
                             :at lead :members (mapv idx mine) :mainx (mapv #(idx [lane (:id %)]) main) :n (max 1 (count main))
                             :fail (boolean (re-find #"failed|refused|halted" (str ty)))
                             :label (case (:kind it)
                                      "tick" (str "ORC tick" (when (:sheet it) (str ": " (:sheet it))))
                                      "orc-def" "ORC workflows defined"
                                      (str (human ty) (:what it)))
                             :sub (str "+" (dur (- (:ms (by-key (first mine))) t0))
                                       (case (:kind it)
                                         "relay" (str " · relayed from " (namespace ty))
                                         ("event") (when (seq (:ticks it)) " · ORC tick")
                                         "orc-def" (str " · " (count (:defs it)) " definitions")
                                         ""))
                             :desc (:desc m) :cmd (some-> (:cmd m) str (subs 1))
                             :href (when m (str "../index.html#ev-" (namespace ty) "-" (name ty)))}))
                   (sort-by (juxt :at :col))
                   vec)
        ;; stages: the owner's events of each stage type (relayed copies do not count)
        owned (filter #(and (domain? %) (= (namespace (:type %)) (:lane %))) all)
        stage-evs (fn [s] (filter #((:types s) (:type %)) owned))
        stages (reduce (fn [acc s]
                         (let [es (stage-evs s) prev (some #(when (:at %) (:ms0 %)) (reverse acc))]
                           (conj acc (merge (select-keys s [:id :name])
                                            (when (seq es)
                                              {:at (idx [(:lane (first es)) (:id (first es))]) :ms0 (:ms (last es))
                                               :when (str "+" (dur (- (:ms (first es)) t0)))
                                               :line (stage-line (:id s) es prev)})))))
                       [] STAGES)
        outs (filter #(OUTCOME (:type %)) owned)
        concl (or (first (filter #(#{:regeneration-cycle/cycle-passed :regeneration-cycle/cycle-failed} (:type %)) outs)) (last outs))
        status (cond (nil? concl) "unfinished" (re-find #"passed" (str (:type concl))) "passed"
                     (= :cycle-runner/run-concluded (:type concl)) (get-in concl [:d :cycle-status] "concluded")
                     :else "failed")
        rs (some #(when (= :cycle-runner/run-started (:type %)) %) all)
        run-id (or (get-in rs [:d :run-id]) (str (fs/file-name run-dir)))
        concluded (some #(when (#{:cycle-runner/run-concluded :cycle-runner/run-halted} (:type %)) %) all)
        span (if (and rs concluded) (- (:ms concluded) (:ms rs)) (- (:ms (last all)) t0))
        score (:line (first (filter #(= "score" (:id %)) stages)))
        outcome {:class (str "o-" status) :label (case status "passed" "Cycle passed" "failed" "Cycle failed" "unfinished" "Run unfinished" (str "Cycle " status))
                 :duration (str (dur span) (if (and rs concluded) " from run start to conclusion" " from first to last event")) :score score
                 :sentence (str "Run " run-id (when-let [c (get-in rs [:d :cut-id])] (str " of cut " c))
                                (when-let [c (get-in rs [:d :commit])] (str " at " (subs c 0 7)))
                                (when-let [s (get-in rs [:d :selection-id])] (str ", selection " s))
                                ". " (count all) " Grain and ORC events from " (count lanes) " stores, replayed in time order"
                                " with long waits compressed.")}
        title (or (:title opts) (str "Replay of " run-id))
        data {:t0 t0
              :stages (mapv #(select-keys % [:id :at]) stages)
              :lanes (mapv #(select-keys % [:id :name]) lanes)
              :items (mapv #(select-keys % [:lane :kind :label :type :at :members :mainx :desc :cmd :href :sheet :n]) items)
              :events (vec (for [[i e] (map-indexed vector all)]
                             {:t (- (:ms e) t0) :ty (subs (str (:type e)) 1) :l (:lane e)
                              :d (walk (dissoc (:d e) :event/tags)) :tags (walk (vec (get-in e [:d :event/tags])))}))}
        st @stats
        html (str "<!doctype html>\n"
                  (r/render (page {:title title :lanes lanes :stages stages :outcome outcome :items items :data data :run-id run-id
                                   :stats (assoc st :total (count all) :views-css (slurp (str here "/../views/src/specviews/page.css")))})))]
    (when-let [leak (re-find #"/Users/[^\s\"<]*|/private/[^\s\"<]*" html)] (throw (ex-info (str "a host path survived scrubbing: " leak) {})))
    (fs/create-dirs (fs/parent (fs/absolutize out)))
    (spit out html)
    (println (str "wrote " out " (" (quot (count (.getBytes html "UTF-8")) 1024) " KB): " (count all) " events, " (count items) " stickies, "
                  (:paths st) " host paths scrubbed" (when (seq (:keys st)) (str ", dropped " (str/join " " (:keys st))))
                  (when (pos? (:trimmed st)) (str ", " (:trimmed st) " strings trimmed"))))
    (doseq [[lane f evs] lane-evs] (println (str "  " lane ": " (count evs) " events from " (fs/relativize run-dir f))))))
