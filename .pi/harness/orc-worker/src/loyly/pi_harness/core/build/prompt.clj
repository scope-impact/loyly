(ns loyly.pi-harness.core.build.prompt
  "ORC-owned, purpose-specific builder instructions and deterministic rendering."
  (:require [ai.obney.orc.orc-service.interface :as orc]
            [clojure.string :as str]
            [loyly.pi-harness.core.build.project-profile :as project-profile]
            [loyly.pi-harness.grain.selection :as selection])
  (:import [java.math BigInteger]
           [java.security MessageDigest]))

(def purposes #{:fresh-generation :verification-repair})

(def ^:private system-instructions
  {:fresh-generation
   "You are a clean-room regeneration builder. Read only the immutable contract under $HARNESS_CUT_ROOT. Write the candidate only under $HARNESS_WORKSPACE_ROOT. Use the bash tool to implement and test the approved scope."
   :verification-repair
   "You are a clean-room verification-repair builder. Read the immutable contract, existing candidate, and verifier evidence only through their declared roots. Repair the candidate under $HARNESS_WORKSPACE_ROOT and use the bash tool to prove the repair."})

(def ^:private seed-instructions
  {:fresh-generation
   (str "Reconstruct the approved slice from its pinned product contract without copying prior implementation files. "
        "Map every approved item and Then step to an implementation rule and a public-seam candidate test. Work test-first: observe the assertion fail, make the smallest change, then run focused and full suites. "
        "Create impl/compose.yaml with app and test services built from impl/Dockerfile. The app listens on internal port 8080, has a runtime-native healthcheck, publishes no host port, and tests call http://app:8080. "
        "Create executable impl/start.sh accepting a numeric port argument, then PORT, then 8080. Use the exact requested stack and offline base image; never fetch packages at build or run time. "
        "Use the selected language runtime for health checks rather than assuming curl or wget. The builder room has the selected compiler or runtime but no Docker daemon; use native compile, test, and startup commands there, while downstream verification owns docker compose up --detach --build --wait app and docker compose run --rm test. "
        "Because the image is never built in the room, prove its contents natively before finishing: every path that a Dockerfile COPY or ADD, a Compose command, entrypoint, or healthcheck, or start.sh references must exist under impl and reach the image at the path it is used from, and every Dockerfile RUN step must succeed when replayed in the room. Build steps must not run the candidate test suite, which needs the running app service. "
        "Implement every declared API and applicable human affordance exactly, including paths, methods, field spelling, navigation, outcomes, refusals, and encoding. Do not invent a UI where no affordance applies. "
        "Copy wire names exactly without camelCase aliases. Percent-decode dynamic path values before domain use and percent-encode them in clients. Assert exact observable fields and preserve declared identity fields across every projection. A rejection that offers a follow-up action must return every input needed to perform it. "
        "Keep accepted facts in one authoritative structured state. Rejections preserve state; mutations are atomic; projections derive from the same committed fact. "
        "When runtime evidence applies, emit its machine-readable signals and assert counts, required fields, and correlations without authoring verdicts. The candidate suite must drive every selected scenario through the Compose app; a health-only suite is insufficient. "
        "Isolate scenario state and genuinely overlap concurrent requests before observing either response, repeating exactly as specified. "
        "Before finishing, run syntax or compilation, a candidate suite with at least one test discovered by its runner, and a real startup check. Record commands and outcomes; never weaken assertions to obtain green or substitute an import-time assertion or printed PASS.")
   :verification-repair
   (str "Repair the existing candidate against supplied verifier evidence while preserving behavior that already passes. "
        "Translate each failure into a public-seam regression test and observe the expected failure before changing implementation. "
        "Trace failures to the pinned contract, make the smallest coherent correction, then rerun focused and full tests, syntax or compilation, and startup. "
        "Do not delete, skip, weaken, or replace assertions. Do not alter the requested stack, Compose boundary, evidence contract, or applicable affordances. "
        "When the evidence shows an image build failure or a missing file inside a container, correct the Dockerfile or Compose packaging and check every other referenced path, not only the one reported. "
        "Verifier evidence is diagnostic input; the immutable product contract remains authoritative. Record red and green commands and outcomes.")})

(defn- valid-purpose! [purpose]
  (when-not (contains? purposes purpose)
    (throw (ex-info "unknown builder instruction purpose" {:purpose purpose})))
  purpose)

(defn system-instruction [purpose]
  (get system-instructions (valid-purpose! purpose)))

(defn seed-instruction [purpose]
  (get seed-instructions (valid-purpose! purpose)))

(defn component-name [purpose]
  (str (name (valid-purpose! purpose)) "-instruction"))

(defn digest [value]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (str value) "UTF-8"))]
    (str "sha256:" (format "%064x" (BigInteger. 1 bytes)))))

(defn expose-candidate-instruction [{:keys [node]}]
  {:prompt (:instruction node)})

(defn workflow [purpose]
  (let [purpose (valid-purpose! purpose)]
   (orc/workflow (str "pi-harness-" (name purpose) "-prompt")
    (orc/blackboard
      {:task :string
       :request-id :string
       :selection selection/contract-schema
       :scope [:vector :string]
       :prompt :string})
    (assoc
     (orc/code (component-name purpose)
       :fn "loyly.pi-harness.prompt/expose-candidate-instruction"
       :reads [:task :request-id :selection :scope]
       :writes [:prompt])
     :instruction (seed-instruction purpose)))))

(defn render-builder-prompt
  [{:keys [purpose instruction request-id scope selection target-stack model-root
           verification-evidence-root]}]
  (valid-purpose! purpose)
  (when-not (and (string? instruction) (not (str/blank? instruction)))
    (throw (ex-info "builder instruction must be non-empty" {:purpose purpose})))
  (let [{:keys [base source]} (project-profile/stack-requirements target-stack)
        context-lines (cond->
                       [(str "Purpose: " (name purpose))
                        (str "Cycle: " request-id)
                        (str "Approved slice: " (:slice selection))
                        (str "Approved items: " (str/join ", " (map :id (:items selection))))
                        (str "Target stack: " target-stack)
                        (str "Base image: " base)
                        (str "Candidate source: " source)
                        (str "Product model root: $HARNESS_CUT_ROOT/" model-root)
                        (str "Writable scope: " (str/join ", " (map #(str "$HARNESS_WORKSPACE_ROOT/" %) scope)))]
                        verification-evidence-root
                        (conj (str "Verifier evidence: $HARNESS_VERIFICATION_EVIDENCE_ROOT (mounted from "
                                   verification-evidence-root ")")))
        task-context (str/join "\n" context-lines)
        rendered (str instruction "\n\nDeterministic runtime context:\n" task-context)
        system (system-instruction purpose)]
    {:purpose purpose
     :instruction instruction
     :system-prompt system
     :task-context task-context
     :prompt rendered
     :system-prompt-digest (digest system)
     :instruction-digest (digest instruction)
     :task-context-digest (digest task-context)
     :prompt-digest (digest rendered)}))

(defn winner-instruction [purpose optimization-result]
  (get-in optimization-result [:best-candidate :instructions (component-name purpose)]))
