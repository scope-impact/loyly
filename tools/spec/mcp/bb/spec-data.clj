;; The review tools' data: the event-model page's own extraction (tools/spec/views/extract.clj),
;; printed as JSON.   bb spec-data.clj <repo-root> <model.edn> <coverage.json>
;; extract.clj reads `opts` ({:root :model :coverage}), as render.clj defines it. The server
;; passes an empty Grain dump ({:model {} :files {}}): the tools need the Allium, the Gherkin and
;; the coverage report, not the Event Model, and skipping it avoids a JVM.
(require '[cheshire.core :as json] '[clojure.java.io :as io])
(def opts (zipmap [:root :model :coverage] *command-line-args*))
(def extract (str (.getParent (io/file *file*)) "/../../views/extract.clj"))
(binding [*out* *err*] (load-file extract))   ; extract.clj may print a count line; keep stdout JSON
(println (json/generate-string (select-keys spec [:commit :dirty :modules :slices :scenarios :coverage])))
