;; Render the event-model page at build time: the specs (extract.clj's `spec` map) → Hiccup,
;; one pure function per view or component → one static HTML file, rendered by Replicant.
;;   bb --config tools/spec/views/bb.edn tools/spec/views/render.clj [options]
;;     --mode page|app     page (default): the public page. app: the same page plus review
;;                         controls (data-review-action/-target buttons, data-threads-for badges)
;;                         and a <script id="review-app"> for the MCP app's wiring script
;;     --out <file>        default tools/spec/views/dist/event-model.html
;;     --app-js <file>     app mode: inline this file into <script id="review-app">
;;     --threads <edn>     app mode: open-thread counts, {"rule:Name" 2 …}, or a vector of
;;                         {:target "rule:Name" :status …} maps (counted unless :resolved)
;;     --built <date>      the footer's build date, default today
;;     --model <edn> --coverage <json>   the evaluated Event Model and the coverage report;
;;                         when absent, made here (Grain on the Clojure CLI, coverage.py)
;; Every view is a <div class="view" id="…"> in the one file; page.css shows the one the address
;; targets (home by default). page.js only draws Mermaid and sizes diagrams. Text is escaped by
;; Replicant; the page holds nothing written by hand.
(require '[clojure.string :as str] '[clojure.java.io :as io] '[clojure.edn :as edn] '[cheshire.core :as json]
         '[babashka.process :as p] '[babashka.fs :as fs] '[replicant.string :as r])
(def here (str (fs/parent (fs/absolutize *file*))))
(def opts
  (let [o (into {} (for [[k v] (partition 2 *command-line-args*)] [(keyword (str/replace k #"^--" "")) v]))
        root (str/trim (:out (p/sh {:dir here} "git" "rev-parse" "--show-toplevel")))
        tmp (str (fs/create-temp-dir))]
    (when-not (:model o)
      (p/shell {:dir root :out :string} "clojure" "-Sdeps" "{:paths [\".pi/model/event-model\"] :deps {g/cat {:local/root \"grain/projects/grain-code-agent-tools\"}}}"
               "-M" "tools/spec/grain-validate.clj" "--dump" (str tmp "/model.edn")))
    (when-not (:coverage o)   ; the gate may fail (exit 1, shown on the page); it may not crash
      (let [{:keys [exit]} (p/sh {:dir root} "python3" "tools/spec/coverage.py" "--report" (str tmp "/coverage.json"))]
        (when (> exit 1) (throw (ex-info (str "coverage.py failed (" exit ")") {})))))
    (merge {:mode "page" :out (str here "/dist/event-model.html") :built (str (java.time.LocalDate/now))
            :model (str tmp "/model.edn") :coverage (str tmp "/coverage.json")}
           o {:root root})))
(def app? (= "app" (:mode opts)))
(def threads
  (let [t (some-> (:threads opts) slurp edn/read-string)]
    (if (map? t) t (frequencies (for [x t :when (not= :resolved (keyword (name (or (:status x) :open))))] (:target x))))))
(load-file (str here "/extract.clj"))   ; defines `spec`

;; Replicant 2026.07.1 escapes `"` as `&#39;` (an apostrophe): spec text and Mermaid labels
;; would change. Escape as Hiccup does, `"` → `&quot;`.
(alter-var-root #'r/escape-html
                (constantly (fn [s] (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))))

;; ---------- names ----------
(defn short [k] (last (str/split (str k) #"/")))
(defn ns-of [k] (when (str/includes? (str k) "/") (first (str/split (str k) #"/"))))
(defn last-seg [s] (last (str/split (str s) #"[./]")))
(def AREAS (:areas spec))
(def MAIN (first (sort-by #(- (count (:flows (AREAS %)))) (keys AREAS))))
(defn label [k] (if (and (ns-of k) (not= (ns-of k) MAIN)) (str (ns-of k) " · " (short k)) (short k)))
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

;; ---------- the unified model: one bounded context, the Pi harness ----------
(def KINDS (array-map :commands "Command" :events "Event" :read-models "Read model" :queries "Query"
                      :todo-processors "Policy (to-do processor)" :periodic-tasks "Timer (periodic task)" :screens "Screen"))
(def CLS {:commands "cmd" :events "evt" :read-models "rm" :queries "rm" :todo-processors "proc" :periodic-tasks "proc" :screens "scr"})
(def STEP {"command" :commands "event" :events "read-model" :read-models "query" :queries
           "todo-processor" :todo-processors "periodic-task" :periodic-tasks "screen" :screens})
(def ALL (into {} (for [k (keys KINDS)] [k (into (sorted-map) (mapcat #(get % k) (vals AREAS)))])))
(def FLOWS (into (sorted-map) (mapcat :flows (vals AREAS))))
(def MODS (:modules spec))
(def SLICES (:slices spec))
(def scenarios (:scenarios spec))
(defn slice-of [mid] (first (filter #(= mid (:module %)) SLICES)))
(def rule-list (for [[mid m] MODS r (:rules m)] (assoc r :mid mid)))
(def RULES (into {} (map (juxt :name identity) rule-list)))
(def SURFACES (for [[mid m] MODS s (:surfaces m)] (assoc s :mid mid)))
(def TAG (reduce (fn [acc s] (reduce (fn [a t] (if (str/starts-with? t "@allium-") (update a (subs t 8) (fnil conj []) s) a)) acc (:tags s))) {} scenarios))
(defn mod-of-spec [sp] (let [m (second (re-find #"([\w-]+)\.allium$" (str sp)))] (when (MODS m) m)))
(def owner   ; a block's aggregate: the Allium file of the rule it links; an event, its command's
  (let [o (reduce (fn [o [k key b]] (if (o key) o (assoc o key (some (comp mod-of-spec :spec) (:allium b)))))
                  {} (for [k (keys KINDS) [key b] (ALL k)] [k key b]))]
    (reduce (fn [o [key b]] (reduce #(if (o %2) %1 (assoc %1 %2 (o key))) o (:produces b))) o (:commands ALL))))
(def CTX {:id MAIN :name (str (human (last (str/split MAIN #"\."))) " context") :description (or (:description (AREAS MAIN)) "")
          :scen (filter #(= "spec" (:kind %)) scenarios) :drafts (filter #(= "drafts" (:kind %)) scenarios)})
(defn em-file [key] (or (:file (AREAS (ns-of key))) ""))
(defn mod-title [mid] (if (MODS mid) (human mid) (human (str/replace (str mid) #"^\d+-" ""))))
(defn agg-hash [mid] (str "mod-" mid))

;; ---------- obligations: the coverage gate's own classification ----------
(def ob-list (get-in spec [:coverage :obligations]))
(def OB (into {} (map (juxt :id identity) ob-list)))
(defn cov [id]
  (let [o (OB id) sc (get TAG id []) n (count (filter #(= "spec" (:kind %)) sc))]
    (merge {:sc sc :o o}
           (cond (nil? o) {:k "none" :t "not an obligation"}
                 (= "covered" (:status o)) {:k "spec" :t (plural n "scenario")}
                 (= "drafted" (:status o)) {:k "draft" :t "draft"}
                 (= "unit" (:status o)) {:k "unit" :t "unit level"}
                 (= "gate" (:status o)) {:k "gate" :t (str "gate " (:gate o))}
                 :else {:k "none" :t "uncovered"}))))
(defn badge [c] [:span {:class ["cov" (:k c)]} (:t c)])
(defn requires-of [r]
  (let [fs (sort-by :n (filter #(and (= "rule-failure" (:kind %)) (= (:name r) (:rule %))) ob-list))]
    (if (seq fs) (map :clause fs) (:requires r))))
(defn mod-obligations [mid] (filter #(= (str mid ".allium") (:file %)) ob-list))
(defn cov-count [obs] (merge {"spec" 0 "draft" 0 "unit" 0 "gate" 0 "none" 0} (frequencies (map (comp :k cov :id) obs))))

;; ---------- who issues a rule's trigger; its Event Model block ----------
(defn trig [r] (second (re-find #"^(\w+)\(" (or (:when r) ""))))
(defn actor-of [r]
  (let [t (trig r) w (or (:when r) "")]
    (if-not t
      (cond (re-find #"\bnow\b" w) "time" (re-find #"\bbecomes\b" w) "on state change" :else "")
      (if-let [s (first (filter #(some #{t} (:provides %)) SURFACES))] (human (or (:facingType s) (:facing s))) "on event"))))
(def rule-block
  (memoize (fn [r] (first (for [k [:commands :todo-processors :periodic-tasks :queries :read-models :screens]
                                [key b] (ALL k) :when (some #(and (= "rule" (:kind %)) (= (:name r) (:name %))) (:allium b))]
                            [key k])))))

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

;; ---------- entities and their states, across every Allium file ----------
(defn uncomment [l] (str/trim (str/replace l #"--.*$" "")))
(defn parse-entity [src]
  (let [body (-> src (str/replace #"^[^{]*\{" "") (str/replace #"\}\s*$" ""))
        trans (for [[_ field block] (re-seq #"transitions\s+(\w+)\s*\{([\s\S]*?)\n\s*\}" body)
                    :let [ls (map uncomment (str/split-lines block))]]
                {:field field
                 :edges (vec (keep #(when-let [[_ a b] (re-find #"^(\w+)\s*->\s*(\w+)" %)] [a b]) ls))
                 :terminal (vec (mapcat #(when-let [[_ x] (re-find #"^terminal:\s*(.+)$" %)] (map str/trim (str/split x #","))) ls))})
        rest-src (str/replace body #"transitions\s+\w+\s*\{[\s\S]*?\n\s*\}" "")]
    {:trans (vec trans)
     :fields (vec (for [l (map uncomment (str/split-lines rest-src)) :let [[_ n ty] (re-find #"^(\w+):\s*(.+)$" l)] :when n]
                    (let [enum? (and (re-find #" \| " ty) (re-matches #"[\w |]+" ty))
                          derived? (and (not enum?) (or (re-find #"[=<>()]| and | or | not |\bnow\b|\bin\b" ty) (re-find #"\.\w+\s*[-+]" ty)))]
                      {:name n :type (str/trim ty) :kind (cond enum? "enum" derived? "derived" :else "field")})))}))
(def ent-order (distinct (for [[_ m] MODS e (:entities m)] (:name e))))
(def ENTITIES (into {} (for [[mid m] MODS e (:entities m)] [(:name e) {:e e :mid mid :p (parse-entity (:src e))}])))
(defn state-fields [n]
  (when-let [E (ENTITIES n)]
    (distinct (concat (map :field (get-in E [:p :trans])) (when (some #(= "status" (:name %)) (get-in E [:p :fields])) ["status"])))))
(def state-ents (filter #(seq (state-fields %)) ent-order))
(defn entity-of-var [r v]
  (let [m1 (second (re-find (re-pattern (str "\\b" v ":\\s*(?:\\w+[./])?([A-Z]\\w*)")) (:src r)))
        m2 (some-> (re-find (re-pattern (str "for " v " in (\\w+)")) (:src r)) second (str/replace #"s$" ""))]
    (cond (ENTITIES m1) m1 m2 (when (ENTITIES m2) m2)
          :else (first (filter #(or (= (snake %) v) (str/ends-with? (snake %) (str "_" v))) state-ents)))))
(defn created-args [ens]   ; [[Entity "args of Entity.created(…)"] …]
  (let [m (re-matcher #"\b(?:\w+[./])?([A-Z]\w*)\.created\(" ens)]
    (loop [out []]
      (if-not (.find m) out
        (let [s (.end m)
              e (loop [i s d 1] (if (or (>= i (count ens)) (zero? d)) i
                                    (recur (inc i) (case (nth ens i) \( (inc d) \) (dec d) d))))]
          (recur (conj out [(.group m 1) (subs ens s (dec e))])))))))
(def STATES   ; rule -> {:needs [[E f v]…] (requires, a `becomes` trigger) :sets [[E f v]…]}
  (into {} (for [r rule-list]
             (let [i (str/index-of (:src r) "ensures:") req (if i (subs (:src r) 0 i) (:src r)) ens (if i (subs (:src r) i) "")
                   take (fn [path f v] (let [E (entity-of-var r (last (str/split path #"\.")))] (when (and E (some #{f} (state-fields E))) [[E f v]])))
                   b (re-find #"(\w+):\s*(?:\w+[./])?([A-Z]\w*)\.(\w+)\s+becomes\s+(\w+)" (or (:when r) ""))]
               [(:name r)
                {:needs (vec (concat (mapcat (fn [[_ p f v]] (take p f v)) (re-seq #"(?m)requires:\s*([\w.]+)\.(\w+)\s*=\s*(\w+)\s*$" req))
                                     (when (and b (ENTITIES (nth b 2))) [(vec (drop 2 b))])))
                 :sets (vec (concat (mapcat (fn [[_ p f v]] (take p f v)) (re-seq #"(?m)^\s*(?:ensures:\s*)?([\w.]+)\.(\w+)\s*=\s*(\w+)\s*$" ens))
                                    (for [[E args] (created-args ens) f (state-fields E)
                                          :let [v (second (re-find (re-pattern (str "\\b" f ":\\s*(\\w+)")) args))] :when v]
                                      [E f v])))}]))))
(defn st-label [[E f v]] (str E "." f " = " v))
(defn timed? [r] (re-find #"(<=|>=|<|>)\s*now\b" (or (:when r) "")))
(defn state-rules [ent t]   ; {:lab {[a b] [rule…]} :init {state [rule…]}}
  (reduce
    (fn [acc r]
      (let [s (STATES (:name r)) nm (str (when (timed? r) "⏱ ") (:name r))
            froms (set (keep (fn [[E f v]] (when (and (= E ent) (= f (:field t))) v)) (:needs s)))
            created (some #(= ent (first %)) (created-args (subs (:src r) (max 0 (or (str/index-of (:src r) "ensures:") 0)))))]
        (reduce (fn [acc [_ _ to]]
                  (if created (update-in acc [:init to] (fnil conj []) nm)
                      (reduce (fn [acc [a b]] (if (and (= b to) (or (empty? froms) (froms a))) (update-in acc [:lab [a b]] (fnil conj []) nm) acc))
                              acc (:edges t))))
                acc (filter (fn [[E f]] (and (= E ent) (= f (:field t)))) (:sets s)))))
    {:lab {} :init {}} rule-list))
(defn base-type [ty] (-> ty (str/replace #"^\w+[./]" "") (str/replace #"\?$" "") (str/replace #"^(Set|List)<(?:\w+[./])?(\w+)>$" "$2")))

(defn entity-card [mid root e]
  (let [p (:p (ENTITIES (:name e))) sf (set (state-fields (:name e)))
        ids (filter #(and (re-find #"^identity$|_identity$" (:name %)) (not (ENTITIES (base-type (:type %))))) (:fields p))
        states (filter #(sf (:name %)) (:fields p))
        refs (filter #(and (= "field" (:kind %)) (ENTITIES (base-type (:type %))) (not= (:name e) (base-type (:type %)))) (:fields p))
        shown (set (map :name (concat ids states refs)))
        more (remove #(shown (:name %)) (:fields p))
        values (fn [f] (let [t (first (filter #(= (:name f) (:field %)) (:trans p)))
                             vs (cond t (distinct (flatten (:edges t))) (= "enum" (:kind f)) (map str/trim (str/split (:type f) #"\|")))]
                         (if (seq vs) (for [v vs] [:span.ev-chip v]) (code (base-type (:type f))))))
        link (fn [n] (if (= mid (:mid (ENTITIES n))) [:a {:href (str "#ent-" n)} n]
                         (list [:a {:href (str "#" (agg-hash (:mid (ENTITIES n))))} n] " " (muted "(" (mod-title (:mid (ENTITIES n))) ")"))))
        root? (= root (:name e))]
    [:article {:class ["ent" (when root? "root")] :id (str "ent-" (:name e))}
     [:div.ent-h [:b (:name e)] [:span.ent-k (if root? "aggregate root" "entity")]]
     (when (seq ids) [:div.ent-r [:span "identity"] [:div (interpose " " (map (comp code :name) ids))]])
     (for [f states] [:div.ent-r [:span (:name f)] [:div (values f)]])
     (when (seq refs) [:div.ent-r [:span "refers to"] [:div (interpose [:br] (for [f refs] (list (link (base-type (:type f))) " " (muted "as " (:name f)))))]])
     (when (seq more)
       [:details.ent-more [:summary (plural (count more) "more field")]
        (for [f more] [:div (code (:name f)) " " (muted (if (= "derived" (:kind f)) "derived" (base-type (:type f))))])])]))
(defn entity-cards [mid m]
  (let [own (remove :external (:entities m))
        score (fn [e] (+ (* 10 (reduce + (map (comp count :edges) (get-in ENTITIES [(:name e) :p :trans])))) (count (get-in ENTITIES [(:name e) :p :fields]))))
        root (:name (or (first (filter #(= mid (str/replace (snake (:name %)) "_" "-")) own)) (first (sort-by (comp - score) own))))
        ext (filter :external (:entities m))]
    (list [:div.ents (map #(entity-card mid root %) (concat (filter #(= root (:name %)) own) (remove #(= root (:name %)) own)))]
          (when (seq ext) [:p.muted "External, owned elsewhere: " (interpose " " (for [e ext] [:span.ev-chip.ext (:name e)]))]))))
(defn state-diagram [e t]
  (when (seq (:edges t))
    (let [{:keys [lab init]} (state-rules (:name e) t)
          starts (if (seq init) (keys init) [(ffirst (:edges t))])
          lines (concat (for [s starts] (str "    [*] --> " s (when-let [l (init s)] (str " : " (str/join ", " (distinct l))))))
                        (for [[a b] (:edges t)] (str "    " a " --> " b (when-let [l (lab [a b])] (str " : " (str/join ", " (distinct l))))))
                        (for [x (:terminal t)] (str "    " x " --> [*]")))]
      (str "stateDiagram-v2\n    direction LR\n" (str/join "\n" lines)))))

;; ---------- Allium source and Grain blocks, drawn ----------
(def KW #"\b(entity|external|rule|when|requires|ensures|invariant|surface|facing|context|exposes|provides|contracts|fulfils|transitions|terminal|contract|for|in|implies|and|or|not|exists|if|use|as|config|let|becomes|actor|enum|value)\b")
(defn allium [src]
  [:pre.code
   (interpose "\n" (for [l (str/split-lines src) :let [i (str/index-of l "--") c (if i (subs l 0 i) l)]]
                     (list (map-indexed (fn [j x] (if (odd? j) [:span.k x] x)) (str/split (str/replace c KW "\u0000$1\u0000") #"\u0000" -1))
                           (when i [:span.c (subs l i)]))))])
(def TYPES {":string" "text" "string?" "text" ":int" "whole number" "int?" "whole number" "pos-int?" "positive whole number" ":double" "number" "number?" "number"
            ":boolean" "yes / no" "boolean?" "yes / no" ":keyword" "keyword" ":uuid" "id" "uuid?" "id" "inst?" "timestamp" ":inst" "timestamp" ":any" "anything" ":nil" "nothing" "nil?" "nothing"})
(defn unkw [x] (str/replace (str x) #"^:" ""))
(declare m-table)
(defn m-type [t]
  (if-not (vector? t)
    (if (string? t) (pr-str t) (get TYPES (str t) (unkw t)))
    (let [[head & rest0] t opts (when (map? (first rest0)) (first rest0)) rest (if opts (next rest0) rest0)
          lit #(cond (string? %) % (vector? %) (m-type %) :else (str %))]
      (case (str head)
        ":map" (m-table t)
        ":enum" (list "one of " (interpose " " (for [x rest] (code (lit x)))))
        (":vector" ":sequential" ":set") (list "list of " (m-type (first rest)) (when (:min opts) (list " " (muted "(at least " (:min opts) ")"))))
        ":maybe" (list (m-type (first rest)) " " (muted "or nothing"))
        ":or" (interpose (list " " (muted "or") " ") (map m-type rest))
        ":tuple" (list "(" (interpose ", " (map m-type rest)) ")")
        ":=" (list "exactly " (code (lit (first rest))))
        ":map-of" (list "map of " (m-type (first rest)) " to " (m-type (second rest)))
        ":fn" (m-type (first rest))
        (list (unkw head) " " (interpose " " (map m-type rest)))))))
(defn m-table [t]
  (let [rows (for [e (rest t) :when (vector? e) :let [[k a b] e opts (when (map? a) a) ty (if opts b a)]]
               [:tr [:td (code (unkw k)) (when (true? (:optional opts)) (list " " [:span.opt "optional"]))] [:td (m-type ty)]])]
    [:table.schema [:tbody (if (seq rows) rows [:tr [:td.muted {:colspan "2"} "no fields"]])]]))
(defn edn-raw [key b]
  (let [row (fn [k v] (list "  " [:span.q (str ":" k)] " " v))
        set-of #(str "#{" (str/join " " (map (fn [x] (str ":" x)) %)) "}")]
    [:pre.code
     (interpose "\n" (concat [[:span.k (str ":" key)]]
                             (keep identity
                                   [(when (:description b) (row "description" (json/generate-string (:description b))))
                                    (when (:schema-src b) (row "schema" (:schema-src b)))])
                             (for [k [:subscribes :consumes :reads :queries :commands :produces] :when (seq (b k))] (row (name k) (set-of (b k))))
                             (keep identity
                                   [(when (:schedule b) (row "schedule" (json/generate-string (:schedule b))))
                                    (when (seq (:allium b)) (row "grain/allium" (str "[" (str/join " " (for [a (:allium b)] (str "{:spec \"" (:spec a) "\" :kind :" (:kind a) " :name \"" (:name a) "\"}"))) "]")))])))]))
(defn kind-of [key prefer] (if (and prefer (get-in ALL [prefer key])) prefer (first (filter #(get-in ALL [% key]) (keys KINDS)))))
(defn blk-hash [key kind] (str "blk-" (if (= "rm" (CLS kind)) (if (= kind :queries) "q" "rm") (CLS kind)) "-" (ns-of key) "--" (short key)))
(def BLK (into (array-map) (for [kind (keys KINDS) key (keys (ALL kind))] [(blk-hash key kind) [key kind]])))
(defn block-link [key kind & [sub]]
  (let [k (or (kind-of key kind) kind) h (blk-hash key k)]
    (st (get CLS k "scr") (label key) sub (when (BLK h) h))))
(defn edn-block [key b show-desc kind0]
  (let [kind (kind-of key kind0)
        conn (for [[f lbl k] [[:subscribes "listens to" :events] [:consumes "built from" :events] [:reads "reads" nil] [:queries "shows" :queries]
                              [:commands "issues" :commands] [:produces (if (= kind :commands) "produces" "issues") (if (= kind :commands) :events :commands)]]
                   :when (seq (b f))]
               (list [:div.k lbl] [:div.row-wrap (for [x (b f)] (block-link x (or k (if (#{:todo-processors :screens} kind) :queries :read-models))))]))]
    [:div.edn
     [:div.edn-h [:span {:class ["st" (get CLS kind "scr")]} (short key) [:small (get KINDS kind "block") " · :" (ns-of key)]]]
     (when (and show-desc (:description b)) [:p.edn-d (:description b)])
     [:div.edn-grid
      (when (:schema b) (list [:div.k "schema"] [:div (m-type (:schema b))]))
      conn
      (when (:schedule b) (list [:div.k "schedule"] [:div (json/generate-string (:schedule b))]))
      (when (seq (:allium b)) (list [:div.k "Allium"] [:div.row-wrap (for [a (:allium b)] [:span.al (:kind a) " " [:b (:name a)]])]))]
     [:details.src [:summary "Grain · " (em-file key)] (edn-raw key b)]]))

;; ---------- one rule: Given / When / Only if / Then / Examples ----------
(defn rid [n] (str "rule-" (str/replace (str n) #"[^A-Za-z0-9]" "-")))
(defn ensures-parts [src]
  (when-let [at (str/index-of src "ensures:")]
    (let [stmts (reduce (fn [[out depth] raw]
                          (let [line (str/replace raw #"--.*$" "")
                                opens (- (count (re-seq #"\(" line)) (count (re-seq #"\)" line)))]
                            (if (and (seq out) (pos? depth))
                              [(update-in out [(dec (count out)) :t] str " " (str/trim line)) (+ depth opens)]
                              (let [t (str/replace (str/trim line) #"^ensures:\s*" "")]
                                (if (str/blank? t) [out depth]
                                    [(conj out {:t t :ind (if (re-find #"^\s*ensures:" line) 0 (max 0 (- (count (re-find #"^\s*" raw)) 8)))}) opens])))))
                        [[] 0] (str/split-lines (str/replace (subs src at) #"\n\}\s*$" "")))]
      (for [{:keys [t ind]} (first stmts) :let [d (max 0 (Math/round (/ ind 4.0)))]]
        (if-let [[_ e] (re-find #"^(?:\w+[./])?([A-Z]\w*)\.created\(" t)] ["creates" e d]
          (if-let [[_ q e] (re-find #"^(?:(\w+)[./])?([A-Z]\w*)\(" t)] ["emits" (str (when (and q (re-find #"^[a-z]" q)) (str q ".")) e) d]
            (if-let [[_ _ f v] (re-find #"^([\w.]+)\.(\w+)\s*=\s*(.+)$" t)] ["sets" (str f " = " v) d]
              (if-let [[_ w] (re-find #"^(if|else|for|let)\b" t)]
                [(get {"else" "otherwise" "for" "for each" "let" "let"} w "if") (-> t (str/replace #"^(if|else|for|let)\s*" "") (str/replace #":$" "")) d]
                ["also" t d]))))))))
(def gwt-of   ; Given = the facts that left the aggregate in the state the rule requires; When = its command or trigger
  (memoize
    (fn [r]
      (let [s (get STATES (:name r) {:needs [] :sets []}) others (remove #(= (:name r) (:name %)) rule-list)
            given (for [[E f v] (:needs s) :let [prods (filter #(some #{[E f v]} (:sets (STATES (:name %)))) others)]]
                    {:state (st-label [E f v]) :by (map :name prods)
                     :events (distinct (mapcat #(if (seq (:emits %)) (map last-seg (:emits %)) (keep identity [(trig %)])) prods))})
            t (trig r) blk (rule-block r) actor (actor-of r)
            trig-event? (and t (some (fn [o] (some #(= t (last-seg %)) (:emits o))) others))]
        {:given given :blk blk
         :trigger (when (and trig-event? (not (some #(some #{t} (:events %)) given))) t)
         :when (cond (timed? r) (str "time passes: " (:when r))
                     (and blk (= :commands (second blk))) (str (when (and (seq actor) (not= actor "on event")) (str actor " · ")) (human (short (first blk))))
                     t (str (human t) " happens")
                     :else (human (:name r)))}))))
(defn given-lines [g]
  (concat (for [x (:given g)] (if (seq (:events x)) (list (str/join " or " (map human (:events x))) " " (muted "(" (:state x) ")")) (muted "the aggregate is " (:state x))))
          (when (:trigger g) [(list (human (:trigger g)) " " (muted "(triggers it)"))])))

(defn suggest-for [s]
  (let [has (fn [k] (some #(str/starts-with? % (str k " ")) (:steps s)))
        r (some #(RULES (-> % (str/replace #"^@allium-rule-(success|failure)\." "") (str/replace #"\.\d+$" "")))
                (filter #(re-find #"^@allium-rule-(success|failure)\." %) (:tags s)))]
    (when (and r (not (and (has "Given") (has "When"))))
      (let [g (gwt-of r) ls (given-lines g)]
        [:div.suggest [:span.lbl "suggested from the event model · rule " (:name r)]
         (when-not (has "Given")
           (if (seq ls) (map-indexed (fn [i l] [:div.step [:span.kw.given (if (zero? i) "Given" "And")] " " l]) ls)
               [:div.step [:span.kw.given "Given"] " " (muted "nothing yet (the rule starts the lifecycle)")]))
         (when-not (has "When") [:div.step [:span.kw.when "When"] " " (:when g)])]))))
(defn gherkin [s]
  [:div {:class ["gw" (when (= "drafts" (:kind s)) "draft")]}
   [:div.tags (for [t (:tags s)] [:span {:class ["tag" (when (or (str/starts-with? t "@blocked") (= t "@draft")) "blocked")]} t]) (tier-chip (:tier s))]
   [:div.title "Scenario: " (:title s)
    (if (= "drafts" (:kind s)) (review (str "draft:" (:slice s) ":" (:title s)) "accept" "comment") (review (str "scenario:" (:item s)) "comment"))]
   (for [l (:steps s) :let [[w more] (str/split l #" " 2)]] [:div.step [:span {:class ["kw" (kw-class w)]} w] " " more])
   (suggest-for s)
   [:div.from (:file s) ":" (:line s) (when (:rule s) (str " · Rule: " (:rule s)))]])
(defn examples [list]
  (seq (for [s list] [:details {:class ["ex" (when (= "drafts" (:kind s)) "draft")]}
                      [:summary (if (= "drafts" (:kind s)) "draft" (str "@item-" (:item s))) " · " (:title s) " " (tier-chip (:tier s))]
                      (gherkin s)])))
(defn rule-card
  "One rule as a review card. `anchored?`: the copy on its aggregate's page, which owns the ids
  rule-<Rule> and rule-<Rule>-<n>; the copies on block pages carry none."
  [r anchored?]
  (let [blk (rule-block r) ok (cov (str "rule-success." (:name r))) reqs (requires-of r)
        fail-covs (map-indexed (fn [i _] (cov (str "rule-failure." (:name r) "." (inc i)))) reqs)
        tiers (distinct (for [c (cons ok fail-covs) s (:sc c) :when (= "spec" (:kind s))] (:tier s)))
        refusals (map-indexed (fn [i q] (let [id (str "rule-failure." (:name r) "." (inc i)) cv (nth fail-covs i)]
                                          [:li.rq {:id (when anchored? (str (rid (:name r)) "-" (inc i)))} [:span.n (inc i)]
                                           [:div.rq-body [:code.cl q]
                                            [:div.rq-meta (badge cv) (muted "@allium-" id) (review (str "obligation:" id) "comment")
                                             (when (= "unit" (:k cv)) (muted "checked by a unit test: a refusal on the aggregate's own state, a pick between two rules, or a redelivery"))]
                                            (examples (:sc cv))]]))
                              reqs)
        thens (for [[k t d] (ensures-parts (:src r))] [:li {:style {:margin-left (str (* d 22) "px")}} [:span {:class ["verb" (str/replace k " " "")]} k] (when (seq t) (code t))])
        given (given-lines (gwt-of r))]
    [:article.spec {:id (when anchored? (rid (:name r)))}
     [:header.spec-h [:div [:span.lbl "rule"] [:h3 (:name r)] (review (str "rule:" (:name r)) "comment" "discuss")]
      [:div.spec-tags (st "scr" (let [a (actor-of r)] (if (seq a) a "on event")) "issued by") (when blk (block-link (first blk) (second blk) "Event Model")) (badge ok) (map tier-chip tiers)]]
     [:div.spec-grid
      [:div.k "Given"] [:div (if (seq given) [:ul.then (for [l given] [:li [:span.verb.emits "event"] [:span l]])] (muted "nothing yet: this rule starts the lifecycle"))]
      [:div.k "When"] [:div [:code.cl (or (:when r) "")]]
      [:div.k "Only if"] [:div (if (seq refusals) [:ol.rqs refusals] (muted "nothing can refuse it"))]
      [:div.k "Then"] [:div (if (seq thens) [:ul.then thens] (muted "no effect stated"))]
      [:div.k "Examples"] [:div (or (examples (:sc ok)) (muted "no scenario" (when (= "none" (:k ok)) (str ": @allium-rule-success." (:name r) " is uncovered"))))]]
     [:details.src [:summary "Source: " (:file (MODS (:mid r))) (when blk (str " · " (em-file (first blk))))]
      [:div.pair (allium (:src r)) (when blk (edn-block (first blk) (get-in ALL [(second blk) (first blk)]) true (second blk)))]]]))

;; ---------- Mermaid ----------
(defn mq [t] (-> (str t) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "#quot;")))
(defn nid [t] (str "n_" (str/replace (str t) #"[^A-Za-z0-9]" "_")))
(def KCLS {"command" "cmd" "event" "evt" "read-model" "rm" "query" "q" "todo-processor" "pol" "periodic-task" "pol" "screen" "scr"})
(defn c4-dynamic [fk f]
  (let [steps (filter #(nth % 2) (:steps f))
        nodes (distinct (concat (for [[ak an zk zn] steps p [[zk zn] (when ak [ak an])] :when p] p)))
        id (fn [[k n]] (nid (str k "_" n)))]
    (str "---\ntitle: \"Dynamic diagram: " (mq (short fk)) " (:" (mq (ns-of fk)) " flow)\"\n---\nflowchart LR\n"
         (str/join "\n" (for [[k n :as nd] nodes] (str "    " (id nd) "[\"<b>" (mq (label n)) "</b><br/>[" (mq (get {"todo-processor" "policy" "periodic-task" "timer" "read-model" "read model"} k k)) "]\"]")))
         "\n"
         (str/join "\n" (keep-indexed (fn [i [ak an zk zn]] (when (and zk ak) (str "    " (id [ak an]) " -->|\"" (inc i) "\"| " (id [zk zn])))) (:steps f)))
         "\n    classDef cmd fill:#cfe0fb,stroke:#1d3f7a,color:#1d3f7a\n    classDef evt fill:#fde2bd,stroke:#7a4207,color:#7a4207\n    classDef rm fill:#cdeed5,stroke:#1c5a2c,color:#1c5a2c\n    classDef q fill:#e3f5e7,stroke:#1c5a2c,color:#1c5a2c\n    classDef pol fill:#e6d9f7,stroke:#4b2a7a,color:#4b2a7a\n    classDef scr fill:#e6e9e7,stroke:#2d3833,color:#2d3833\n    linkStyle default stroke:#2d3833,stroke-width:1.7px\n"
         (str/join "\n" (for [[k :as nd] nodes] (str "    class " (id nd) " " (get KCLS k "scr")))))))
(defn mm-block [src note]
  (list [:div.mm [:button.size {:type "button" :aria-pressed "false"} "Actual size"] [:pre.mermaid src]]
        (when note [:p.c4-note note])))
(defn c4 [n] (first (filter #(str/starts-with? (:title %) (str "Level " n)) (:c4 spec))))
(defn c4-section [n heading]
  (when-let [d (c4 n)] [:section [:h2 heading] (mm-block (:source d) (list "From " (code ".pi/architecture.md") ", " (:title d) "."))]))

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
(def home-crumb ["Pi Harness" "home"])
(def ctx-crumb [(:name CTX) "ctx"])

;; ---------- L1: the Pi harness ----------
(defn view-home []
  (let [nrules (count RULES) cv (cov-count ob-list) tot (reduce + (vals cv))
        pct #(format "%.1f%%" (/ (* 100.0 (cv %)) (max tot 1)))
        ncmd (count (:commands ALL)) nev (count (:events ALL))]
    (view "home" [home-crumb]
      [:div.head
       [:div.eyebrow "Level 1 · the big picture"]
       [:h1 "The Pi harness, as one event model"]
       [:p.muted (:description CTX) " One bounded context, specified in three layers; ORC's " (code ":sheet") " blocks are the facts it dispatches and projects. Open the context for its aggregates, one per Allium file."]
       (stats (n-of (count MODS) "Allium files") (n-of nrules "rules") (n-of ncmd "commands") (n-of nev "events") (n-of (count SLICES) "slices") (n-of (count (:scen CTX)) "scenarios") (n-of (count (:drafts CTX)) "drafts"))]
      [:section [:div.cards
                 [:a.card {:href "#ctx"}
                  [:h3 (:name CTX)] [:span.muted {:style {:font-size "12.5px"}} "Grain service area " (code (str ":" MAIN))]
                  [:div.nums (for [[n w] [[(count MODS) "aggregates"] [ncmd "commands"] [nev "events"] [nrules "rules"] [(count (:scen CTX)) "scenarios"] [(count (:drafts CTX)) "drafts"]]] [:div [:b n] w])]
                  [:div.cov-bar {:title (str "obligations: " (cv "spec") " in scenarios, " (cv "draft") " in drafts, " (cv "unit") " unit level, " (cv "gate") " by a gate, " (cv "none") " uncovered")}
                   [:i {:style {:width (pct "spec") :background "var(--accent)"}}] [:i {:style {:width (pct "draft") :background "var(--warn)"}}] [:i {:style {:width (pct "gate") :background "var(--muted)"}}]]
                  [:span.muted {:style {:font-size "11.5px"}} tot " obligations: " (cv "spec") " in scenarios, " (cv "draft") " drafted, " (cv "unit") " unit level, " (cv "none") " uncovered"]
                  [:span.src "Allium · Event Model · Gherkin"]]]]
      [:section
       [:h2 "Each part is specified in three layers"]
       [:div.layers
        [:div.layer [:span.lbl "behaviour"] [:b "Allium"] (muted (code ".pi/model/*.allium") ": one file per aggregate. Its state and lifecycle, the rules that change it (" (code "when") " · " (code "requires") " · " (code "ensures") "), invariants, and the surfaces that say who may act.")]
        [:div.layer [:span.lbl "topology"] [:b "Event Model"] (muted (code ".pi/model/event-model/**/event_model.clj") ": Grain's " (code "defeventmodel") " blocks (commands, events with schemas, read models, queries, policies) and the flows that join them. Each block links back to its Allium rule.")]
        [:div.layer [:span.lbl "examples"] [:b "Gherkin"] (muted (code "plan/NN-slug/spec.feature") " and " (code "drafts.feature") ": Given/When/Then, each scenario stamped " (code "@item-NN-SSS") " with its build tier, and tagged " (code "@allium-…") " with the obligations it checks. A rule's success and each of its refusals is one obligation.")]]]
      (c4-section 1 "System context (C4 level 1)")
      (c4-section 2 "Containers (C4 level 2)"))))

;; ---------- L2: the context ----------
(defn proc-row [k p kind]
  (let [ins (concat (map #(block-link % :events) (:subscribes p)) (map #(block-link % :queries "to-do") (:reads p))
                    (when (= kind :periodic-tasks) [(st "scr" "clock" (if (:schedule p) (-> (json/generate-string (:schedule p)) (str/replace #"[{}\"]" "") (str/replace "," ", ")) ""))]))]
    [:div.flow [:span.row-wrap ins] [:span.arr "→"] (block-link k kind (if (= kind :periodic-tasks) "timer" "policy")) [:span.arr "→"]
     [:span.row-wrap (map #(block-link % :commands) (:produces p))] (when (:description p) [:p (:description p)])]))
(defn rm-row [k rm]
  (let [qs (for [[q b] (:queries ALL) :when (some #{k} (:reads b))] q)]
    [:div.flow [:span.row-wrap (map #(block-link % :events) (:consumes rm))] [:span.arr "→"]
     (block-link k :read-models "read model") [:span.arr "→"] [:span.row-wrap (map #(block-link % :queries "query") qs)]
     (when (:description rm) [:p (:description rm)])]))
(defn mod-blocks [kind mid] (filter #(= mid (owner %)) (keys (ALL kind))))
(defn slice-count [sl kind] (count (filter #(and (= (:id sl) (:slice %)) (or (nil? kind) (= kind (:kind %)))) scenarios)))
(defn agg-row [[mid m]]
  (let [sl (slice-of mid) cv (cov-count (mod-obligations mid))
        nprops (+ (count (:invariants m)) (reduce + (map (comp count :guarantees) (:surfaces m))))]
    [:div.agg-row
     [:div.agg-name [:a {:href (str "#" (agg-hash mid))} (mod-title mid)] [:span.muted {:style {:font-size "12px"}} (:file m)]
      (when sl [:span.row-wrap (st "scr" (:id sl) "slice")]) [:a.drill {:href (str "#" (agg-hash mid))} "Open →"]]
     [:div [:div.row-wrap (map #(st "scr" %) (distinct (remove str/blank? (map actor-of (:rules m)))))]
      [:div.row-wrap {:style {:margin-top "6px"}} (map #(block-link % :commands) (mod-blocks :commands mid))]]
     [:div [:div.row-wrap (or (seq (map #(block-link % :events) (mod-blocks :events mid))) [:span.muted {:style {:font-size "12.5px"}} "none on the event model"])]]
     [:div [:div.stack {:style {:font-size "12.5px"}} [:span [:b (count (:rules m))] " rules"] [:span [:b (reduce + (map (comp count requires-of) (:rules m)))] " refusals"]
            [:span [:b nprops] " invariants and guarantees"] (when (seq (:openQuestions m)) (muted (count (:openQuestions m)) " open questions"))]]
     [:div {:style {:font-size "12.5px"}} [:b (if sl (slice-count sl "spec") 0)] " scenarios" [:br] (muted (if sl (slice-count sl "drafts") 0) " drafts") [:br] (muted (cv "spec") " covered · " (cv "none") " uncovered")]]))
(defn obligation-details [title cv id-line none-text & [body]]
  [:details.inv [:summary [:span.t title] [:span.meta (badge cv)] [:span.id id-line]]
   body
   [:div.scens (or (seq (map gherkin (:sc cv))) (none none-text))]])
(defn view-ctx []
  (let [rows (concat (map agg-row MODS)
                     (for [s SLICES :when (not (:module s))]
                       [:div.agg-row [:div.agg-name [:a {:href (str "#" (agg-hash (:id s)))} (mod-title (:id s))] [:span.row-wrap (st "scr" (:id s) "slice")] [:a.drill {:href (str "#" (agg-hash (:id s)))} "Open →"]]
                        [:div.muted {:style {:font-size "12.5px"}} "no Allium file"] [:div] [:div.muted {:style {:font-size "12.5px"}} "no spec"]
                        [:div {:style {:font-size "12.5px"}} [:b (slice-count s nil)] " scenarios"]]))
        npol (+ (count (:todo-processors ALL)) (count (:periodic-tasks ALL)))
        procs (concat (for [[k p] (:todo-processors ALL)] (proc-row k p :todo-processors)) (for [[k p] (:periodic-tasks ALL)] (proc-row k p :periodic-tasks)))
        rms (for [[k rm] (:read-models ALL)] (rm-row k rm))
        bound (for [m (vals MODS) k (:contracts m) i (:invariants k) :let [cv (cov (str "boundary." (:name i)))]]
                (obligation-details (list [:b (:name i)] " · " (str/replace (:text i) #"Verified by:.*$" "")) cv (str "contract " (:name k) " · @allium-boundary." (:name i))
                                    (if (= "gate" (:k cv)) (str "Checked by gate " (:gate (:o cv)) ", not by a scenario.") "No scenario.")))
        ext (for [m (vals MODS) e (:entities m) :when (:external e)] (st "ext" (:name e)))
        actors (mapcat :actors (vals MODS))
        oq (mapcat :openQuestions (vals MODS))]
    (view "ctx" [home-crumb ctx-crumb]
      [:div.head
       [:div.eyebrow "Level 2 · bounded context"]
       [:h1 (:name CTX)]
       [:p.muted (:description CTX)]
       (stats (list "Grain area " [:b ":" MAIN]) (n-of (count MODS) "aggregates") (n-of (count (:commands ALL)) "commands") (n-of (count (:events ALL)) "events")
              (n-of (count (:read-models ALL)) "read models") (n-of (count (:queries ALL)) "queries") (n-of npol "policies and timers") (n-of (count (:scen CTX)) "scenarios") (n-of (count (:drafts CTX)) "drafts"))
       [:div.note.ok "Specs in " (code ".pi/model/") ": " (code "*.allium") ", " (interpose ", " (for [a (vals AREAS)] (code (str/replace (:file a) ".pi/model/" "")))) ", " (code "plan/NN-slug/spec.feature")
        (when (seq (:drafts CTX)) (list ", " (code "drafts.feature"))) "."]]
      [:section
       [:h2 "Event model, by aggregate"]
       [:p.muted "One Allium file per aggregate, with its Gherkin slice. Who acts on it, the commands it handles, and the events it records (a block belongs to the Allium file its " (code ":grain/allium") " link names). Open one for its rules, refusals and Given/When/Then."]
       [:div.frame [:div.agg-board [:div.agg-row.h [:div "Aggregate · slice"] [:div "Actors · commands"] [:div "Events"] [:div "Allium"] [:div "Gherkin"]] rows]]]
      [:section
       (when-let [d (c4 3)] (list [:h2 "Components (C4 level 3)"] (mm-block (:source d) (list "From " (code ".pi/architecture.md") ", " (:title d) "."))))
       [:details.inv [:summary [:span.t "Policies, timers, read models and queries, with their descriptions"] [:span.meta npol " policies · " (count (:read-models ALL)) " read models"]]
        [:div.inv-body [:div.two [:div.flow-list (or (seq procs) (none "No policies or timers."))] [:div.flow-list (or (seq rms) (none "No read models."))]]]]]
      (when (seq FLOWS)
        [:section [:h2 "Flows (dynamic views)"] [:p.muted "Each " (code ":flows") " entry of the Event Model, numbered: screen → command → event → read model → query → policy, as Grain validates it."]
         [:div.key (for [[c t] [["#e6e9e7" "screen"] ["#cfe0fb" "command"] ["#fde2bd" "event"] ["#cdeed5" "read model"] ["#e3f5e7" "query"] ["#e6d9f7" "policy or timer"]]] [:span [:i {:style {:background c}}] t])]
         (for [[fk f] FLOWS] (list [:h3 (short fk)] (mm-block (c4-dynamic fk f) (or (:description f) ""))))])
      [:section
       [:h2 "The boundary: what holds everywhere"]
       [:p.muted "The actors that may act (" (if (seq actors) (interpose ", " (map code actors)) "none") "), any contract every surface fulfils, and what others own."]
       (when (seq ext) [:div.row-wrap [:span.lbl {:style {:align-self "center"}} "outside the harness"] ext])
       [:div.flow-list (or (seq bound) (none "No boundary contract."))]]
      (when (seq oq) [:section [:h2 "Open questions"] [:p.muted "Allium " (code "open question") "s: what no source settles yet."] (qlist oq)]))))

;; ---------- one Event Model block: its spec first ----------
(defn refs-to [field key] (for [k (keys KINDS) [kk v] (ALL k) :when (some #{key} (get v field))] [kk k]))
(defn around [l1 xs1 none1 l2 xs2 none2]
  [:section [:h2 "Around it"]
   [:div.pair [:div.stack {:style {:align-items "stretch"}} [:span.lbl l1] [:div.row-wrap (or (seq xs1) (when none1 (muted none1)))]]
    [:div.stack {:style {:align-items "stretch"}} [:span.lbl l2] [:div.row-wrap (or (seq xs2) (muted none2))]]]])
(defn view-block [[h [key kind]]]
  (let [b (get-in ALL [kind key]) mid (owner key)
        rules (keep #(when (= "rule" (:kind %)) (RULES (:name %))) (:allium b))
        [spec-part around-part]
        (case kind
          :events
          (let [producers (for [[kk v] (:commands ALL) :when (some #{key} (:produces v))] kk)
                shown (distinct (for [kk producers a (:allium (get-in ALL [:commands kk])) :when (= "rule" (:kind a)) :let [r (RULES (:name a))] :when r] r))]
            [(list [:section [:h2 "Spec"] [:p.muted "The event's schema, then the Allium rule" (when (not= 1 (count shown)) "s") " whose command records it, with " (if (= 1 (count shown)) "its" "their") " obligations and Given/When/Then."] (edn-block key b false kind)]
                   (when (seq shown) [:section [:h2 "Emitted by"] [:div.flow-list (map #(rule-card % false) shown)]]))
             (around "Produced by" (map #(block-link % :commands) producers) (if (= MAIN (ns-of key)) "no command" "ORC's runtime")
                     "Consumed by" (for [[kk k] (concat (refs-to :consumes key) (refs-to :subscribes key))] (block-link kk k)) "no read model or policy")])
          :commands
          (let [issuers (concat (remove #(= :commands (second %)) (refs-to :produces key)) (refs-to :commands key))]
            [[:section [:h2 "Spec"] [:p.muted "The Event Model block, and the Allium rule it carries out: who issues it, each " (code "requires") " that refuses it, what it ensures, and the Given/When/Then behind every obligation."]
              [:div.flow-list (or (seq (map #(rule-card % false) rules)) (edn-block key b false kind))]]
             (around "Issued by" (concat (map #(st "scr" %) (remove str/blank? (distinct (map actor-of rules)))) (for [[kk k] issuers] (block-link kk k))) nil
                     "Produces" (map #(block-link % :events) (:produces b)) "no event")])
          (let [ins (concat (map #(block-link % :events) (:consumes b)) (map #(block-link % :events) (:subscribes b)) (map #(block-link % (if (= kind :queries) :read-models :queries)) (:reads b)))
                outs (concat (map #(block-link % :commands) (:produces b)) (map #(block-link % :commands) (:commands b)) (map #(block-link % :queries) (:queries b))
                             (for [[kk k] (refs-to :reads key) :when (not= k kind)] (block-link kk k)) (for [[kk k] (refs-to :queries key)] (block-link kk k)))]
            [[:section [:h2 "Spec"] (edn-block key b false kind)
              (cond (seq rules) [:div.flow-list (map #(rule-card % false) rules)]
                    (seq (:allium b)) [:p.muted "Linked to Allium " (interpose ", " (for [a (:allium b)] (list (:kind a) " " (code (:name a))))) "."])]
             (around "Fed by" ins "none" "Feeds" (distinct outs) "none")]))
        flows (for [[fk f] FLOWS :when (some (fn [[ak a zk z]] (or (and (= a key) (= (STEP ak) kind)) (and (= z key) (= (STEP zk) kind)))) (:steps f))] fk)]
    (view h (concat [home-crumb ctx-crumb] (when mid [[(mod-title mid) (agg-hash mid)]]) [[(label key) h]])
      [:div.head
       [:div.eyebrow (KINDS kind) " in :" (ns-of key) (when mid (str " · aggregate " (mod-title mid)))]
       [:h1 (short key)]
       (when (:description b) [:p.muted (:description b)])
       [:div.note.ok (code (em-file key)) (when (seq rules) (list " · " (code (:file (MODS (:mid (first rules))))))) (when mid (list " · " [:a {:href (str "#" (agg-hash mid))} "open the aggregate"]))]]
      spec-part around-part
      (when (seq flows) [:section [:h2 "In the flows"] [:p.muted "Drawn on " [:a {:href "#ctx"} (:name CTX)] ": " (interpose ", " (map #(vector :b (short %)) flows)) "."]]))))

;; ---------- the aggregate as an event-storming board ----------
;; Columns by the order rules follow one another (the state one sets and the next needs, the event
;; that triggers, the policy that issues); each cluster names the rules it follows, in place of
;; the connector arrows the runtime page drew.
(defn agg-board [mid]
  (let [m (MODS mid) ents (remove :external (:entities m)) rules (map #(RULES (:name %)) (:rules m))
        S #(STATES (:name %))
        agg-of (fn [r] (or (first (or (first (:sets (S r))) (first (:needs (S r))))) (ffirst (created-args (:src r))) (:name (first ents)) (mod-title mid)))
        many (> (count (distinct (map agg-of rules))) 1)
        ev-key (fn [e] (first (filter #(= (short %) (kebab (last-seg e))) (keys (:events ALL)))))
        alias (fn [e] (when (re-find #"[./]" e) (first (str/split e #"[./]"))))
        edges (for [a rules b rules :when (not= a b)
                    e [(let [shared (distinct (for [[E f x :as s] (:sets (S a)) :when (some #{s} (:needs (S b)))] (str (when many (str E ".")) x)))]
                         (when (seq shared) [a b "state" (str/join ", " shared)]))
                       (when (some #(and (not (alias %)) (= % (trig b))) (:emits a)) [a b "event" (human (trig b))])]
                    :when e] e)
        policy-of (fn [r] (distinct (for [[pk p] (concat (:todo-processors ALL) (:periodic-tasks ALL)) k (:produces p) :when (= k (first (rule-block r)))] (short pk))))
        outs (fn [r] (for [[x y] edges :when (= x r)] y))
        starts (concat (filter #(and (seq (:sets (S %))) (empty? (:needs (S %)))) rules) (filter (fn [r] (not-any? #(= r (second %)) edges)) rules))
        back (let [seen (atom #{}) on (atom #{}) back (atom #{})]
               (letfn [(walk [r] (swap! seen conj r) (swap! on conj r)
                         (doseq [y (outs r)] (cond (@on y) (swap! back conj [(:name r) (:name y)]) (not (@seen y)) (walk y)))
                         (swap! on disj r))]
                 (doseq [r (concat starts rules) :when (not (@seen r))] (walk r)))
               @back)
        forward (remove (fn [[x y]] (back [(:name x) (:name y)])) edges)
        layer (reduce (fn [l _] (reduce (fn [l [x y]] (let [la (inc (l x))] (if (> la (l y)) (assoc l y la) l))) l forward))
                      (zipmap rules (repeat 0)) rules)
        pairs (for [[[a b] es] (ordered-groups (fn [[a b]] [a b]) edges)
                    :let [pol (policy-of b)]]
                {:a a :kind (cond (some #(= "state" (nth % 2)) es) "state" (some #(= "event" (nth % 2)) es) "event" :else "policy")
                 :b b :lbl (str/join " · " (distinct (concat (for [e es :when (not= "event" (nth e 2))] (nth e 3)) pol)))})
        cluster
        (fn [r]
          (let [blk (rule-block r) t (trig r) actor (actor-of r)
                grain (when (= :commands (second blk)) (for [e (:produces (get-in ALL [:commands (first blk)]))] [:a.sk.evt {:href (str "#" (blk-hash e :events))} (human (short e))]))
                evs (for [e (:emits r) :when (or (empty? grain) (not (ev-key e)))
                          :let [key (ev-key e) f (some-> (alias e) ((:uses m))) sub (when (MODS f) [:small "→ " [:span (mod-title f)]])]]
                      (if key [:a.sk.evt {:href (str "#" (blk-hash key :events))} (human e) sub] [:span.sk.evt (human e) sub]))
                sets (for [[E f x] (:sets (S r))] [:span.sk.evt.quiet (str (when many (str E " ")) f " → " x)])
                refs (map-indexed (fn [i q] [:a.sk.fail {:href (str "#" (rid (:name r)) "-" (inc i)) :title q} (human (:name r)) " refused" [:br]
                                             [:small (if (> (count q) 54) (str (subs q 0 52) "…") q)]])
                                  (requires-of r))]
            [:div.clu {:data-rule (:name r)}
             [:div.aft-spot]
             [:div.agg-spot [:span.sk.agg (agg-of r)]]
             [:div.cmd-spot (if blk [:a.sk.cmd {:href (str "#" (blk-hash (first blk) (second blk)))} (human (short (first blk)))] [:a.sk.cmd {:href (str "#" (rid (:name r)))} (human (:name r))])]
             [:div.evt-spot (or (seq (concat grain evs)) (seq sets) [:span.sk.evt.quiet "no event"])]
             [:div.act-spot (cond (and (seq actor) (not= actor "on event")) [:span.sk.actor (if (= actor "time") "Time" actor)]
                                  t [:span.sk.actor.quiet "on " (human t)])]
             [:div.ref-spot refs]]))]
    [:div.storm {:data-edges (json/generate-string (for [p pairs] {:a (:name (:a p)) :b (:name (:b p)) :skip (- (layer (:b p)) (layer (:a p))) :kind (:kind p) :lbl (:lbl p)}))}
     [:div.storm-t (mod-title mid)]
     [:div.storm-key [:span [:i.ln.state] "state it reaches"] [:span [:i.ln.event] "event that triggers"] [:span [:i.ln.policy] "policy"]]
     [:div.storm-flow (for [[_ col] (sort-by key (group-by layer rules))] [:div.storm-col (map cluster (filter (set col) rules))])
      [:svg.storm-svg {:aria-hidden "true"}]]]))

;; ---------- L3: one aggregate (an Allium file, its Grain blocks, its slice) ----------
(defn slice-scenarios [sl only-unlinked?]
  (for [[r ss] (ordered-groups #(or (:rule %) "(no Rule)")
                               (filter #(and (= (:id sl) (:slice %)) (or (not only-unlinked?) (not-any? (fn [t] (str/starts-with? t "@allium-")) (:tags %)))) scenarios))]
    [:details.inv [:summary [:span.t r] [:span.meta (count ss) " scenarios"]] [:div.scens (map gherkin ss)]]))
(defn view-slice [sl]
  (view (agg-hash (:id sl)) [home-crumb ctx-crumb [(mod-title (:id sl)) (agg-hash (:id sl))]]
    [:div.head [:div.eyebrow "Level 3 · slice in " (:name CTX)] [:h1 (mod-title (:id sl))]
     [:p.muted (or (:feature sl) "") ". " (or (:description sl) "")]
     [:div.note "No Allium file: this slice is specified only in Gherkin (" (code (or (:specFile sl) "")) ")."]]
    [:section [:h2 "Rules, from the feature file"] [:div.flow-list (slice-scenarios sl false)]]))
(defn view-mod [[mid m]]
  (let [sl (slice-of mid) cv (cov-count (mod-obligations mid)) own (remove :external (:entities m))
        props (for [p (concat (for [i (:invariants m)] {:id (str "invariant." (:name i)) :name (:name i) :kind "invariant" :src (:src i)})
                              (for [s (:surfaces m) g (:guarantees s)] {:id (str "guarantee." (:name g)) :name (:name g) :kind (str "guarantee on " (:name s)) :text (:text g)}))
                    :let [c (cov (:id p))]]
                (obligation-details (list [:b (:name p)] (when (:text p) (str " · " (str/replace (:text p) #"Verified by:.*$" "")))) c (str (:kind p) " · @allium-" (:id p))
                                    (if (= "gate" (:k c)) (str "Checked by gate " (:gate (:o c)) ".") "No scenario.")
                                    (when (:src p) [:div.inv-body (allium (:src p))])))
        states (for [e own t (get-in ENTITIES [(:name e) :p :trans]) :let [d (state-diagram e t)] :when d] [(str (:name e) "." (:field t)) d])
        unlinked (when sl (seq (slice-scenarios sl true)))]
    (view (agg-hash mid) [home-crumb ctx-crumb [(mod-title mid) (agg-hash mid)]]
      [:div.head
       [:div.eyebrow "Level 3 · aggregate in " (:name CTX)]
       [:h1 (mod-title mid)]
       [:p.muted (:header m)]
       (stats (when sl (list "slice " [:b (:id sl)])) (n-of (count (mod-blocks :commands mid)) "commands") (n-of (count (:rules m)) "rules") (n-of (count (mod-blocks :events mid)) "events")
              (n-of (if sl (slice-count sl "spec") 0) "scenarios") (n-of (if sl (slice-count sl "drafts") 0) "drafts")
              (list [:b (cv "spec")] " obligations covered · " [:b (cv "draft")] " drafted · " [:b (cv "none")] " uncovered · " [:b (cv "unit")] " unit level"))
       [:div.note.ok (code (:file m)) (when sl (list " · " (code (:specFile sl)))) (when (:draftsFile sl) (list " · " (code (:draftsFile sl))))]]
      [:section [:h2 "Event storm"]
       [:p.muted "Each rule as a sticky cluster: the command (blue) and who issues it, the aggregate (yellow) over the event it records (orange), and below it each refusal, a " (code "requires") " that must hold. Read left to right: each column follows the one before it, by the state it needs, the event that triggers it or the policy that issues it; above each cluster, the rules it follows. Click a sticky for its spec."]
       (agg-board mid)]
      [:section
       [:h2 "Entities and lifecycles"]
       [:p.muted "Each entity with its identity, its states and what it refers to; other fields fold away. Each lifecycle is a " (code "transitions") " block: every arrow is the rule that makes it (⏱ triggered by time)."]
       (if (seq own) (entity-cards mid m) (none "No entity of its own."))
       (when (seq states) [:div.lifecycles (for [[k d] states] [:div {:class ["lc" (when (> (count (re-seq #"-->" d)) 6) "wide")]} [:div.lc-h k] [:pre.mermaid d]])])
       [:details.inv [:summary [:span.t "Allium source"] [:span.meta (:file m)]] [:div.inv-body (map (comp allium :src) own)]]]
      [:section
       [:h2 "Rules, with their Given/When/Then"]
       [:p.muted "Each rule beside its Event Model block. Below each, its obligations: the success, and one per " (code "requires") ". Each obligation opens the scenarios tagged with it; dashed amber ones are drafts awaiting the product owner."]
       [:div.flow-list (or (seq (for [r (:rules m)] (rule-card (RULES (:name r)) true))) (none "No rules."))]]
      (when (seq props) [:section [:h2 "Invariants and guarantees"] [:p.muted "What always holds (" (code "invariant") "), and what a surface promises (" (code "@guarantee") ")."] [:div.flow-list props]])
      (when unlinked [:section [:h2 "Scenarios not yet linked to a rule"] [:p.muted "Scenarios of " (code (:specFile sl)) " with no " (code "@allium-…") " tag, by their Gherkin " (code "Rule:") ". Tagging each with the obligations it checks moves it under its rule above."] [:div.flow-list unlinked]])
      (when (seq (:openQuestions m)) [:section [:h2 "Open questions"] (qlist (:openQuestions m))]))))

;; ---------- Review: what the product owner decides ----------
(defn view-review []
  (let [S (get-in spec [:coverage :summary]) drafts (:drafts CTX)
        oqs (for [[mid m] MODS q (:openQuestions m)] [mid q])
        uncovered (for [[mid m] MODS :let [os (filter #(and (= (str mid ".allium") (:file %)) (= "uncovered" (:status %))) ob-list)] :when (seq os)]
                    [:details.inv [:summary [:span.t [:b (mod-title mid)]] [:span.meta (count os) " uncovered"] [:span.id (:file m) " · tag each on the scenario that checks it, or draft one in drafts.feature"]]
                     [:div.inv-body (qlist (for [o os] (list [:a {:href (str "#" (if (:rule o) (rid (:rule o)) (agg-hash mid)))} (code (str "@allium-" (:id o)))] (when (:clause o) (list " " (muted (:clause o)))))))]])
        draft-at (fn [s] (let [r (some #(let [n (second (re-find #"^@allium-rule-(?:success|failure)\.(\w+)" %))] (when (RULES n) n)) (:tags s))]
                           (cond r (rid r) (:module s) (agg-hash (:module s)) :else "ctx")))
        unknown (get-in spec [:coverage :unknownTags])]
    (view "review" [["Review" "review"]]
      [:div.head
       [:div.eyebrow "Review · the coverage gate, " (:commit spec) (when (:dirty spec) " with local changes")]
       [:h1 "What the product owner reviews"]
       [:p.muted "The coverage gate's own result (" (code "tools/spec/coverage.py") "): every must-cover Allium obligation should be tagged " (code "@allium-<obligation>") " on an accepted scenario's tag line, or on a draft in the slice's " (code "drafts.feature") ". Only the product owner moves a draft into " (code "spec.feature") "."]
       (stats (n-of (:mustCover S) "must-cover") (n-of (:covered S) "covered") (n-of (:drafted S) "drafted") (n-of (:uncovered S) "uncovered") (n-of (:unitLevel S) "unit level") (n-of (count drafts) "drafts") (n-of (count oqs) "open questions"))
       [:div {:class ["note" (when-not (or (pos? (:uncovered S)) (seq unknown)) "ok")]}
        (code (str "must-cover " (:mustCover S) " | covered " (:covered S) " | drafted " (:drafted S) " | uncovered " (:uncovered S) " | unit-level " (:unitLevel S)))]]
      (when (seq unknown) [:section [:h2 "Unknown tags"] [:p.muted "Tags naming no obligation " (code "allium plan") " derives: a renamed rule, or a typo."] (qlist (for [t unknown] (code (str "@allium-" t))))])
      [:section [:h2 "Drafts awaiting acceptance"] [:p.muted "Scenarios proposed for obligations no accepted scenario covers; each opens the rule where it is shown in full. The harness does not read them until they are accepted and stamped."]
       (if (seq drafts) (qlist (for [s drafts] (list [:a {:href (str "#" (draft-at s))} (:title s)] " " (muted (:file s) ":" (:line s))))) (none "No drafts."))]
      [:section [:h2 "Uncovered obligations"] [:p.muted "By Allium file. Each opens its rule card, where the obligation sits under its " (code "requires") " or its examples."] [:div.flow-list (or (seq uncovered) (none "None."))]]
      [:section [:h2 "Unit level, set aside"] [:p.muted "Refusals a unit test checks, not the black box: a refusal on the aggregate's own state, a pick between two rules, or a redelivery."]
       (qlist (for [o ob-list :when (= "unit" (:status o))] (list [:a {:href (str "#" (rid (:rule o)))} (code (:id o))] " " (muted (:unit o) " · " (or (:clause o) "")))))]
      [:section [:h2 "Open questions"] [:p.muted "Allium " (code "open question") "s, listed on each aggregate's page."]
       (if (seq oqs) (qlist (for [[mid q] oqs] (list [:a {:href (str "#" (agg-hash mid))} (mod-title mid)] ": " q))) (none "None."))])))

;; ---------- the page ----------
(defn page [built]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:title "Pi Harness Event Model"]
    [:meta {:name "viewport" :content "width=device-width,initial-scale=1,viewport-fit=cover"}]
    [:meta {:name "claude:viewport-fit-sides" :content "cover"}]
    [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
    [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin ""}]
    [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=Familjen+Grotesk:wght@500;600;700&family=Instrument+Sans:wght@400;500;600&family=JetBrains+Mono:wght@400;500&display=swap"}]
    [:style {:innerHTML (slurp (str here "/page.css"))}]
    [:script {:src "https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js"}]]
   [:body
    [:div.wrap
     (view-home) (view-ctx) (view-review)
     (map view-mod MODS)
     (map view-slice (remove :module SLICES))
     (map view-block BLK)
     [:footer "Built on " built " by " (code "tools/spec/views/build.sh") " from the specs in " (code ".pi/model/") ": " (code "*.allium") " (behaviour), the Grain Event Model in " (code "event-model/**/event_model.clj")
      " (topology, as Grain registers it), " (code "plan/NN-slug/spec.feature") " and " (code "drafts.feature") " (examples), the coverage gate's own classification (" (code "tools/spec/coverage.py --report")
      "), and the C4 views of " (code ".pi/architecture.md") ". Nothing here is written by hand: change the specs and rebuild."]]
    [:script {:innerHTML (slurp (str here "/page.js"))}]
    (when app? [:script#review-app {:innerHTML (if (:app-js opts) (slurp (:app-js opts)) "")}])]])

(let [{:keys [out built mode]} opts]
  (fs/create-dirs (fs/parent (fs/absolutize out)))
  (spit out (str "<!doctype html>\n" (r/render (page built))))
  (println mode "views" (+ 3 (count MODS) (count (remove :module SLICES)) (count BLK)) "· wrote" out))
