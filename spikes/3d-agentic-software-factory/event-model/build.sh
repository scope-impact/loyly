#!/usr/bin/env bash
set -euo pipefail
view_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(git -C "$view_dir" rev-parse --show-toplevel)"
page_root="$repo_root/spikes/3d-agentic-software-factory"
mkdir -p "$page_root/public"
model_json="$page_root/public/event-model.json"
bb "$view_dir/extract.clj" "$repo_root" "$model_json"
node "$view_dir/build.mjs" "$view_dir/template.html" "$page_root/index.html"
echo "wrote $page_root/index.html and $model_json"
