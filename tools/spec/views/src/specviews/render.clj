;; Render the spec pages at build time: the specs (extract.clj's `world` map) → Hiccup, one pure
;; function per view or component → static HTML, rendered by Replicant. Loaded by main.clj with
;; `cfg` (project.edn) and `cli`. The drill-down, top to bottom:
;;   L0 home: the title, one plain sentence (:text :summary), what changed since --base (diff.clj,
;;      compact), the event storm (story.clj), the contexts, and lower down About this page;
;;   L1 each context's event model as swimlanes, L2 its spec cards, L3 under the hood (context.clj,
;;      loaded once per context into a namespace of its own, ctx.<context>, reading `spec`);
;;   beside it: the architecture view (handoffs, then the C4 views) and the Review view.
;;   bb -m specviews.main --config <project.edn> [options]
;;     --mode page|app     page (default): the public page. app: the same page plus review
;;                         controls (data-review-action/-target buttons, data-threads-for badges)
;;                         and a <script id="review-app"> for the MCP app's wiring script
;;     --out <file> | --out-dir <dir>   default <:out-dir>/event-model.html
;;     --base <git-ref>    mark what changed in the spec since that ref
;;     --app-js <file>     app mode: inline this file into <script id="review-app">
;;     --threads <edn>     app mode: open-thread counts, {"rule:Name" 2 …}, or a vector of
;;                         {:target "rule:Name" :status …} maps (counted unless :resolved)
;;     --built <date>      the footer's build date, default today
;;     --coverage <json>   the coverage report; when absent, made by the config's :coverage command
;; Writes the page, review.html (the same page, self-contained, titled for review) and story.svg.
;; Every view is a <div class="view" id="…"> in the one file; page.css shows the one the address
;; targets (home by default). page.js draws Mermaid and the event-storm arrows, opens a folded
;; target, and sizes diagrams. Text is escaped by Replicant; the page holds nothing written by hand.
(require '[clojure.string :as str] '[clojure.java.io :as io] '[clojure.edn :as edn] '[cheshire.core :as json]
         '[babashka.process :as p] '[babashka.fs :as fs] '[replicant.string :as r])
;; `cfg` (project.edn, with :root), `cli` (the command line), `load-into` and `resource` come from main.clj.
(defn T "A text of the project's config (:text), with {placeholders} filled from `m`."
  [k & [m]] (reduce-kv (fn [s a b] (str/replace s (str "{" (name a) "}") (str b))) (get-in cfg [:text k] "") (or m {})))
(def ^:dynamic opts
  (let [o cli root (:root cfg)
        tmp (str (fs/create-temp-dir))
        cov (:coverage cfg)]
    (when (and cov (not (:coverage o)))   ; the gate may fail (exit 1, shown on the page); it may not crash
      (let [{:keys [exit]} (apply p/sh {:dir root} (concat cov [(str tmp "/coverage.json")]))]
        (when (> exit 1) (throw (ex-info (str "coverage failed (" exit ")") {})))))
    (merge {:mode "page" :out (let [d (:out-dir cfg "dist")] (str (fs/path (if (fs/absolute? d) d (fs/path root d)) (get-in cfg [:outputs :page] "event-model.html"))))
            :built (str (java.time.LocalDate/now)) :coverage (str tmp "/coverage.json")}
           (when (:out-dir o) {:out (str (fs/path (fs/absolutize (:out-dir o)) (get-in cfg [:outputs :page] "event-model.html")))})
           (dissoc o :out-dir) {:root root})))
(def app? (= "app" (:mode opts)))
(def threads
  (let [t (some-> (:threads opts) slurp edn/read-string)]
    (if (map? t) t (frequencies (for [x t :when (not= :resolved (keyword (name (or (:status x) :open))))] (:target x))))))
(load-string (resource "extract.clj"))   ; defines `world`

;; Replicant 2026.07.1 escapes `"` as `&#39;` (an apostrophe): spec text and Mermaid labels
;; would change. Escape as Hiccup does, `"` → `&quot;`.
(alter-var-root #'r/escape-html
                (constantly (fn [s] (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))))

(load-string (resource "common.clj"))
(if (:base opts) (load-string (resource "diff.clj")) (def CHG nil))   ; what changed since --base

;; ---------- each context, loaded into a namespace of its own ----------
(def CTXS
  (vec (for [c (:contexts world)]
         {:id (:id c) :c c
          :ns (load-into (symbol (str "ctx." (:id c))) {'spec c 'world world 'app? app? 'threads threads 'cfg cfg 'T T 'CHG CHG} "common.clj" "context.clj")})))
(defn in-ctx [id f & args] (apply (ns-resolve (:ns (first (filter #(= id (:id %)) CTXS))) f) args))
(load-string (resource "story.clj"))   ; defines `story-svg`, the event storm (L0)
(defn chg-badge [m] (in-ctx (:id (first CTXS)) 'chg-badge m))

;; ---------- L1: the six contexts and the handoffs between them ----------
(defn handoff-map []   ; a Mermaid flowchart: one node per context, one edge per producer -> consumer
  (let [edges (distinct (for [h (:handoffs world) c (:consumers h)] [(:producer h) (:ctx c)]))
        lbl (fn [p c] (str/join "<br/>" (for [h (:handoffs world) :when (and (= p (:producer h)) (some #(= c (:ctx %)) (:consumers h)))] (mq (human (short (:event h)))))))]
    (str "flowchart LR\n"
         (str/join "\n" (for [c (:contexts world)] (str "    " (nid (:id c)) "[\"<b>" (mq (human (:id c))) "</b>" (when (:accepted c) (str "<br/>slice " (mq (:slice c)))) "\"]")))
         "\n"
         (str/join "\n" (for [[p c] edges] (str "    " (nid p) " -->|\"" (lbl p c) "\"| " (nid c))))
         "\n    classDef ctx fill:#e6e9e7,stroke:#2d3833,color:#2d3833\n    linkStyle default stroke:#6b3fc4,stroke-width:1.7px\n"
         (str/join "\n" (for [c (:contexts world)] (str "    class " (nid (:id c)) " ctx"))))))
(defn arch? [] (or (seq (:c4 world)) (seq (:handoffs world))))
(defn changes-summary []   ; compact, above the storm: one line of counts, the changed cards folded
  (when CHG
    (let [scen-n (fn [st] (count (filter #(= st (val %)) (:scen CHG))))
          cnt (fn [k] (count (get CHG k)))
          ;; each changed card, with its context: rule cards, property cards, journeys
          cards (for [c CTXS :let [rows (in-ctx (:id c) 'changed-cards)] :when (seq rows)] [c rows])
          removed (for [[k v] (:removed CHG) x v] [k x])
          counts (for [[n w] [[(scen-n :new) "new scenarios"] [(scen-n :changed) "changed scenarios"] [(cnt :rules) "rules"]
                              [(cnt :props) "invariants and guarantees"]
                              [(count (filter (fn [[[_ k _] _]] (= k :events)) (:blocks CHG))) "events"]
                              [(count (remove (fn [[[_ k _] _]] (= k :events)) (:blocks CHG))) "other event-model blocks"]
                              [(cnt :flows) "flows"] [(count removed) "removed"]]
                       :when (pos? n)] (n-of n w))
          ncards (reduce + (map (comp count second) cards))]
      [:section.changes {:id "changes"}
       [:p.chg-line [:b "Changed since " (code (:base CHG))] " (" (code (:base-commit CHG)) "): "
        (if (seq counts) (interpose " · " counts) "nothing")]
       [:details.chg-more
        [:summary (n-of ncards (if (= 1 ncards) "changed card" "changed cards")) (when (seq removed) (list ", " (n-of (count removed) "removed")))]
        [:div.chg-body
         [:p.muted "The spec now against " (code (:base CHG)) " (" (code (:base-commit CHG)) "), both read by the same extractor from git. A green " [:span.chg.changed "changed"] " or pink " [:span.chg.new "new"] " mark follows the change down every level: the event storm, the event model, the spec card and the scenario."]
         (if (seq cards)
           [:div.chg-list (for [[c rows] cards]
                            [:div.chg-ctx [:a {:href (str "#ctx-" (:id c))} [:b (human (:id c))]]
                             [:ul (for [{:keys [href title m kind scs]} rows]
                                    [:li (chg-badge m) " " [:a {:href href} title] " " [:span.muted kind (when (seq scs) (str " · " (str/join ", " scs)))]])]])]
           (none "No spec card changed."))
         (when (seq removed)
           [:details.inv [:summary [:span.t "Removed since " (:base CHG)] [:span.meta (count removed)]]
            [:div.inv-body (qlist (for [[k x] removed] (list (muted (name k)) " " (code (str/join " " (map str (if (coll? x) x [x])))))))]])]]])))
(defn view-home [built]
  (let [cs (:contexts world) items (:items world) S (:coverage world)]
    (view "home" [home-crumb]
      [:div.head
       [:h1 (:page-title cfg)]
       (when (seq (T :summary)) [:p.lede (T :summary {:contexts (count cs)})])]
      (changes-summary)
      [:section.storm-sec {:aria-label "The event storm"}
       [:div.story-bar
        [:div.story-key [:span [:i.k-evt] "domain event"] [:span [:i.k-un] "unhappy path"] [:span [:i.k-arrow] "handoff, in time order"]
         (when CHG (list [:span [:i.k-chg] "changed"] [:span [:i.k-new] "new"]))]
        [:div.story-tools [:span.story-hint "Time runs left to right: scroll sideways."]
         [:button.story-fit {:type "button" :aria-pressed "false"} "Fit to screen"]]]
       [:div.story-frame {:innerHTML (story-svg false)}]
       [:p.muted.story-cap "Every domain event, in the order a cycle happens, one band per part of the system. Under each sticky, who or what triggers it; arrows hand work from one part to the next. Click a band or a sticky to open it."]]
      [:section
       [:h2 "Drill down"]
       [:p.muted "Each context's event model (commands, events, the views they feed), then its spec cards (each rule in plain words with its Given/When/Then), then under the hood (the Allium, the Grain blocks, the flows and handoffs)."]
       [:div.cards (for [c CTXS] (in-ctx (:id c) 'card))]]
      [:section
       [:h2 "Beside the drill-down"]
       [:div.cards
        (when (arch?)
          [:a.card {:href "#c4"} [:h3 "Architecture"]
           [:span.muted {:style {:font-size "12.5px"}} "How the architecture grows from the event storm: bounded contexts and their handoffs, then " (interpose " · " (map :title (:c4 world)))]
           (when (:source (:arch world)) [:span.src (:source (:arch world)) " · " (:commit (:arch world)) (when (:dirty (:arch world)) " (dirty)")])])
        [:a.card {:href "#review"} [:h3 "Review"]
         [:span.muted {:style {:font-size "12.5px"}} "What the product owner decides: drafts awaiting acceptance, uncovered obligations, open questions, and the spec text not yet written in plain words."]
         (when (seq S) [:span.src (:must S) " must-cover · " (:uncovered S) " uncovered"])]]]
      [:section.about {:id "about"}
       [:h2 "About this page"]
       [:div.eyebrow "Level 0 · the big picture · built from " (code (:source world)) " at " (code (:commit world)) (when (:dirty world) " with local changes")]
       (when (seq (T :intro)) [:p.muted (T :intro {:contexts (count cs)})])
       (stats (list [:b {:data-count "contexts"} (count cs)] " contexts") (n-of (count SE) "domain events") (list [:b {:data-count "items"} (count items)] " stamped items")
              (n-of (reduce + (for [c cs] (count (filter #(= "drafts" (:kind %)) (:scenarios c))))) "drafts")
              (n-of (count (:handoffs world)) "handoffs") (list "built " [:b built]))
       [:h3 "Each context is specified in three layers"]
       [:div.layers
        [:div.layer [:span.lbl "behaviour"] [:b "Allium"] (muted (code (str (:spec-root cfg) "/<context>/" (get-in cfg [:files :allium]) "/*.allium")) ": one file per aggregate and the context's shared language. Its state and lifecycle, the rules that change it (" (code "when") " · " (code "requires") " · " (code "ensures") "), invariants, contracts, and the surfaces that say who may act.")]
        [:div.layer [:span.lbl "topology"] [:b "Event Model"] (muted (code (str (:spec-root cfg) "/<context>/" (get-in cfg [:files :event-model]) "/*.edn")) ": Grain Event Model fragments (commands, events with schemas, read models, queries, policies, screens) and the flows that join them. Each block links back to its Allium rule.")]
        [:div.layer [:span.lbl "examples"] [:b "Gherkin"] (muted (code (str (:spec-root cfg) "/<context>/" (get-in cfg [:files :spec]))) " and " (code (get-in cfg [:files :drafts])) ": Given/When/Then, each accepted scenario stamped " (code (str (:item-tag cfg) "NN-SSS")) " with its build tier, and tagged " (code "@allium-…") " with the obligations it checks. A rule's success and each of its refusals is one obligation.")]]])))

;; ---------- sideways: the architecture, grown from the event storm ----------
(def C4-HEADINGS {"1" "System context (C4 level 1)" "2" "Containers (C4 level 2)" "3" "Components (C4 level 3)"})
(defn view-c4 []
  (let [{:keys [source commit dirty]} (:arch world)
        nedge (reduce + (map (comp count :consumers) (:handoffs world)))]
    (view "c4" [home-crumb ["Architecture" "c4"]]
      [:div.head
       [:div.eyebrow "Architecture · grown from the event storm" (when source (list " · " (code source) " at " (code commit) (when dirty " with local changes")))]
       [:h1 "Architecture"]
       [:p.lede "How the architecture grows from the event storm: its bands are the bounded contexts; the events one context hands to another are how they link; then the containers that run them and the components inside." (when (seq (:c4 world)) (list " " (T :c4-lede)))]
       (stats (n-of (count (:contexts world)) "bounded contexts") (n-of (count (:handoffs world)) "handoffs") (n-of (count (:c4 world)) "C4 views") (when source (list "source " [:b source])))]
      (when (seq (:handoffs world))
        [:section
         [:h2 "Bounded contexts, and how they link"]
         [:p.muted "Each event a context hands out (its " (code (str (get-in cfg [:files :event-model]) "/" (get-in cfg [:files :handoffs]))) "), and each context whose read models or policies read it. " (T :handoffs-assembly) (count (:handoffs world)) " handoffs, " nedge " producer-consumer edges."]
         (mm-block (handoff-map) (str "Contexts, with the handoff events on each edge." (T :handoffs-note)))
         [:div.handoffs {:style {:margin-top "12px"}} (for [h (:handoffs world)] (in-ctx (:producer h) 'handoff-row h))]])
      (for [d (:c4 world) :let [n (second (re-find #"^Level (\d)" (:title d)))]]
        [:section {:data-c4 (:title d)}
         [:h2 (get C4-HEADINGS n (:title d))]
         (mm-block (:source d) (list "From " (code source) ", " (:title d) "."))]))))

;; ---------- Review: what the product owner decides ----------
(def GAPS (vec (concat (mapcat #(in-ctx (:id %) 'gaps) CTXS)
                       (for [k UNORDERED] (str k ": in no flow after another event, so the event storm starts it at the left")))))
(defn view-review []
  (let [S (:coverage world) parts (map #(in-ctx (:id %) 'review-part) CTXS)
        all (fn [k] (mapcat k parts))]
    (view "review" [home-crumb ["Review" "review"]]
      [:div.head
       [:div.eyebrow "Review · the coverage gate, " (:commit world) (when (:dirty world) " with local changes")]
       [:h1 "What the product owner reviews"]
       (if (seq S)
         (list
       [:p.muted "The coverage gate's own result (" (code (T :coverage-tool)) "): every must-cover Allium obligation of a context should be tagged " (code "@allium-<obligation>") " on an accepted scenario of its " (code (get-in cfg [:files :spec])) ", or on a draft in its " (code "drafts.feature") ". Only the product owner moves a draft into " (code "spec.feature") "."]
       (stats (n-of (:must S) "must-cover") (n-of (:covered S) "covered") (n-of (:drafted S) "drafted") (n-of (:uncovered S) "uncovered") (n-of (:unit S) "unit level") (n-of (:gate S) "gate-verified") (n-of (count (all :drafts)) "drafts") (n-of (count (all :oqs)) "open questions"))
       [:div {:class ["note" (when-not (or (pos? (:uncovered S)) (seq (all :unknown))) "ok")]}
        (code (str "must-cover " (:must S) " | covered " (:covered S) " | drafted " (:drafted S) " | uncovered " (:uncovered S) " | unit-level " (:unit S) " | gate-verified " (:gate S)))])
         [:p.muted "The project names no coverage report (" (code ":coverage") " in its config): drafts and open questions only."])]
      (when (seq (all :unknown)) [:section [:h2 "Unknown tags"] [:p.muted "Tags naming no obligation " (code "allium plan") " derives: a renamed rule, or a typo."] (qlist (all :unknown))])
      [:section [:h2 "Drafts awaiting acceptance"] [:p.muted "Scenarios proposed for obligations no accepted scenario covers; each opens the rule where it is shown in full. " (T :drafts-unread)]
       (if (seq (all :drafts)) (qlist (all :drafts)) (none "No drafts."))]
      [:section [:h2 "Uncovered obligations"] [:p.muted "By context. Each opens its rule card, where the obligation sits under its " (code "requires") " or its examples."] [:div.flow-list (or (seq (keep :uncovered parts)) (none "None."))]]
      [:section [:h2 "Verified by a gate"] [:p.muted "Guarantees and contract invariants whose prose says \"Verified by: gate\": a gate checks them, not a scenario."] (if (seq (all :gate)) (qlist (all :gate)) (none "None."))]
      [:section [:h2 "Unit level, set aside"] [:p.muted "Refusals a unit test checks, not the black box: a refusal on the aggregate's own state, a pick between two rules, a redelivery, or a clause guarded upstream."]
       (qlist (all :unit))]
      [:section [:h2 "Open questions"] [:p.muted "Allium " (code "open question") "s, listed on each Allium file's page."]
       (if (seq (all :oqs)) (qlist (all :oqs)) (none "None."))]
      [:section {:id "gaps"} [:h2 "Not yet in plain words"] [:p.muted "Blocks and rules the page can show only by name: the spec gives them no description, no Gherkin " (code "Rule:") " line and no Allium comment. A spec round can add the words; the page never invents them."]
       (if (seq GAPS) (qlist (map code GAPS)) (none "None: every block, flow and rule has its words."))])))

;; ---------- the page ----------
(defn page [built]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:title (if (:review? opts) (:review-title cfg) (:page-title cfg))]
    [:meta {:name "viewport" :content "width=device-width,initial-scale=1,viewport-fit=cover"}]
    [:meta {:name "claude:viewport-fit-sides" :content "cover"}]
    [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
    [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin ""}]
    [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=Familjen+Grotesk:wght@500;600;700&family=Instrument+Sans:wght@400;500;600&family=JetBrains+Mono:wght@400;500&display=swap"}]
    [:style {:innerHTML (resource "page.css")}]
    [:script {:src "https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js"}]]
   [:body
    [:div.wrap
     (view-home built) (view-review) (when (arch?) (view-c4))
     (for [c CTXS] (in-ctx (:id c) 'views))
     [:footer "Built on " built " by " (code (T :footer-by)) " from " (T :footer-spec) " in " (code (str (:spec-root cfg) "/<context>/")) " at " (code (:commit world)) (when (:dirty world) " (with local changes)") ": " (code "allium/*.allium") " (behaviour), the Grain Event Model fragments " (code "event-model/*.edn")
      " (topology, merged by " (code (T :loader)) "), " (code (get-in cfg [:files :spec])) " and " (code (get-in cfg [:files :drafts])) " (examples), and the coverage gate's own classification (" (code (T :coverage-report))
      "); and the C4 views of " (code (:source (:arch world))) " at " (code (:commit (:arch world))) (when (:dirty (:arch world)) " (with local changes)") ". Nothing here is written by hand: change the specs and rebuild."]]
    [:script {:innerHTML (resource "page.js")}]
    (when app? [:script#review-app {:innerHTML (if (:app-js opts) (slurp (:app-js opts)) "")}])]])

;; ---------- outputs: the page, the self-contained review page, the event storm alone ----------
(let [{:keys [out built mode]} opts
      dir (str (fs/parent (fs/absolutize out)))
      outs (merge {:review "review.html" :story "story.svg"} (:outputs cfg))]
  (fs/create-dirs dir)
  (spit out (str "<!doctype html>\n" (r/render (page built))))
  (when-not app?
    (let [review (str dir "/" (:review outs))]
      (spit review (str "<!doctype html>\n" (r/render (binding [opts (assoc opts :review? true)] (page built)))))
      (println (str "wrote " review (when CHG (str " (changes since " (:base CHG) ")")))))
    (spit (str dir "/" (:story outs)) (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" (story-svg true) "\n"))
    (println "wrote" (str dir "/" (:story outs))))
  (when (seq GAPS) (println "plain-language gaps:" (count GAPS) "(listed on the Review view, #gaps)"))
  (println mode "views" (+ 2 (if (arch?) 1 0) (reduce + (for [c CTXS] (count (in-ctx (:id c) 'views))))) "· wrote" out))
