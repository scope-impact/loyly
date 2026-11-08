(ns loyly.cycle-preparation.domain
  "The Grain building blocks of Cycle preparation, one per block of the event
   model and named as it: the event cycle-preparation/source-cut-pinned, the
   command cycle-preparation/pin-source-cut, the read model and the query
   cycle-preparation/source-cuts. Every schema is the model's own."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [ai.obney.grain.event-store-v3.interface :as es :refer [defevent]]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [cognitect.anomalies :as anom]
            [loyly.cycle-preparation.model :as model]))

(def pin-source-cut :cycle-preparation/pin-source-cut)
(def source-cut-pinned :cycle-preparation/source-cut-pinned)
(def source-cuts :cycle-preparation/source-cuts)

;; ---------------------------------------------------------------------------
;; Schemas and the event, as the model gives them
;; ---------------------------------------------------------------------------

(defschemas commands
  {pin-source-cut (model/command-schema pin-source-cut)})

(defschemas queries
  {source-cuts (:schema (model/query-block source-cuts))})

(defevent :cycle-preparation/source-cut-pinned
  (:description (model/event-block source-cut-pinned))
  {:schema (:schema (model/event-block source-cut-pinned))})

;; ---------------------------------------------------------------------------
;; Read model: every pinned cut, by cut identity
;; ---------------------------------------------------------------------------

(defreadmodel :cycle-preparation source-cuts
  {:events (:consumes (model/read-model-block source-cuts))
   :version 1
   :schema (:schema (model/read-model-block source-cuts))}
  "Every pinned cut, with its commit and tree digest."
  [state event]
  (if (= source-cut-pinned (:event/type event))
    (let [cut-id (:cut-id event)]
      (if (contains? state cut-id)
        state                           ; immutable once pinned: first pin wins
        (assoc state cut-id (select-keys event [:cut-id :commit :tree-digest :pinned-at]))))
    state))

(defn pinned-cuts
  "The pinned cuts, newest first."
  [context]
  (->> (vals (rmp/project context source-cuts))
       (sort-by :pinned-at #(compare %2 %1))
       vec))

;; ---------------------------------------------------------------------------
;; The contract ProjectRepository, fulfilled by the commits handed in
;; ---------------------------------------------------------------------------

(defn project-commits
  "The project repository's commits, read now from the JSON file
   LOYLY_PROJECT_COMMITS names: {commit tree-digest}. An unset variable, an
   absent or unreadable file are no commits."
  []
  (let [path (System/getenv "LOYLY_PROJECT_COMMITS")
        f (when (seq path) (io/file path))]
    (if (and f (.isFile f))
      (try
        (let [m (json/read-str (slurp f))]
          (if (map? m) m {}))
        (catch Exception e
          (binding [*out* *err*]
            (println "cycle-preparation: unreadable LOYLY_PROJECT_COMMITS:" (ex-message e)))
          {}))
      {})))

(defn is-commit? [commits commit] (contains? commits commit))

(defn tree-of [commits commit]
  (let [t (get commits commit)] (when (string? t) t)))

;; ---------------------------------------------------------------------------
;; Command: PinSourceCut
;; ---------------------------------------------------------------------------

(defn- refusal [code message]
  {::anom/category ::anom/conflict
   ::anom/message message
   :refusal code})

(defn cut-tag [cut-id]
  [:source-cut (java.util.UUID/nameUUIDFromBytes (.getBytes (str "source-cut:" cut-id) "UTF-8"))])

(defcommand :cycle-preparation pin-source-cut
  {:authorized? (constantly true)
   :grain.event-model/produces (:produces (model/command-block pin-source-cut))
   :grain.event-model/reads (:reads (model/command-block pin-source-cut))}
  "Pin an immutable source cut: one commit and its tree content."
  [context]
  (let [{:keys [cut-id commit tree-digest]} (:command context)
        pinned (rmp/project context source-cuts)
        commits (project-commits)]
    (cond
      ;; requires 1: the cut identity is not yet pinned
      (contains? pinned cut-id)
      (refusal "cut-already-pinned" (str "Source cut " cut-id " is already pinned."))

      ;; requires 2: is_commit(commit)
      (not (is-commit? commits commit))
      (refusal "not-a-commit" (str commit " is not a commit of the project repository."))

      ;; requires 3: tree_of(commit) = tree_digest
      (not= (tree-of commits commit) tree-digest)
      (refusal "tree-digest-not-the-commits"
               (str "Tree digest " tree-digest " is not the tree of commit " commit "."))

      :else
      (let [tag (cut-tag cut-id)]
        {:command-result/events
         [(es/->event {:type source-cut-pinned
                       :tags #{tag}
                       :body {:cut-id cut-id
                              :commit commit
                              :tree-digest tree-digest
                              :pinned-at (java.time.Instant/now)}})]
         ;; One cut per identity, even against a concurrent pin.
         :command-result/cas {:types #{source-cut-pinned}
                              :tags #{tag}
                              :predicate-fn (fn [evts] (zero? (count (into [] evts))))}}))))
