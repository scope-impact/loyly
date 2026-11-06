#!/usr/bin/env bb
;; stamp.clj [context ...]: stamp the accepted scenarios of .pi/spec/<context>/spec.feature with
;; the legacy stamper's own rules (harness/stamp_item_ids.clj: idempotent, never renumbers, the
;; next number is one past the highest in the file). A v2 context folder has no "NN-" prefix, so
;; its slice number comes from .pi/spec/slices.edn. All contexts with a spec.feature when none is
;; named.
(require '[clojure.java.io :as io] '[clojure.string :as str] '[clojure.edn :as edn])

(def root (str/trim (:out (babashka.process/sh "git" "rev-parse" "--show-toplevel"))))

;; Load the stamper's definitions, not its run (the last form) nor its root.
(let [src (slurp (io/file root "harness/stamp_item_ids.clj"))]
  (babashka.classpath/add-classpath (str root "/harness"))
  (load-string (-> (subs src 0 (str/index-of src "\n(let [files (hjson/spec-files"))
                   (str/replace "(def root (hjson/root *file*))" "")
                   (str/replace #"\(cp/add-classpath[^\n]*\n" ""))))

;; One change: only tag lines (stripped text starting "@") count toward the next number. A
;; scenario's own steps quote example ids ("@item-03-004"), and those must not push the counter.
(defn next-number [lines]
  (inc (reduce max 0 (for [l lines :when (str/starts-with? (str/trim l) "@")
                           [_ n] (re-seq item-tag l)]
                       (Long/parseLong n)))))

(def slices (edn/read-string (slurp (io/file root ".pi/spec/slices.edn"))))

(let [ctxs (or (seq *command-line-args*)
               (sort (for [d (.listFiles (io/file root ".pi/spec"))
                           :when (.exists (io/file d "spec.feature"))] (.getName d))))
      total (reduce
             (fn [total c]
               (let [f (io/file root ".pi/spec" c "spec.feature")
                     slice (or (get slices c) (throw (ex-info (str "no slice number for " c " in .pi/spec/slices.edn") {})))
                     [text added] (stamp-text (slurp f) slice)]
                 (when (pos? added) (spit f text))
                 (println (format "stamped %3d  .pi/spec/%s/spec.feature (slice %s)" added c slice))
                 (+ total added)))
             0 ctxs)]
  (println "total stamped:" total))
