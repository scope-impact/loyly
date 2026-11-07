(ns loyly.pi-provider
  "The LLM provider :pi for ORC: every completion ORC asks of :pi (the open grader's Phase-1
   researcher turns and any :llm node it emits) is one non-interactive pi session, the plain pi
   CLI in print mode, with no tools, extensions, skills, context files or saved session: pi is
   only the model here, and the tools are ORC's.

     pi -p --no-session --no-tools --no-extensions --no-skills --no-prompt-templates
        --no-context-files --no-themes [--model M]      the request's messages on stdin

   It is a litellm-clj provider (litellm's provider multimethods, dispatched on :pi) registered
   under the config name :pi with ORC's own llm/register-provider!. ORC's ORC-side contract
   (marker-format prompts, parsed by SIO) is unchanged: pi gets the rendered prompt as text
   and its stdout is the completion. The model is the node's :model; \"pi-default\" passes no
   --model, so pi uses its own default. LOYLY_PI_CMD replaces the pi executable (a scripted
   stand-in for tests); LOYLY_PI_TIMEOUT bounds one call, in seconds (default 900).

   Later, ORC launches pi through @earendil-works/pi-durable; this is the plain CLI for now."
  (:require [ai.obney.orc.llm.interface :as llm]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [litellm.providers.core :as providers])
  (:import (java.util.concurrent TimeUnit)))

(def default-model "pi-default")

(defn pi-command []
  (or (System/getenv "LOYLY_PI_CMD") "pi"))

(defn argv [model]
  (cond-> [(pi-command) "-p" "--no-session" "--no-tools" "--no-extensions" "--no-skills"
           "--no-prompt-templates" "--no-context-files" "--no-themes"]
    (and model (not= model default-model)) (conj "--model" model)))

(defn- content-text [content]
  (if (string? content)
    content
    (str/join "\n" (keep #(or (:text %) (get % "text")) content))))

(defn render-messages
  "One text for pi's stdin: a lone user message as is; otherwise each message headed by its role."
  [messages]
  (if (and (= 1 (count messages)) (= "user" (name (:role (first messages)))))
    (content-text (:content (first messages)))
    (str/join "\n\n" (map #(str "## " (name (:role %)) "\n\n" (content-text (:content %))) messages))))

(defn run-pi
  "Run one pi session on TEXT; its stdout, or an exception."
  [model text]
  (let [timeout-s (parse-long (or (System/getenv "LOYLY_PI_TIMEOUT") "900"))
        dir (.toFile (java.nio.file.Files/createTempDirectory
                      "pi-call-" (make-array java.nio.file.attribute.FileAttribute 0)))
        err (io/file dir "stderr.log")
        p (.start (doto (ProcessBuilder. ^java.util.List (argv model))
                    (.directory dir)
                    (.redirectError err)))
        out (future (slurp (.getInputStream p) :encoding "UTF-8"))]
    (with-open [w (io/writer (.getOutputStream p) :encoding "UTF-8")] (.write w ^String text))
    (if-not (.waitFor p timeout-s TimeUnit/SECONDS)
      (do (.destroyForcibly p)
          (throw (ex-info (str "pi did not answer within " timeout-s "s") {:model model})))
      (let [stdout @out]
        (when-not (zero? (.exitValue p))
          (throw (ex-info (str "pi exited " (.exitValue p) ": "
                               (let [e (slurp err)] (subs e (max 0 (- (count e) 600)))))
                          {:model model})))
        stdout))))

(defmethod providers/transform-request :pi [_ request _] request)

(defmethod providers/make-request :pi [_ request _ _ _]
  (future
    (let [model (:model request)
          text (run-pi model (render-messages (:messages request)))]
      {:id (str "pi-" (random-uuid))
       :model model
       :choices [{:index 0
                  :message {:role :assistant :content text}
                  :finish-reason "stop"}]
       :usage {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}})))

(defmethod providers/transform-response :pi [_ response] response)

(defn register! []
  (llm/register-provider! :pi {:provider :pi :model default-model :config {}}))
