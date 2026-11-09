(ns specviews.main
  "Entry point: build the spec pages of one project from its project.edn.
     bb -m specviews.main [--config <project.edn>] [--base <git-ref>] [--built <date>]
                          [--out-dir <dir>] [--mode page|app] [--coverage <json>] …
   The config names every project-specific value (see README.md). The page code is loaded from
   the classpath (resources under specviews/), so another repository can use this as a git
   dependency with its own config."
  (:require [clojure.edn :as edn] [clojure.java.io :as io] [clojure.string :as str]
            [babashka.fs :as fs] [babashka.process :as p]))

(defn resource [f] (slurp (io/resource (str "specviews/" f))))

(defn load-into
  "Load the classpath script specviews/<f> into namespace `ns-sym`, after interning `vars`."
  [ns-sym vars & fs]
  (let [n (create-ns ns-sym)]
    (binding [*ns* n]
      (eval '(clojure.core/refer-clojure))
      (doseq [[k v] vars] (intern n k v))
      (doseq [f fs] (load-string (resource f))))
    n))

(defn read-config [path]
  (let [f (fs/absolutize path)
        cfg (edn/read-string (slurp (str f)))
        dir (str (fs/parent f))
        root (if (:root cfg)
               (str (fs/normalize (fs/absolutize (fs/path dir (:root cfg)))))
               (str/trim (:out (p/sh {:dir dir} "git" "rev-parse" "--show-toplevel"))))]
    (assoc cfg :root root :config-dir dir)))

(defn -main [& args]
  (let [o (into {} (for [[k v] (partition 2 args)] [(keyword (str/replace k #"^--" "")) v]))
        cfg (read-config (or (:config o) (str (fs/path (fs/parent (fs/absolutize (str (io/file (.toURI (io/resource "specviews/main.clj")))))) "../../project.edn"))))]
    (load-into 'specviews.page {'cfg cfg 'cli o 'load-into load-into 'resource resource} "render.clj")
    nil))
