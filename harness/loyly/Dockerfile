FROM alpine:3.23.7

RUN apk add --no-cache \
    bash \
    bpftool \
    ca-certificates \
    chromium \
    curl \
    coreutils \
    perf \
    strace

COPY install-otel-ebpf.sh /tmp/install-otel-ebpf.sh
COPY otel-collector.yaml /etc/loyly/otel/collector.yaml
COPY otel-profiler.yaml /etc/loyly/otel/profiler.yaml
RUN chmod +x /tmp/install-otel-ebpf.sh && /tmp/install-otel-ebpf.sh
