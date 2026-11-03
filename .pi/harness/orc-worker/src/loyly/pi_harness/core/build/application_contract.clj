(ns loyly.pi-harness.core.build.application-contract
  (:require [clojure.string :as str]))

(defn stack-requirements [target-stack]
  (let [stack (str/lower-case target-stack)]
    (cond
      (str/includes? stack "rust")
      {:id "rust" :base "rust:1.91-alpine" :archive "rust-1.91-alpine.tar"
       :source "Rust .rs source files"}

      (str/includes? stack "java")
      {:id "java" :base "eclipse-temurin:21-jdk-alpine"
       :archive "eclipse-temurin-21-jdk-alpine.tar" :source "Java .java source files"}

      (or (str/includes? stack "rails") (str/includes? stack "ruby"))
      {:id "rails" :base "loyly-rails:8-offline" :archive "loyly-rails-8-offline.tar"
       :source "a Ruby on Rails application with Gemfile, config/application.rb, and app/ source"}

      (str/includes? stack "python")
      {:id "python" :base "python:3.12-alpine" :source "Python .py source files"}

      :else
      {:id "node" :base "node:22-alpine" :source "Node JavaScript or TypeScript source files"})))

(defn builder-instruction [{:keys [base-instruction request-id scope selection target-stack model-root]}]
  (let [item-ids (mapv :id (:items selection))
        stack (stack-requirements target-stack)]
  (str base-instruction
       "\n\nRegeneration cycle: " request-id ".\n"
       "Target implementation stack: " target-stack ". Use that stack for application and candidate tests.\n"
       "Read the canonical product contract under $HARNESS_CUT_ROOT/" model-root " and supporting source material under $HARNESS_CUT_ROOT.\n"
       "Implement the requested scope under $HARNESS_WORKSPACE_ROOT/" (first scope) ".\n"
       "Provide $HARNESS_WORKSPACE_ROOT/impl/compose.yaml with app and test services. "
       "The app service must listen on internal port 8080 and define a healthcheck; the test service must run the candidate test suite. Do not publish host ports; services communicate only on the Compose network. "
       "Tests running in the test service must call the application at http://app:8080, never localhost, a host-mapped port, or a second application process started by the tests. "
       "The downstream verifier proves this exact boundary with docker compose run --rm test.\n"
       "Define the app healthcheck using the selected language runtime guaranteed to exist in the declared base image; do not assume curl, wget, or another utility is installed. "
       "The downstream verifier runs docker compose up --detach --build --wait app and requires it to become healthy.\n"
       "Both Compose services must build from $HARNESS_WORKSPACE_ROOT/impl/Dockerfile. The first Dockerfile instruction must be FROM " (:base stack) ". "
       "The candidate must contain " (:source stack) "; substituting another language or runtime is a failed regeneration. "
       "Gondolin grading has no registry or package-index access, so the image build and test command must not pull images or install packages at runtime. Use only the selected base image's standard library and bundled tools.\n"
       "The builder Gondolin room contains the exact selected compiler or runtime but deliberately has no Docker daemon. Use its native compiler and test commands to repair every syntax, compile, and startup failure before finishing; Compose validation happens downstream.\n"
       "Provide executable $HARNESS_WORKSPACE_ROOT/impl/start.sh for the Compose app command. It must start the app on the numeric port passed as its first argument, falling back to the PORT environment variable and then 8080.\n"
       "Independent grading starts the Compose app inside deny-egress Gondolin and addresses it through its private container network. Prove that startup path and the health endpoint before finishing.\n"
       "The approved item IDs are " (str/join ", " item-ids) ". Independent black-box grading determines their behavioral verdicts.\n"
       "Before coding, make a traceability checklist that maps every approved item and every Then step to an implementation rule and a candidate assertion; do not stop while any row is uncovered.\n"
       "Represent accepted domain facts in one authoritative structured state, not by searching serialized responses, log text, or ad-hoc strings. "
       "Each rejected command must leave that authoritative state unchanged, and candidate tests must assert both the rejection and the preserved prior state.\n"
       "Make each state-changing command atomic with respect to concurrent commands: validate and commit under one transaction or critical section, then form the response from the committed result. "
       "All declared projections and downstream views must be derived from the same committed domain fact rather than updated as unrelated response-specific copies.\n"
       "Treat project runtime-evidence contract files under the canonical product model as binding. "
       "When their configured evidence environment variables are present, the running candidate must emit the declared machine-readable runtime signals; candidate-authored pass/fail verdicts are forbidden.\n"
       "The candidate suite must drive every approved item through the Compose app service and assert all of that scenario's observable outcomes; a health-only suite is insufficient. "
       "With the evidence environment configured, it must also parse the emitted runtime evidence and assert the contract's record counts, required fields, and correlation fields. Server exceptions or missing evidence must fail the suite.\n"
       "Treat every method, path, query, and body shape declared by applicable product affordance and black-box measurement contracts as exact external interfaces.\n"
       "An affordance contract applies to this generation only when its slice or slices selector includes the approved slice " (:slice selection) ". A slice with no applicable affordance contract does not require an invented UI. "
       "When applicable, treat the product affordance contract as the minimum human interaction surface, not merely an API catalogue. Implement every declared screen path, make each offered action operable through labelled controls, preserve declared navigation, and render required outcomes and refusals where a browser user can perceive them. "
       "Layout, styling, extra content, framework choice, and additional affordance metadata remain free unless the contract explicitly makes them observable. Do not replace a declared screen with JSON or satisfy a human action only through candidate tests or direct HTTP calls.\n"
       "Apply standard HTTP semantics at that boundary: percent-decode dynamic path parameters before storing, comparing, or returning their domain values.\n"
       "HTTP clients and candidate tests must percent-encode dynamic path-segment values before constructing request URLs.\n"
       "Copy wire field names exactly, including punctuation and casing. Do not translate kebab-case, snake_case, or other declared names into language-style camelCase aliases in either the implementation or candidate tests.\n"
       "For every scenario Then step, candidate tests must assert the exact externally observable field names and values, not only status codes, collection sizes, messages, or semantically similar aliases. "
       "When one accepted fact feeds multiple declared derived views, preserve its declared identity fields unchanged in every view and assert those exact fields independently in each response.\n"
       "Give every scenario an isolated initial state or explicit cleanup so earlier tests cannot satisfy or poison later scenarios. "
       "When a scenario distinguishes concurrent from sequential behavior, issue the requests so they overlap before either response is observed, and repeat the experiment exactly as many times as the contract states.\n"
       "When a rejection offers an alternative action, include every input needed to submit that action in the observable rejection response, and prove usability by constructing the follow-up request from that response alone.\n"
       "Do not copy implementation files from $HARNESS_CUT_ROOT; rebuild from the specification.\n"
       "Use the bash tool now: inspect $HARNESS_CUT_ROOT, create the implementation under $HARNESS_WORKSPACE_ROOT, "
       "and run relevant tests before finishing. Do not finish with prose only. "
       "Write code, tests, and configuration needed by the scope. Before finishing, run the language syntax or compile check, execute the declared test command, and prove the app process can start. "
       "The declared test command must execute at least one test recognized by its test runner; import-time assertions or a printed PASS with zero discovered tests are not acceptable.")))
