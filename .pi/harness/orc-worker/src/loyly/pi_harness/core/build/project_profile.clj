(ns loyly.pi-harness.core.build.project-profile
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def supported-stacks [:typescript :rust :java :rails])

(defn stack-requirements [target-stack]
  (let [stack (str/lower-case target-stack)]
    (cond
      (str/includes? stack "rust")
      {:id "rust"
       :base "pi-harness/rust:1.91-web"
       :archive "rust-1.91-web.tar"
       :source (str "Rust .rs source files built with Cargo; the offline crate set "
                    "(axum, tokio, serde, serde_json, and reqwest for tests) is pinned in "
                    "/opt/platform/Cargo.toml and no other crate resolves")}
      (str/includes? stack "java")
      {:id "java"
       :base "pi-harness/java:21-web"
       :archive "java-21-web.tar"
       :source (str "Java .java source files built with Maven; start from /opt/platform/pom.xml, "
                    "whose Jackson, JUnit 5, and plugin versions are the only ones that resolve offline")}
      (or (str/includes? stack "rails") (str/includes? stack "ruby"))
      {:id "rails"
       :base "pi-harness/rails:8-offline"
       :archive "rails-8-offline.tar"
       :source "a Ruby on Rails application with Gemfile, config/application.rb, and app/ source"}
      (str/includes? stack "python")
      {:id "python"
       :base "python:3.12-alpine"
       :source "Python .py source files"}
      :else
      {:id "node"
       :base "node:22-alpine"
       :archive "node-22-alpine.tar"
       :source "Node JavaScript or TypeScript source files"})))

(defn- safe-id! [label value]
  (when-not (and (string? value)
                 (re-matches #"[A-Za-z0-9._-]+" value)
                 (not (#{"." ".."} value)))
    (throw (ex-info (str "invalid " label) {label value})))
  value)

(defn- expand-template [template values]
  (reduce-kv (fn [result key value]
               (str/replace result (str "{" (name key) "}") (str value)))
             template values))

(defn- project-file! [project-root project-id relative]
  (let [project-dir (.getCanonicalFile (io/file project-root "harness" project-id))
        file (.getCanonicalFile (io/file project-root relative))]
    (when-not (and (.startsWith (.toPath file) (.toPath project-dir))
                   (.isFile file))
      (throw (ex-info "project runtime file is unavailable or outside its project directory"
                      {:project-id project-id :path relative})))
    (.getPath file)))

(defn load! [project-root project-id]
  (safe-id! :project-id project-id)
  (let [runtime-root (str "harness/" project-id)
        profile {:modelRoot (str "model/" project-id)
                 :graderProducer (str project-id "-black-box")
                 :graderId "{componentId}-v1"
                 :itemIdPrefix "@item-"
                 :itemGraderScript "{componentId}-{itemSuffix}.sh"
                 :runtimeEvidenceContract (str "model/" project-id "/runtime-evidence-contract.json")
                 :gondolinVmConfig (str runtime-root "/gondolin.json")
                 :stackDockerfiles (into {} (map (fn [stack]
                                                   [stack (str runtime-root "/Dockerfile." (name stack))])
                                                 supported-stacks))}]
    (project-file! project-root project-id (:gondolinVmConfig profile))
    (doseq [[_ dockerfile] (:stackDockerfiles profile)]
      (project-file! project-root project-id dockerfile))
    profile))

(defn resolved [project-root selection]
  (let [project-id (safe-id! :project-id (:projectId selection))
        component-id (safe-id! :component-id (:componentId selection))
        profile (load! project-root project-id)
        values {:projectId project-id :componentId component-id}]
    (reduce-kv (fn [result key value]
                 (assoc result key
                        (if (string? value)
                          (expand-template value values)
                          value)))
               profile profile)))

(defn grader-script [profile item-id]
  (let [prefix (:itemIdPrefix profile)
        suffix (when (str/starts-with? item-id prefix)
                 (subs item-id (count prefix)))
        script (when suffix
                 (expand-template (:itemGraderScript profile)
                                  {:itemId item-id :itemSuffix suffix}))]
    (when-not (and script (re-matches #"[A-Za-z0-9._-]+" script))
      (throw (ex-info "project profile cannot resolve a safe item grader"
                      {:item-id item-id :item-id-prefix prefix :script script})))
    script))
