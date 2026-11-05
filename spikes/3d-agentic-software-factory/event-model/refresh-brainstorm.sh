#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <path-to-harness-impl>" >&2
  exit 2
fi

candidate_root="$(cd "$1" && pwd)"
state_dir="$candidate_root/.state"
view_dir="$(cd "$(dirname "$0")" && pwd)"
page_root="$(cd "$view_dir/.." && pwd)"
output="$page_root/public/architecture-brainstorm.json"
source_label=".pi/harness/.state/rooms/harness-regen-9/harness-impl/.state"

if [[ ! -f "$state_dir/events.sqlite" ]]; then
  echo "missing Grain event store: $state_dir/events.sqlite" >&2
  exit 1
fi

(
  cd "$candidate_root"
  clojure \
    -J--add-opens=java.base/java.nio=ALL-UNNAMED \
    -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
    -M "$view_dir/extract-brainstorm.clj" \
    "$state_dir" "$output" "$source_label"
)
