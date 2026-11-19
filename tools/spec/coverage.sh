#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
mode=spec.coverage-v1
if [[ ${1:-} == --v2 ]]; then mode=spec.coverage-v2; shift; fi
cd "$here"
exec clojure -M -m "$mode" "$@"
