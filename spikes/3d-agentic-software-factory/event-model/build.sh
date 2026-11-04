#!/usr/bin/env bash
set -euo pipefail
view_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(git -C "$view_dir" rev-parse --show-toplevel)"
page_root="$repo_root/spikes/3d-agentic-software-factory"
mkdir -p "$page_root/src/event-model"
bb "$view_dir/extract.clj" "$repo_root" "$page_root/src/event-model/model.json"
echo "wrote $page_root/src/event-model/model.json"
