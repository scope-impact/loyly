(ns loyly.pi-harness.core.grading.grader-commands
  (:require [clojure.data.json :as json]))

(def gondolin-daemon
  "mkdir -p /sys/fs/cgroup && (mountpoint -q /sys/fs/cgroup || mount -t cgroup2 none /sys/fs/cgroup) || exit 1; docker info >/dev/null 2>&1 || (dockerd --storage-driver=overlay2 >/tmp/dockerd.log 2>&1 & attempts=0; until docker info >/dev/null 2>&1; do attempts=$((attempts + 1)); [ $attempts -lt 100 ] || { cat /tmp/dockerd.log >&2; exit 1; }; sleep 0.1; done)")

(def gondolin-runtime
  (str gondolin-daemon
       "; base=$(head -n 1 impl/Dockerfile); base=${base#FROM }; case \"$base\" in node:*) libs=$(for executable in /bin/* /usr/bin/node /usr/bin/dirname; do ldd \"$executable\" 2>/dev/null || true; done | awk '{for (i=1;i<=NF;i++) if ($i ~ /^\\//) print substr($i,2)}' | sort -u); (cd / && tar -ch bin tmp usr/bin/node usr/bin/dirname $libs) | docker import - \"$base\" >/dev/null ;; python:*) (cd / && tar -c bin tmp lib/ld-musl-aarch64.so.1 usr/bin/env usr/bin/dirname usr/bin/python3 usr/bin/python3.12 usr/lib/python3.12 usr/lib/*.so*) | docker import - \"$base\" >/dev/null ;; *) echo \"unsupported offline base image: $base\" >&2; exit 1 ;; esac"))

(defn- runtime-command [backend runtime-image-archive]
  (if (= "gondolin" backend)
    (if runtime-image-archive
      (do
        (when-not (re-matches #"[A-Za-z0-9._-]+\.tar" runtime-image-archive)
          (throw (ex-info "runtime image archive must be a safe tar name"
                          {:runtime-image-archive runtime-image-archive})))
        (str gondolin-daemon "; docker load -i /runtime-images/" runtime-image-archive))
      gondolin-runtime)
    "docker info >/dev/null 2>&1"))

(def independent-grader-script "booking-core-01-007.sh")

(def compose-files "-f impl/compose.yaml -f /tmp/pi-harness-evidence-compose.json")

(def evidence-service
  {:environment {"HARNESS_EVIDENCE_ROOT" "/evidence"
                 "PLAYWRIGHT_OUTPUT_DIR" "/evidence/playwright"
                 "OTEL_FILE_EXPORT_DIR" "/evidence/otel"}
   :volumes [{:type "bind" :source "${HARNESS_EVIDENCE_ROOT}" :target "/evidence"}]})

(def evidence-compose-json
  (json/write-str {:services {:app evidence-service :test evidence-service}} :escape-slash false))

(def evidence-compose
  (str "echo " (pr-str evidence-compose-json)
       " > /tmp/pi-harness-evidence-compose.json"))

(defn commands
  ([mode backend] (commands mode backend {:grader-script independent-grader-script}))
  ([mode backend {:keys [grader-script project-id item-ids runtime-image-archive]
                  :or {grader-script independent-grader-script}}]
  (case mode
    "compose"
    [{:name "compose-runtime" :command (runtime-command backend runtime-image-archive)}
     {:name "compose-evidence" :command evidence-compose}
     {:name "compose-config" :command (str "docker compose " compose-files " config --format json")}
     {:name "compose-services" :command (str "docker compose " compose-files " config --services")}
     {:name "compose-test" :command (str "docker compose " compose-files " run --rm test")}
     {:name "compose-up" :command (str "docker compose " compose-files " up --detach --build --wait --wait-timeout 10 app")}
     {:name "compose-logs" :command (str "docker compose " compose-files " logs --no-color")}
     {:name "compose-down" :command (str "docker compose " compose-files " down --volumes --remove-orphans")}]

    "independent"
    (do
      (when-not (= "gondolin" backend)
        (throw (ex-info "independent grading currently requires Gondolin" {:backend backend})))
      (when-not (re-matches #"[A-Za-z0-9._-]+" (or grader-script ""))
        (throw (ex-info "independent grading requires a safe grader script name"
                        {:grader-script grader-script})))
      (let [item-directory (or (first item-ids) "unscoped")
            _ (when-not (re-matches #"@item-[A-Za-z0-9._-]+|unscoped" item-directory)
                (throw (ex-info "independent grading requires a safe evidence item identity"
                                {:item-id item-directory})))
            runtime-root (str "/evidence/otel/independent/" item-directory)
            independent-overlay "/tmp/pi-harness-independent-evidence-compose.json"
            independent-compose-files (str compose-files " -f " independent-overlay)
            independent-compose-json
            (json/write-str
             {:services {:app {:environment {"OTEL_FILE_EXPORT_DIR" runtime-root}}}}
             :escape-slash false)]
      [{:name "independent-runtime" :command (runtime-command backend runtime-image-archive)}
       {:name "independent-evidence" :command evidence-compose}
       {:name "independent-up"
        :command (str "echo " (pr-str independent-compose-json) " > " independent-overlay
                      "; mkdir -p " runtime-root
                      "; docker compose " independent-compose-files " up --detach --build --wait --wait-timeout 10 app"
                      "; container=$(docker compose " independent-compose-files " ps -q app)"
                      "; ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \"$container\")"
                      "; test -n \"$ip\"; echo http://$ip:8080 >/tmp/pi-harness-independent-base-url"
                      "; curl -fsS \"http://$ip:8080/health\" >/dev/null")}
       {:name "independent-observe-start"
        :command (str "mkdir -p " runtime-root
                      "; OTEL_FILE_EXPORT_DIR=" runtime-root
                      " nohup /usr/local/bin/otelcol-contrib --config=/etc/loyly/otel/collector.yaml"
                      " >" runtime-root "/collector.log 2>&1 & echo $! >/tmp/pi-harness-otel-collector.pid"
                      "; OTEL_FILE_EXPORT_DIR=" runtime-root
                      " nohup /usr/local/bin/otelcol-ebpf-profiler"
                      " --feature-gates=+service.profilesSupport"
                      " --config=/etc/loyly/otel/profiler.yaml"
                      " >" runtime-root "/profiler.log 2>&1 & echo $! >/tmp/pi-harness-otel-profiler.pid"
                      "; OTEL_EBPF_OPEN_PORT=8080"
                      " OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318"
                      " OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf"
                      " OTEL_METRIC_EXPORT_INTERVAL=5000"
                      " nohup /usr/local/bin/obi"
                      " >" runtime-root "/obi.log 2>&1 & echo $! >/tmp/pi-harness-obi.pid"
                      "; sleep 2"
                      "; for pidfile in /tmp/pi-harness-otel-collector.pid"
                      " /tmp/pi-harness-otel-profiler.pid /tmp/pi-harness-obi.pid; do"
                      " kill -0 \"$(cat \"$pidfile\")\" || exit 1; done"
                      "; attempts=0; until grep -q 'Launching p.Tracer' " runtime-root "/obi.log; do"
                      " attempts=$((attempts + 1)); [ $attempts -lt 40 ] || exit 1; sleep 0.25; done"
                      "; sleep 1")}
       {:name "independent-grade"
        :command (str "OTEL_FILE_EXPORT_DIR=" runtime-root " bash /grader/" grader-script
                      " \"$(cat /tmp/pi-harness-independent-base-url)\"")}
       {:name "independent-observe-stop"
        :command (str "test -r /tmp/pi-harness-obi.pid"
                      " && kill -INT \"$(cat /tmp/pi-harness-obi.pid)\" 2>/dev/null || true"
                      "; sleep 2"
                      "; test -r /tmp/pi-harness-otel-profiler.pid"
                      " && kill -INT \"$(cat /tmp/pi-harness-otel-profiler.pid)\" 2>/dev/null || true"
                      "; sleep 1"
                      "; test -r /tmp/pi-harness-otel-collector.pid"
                      " && kill -INT \"$(cat /tmp/pi-harness-otel-collector.pid)\" 2>/dev/null || true"
                      "; sleep 2"
                      "; find " runtime-root " -maxdepth 1 -type f -print -exec wc -c {} \\;")}
       {:name "independent-logs" :command (str "docker compose " independent-compose-files " logs --no-color")}
       {:name "independent-down" :command (str "docker compose " independent-compose-files " down --volumes --remove-orphans")}]))

    "baseline"
    (do
      (when-not (= "gondolin" backend)
        (throw (ex-info "baseline grading currently requires Gondolin" {:backend backend})))
      (let [runtime-root "/evidence/otel/independent/baseline"
            baseline-overlay "/tmp/pi-harness-baseline-evidence-compose.json"
            baseline-compose-files (str compose-files " -f " baseline-overlay)
            baseline-compose-json
            (json/write-str
             {:services {:app {:environment {"OTEL_FILE_EXPORT_DIR" runtime-root}}}}
             :escape-slash false)]
        [{:name "baseline-runtime" :command (runtime-command backend runtime-image-archive)}
         {:name "baseline-evidence" :command evidence-compose}
         {:name "baseline-up"
          :command (str "echo " (pr-str baseline-compose-json) " > " baseline-overlay
                        "; mkdir -p " runtime-root
                        "; docker compose " baseline-compose-files
                        " up --detach --build --wait --wait-timeout 10 app"
                        "; container=$(docker compose " baseline-compose-files " ps -q app)"
                        "; ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \"$container\")"
                        "; test -n \"$ip\"; echo http://$ip:8080 >/tmp/pi-harness-baseline-base-url")}
         {:name "baseline-observe-start"
          :command (str "OTEL_FILE_EXPORT_DIR=" runtime-root
                        " nohup /usr/local/bin/otelcol-contrib --config=/etc/loyly/otel/collector.yaml"
                        " >" runtime-root "/collector.log 2>&1 & echo $! >/tmp/pi-harness-otel-collector.pid"
                        "; OTEL_EBPF_OPEN_PORT=8080"
                        " OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318"
                        " OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf"
                        " OTEL_METRIC_EXPORT_INTERVAL=5000"
                        " nohup /usr/local/bin/obi"
                        " >" runtime-root "/obi.log 2>&1 & echo $! >/tmp/pi-harness-obi.pid"
                        "; sleep 2"
                        "; kill -0 \"$(cat /tmp/pi-harness-otel-collector.pid)\""
                        "; kill -0 \"$(cat /tmp/pi-harness-obi.pid)\"")}
         {:name "baseline-grade"
          :command (str "OTEL_FILE_EXPORT_DIR=" runtime-root
                        " node /grader/grade.mjs \"$(cat /tmp/pi-harness-baseline-base-url)\"")}
         {:name "baseline-observe-stop"
          :command (str "kill -INT \"$(cat /tmp/pi-harness-obi.pid)\" 2>/dev/null || true"
                        "; sleep 2"
                        "; kill -INT \"$(cat /tmp/pi-harness-otel-collector.pid)\" 2>/dev/null || true"
                        "; sleep 2"
                        "; find " runtime-root " -maxdepth 1 -type f -print -exec wc -c {} \\;")}
         {:name "baseline-logs"
          :command (str "docker compose " baseline-compose-files " logs --no-color")}
         {:name "baseline-down"
          :command (str "docker compose " baseline-compose-files
                        " down --volumes --remove-orphans")}]))

    "instrument"
    (do
      (when-not (= "gondolin" backend)
        (throw (ex-info "instrument conformance currently requires Gondolin" {:backend backend})))
      (when-not (and (re-matches #"[A-Za-z0-9._-]+" (or project-id ""))
                     (not (#{"." ".."} project-id)))
        (throw (ex-info "instrument conformance requires a safe project identity"
                        {:project-id project-id})))
      (when-not (and (seq item-ids)
                     (every? #(re-matches #"@item-[A-Za-z0-9._-]+" %) item-ids))
        (throw (ex-info "instrument conformance requires safe selected item identities"
                        {:item-ids item-ids})))
      [{:name "instrument-conformance"
        :command (str "python3 /grader/runtime-conformance.py"
                      " --evidence-root /evidence/otel/independent"
                      (apply str (map #(str " --item " %) item-ids))
                      " /workspace/model/" project-id "/runtime-evidence-contract.json")}])

    (throw (ex-info "unsupported grader mode" {:mode mode})))))
