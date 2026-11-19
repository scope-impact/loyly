#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(git -C "$here" rev-parse --show-toplevel)
folder=${1:-$root/.pi/model/plan}
cd "$here"
exec clojure -M -m spec.gwt "$folder"
