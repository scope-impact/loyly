;; One bounded context of spec v2, as views: its context page, one page per Allium file (the
;; event storm, entities and lifecycles, rules with their refusals and Given/When/Then,
;; invariants and guarantees) and one per Event Model block. render.clj loads this file once
;; per context, into a namespace of its own, after common.clj and with `spec` (the context,
;; from extract.clj) and `world` (every context) defined. Ids carry the context's name, so the
;; six contexts share one page.
(require '[clojure.string :as str] '[cheshire.core :as json])

(def AREAS (:areas spec))
(def MAIN (:id spec))   ; the context's Grain service area has its name
(def CX MAIN)
(defn label [k] (if (and (ns-of k) (not= (ns-of k) MAIN)) (str (ns-of k) " · " (short k)) (short k)))

;; ---------- the context's model; WALL, every context's blocks, for links across contexts ----------
(def KINDS (array-map :commands "Command" :events "Event" :read-models "Read model" :queries "Query"
                      :todo-processors "Policy (to-do processor)" :periodic-tasks "Timer (periodic task)" :screens "Screen"))
(def CLS {:commands "cmd" :events "evt" :read-models "rm" :queries "rm" :todo-processors "proc" :periodic-tasks "proc" :screens "scr"})
(def STEP {"command" :commands "event" :events "read-model" :read-models "query" :queries
           "todo-processor" :todo-processors "periodic-task" :periodic-tasks "screen" :screens})
(def ALL (into {} (for [k (keys KINDS)] [k (into (sorted-map) (mapcat #(get % k) (vals AREAS)))])))
(def WALL (into {} (for [k (keys KINDS)] [k (into (sorted-map) (for [c (:contexts world) a (vals (:areas c)) e (get a k)] e))])))
(def FLOWS (into (sorted-map) (mapcat :flows (vals AREAS))))
(def MODS (:modules spec))
(def scenarios (:scenarios spec))
(defn fid [mid] (str/replace (str mid) #"^.*--" ""))   ; "measurement--checklist" -> "checklist"
(def rule-list (for [[mid m] MODS r (:rules m)] (assoc r :mid mid)))
(def RULES (into {} (map (juxt :name identity) rule-list)))
(def SURFACES (for [[mid m] MODS s (:surfaces m)] (assoc s :mid mid)))
(def TAG (reduce (fn [acc s] (reduce (fn [a t] (if (str/starts-with? t "@allium-") (update a (subs t 8) (fnil conj []) s) a)) acc (:tags s))) {} scenarios))
(defn mod-of-spec [sp] (let [[_ c f] (re-find #"([\w-]+)/allium/([\w-]+)\.allium$" (str sp)) m (str c "--" f)] (when (MODS m) m)))
(def owner   ; a block's aggregate: the Allium file of the rule it links; an event, its command's
  (let [o (reduce (fn [o [k key b]] (if (o key) o (assoc o key (some (comp mod-of-spec :spec) (:allium b)))))
                  {} (for [k (keys KINDS) [key b] (ALL k)] [k key b]))]
    (reduce (fn [o [key b]] (reduce #(if (o %2) %1 (assoc %1 %2 (o key))) o (:produces b))) o (:commands ALL))))
(def CTX {:id MAIN :name (human MAIN) :description (:description spec)
          :scen (filter #(= "spec" (:kind %)) scenarios) :drafts (filter #(= "drafts" (:kind %)) scenarios)})
(defn em-file [key] (or (some #(:file (get % key)) (vals WALL)) ""))
(defn mod-title [mid] (human (fid mid)))
(defn agg-hash [mid] (str "mod-" mid))

;; ---------- obligations: the coverage gate's own classification ----------
(def ob-list (:obligations spec))
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
(defn mod-obligations [mid] (filter #(= (str (fid mid) ".allium") (:file %)) ob-list))
(defn mod-scen [mid kind]   ; the scenarios tagged with an obligation of this Allium file
  (let [ids (set (map :id (mod-obligations mid)))]
    (filter #(and (= kind (:kind %)) (some (fn [t] (and (str/starts-with? t "@allium-") (ids (subs t 8)))) (:tags %))) scenarios)))
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
(def ENTITIES (into {} (for [[mid m] MODS e (sort-by (complement :external) (:entities m))] [(:name e) {:e e :mid mid :p (parse-entity (:src e))}])))
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
        link (fn [n] (if (= mid (:mid (ENTITIES n))) [:a {:href (str "#ent-" CX "--" n)} n]
                         (list [:a {:href (str "#" (agg-hash (:mid (ENTITIES n))))} n] " " (muted "(" (mod-title (:mid (ENTITIES n))) ")"))))
        root? (= root (:name e))]
    [:article {:class ["ent" (when root? "root")] :id (str "ent-" CX "--" (:name e))}
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
(defn kind-of [key prefer] (if (and prefer (get-in WALL [prefer key])) prefer (first (filter #(get-in WALL [% key]) (keys KINDS)))))
(defn blk-hash [key kind] (str "blk-" (if (= "rm" (CLS kind)) (if (= kind :queries) "q" "rm") (CLS kind)) "-" (ns-of key) "--" (short key)))
(def BLK (into (array-map) (for [kind (keys KINDS) key (keys (ALL kind))] [(blk-hash key kind) [key kind]])))   ; this context's block pages
(def WBLK (set (for [kind (keys KINDS) key (keys (WALL kind))] (blk-hash key kind))))   ; every context's
(defn block-link [key kind & [sub]]
  (let [k (or (kind-of key kind) kind) h (blk-hash key k)]
    (st (get CLS k "scr") (label key) sub (when (WBLK h) h))))
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
(defn rid [n] (str "rule-" CX "--" (str/replace (str n) #"[^A-Za-z0-9]" "-")))
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
                      [:summary (if (= "drafts" (:kind s)) "draft" (str (:item-tag cfg) (:item s))) " · " (:title s) " " (tier-chip (:tier s))]
                      (gherkin s)])))
(defn rule-card
  "One rule as a review card. `anchored?`: the copy on its aggregate's page, which owns the ids
  rule-<Rule> and rule-<Rule>-<n>; the copies on block pages carry none."
  [r anchored? & [own-id?]]
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
    [:article.spec {:id (when (and anchored? (not (false? own-id?))) (rid (:name r)))}
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

(def ctx-hash (str "ctx-" CX))
(def ctx-crumb [(:name CTX) ctx-hash])

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
(defn agg-row [[mid m]]
  (let [cv (cov-count (mod-obligations mid))
        nprops (+ (count (:invariants m)) (reduce + (map (comp count :guarantees) (:surfaces m))) (reduce + (map (comp count :invariants) (:contracts m))))]
    [:div.agg-row
     [:div.agg-name [:a {:href (str "#" (agg-hash mid))} (mod-title mid)] [:span.muted {:style {:font-size "12px"}} (fid mid) ".allium"]
      [:a.drill {:href (str "#" (agg-hash mid))} "Open →"]]
     [:div [:div.row-wrap (map #(st "scr" %) (distinct (remove str/blank? (map actor-of (:rules m)))))]
      [:div.row-wrap {:style {:margin-top "6px"}} (map #(block-link % :commands) (mod-blocks :commands mid))]]
     [:div [:div.row-wrap (or (seq (map #(block-link % :events) (mod-blocks :events mid))) [:span.muted {:style {:font-size "12.5px"}} "none on the event model"])]]
     [:div [:div.stack {:style {:font-size "12.5px"}} [:span [:b (count (:rules m))] " rules"] [:span [:b (reduce + (map (comp count requires-of) (:rules m)))] " refusals"]
            [:span [:b nprops] " invariants and guarantees"] (when (seq (:openQuestions m)) (muted (count (:openQuestions m)) " open questions"))]]
     [:div {:style {:font-size "12.5px"}} [:b (count (mod-scen mid "spec"))] " scenarios" [:br] (muted (count (mod-scen mid "drafts")) " drafts") [:br] (muted (cv "spec") " covered · " (cv "none") " uncovered")]]))
(defn ctx-link [c] (st "scr" (human c) "context" (str "ctx-" c)))
(defn handoff-row
  "One handoff: its producer, the event (defined in the producer's handoffs.edn), and each
  consumer context with the blocks that read it. data-edge marks producer|event|consumer."
  [{:keys [producer event consumers]}]
  [:div.handoff
   (ctx-link producer) [:span.arr "→"] (block-link event :events "handoff") [:span.arr "→"]
   [:span.stack (if (seq consumers)
                  (for [{c :ctx bs :blocks} consumers]
                    [:span.row-wrap {:data-edge (str producer "|" event "|" c)} (ctx-link c) (for [[bk k] bs] (block-link bk k))])
                  (muted "no consumer on the event model"))]])
(defn obligation-details [title cv id-line none-text & [body]]
  [:details.inv [:summary [:span.t title] [:span.meta (badge cv)] [:span.id id-line]]
   body
   [:div.scens (or (seq (map gherkin (:sc cv))) (none none-text))]])
;; ---------- the glossary (optional): a term's definition, where the spec gives no description ----------
(def GLOSS (or (when-let [g (:glossary cfg)]
                 (let [f (clojure.java.io/file (:root cfg) g)]
                   (when (.exists f)
                     (into {} (for [[_ t d] (re-seq #"(?m)^\*\*([^*\n]+)\*\*:\s*\n((?:[^\n]+\n)+)" (slurp f))]
                                [(str/lower-case (str/trim t)) (str/trim (str/replace (str/replace d #"(?m)^_Avoid_.*$" "") #"\s+" " "))])))))
               {}))
(defn gloss [n] (get GLOSS (str/lower-case (str n))))
(defn describe [b n] (or (not-empty (:description b)) (gloss n)))

;; ---------- what changed since the base ref (CHG, from diff.clj; nil without --base) ----------
(defn mark [k id] (get-in CHG [k id]))
(defn chg-badge [m] (when m [:span {:class ["chg" (name m)] :title (str (if (= m :new) "new" "changed") " since " (:base CHG))} (if (= m :new) "new" "changed")]))
(defn scen-mark [s] (mark :scen [CX (:kind s) (if (= "spec" (:kind s)) (:item s) (:title s))]))
(defn blk-mark [kind key] (mark :blocks [(or (ns-of key) CX) kind key]))
(defn merge-marks [ms] (let [ms (remove nil? ms)] (cond (empty? ms) nil (every? #{:new} ms) :new :else :changed)))
(defn one-line [d] (when (seq d) (let [s (or (second (re-find #"^(.*?[.!?])(\s|$)" d)) d)] (if (> (count s) 150) (str (subs s 0 148) "…") s))))
(defn ctx-name [c] (human c))

;; ---------- L2: a scenario, as a reader reads it ----------
(defn scenario-plain [s]
  (let [m (scen-mark s) draft? (= "drafts" (:kind s))]
    [:div {:class ["sc" (when draft? "draft") (when m "marked")] :data-scenario (or (:item s) (:title s))}
     [:div.sc-h [:b (:title s)] (when draft? [:span.draft-tag "draft"]) (chg-badge m)]
     [:div.sc-steps
      (for [l (:steps s) :let [[w more] (str/split l #" " 2)]]
        (if (or (str/starts-with? l "|") (str/starts-with? l "Examples:"))
          [:div.sc-row l]
          [:div.sc-step [:span {:class ["kw" (kw-class w)]} w] " " more]))]
     [:div.sc-meta (if draft? (muted "draft, not stamped") [:span.item (str (:item-tag cfg) (:item s))]) (tier-chip (:tier s)) [:span.from (:file s) ":" (:line s)]
      (if draft? (review (str "draft:" (:slice s) ":" (:title s)) "accept" "comment") (review (str "scenario:" (:item s)) "comment"))]]))
(defn scenarios-of [covs]   ; the distinct scenarios of some obligations, accepted first
  (let [ss (distinct (mapcat :sc covs))] (concat (filter #(= "spec" (:kind %)) ss) (remove #(= "spec" (:kind %)) ss))))

(defn rule-covs [r] (cons (cov (str "rule-success." (:name r))) (map-indexed (fn [i _] (cov (str "rule-failure." (:name r) "." (inc i)))) (requires-of r))))
(defn rule-plain [r]   ; the rule in plain words: its Gherkin Rule lines, else its Allium comment
  (let [g (distinct (keep :rule (scenarios-of (rule-covs r))))]
    {:rules g :doc (:doc r)}))
(defn rule-mark [r] (merge-marks (cons (mark :rules [CX (:name r)]) (map scen-mark (scenarios-of (rule-covs r))))))

(defn spec-card
  "The review unit (L2): a title, the rule in plain words, its scenarios readable, and L3 (`hood`)
  collapsed beneath."
  [{:keys [id kind title plain doc who scs badge m hood hood-title]}]
  (let [nd (count (filter #(= "drafts" (:kind %)) scs)) ns (- (count scs) nd)]
    [:article {:class ["card2" (when m "marked")] :id id :data-card kind}
     [:header.c2-h [:div [:span.lbl kind] [:h3 title (chg-badge m)]]
      [:div.c2-meta (when badge (list [:span.cnt (if (= kind "rule") "success:" "checked:")] badge)) [:span.cnt (plural ns "scenario")] (when (pos? nd) [:span.cnt.drafts (plural nd "draft")])]]
     (when (seq plain) [:div.plain (for [p plain] [:p p])])
     (when doc [:p.doc doc])
     (when who [:p.who who])
     (if (seq scs) [:div.scs (map scenario-plain scs)] (none "No scenario yet."))
     [:details.hood [:summary [:span "Under the hood"] [:span.muted hood-title]] [:div.hood-body hood]]]))

(defn rule-spec-card [r]
  (let [{:keys [rules doc]} (rule-plain r) g (gwt-of r) a (actor-of r)]
    (spec-card {:id (rid (:name r)) :kind "rule" :title (human (:name r)) :plain rules :doc doc
                :who (list "Triggered by " [:b (:when g)])
                :scs (scenarios-of (rule-covs r)) :badge (badge (cov (str "rule-success." (:name r)))) :m (rule-mark r)
                :hood-title (str "Allium rule " (:name r) ", its refusals, its Grain block")
                :hood (rule-card r true false)})))

(defn prop-data [mid m]   ; invariants, guarantees and boundary invariants, each as a card's data
  (for [p (concat (for [i (:invariants m)] {:id (str "invariant." (:name i)) :name (:name i) :kind "invariant" :src (:src i) :doc (:doc i)})
                  (for [s (:surfaces m) g (:guarantees s)] {:id (str "guarantee." (:name g)) :name (:name g) :kind "guarantee" :on (:name s) :text (:text g)})
                  (for [k (:contracts m) i (:invariants k)] {:id (str "boundary." (:name i)) :name (:name i) :kind "boundary" :on (:name k) :text (:text i)}))
        :let [c (cov (:id p)) scs (scenarios-of [c]) txt (some-> (:text p) (str/replace #"\s*Verified by:.*$" "") not-empty)]]
    {:id (str "prop-" CX "--" (str/replace (:id p) #"[^A-Za-z0-9]" "-")) :kind (:kind p) :title (human (:name p))
     :plain (keep identity [txt]) :doc (:doc p)
     :who (when (:on p) (list (if (= "boundary" (:kind p)) "Held at the boundary by contract " "Promised by surface ") [:b (:on p)]))
     :scs scs :badge (badge c) :m (merge-marks (cons (mark :props [CX (:name p)]) (map scen-mark scs)))
     :hood-title (str "Allium " (:kind p) " " (:name p) " · @allium-" (:id p))
     :hood (list (when (:src p) (allium (:src p))) (when (:text p) [:p.muted (:text p)])
                 (when (= "gate" (:k c)) [:p.muted "Checked by gate " (:gate (:o c)) ", not by a scenario."]))}))
(defn prop-cards [mid m] (map spec-card (prop-data mid m)))

;; ---------- the changed cards, for the summary on L0 ----------
(defn changed-cards []
  (let [sid (fn [ss] (for [s ss :when (scen-mark s)] (if (= "spec" (:kind s)) (str (:item-tag cfg) (:item s)) (str "draft “" (:title s) "”"))))
        journeys (filter #(not-any? (fn [t] (and (str/starts-with? t "@allium-") (not (str/starts-with? t "@allium-context.")))) (:tags %)) scenarios)]
    (concat
      (for [[mid m] MODS r (:rules m) :let [r (RULES (:name r)) mk (rule-mark r)] :when mk]
        {:href (str "#" (rid (:name r))) :title (human (:name r)) :m mk :kind (str "rule · " (mod-title mid)) :scs (sid (scenarios-of (rule-covs r)))})
      (for [[mid m] MODS d (prop-data mid m) :when (:m d)]
        {:href (str "#" (:id d)) :title (:title d) :m (:m d) :kind (str (:kind d) " · " (mod-title mid)) :scs (sid (:scs d))})
      (for [[r ss] (ordered-groups #(or (:rule %) "(no Rule)") journeys) :let [mk (merge-marks (map scen-mark ss))] :when mk]
        {:href (str "#" ctx-hash) :title r :m mk :kind "journey" :scs (sid ss)}))))

;; ---------- L1: a flow as swimlanes: who → command → event → read model / view ----------
(def LANES [["Who triggers it" #{"screen" "todo-processor" "periodic-task"}] ["Command" #{"command"}] ["Event" #{"event"}] ["Read model · view" #{"read-model" "query"}]])
(def LCLS {"screen" "scr" "todo-processor" "proc" "periodic-task" "proc" "command" "cmd" "event" "evt" "read-model" "rm" "query" "rm"})
(def LKIND {"screen" "screen" "todo-processor" "policy" "periodic-task" "timer" "command" "command" "event" "event" "read-model" "read model" "query" "query"})
(defn rule-of-cmd [key] (first (filter #(= key (first (rule-block %))) rule-list)))
(defn producer-cmd [ev] (first (for [[ck c] (:commands ALL) :when (some #{ev} (:produces c))] ck)))
(defn node-href [kind key]
  (let [k (STEP kind) r (cond (= k :commands) (rule-of-cmd key) (= k :events) (some-> (producer-cmd key) rule-of-cmd))]
    (cond r (str "#" (rid (:name r)))
          (WBLK (blk-hash key (kind-of key k))) (str "#" (blk-hash key (kind-of key k)))
          :else (str "#ctx-" (ns-of key)))))
(defn handoff-to [ev] (for [h (:handoffs world) :when (= ev (:event h)) c (:consumers h)] (:ctx c)))
(defn flow-lanes [fk f]
  (let [nodes (distinct (for [[ak an zk zn] (:steps f) nd [[ak an] [zk zn]] :when (first nd)] nd))
        rows (filter (fn [[_ ks]] (some #(ks (first %)) nodes)) LANES)]
    [:div.lanes-frame
     [:div.lanes {:style {:grid-template-columns (str "112px repeat(" (count nodes) ", 168px)") :grid-template-rows (str "auto repeat(" (count rows) ", auto)")}}
      [:div.lane-h.corner "step"]
      (for [i (range (count nodes))] [:div.step-n {:style {:grid-column (str (+ 2 i)) :grid-row "1"}} (inc i)])
      (map-indexed (fn [ri [lbl _]] [:div.lane-h {:style {:grid-row (str (+ 2 ri))}} lbl]) rows)
      (map-indexed (fn [ri _] [:div.lane-bg {:style {:grid-row (str (+ 2 ri)) :grid-column (str "2 / span " (count nodes))}}]) rows)
      (map-indexed
        (fn [i [kind key]]
          (let [ri (first (keep-indexed (fn [j [_ ks]] (when (ks kind) j)) rows))
                b (get-in WALL [(STEP kind) key]) d (or (describe b (human (short key))) "") other (and (ns-of key) (not= (ns-of key) MAIN))
                outs (when (= kind "event") (handoff-to key))]
            [:a {:class ["ln" (LCLS kind) (when (blk-mark (STEP kind) key) "marked")] :href (node-href kind key)
                 :style {:grid-column (str (+ 2 i)) :grid-row (str (+ 2 ri))} :title d :data-node (str kind ":" key)}
             [:span.ln-k (LKIND kind) (chg-badge (blk-mark (STEP kind) key))]
             [:b (human (short key))]
             (when (seq d) [:span.ln-d (one-line d)])
             (when other [:span.ln-x "from " (ctx-name (ns-of key))])
             (when (seq outs) [:span.ln-x "hands off to " (str/join ", " (map ctx-name (distinct outs)))])]))
        nodes)]]))
(defn flow-section [[fk f] open?]
  (let [m (merge-marks [(mark :flows [CX fk])])
        body (list (when (:description f) [:p.flow-d (:description f)]) (flow-lanes fk f))]
    (if open?
      [:div.flow-box {:data-flow fk} [:h3 (human (short fk)) (chg-badge m)] body]
      [:details.flow-box {:data-flow fk} [:summary [:b (human (short fk))] (chg-badge m) " " [:span.muted (one-line (:description f))]] body])))

;; ---------- this context's events, for the event storm (L0) ----------
(defn story-events []
  (vec (for [[k b] (:events ALL) :when (= MAIN (ns-of k))
             :let [ck (producer-cmd k) r (some-> ck rule-of-cmd) a (some-> r actor-of)]]
         {:key k :ctx CX :name (human (short k)) :desc (describe b (human (short k))) :href (node-href "event" k) :mark (merge-marks [(blk-mark :events k) (some->> ck (blk-mark :commands)) (some-> r rule-mark)])
          :cmd (some-> ck short human) :actor (when (and (seq a) (not= a "on event")) (if (= a "time") "Time" a))})))

;; ---------- plain-language gaps: what the spec does not yet say in words ----------
(defn gaps []
  (concat (when (str/blank? (:description spec)) [(str CX ": the context has no :description")])
          (for [k [:commands :events :read-models :queries :screens :todo-processors :periodic-tasks] [bk b] (ALL k) :when (and (= MAIN (ns-of bk)) (str/blank? (describe b (human (short bk)))))]
            (str CX ": " (name k) " " bk " has no :description"))
          (for [[fk f] FLOWS :when (str/blank? (:description f))] (str CX ": flow " fk " has no :description"))
          (for [r rule-list :let [{:keys [rules doc]} (rule-plain r)] :when (empty? rules)]
            (str CX ": rule " (:name r) (if (str/blank? doc) " has no Gherkin Rule line and no Allium comment" " has no scenario with a Gherkin Rule line: its card is headed by its Allium comment")))))

(defn view-ctx []
  (let [rows (map agg-row MODS)
        hs (:handoffs world)
        h-out (filter #(= CX (:producer %)) hs)
        h-in (filter #(some (fn [c] (= CX (:ctx c))) (:consumers %)) hs)
        journeys (filter #(not-any? (fn [t] (and (str/starts-with? t "@allium-") (not (str/starts-with? t "@allium-context.")))) (:tags %)) scenarios)
        npol (+ (count (:todo-processors ALL)) (count (:periodic-tasks ALL)))
        procs (concat (for [[k p] (:todo-processors ALL)] (proc-row k p :todo-processors)) (for [[k p] (:periodic-tasks ALL)] (proc-row k p :periodic-tasks)))
        rms (for [[k rm] (:read-models ALL)] (rm-row k rm))
        bound (for [m (vals MODS) k (:contracts m) i (:invariants k) :let [cv (cov (str "boundary." (:name i)))]]
                (obligation-details (list [:b (:name i)] " · " (str/replace (:text i) #"Verified by:.*$" "")) cv (str "contract " (:name k) " · @allium-boundary." (:name i))
                                    (if (= "gate" (:k cv)) (str "Checked by gate " (:gate (:o cv)) ", not by a scenario.") "No scenario.")))
        ext (for [m (vals MODS) e (:entities m) :when (:external e)] (st "ext" (:name e)))
        actors (mapcat :actors (vals MODS))
        oq (mapcat :openQuestions (vals MODS))
        flows (sort-by (fn [[_ f]] (- (count (:steps f)))) FLOWS)]
    (view ctx-hash [home-crumb ctx-crumb]
      [:div.head
       [:div.eyebrow "Level 1 · event model · bounded context" (when (:accepted spec) (str " · slice " (:slice spec)))]
       [:h1 (:name CTX)]
       [:p.lede (:description CTX)]
       (stats (n-of (count FLOWS) "flows") (n-of (count (:commands ALL)) "commands") (n-of (count (:events ALL)) "events") (n-of (count (:read-models ALL)) "read models")
              (n-of (count RULES) "rules") (n-of (count (:items spec)) "stamped items") (n-of (count (:drafts CTX)) "drafts"))]
      (if (seq flows)
        [:section [:h2 "How it flows"]
         [:p.muted "Each flow of the event model, left to right: who triggers it, the command, the event it records, and the read model or view that shows it next. Click a sticky for its spec card."]
         [:div.key [:span [:i.scr] "who triggers it"] [:span [:i.cmd] "command"] [:span [:i.evt] "event"] [:span [:i.rm] "read model / view"]]
         (map-indexed (fn [i fl] (flow-section fl (zero? i))) flows)]
        [:section [:h2 "How it flows"] (none "The event model has no flow yet.")])
      [:section
       [:h2 "Handoffs"]
       [:p.muted "The events this context hands to others (its " (code (str (get-in cfg [:files :event-model]) "/" (get-in cfg [:files :handoffs]))) ") and those it reads from others, each defined once by its producer." (T :relay-note)]
       [:div.pair [:div.stack {:style {:align-items "stretch"}} [:span.lbl "hands out"] [:div.handoffs (or (seq (map handoff-row h-out)) (muted "none"))]]
        [:div.stack {:style {:align-items "stretch"}} [:span.lbl "reads"] [:div.handoffs (or (seq (map handoff-row h-in)) (muted "none"))]]]]
      [:section
       [:h2 "Spec cards"]
       [:p.muted "The spec, one card per rule, grouped by Allium file: the rule in plain words and its Given/When/Then. Open a file to review its cards."]
       [:div.files (for [[mid m] MODS :let [rs (map #(RULES (:name %)) (:rules m)) ms (merge-marks (map rule-mark rs))]]
                     [:div {:class ["file-card" (when ms "marked")]}
                      [:a.file-h {:href (str "#" (agg-hash mid))} [:b (mod-title mid)] (chg-badge ms) [:span.muted (plural (count rs) "rule") " · " (plural (count (mod-scen mid "spec")) "scenario") (let [d (count (mod-scen mid "drafts"))] (when (pos? d) (str " · " d " drafts")))] [:span.drill "Open →"]]
                      (when (seq (:header m)) [:p.muted (one-line (:header m))])
                      (when (seq rs)
                        [:ul.rule-list (for [r rs :let [{g :rules d :doc} (rule-plain r)]]
                                         [:li [:a {:href (str "#" (rid (:name r)))} (human (:name r))] (chg-badge (rule-mark r)) " " [:span.muted (or (first g) (one-line d))]])])])]]
      (when (seq journeys)
        [:section [:h2 "Scenarios on no single rule"] [:p.muted "Scenarios of " (code (:specFile spec)) " tagged with no rule, invariant or guarantee (a journey), by their Gherkin " (code "Rule:") "."]
         [:div.flow-list (for [[r ss] (ordered-groups #(or (:rule %) "(no Rule)") journeys)]
                           [:div.card2 [:header.c2-h [:div [:span.lbl "journey"] [:h3 r (chg-badge (merge-marks (map scen-mark ss)))]]] [:div.scs (map scenario-plain ss)]])]])
      (when (seq oq) [:section [:h2 "Open questions"] [:p.muted "Allium " (code "open question") "s: what no source settles yet."] (qlist oq)])
      [:section
       [:details.hood.big
        [:summary [:span "Under the hood"] [:span.muted "aggregates, policies and read models, dynamic views, the boundary"]]
        [:div.hood-body
         [:div.note.ok "Specs in " (code (str (:spec-root cfg) "/" CX "/")) ": " (code (str (get-in cfg [:files :allium]) "/*.allium")) ", " (code (str (get-in cfg [:files :event-model]) "/*.edn")) ", " (code (get-in cfg [:files :spec]))
          (when (seq (:drafts CTX)) (list ", " (code (get-in cfg [:files :drafts])))) (when (and (:plan-dir cfg) (:accepted spec)) (list "; the slice " (code (str (:plan-dir cfg) "/" (:slice spec))) " links to it.")) (when-not (and (:plan-dir cfg) (:accepted spec)) ".")]
         (stats (list "Grain area " [:b ":" MAIN]) (n-of (count MODS) "Allium files") (n-of (count (:queries ALL)) "queries") (n-of npol "policies and timers"))
         [:h3 "Event model, by Allium file"]
         [:p.muted "One Allium file per aggregate, and the context's shared language. Who acts on it, the commands it handles, and the events it records (a block belongs to the Allium file its " (code ":grain/allium") " link names)."]
         [:div.frame [:div.agg-board [:div.agg-row.h [:div "Allium file"] [:div "Actors · commands"] [:div "Events"] [:div "Allium"] [:div "Gherkin"]] rows]]
         [:h3 "Policies, timers, read models and queries"]
         [:div.two [:div.flow-list (or (seq procs) (none "No policies or timers."))] [:div.flow-list (or (seq rms) (none "No read models."))]]
         (when (seq FLOWS)
           (list [:h3 "Flows (dynamic views)"] [:p.muted "Each " (code ":flows") " entry of the Event Model, numbered: screen → command → event → read model → query → policy, as Grain validates it."]
                 [:div.key (for [[c t] [["#e6e9e7" "screen"] ["#cfe0fb" "command"] ["#fde2bd" "event"] ["#cdeed5" "read model"] ["#e3f5e7" "query"] ["#e6d9f7" "policy or timer"]]] [:span [:i {:style {:background c}}] t])]
                 (for [[fk f] FLOWS] (list [:h4 (short fk)] (mm-block (c4-dynamic fk f) (or (:description f) ""))))))
         [:h3 "The boundary: what holds everywhere"]
         [:p.muted "The actors that may act (" (if (seq actors) (interpose ", " (map code actors)) "none") "), any contract every surface fulfils, and what others own."]
         (when (seq ext) [:div.row-wrap [:span.lbl {:style {:align-self "center"}} (T :outside)] ext])
         [:div.flow-list (or (seq bound) (none "No boundary contract."))]]]])))

;; ---------- one Event Model block: its spec first ----------
(defn refs-to [field key] (for [k (keys KINDS) [kk v] (WALL k) :when (some #{key} (get v field))] [kk k]))
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
             (around "Produced by" (map #(block-link % :commands) producers) (if (= MAIN (ns-of key)) "no command" (T :runtime))
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
      (when (seq flows) [:section [:h2 "In the flows"] [:p.muted "Drawn on " [:a {:href (str "#" ctx-hash)} (:name CTX)] ": " (interpose ", " (map #(vector :b (short %)) flows)) "."]]))))

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
(defn view-mod [[mid m]]
  (let [cv (cov-count (mod-obligations mid)) own (remove :external (:entities m))
        states (for [e own t (get-in ENTITIES [(:name e) :p :trans]) :let [d (state-diagram e t)] :when d] [(str (:name e) "." (:field t)) d])
        props (prop-cards mid m)]
    (view (agg-hash mid) [home-crumb ctx-crumb [(mod-title mid) (agg-hash mid)]]
      [:div.head
       [:div.eyebrow "Level 2 · spec cards · " (fid mid) ".allium in " (:name CTX)]
       [:h1 (mod-title mid)]
       [:p.lede (:header m)]
       (stats (n-of (count (:rules m)) "rules") (n-of (count (mod-scen mid "spec")) "scenarios") (n-of (count (mod-scen mid "drafts")) "drafts")
              (list [:b (cv "spec")] " obligations covered · " [:b (cv "draft")] " drafted · " [:b (cv "none")] " uncovered"))]
      [:section
       [:h2 "Rules"]
       [:p.muted "One card per rule: what it says in plain words, then the scenarios that show it. Drafts, not yet accepted, have a dashed amber edge. The Allium, the Grain block and the coverage of each card are under its hood."]
       [:div.cards2 (or (seq (for [r (:rules m)] (rule-spec-card (RULES (:name r))))) (none "No rules."))]]
      (when (seq props)
        [:section [:h2 "Invariants and guarantees"] [:p.muted "What always holds, what a surface promises, and what a contract holds at the boundary, each with the scenarios that check it."]
         [:div.cards2 props]])
      (when (seq (:openQuestions m)) [:section [:h2 "Open questions"] (qlist (:openQuestions m))])
      [:section
       [:details.hood.big
        [:summary [:span "Under the hood"] [:span.muted "the aggregate's event storm, entities and lifecycles, the Allium source"]]
        [:div.hood-body
         [:div.note.ok (code (:file m)) (when (:specFile spec) (list " · " (code (:specFile spec)))) (when (:draftsFile spec) (list " · " (code (:draftsFile spec))))]
         (stats (n-of (count (mod-blocks :commands mid)) "commands") (n-of (count (mod-blocks :events mid)) "events") (list [:b (cv "unit")] " obligations unit level"))
         [:h3 "Event storm of the aggregate"]
         [:p.muted "Each rule as a sticky cluster: the command (blue) and who issues it, the aggregate (yellow) over the event it records (orange), and below it each refusal, a " (code "requires") " that must hold. Read left to right: each column follows the one before it, by the state it needs, the event that triggers it or the policy that issues it."]
         (agg-board mid)
         [:h3 "Entities and lifecycles"]
         [:p.muted "Each entity with its identity, its states and what it refers to. Each lifecycle is a " (code "transitions") " block: every arrow is the rule that makes it (⏱ triggered by time)."]
         (if (seq own) (entity-cards mid m) (none "No entity of its own."))
         (when (seq states) [:div.lifecycles (for [[k d] states] [:div {:class ["lc" (when (> (count (re-seq #"-->" d)) 6) "wide")]} [:div.lc-h k] [:pre.mermaid d]])])
         [:details.inv [:summary [:span.t "Allium source"] [:span.meta (:file m)]] [:div.inv-body (map (comp allium :src) own)]]]]])))

;; ---------- this context's part of the Review view ----------
(defn review-part []
  (let [uncovered (filter #(= "uncovered" (:status %)) ob-list)
        draft-at (fn [s] (let [r (some #(let [n (second (re-find #"^@allium-rule-(?:success|failure)\.(\w+)" %))] (when (RULES n) n)) (:tags s))]
                           (cond r (rid r) :else ctx-hash)))
        oqs (for [[mid m] MODS q (:openQuestions m)] [mid q])
        at (fn [o] (if (:rule o) (rid (:rule o)) (agg-hash (some #(when (= (:file o) (str (fid %) ".allium")) %) (keys MODS)))))]
    {:uncovered (when (seq uncovered)
                  [:details.inv [:summary [:span.t [:b (:name CTX)]] [:span.meta (count uncovered) " uncovered"] [:span.id (str "tag each on the scenario that checks it, or draft one in " (get-in cfg [:files :drafts]))]]
                   [:div.inv-body (qlist (for [o uncovered] (list [:a {:href (str "#" (at o))} (code (str "@allium-" (:id o)))] (when (:clause o) (list " " (muted (:clause o)))))))]])
     :drafts (for [s (:drafts CTX)] (list [:a {:href (str "#" (draft-at s))} (:title s)] " " (muted (:file s) ":" (:line s))))
     :unit (for [o ob-list :when (= "unit" (:status o))] (list [:a {:href (str "#" (at o))} (code (:id o))] " " (muted (:name CTX) " · " (:unit o) " · " (or (:clause o) ""))))
     :gate (for [o ob-list :when (= "gate" (:status o))] (list [:a {:href (str "#" (at o))} (code (:id o))] " " (muted (:name CTX) " · gate " (:gate o) (when (:planned o) " (planned)"))))
     :unknown (for [t (:unknownTags spec)] (list (code (str "@allium-" t)) " " (muted (:name CTX))))
     :oqs (for [[mid q] oqs] (list [:a {:href (str "#" (agg-hash mid))} (:name CTX) " · " (mod-title mid)] ": " q))}))

;; ---------- the context's cover: its card on the home view, and every view it owns ----------
(defn card []
  (let [cv (cov-count ob-list) tot (reduce + (vals cv)) pct #(format "%.1f%%" (/ (* 100.0 (cv %)) (max tot 1)))]
    [:a {:class ["card" (when (seq (changed-cards)) "marked")] :href (str "#" ctx-hash) :data-context CX}
     [:h3 (when (:accepted spec) (list (:slice spec) " · ")) (:name CTX) (when-let [n (seq (changed-cards))] (list " " [:span.chg.changed (count n) " changed"]))] [:span.muted {:style {:font-size "12.5px"}} (:description CTX)]
     [:div.nums (for [[n w] [[(count MODS) "Allium files"] [(count (:commands ALL)) "commands"] [(count (:events ALL)) "events"] [(count RULES) "rules"] [(count (:items spec)) "items"] [(count (:drafts CTX)) "drafts"]]] [:div [:b n] w])]
     [:div.cov-bar {:title (str "obligations: " (cv "spec") " in scenarios, " (cv "draft") " in drafts, " (cv "unit") " unit level, " (cv "gate") " by a gate, " (cv "none") " uncovered")}
      [:i {:style {:width (pct "spec") :background "var(--accent)"}}] [:i {:style {:width (pct "draft") :background "var(--warn)"}}] [:i {:style {:width (pct "gate") :background "var(--muted)"}}]]
     [:span.muted {:style {:font-size "11.5px"}} tot " obligations: " (cv "spec") " in scenarios, " (cv "draft") " drafted, " (cv "unit") " unit level, " (cv "gate") " by a gate, " (cv "none") " uncovered"]
     [:span.src "Allium · Event Model · Gherkin"]]))
(defn views [] (concat [(view-ctx)] (map view-mod MODS) (map view-block BLK)))
