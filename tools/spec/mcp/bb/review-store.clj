;; The review store (.pi/model/review.edn) as JSON, both ways, so the server needs no EDN code.
;;   bb review-store.clj read  <file>   prints {"threads": [...]} (an absent file reads as empty)
;;   bb review-store.clj write <file>   reads that JSON on stdin and writes the EDN file
;; Thread: {:id "t-…" :target "rule:Name" :status :open|:resolved :entries [{:author :at :text}]}
(require '[cheshire.core :as json] '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.pprint :as pp])
(let [[cmd file] *command-line-args*]
  (case cmd
    "read"  (let [f (io/file file)
                  d (if (.exists f) (edn/read-string (slurp f)) {:threads []})]
              (println (json/generate-string {:threads (mapv #(update % :status name) (:threads d []))})))
    "write" (let [d (json/parse-string (slurp *in*) true)
                  ts (mapv (fn [t] {:id (:id t) :target (:target t) :status (keyword (:status t))
                                    :entries (mapv #(select-keys % [:author :at :text]) (:entries t))})
                           (:threads d))]
              (spit file (str ";; Review threads on the spec, written by tools/spec/mcp (spec_comment, spec_resolve_thread).\n"
                              (with-out-str (pp/pprint {:threads ts})))))))
