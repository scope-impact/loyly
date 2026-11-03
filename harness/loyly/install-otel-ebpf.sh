#!/usr/bin/env sh
set -eu

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fetch() {
  url="$1"
  checksum="$2"
  archive="$work/$(basename "$url")"
  curl -fsSL "$url" -o "$archive"
  printf '%s  %s\n' "$checksum" "$archive" | sha256sum -c -
  tar -xzf "$archive" -C "$work"
}

fetch \
  "https://github.com/open-telemetry/opentelemetry-ebpf-instrumentation/releases/download/v0.15.2/obi-v0.15.2-linux-arm64.tar.gz" \
  "5ee7d65c952fc2bb712dba89c8d5bbb61ec426dcd266342a28db5cf3f6e7b80e"
install -m 0755 "$work/obi" /usr/local/bin/obi

fetch \
  "https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v0.164.0/otelcol-contrib_0.164.0_linux_arm64.tar.gz" \
  "8bd4a7f443405522a2175cc1a8675f9e99cc047c7181b73461efd08efbba38d3"
install -m 0755 "$work/otelcol-contrib" /usr/local/bin/otelcol-contrib

fetch \
  "https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v0.164.0/otelcol-ebpf-profiler_0.164.0_linux_arm64.tar.gz" \
  "1b8286d6a3c55a55046171c651d7171144f2a16b851ceefd5b0a66d90bbe781b"
install -m 0755 "$work/otelcol-ebpf-profiler" /usr/local/bin/otelcol-ebpf-profiler
