;; Render the spec v2 event-model page at build time: the specs (extract.clj's `world` map) →
;; Hiccup, one pure function per view or component → one static HTML file, rendered by Replicant.
;; The home view (the six contexts and the handoffs between them) and the Review view are here;
;; each context's views come from context.clj, loaded once per context into a namespace of its
;; own (ctx.<context>), so the per-context code reads `spec` as that context.
;;   bb --config tools/spec/views/bb.edn tools/spec/views/render.clj [options]
;;     --mode page|app     page (default): the public page. app: the same page plus review
;;                         controls (data-review-action/-target buttons, data-threads-for badges)
;;                         and a <script id="review-app"> for the MCP app's wiring script
;;     --out <file>        default tools/spec/views/dist/event-model.html
;;     --app-js <file>     app mode: inline this file into <script id="review-app">
;;     --threads <edn>     app mode: open-thread counts, {"rule:Name" 2 …}, or a vector of
;;                         {:target "rule:Name" :status …} maps (counted unless :resolved)
;;     --built <date>      the footer's build date, default today
;;     --coverage <json>   the coverage report; when absent, made here (tools/spec/v2/coverage.py)
;; Every view is a <div class="view" id="…"> in the one file; page.css shows the one the address
;; targets (home by default). page.js draws Mermaid and the event-storm arrows, and sizes diagrams. Text is escaped by
;; Replicant; the page holds nothing written by hand.
(require '[clojure.string :as str] '[clojure.java.io :as io] '[clojure.edn :as edn] '[cheshire.core :as json]
         '[babashka.process :as p] '[babashka.fs :as fs] '[replicant.string :as r])
(def here (str (fs/parent (fs/absolutize *file*))))
(def opts
  (let [o (into {} (for [[k v] (partition 2 *command-line-args*)] [(keyword (str/replace k #"^--" "")) v]))
        root (str/trim (:out (p/sh {:dir here} "git" "rev-parse" "--show-toplevel")))
        tmp (str (fs/create-temp-dir))]
    (when-not (:coverage o)   ; the gate may fail (exit 1, shown on the page); it may not crash
      (let [{:keys [exit]} (p/sh {:dir root} "python3" "tools/spec/v2/coverage.py" "--report" (str tmp "/coverage.json"))]
        (when (> exit 1) (throw (ex-info (str "coverage.py failed (" exit ")") {})))))
    (merge {:mode "page" :out (str here "/dist/event-model.html") :built (str (java.time.LocalDate/now))
            :coverage (str tmp "/coverage.json")}
           o {:root root})))
(def app? (= "app" (:mode opts)))
(def threads
  (let [t (some-> (:threads opts) slurp edn/read-string)]
    (if (map? t) t (frequencies (for [x t :when (not= :resolved (keyword (name (or (:status x) :open))))] (:target x))))))
(load-file (str here "/extract.clj"))   ; defines `world`

;; Replicant 2026.07.1 escapes `"` as `&#39;` (an apostrophe): spec text and Mermaid labels
;; would change. Escape as Hiccup does, `"` → `&quot;`.
(alter-var-root #'r/escape-html
                (constantly (fn [s] (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))))

(load-file (str here "/common.clj"))

;; ---------- each context, loaded into a namespace of its own ----------
(def CTXS
  (vec (for [c (:contexts world)]
         (let [n (create-ns (symbol (str "ctx." (:id c))))]
           (binding [*ns* n]
             (eval '(clojure.core/refer-clojure))
             (doseq [[k v] {'spec c 'world world 'app? app? 'threads threads}] (intern n k v))
             (load-file (str here "/common.clj"))
             (load-file (str here "/context.clj")))
           {:id (:id c) :ns n :c c}))))
(defn in-ctx [id f & args] (apply (ns-resolve (:ns (first (filter #(= id (:id %)) CTXS))) f) args))

;; ---------- L1: the six contexts and the handoffs between them ----------
(defn handoff-map []   ; a Mermaid flowchart: one node per context, one edge per producer -> consumer
  (let [edges (distinct (for [h (:handoffs world) c (:consumers h)] [(:producer h) (:ctx c)]))
        lbl (fn [p c] (str/join "<br/>" (for [h (:handoffs world) :when (and (= p (:producer h)) (some #(= c (:ctx %)) (:consumers h)))] (mq (human (short (:event h)))))))]
    (str "flowchart LR\n"
         (str/join "\n" (for [c (:contexts world)] (str "    " (nid (:id c)) "[\"<b>" (mq (human (:id c))) "</b><br/>slice " (mq (:slice c)) "\"]")))
         "\n"
         (str/join "\n" (for [[p c] edges] (str "    " (nid p) " -->|\"" (lbl p c) "\"| " (nid c))))
         "\n    classDef ctx fill:#e6e9e7,stroke:#2d3833,color:#2d3833\n    linkStyle default stroke:#6b3fc4,stroke-width:1.7px\n"
         (str/join "\n" (for [c (:contexts world)] (str "    class " (nid (:id c)) " ctx"))))))
(defn view-home [built]
  (let [cs (:contexts world) items (:items world) S (:coverage world)
        nedge (reduce + (map (comp count :consumers) (:handoffs world)))]
    (view "home" [home-crumb]
      [:div.head
       [:div.eyebrow "Level 1 · the big picture · built from " (code (:source world)) " at " (code (:commit world)) (when (:dirty world) " with local changes")]
       [:h1 "Löyly spec v2 — event model"]
       [:p.muted "The Pi harness, specified as " (count cs) " bounded contexts. Each is a slice of its own: its Allium, its Grain Event Model, its stamped scenarios. The contexts meet only through handoffs, events one context defines and others read; the cycle runner relays each from its producer's store."]
       (stats (list [:b {:data-count "contexts"} (count cs)] " contexts") (list [:b {:data-count "items"} (count items)] " stamped items")
              (n-of (reduce + (for [c cs] (count (filter #(= "drafts" (:kind %)) (:scenarios c))))) "drafts")
              (n-of (count (:handoffs world)) "handoffs") (n-of (:must S) "must-cover obligations") (n-of (:covered S) "covered")
              (list "source " [:b (:source world)]) (list "commit " [:b (:commit world)] (when (:dirty world) " (dirty)")) (list "built " [:b built]))]
      [:section [:h2 "The contexts"] [:div.cards (for [c CTXS] (in-ctx (:id c) 'card))]]
      [:section
       [:h2 "Handoffs: producer → event → consumer"]
       [:p.muted "Each event a context hands out (its " (code "event-model/handoffs.edn") "), and each context whose read models or policies read it. This is the assembly the cycle runner's relay drives: " (count (:handoffs world)) " handoffs, " nedge " producer-consumer edges."]
       (mm-block (handoff-map) "Contexts, with the handoff events on each edge. The cycle runner consumes none: it reads each from the producer's store and hands it in through the consumer's seam.")
       [:div.handoffs {:style {:margin-top "12px"}} (for [h (:handoffs world)] (in-ctx (:producer h) 'handoff-row h))]]
      [:section
       [:h2 "Each context is specified in three layers"]
       [:div.layers
        [:div.layer [:span.lbl "behaviour"] [:b "Allium"] (muted (code ".pi/spec/<context>/allium/*.allium") ": one file per aggregate and the context's shared language. Its state and lifecycle, the rules that change it (" (code "when") " · " (code "requires") " · " (code "ensures") "), invariants, contracts, and the surfaces that say who may act.")]
        [:div.layer [:span.lbl "topology"] [:b "Event Model"] (muted (code ".pi/spec/<context>/event-model/*.edn") ": Grain Event Model fragments (commands, events with schemas, read models, queries, policies, screens) and the flows that join them. Each block links back to its Allium rule.")]
        [:div.layer [:span.lbl "examples"] [:b "Gherkin"] (muted (code ".pi/spec/<context>/spec.feature") " and " (code "drafts.feature") ": Given/When/Then, each accepted scenario stamped " (code "@item-NN-SSS") " with its build tier, and tagged " (code "@allium-…") " with the obligations it checks. A rule's success and each of its refusals is one obligation.")]]])))

;; ---------- Review: what the product owner decides ----------
(defn view-review []
  (let [S (:coverage world) parts (map #(in-ctx (:id %) 'review-part) CTXS)
        all (fn [k] (mapcat k parts))]
    (view "review" [home-crumb ["Review" "review"]]
      [:div.head
       [:div.eyebrow "Review · the coverage gate, " (:commit world) (when (:dirty world) " with local changes")]
       [:h1 "What the product owner reviews"]
       [:p.muted "The coverage gate's own result (" (code "tools/spec/v2/coverage.py") "): every must-cover Allium obligation of a context should be tagged " (code "@allium-<obligation>") " on an accepted scenario of its " (code "spec.feature") ", or on a draft in its " (code "drafts.feature") ". Only the product owner moves a draft into " (code "spec.feature") "."]
       (stats (n-of (:must S) "must-cover") (n-of (:covered S) "covered") (n-of (:drafted S) "drafted") (n-of (:uncovered S) "uncovered") (n-of (:unit S) "unit level") (n-of (:gate S) "gate-verified") (n-of (count (all :drafts)) "drafts") (n-of (count (all :oqs)) "open questions"))
       [:div {:class ["note" (when-not (or (pos? (:uncovered S)) (seq (all :unknown))) "ok")]}
        (code (str "must-cover " (:must S) " | covered " (:covered S) " | drafted " (:drafted S) " | uncovered " (:uncovered S) " | unit-level " (:unit S) " | gate-verified " (:gate S)))]]
      (when (seq (all :unknown)) [:section [:h2 "Unknown tags"] [:p.muted "Tags naming no obligation " (code "allium plan") " derives: a renamed rule, or a typo."] (qlist (all :unknown))])
      [:section [:h2 "Drafts awaiting acceptance"] [:p.muted "Scenarios proposed for obligations no accepted scenario covers; each opens the rule where it is shown in full. The harness does not read them until they are accepted and stamped."]
       (if (seq (all :drafts)) (qlist (all :drafts)) (none "No drafts."))]
      [:section [:h2 "Uncovered obligations"] [:p.muted "By context. Each opens its rule card, where the obligation sits under its " (code "requires") " or its examples."] [:div.flow-list (or (seq (keep :uncovered parts)) (none "None."))]]
      [:section [:h2 "Verified by a gate"] [:p.muted "Guarantees and contract invariants whose prose says \"Verified by: gate\": a gate checks them, not a scenario."] (if (seq (all :gate)) (qlist (all :gate)) (none "None."))]
      [:section [:h2 "Unit level, set aside"] [:p.muted "Refusals a unit test checks, not the black box: a refusal on the aggregate's own state, a pick between two rules, a redelivery, or a clause guarded upstream."]
       (qlist (all :unit))]
      [:section [:h2 "Open questions"] [:p.muted "Allium " (code "open question") "s, listed on each Allium file's page."]
       (if (seq (all :oqs)) (qlist (all :oqs)) (none "None."))])))

;; ---------- the page ----------
(defn page [built]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:title "Löyly spec v2 — event model"]
    [:meta {:name "viewport" :content "width=device-width,initial-scale=1,viewport-fit=cover"}]
    [:meta {:name "claude:viewport-fit-sides" :content "cover"}]
    [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
    [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin ""}]
    [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=Familjen+Grotesk:wght@500;600;700&family=Instrument+Sans:wght@400;500;600&family=JetBrains+Mono:wght@400;500&display=swap"}]
    [:style {:innerHTML (slurp (str here "/page.css"))}]
    [:script {:src "https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js"}]]
   [:body
    [:div.wrap
     (view-home built) (view-review)
     (for [c CTXS] (in-ctx (:id c) 'views))
     [:footer "Built on " built " by " (code "tools/spec/views/build.sh") " from spec v2 in " (code ".pi/spec/<context>/") " at " (code (:commit world)) (when (:dirty world) " (with local changes)") ": " (code "allium/*.allium") " (behaviour), the Grain Event Model fragments " (code "event-model/*.edn")
      " (topology, merged by " (code "tools/spec/v2/load.clj") "), " (code "spec.feature") " and " (code "drafts.feature") " (examples), and the coverage gate's own classification (" (code "tools/spec/v2/coverage.py --report")
      "). Nothing here is written by hand: change the specs and rebuild."]]
    [:script {:innerHTML (slurp (str here "/page.js"))}]
    (when app? [:script#review-app {:innerHTML (if (:app-js opts) (slurp (:app-js opts)) "")}])]])

(let [{:keys [out built mode]} opts]
  (fs/create-dirs (fs/parent (fs/absolutize out)))
  (spit out (str "<!doctype html>\n" (r/render (page built))))
  (println mode "views" (+ 2 (reduce + (for [c CTXS] (count (in-ctx (:id c) 'views))))) "· wrote" out))
