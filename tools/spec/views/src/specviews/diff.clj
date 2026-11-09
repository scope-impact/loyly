;; What changed in this round: the spec now (`world`) against the spec at a base git ref, both
;; read by the same extract.clj. Loaded by render.clj when --base is given; defines `CHG`:
;;   {:base "<ref>" :base-commit "<hash>"
;;    :blocks {[ctx kind key] :new|:changed} :rules {[ctx name] …} :scen {[ctx kind id] …}
;;    :props {[ctx name] …} :flows {[ctx key] …} :removed {:rules [..] :scen [..] :blocks [..]}}
;; The base files come from `git archive <ref>` of the spec root (and the plan dir) into a
;; temporary directory; nothing is checked out and nothing is written to the work tree.
(require '[clojure.string :as str] '[clojure.walk] '[babashka.fs :as fs] '[babashka.process :as p])

(def base-world
  (let [ref (:base opts) root (:root cfg) tmp (str (fs/create-temp-dir))
        paths (remove nil? [(:spec-root cfg) (:plan-dir cfg)])
        ok (p/sh {:dir root} "git" "rev-parse" "--verify" "--quiet" (str ref "^{commit}"))
        _ (when-not (zero? (:exit ok)) (throw (ex-info (str "--base " ref ": no such commit") {})))
        prefix (str/trim (:out (p/sh {:dir root} "git" "rev-parse" "--show-prefix")))
        _ (doseq [d paths] (p/shell {:dir root :out :string} "bash" "-c" (str "git archive --format=tar '" ref ":" prefix "' -- '" d "' 2>/dev/null | tar -x -C '" tmp "' || true")))
        n (load-into 'specviews.base {'cfg (assoc cfg :root tmp :architecture nil) 'resource resource
                                      'opts {:root tmp :coverage (str tmp "/no-coverage.json")}}
                     "extract.clj")]
    (assoc @(ns-resolve n 'world)
           :ref ref :ref-commit (str/trim (:out (p/sh {:dir root} "git" "rev-parse" "--short" ref))))))

(defn- norm [x] (if (string? x) (str/replace (str/trim x) #"\s+" " ") x))
(defn- same? [a b] (= (clojure.walk/postwalk norm a) (clojure.walk/postwalk norm b)))
(defn- compare-maps [now base]   ; {k v} × {k v} → {k :new|:changed}, and the removed keys
  {:marks (into {} (for [[k v] now :let [b (get base k ::none)] :when (or (= ::none b) (not (same? v b)))]
                     [k (if (= ::none b) :new :changed)]))
   :removed (vec (sort-by str (remove (set (keys now)) (keys base))))})

(defn- index [w]
  (let [blocks (into {} (for [c (:contexts w) [_ a] (:areas c) k [:commands :events :read-models :queries :todo-processors :periodic-tasks :screens]
                              [bk b] (get a k)] [[(:id c) k bk] (dissoc b :file)]))
        rules (into {} (for [c (:contexts w) m (vals (:modules c)) r (:rules m)] [[(:id c) (:name r)] (select-keys r [:src :doc])]))
        props (into {} (for [c (:contexts w) m (vals (:modules c))
                             x (concat (:invariants m) (for [s (:surfaces m) g (:guarantees s)] g) (for [k (:contracts m) i (:invariants k)] i))]
                         [[(:id c) (:name x)] (select-keys x [:src :text :doc])]))
        scen (into {} (for [c (:contexts w) s (:scenarios c)]
                        [[(:id c) (:kind s) (if (= "spec" (:kind s)) (:item s) (:title s))] (select-keys s [:title :steps :tags :rule])]))
        flows (into {} (for [c (:contexts w) [_ a] (:areas c) [fk f] (:flows a)] [[(:id c) fk] f]))]
    {:blocks blocks :rules rules :props props :scen scen :flows flows}))

(def CHG
  (let [now (index world) base (index base-world)
        r (into {} (for [k (keys now)] [k (compare-maps (now k) (base k))]))]
    (merge {:base (:ref base-world) :base-commit (:ref-commit base-world)}
           (into {} (for [[k v] r] [k (:marks v)]))
           {:removed (into {} (for [[k v] r] [k (:removed v)]))})))

(println "changes since" (:base CHG) (str "(" (:base-commit CHG) "):")
         (str/join ", " (for [k [:scen :rules :props :blocks :flows]] (str (name k) " " (count (get CHG k)))))
         "· removed" (str/join ", " (for [[k v] (:removed CHG) :when (seq v)] (str (name k) " " (count v)))))
