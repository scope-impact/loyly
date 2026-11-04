#!/usr/bin/env bash
# probe.sh: prove the thing answering is THIS application, by domain behavior, not a name.
# The harness's identity is its cycle: the served root page must name the harness and list
# its stages in order (@item-02-010). A page that merely carries the name is not evidence.
set -uo pipefail
URL="${1:?usage: probe.sh URL}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$ROOT/harness/config.sh"

page="$(curl -s "$URL")"
grep -qi "${APP_MARKER:?probe.sh needs APP_MARKER}" <<<"$page" || {
  echo "the served page does not name '$APP_MARKER'"
  exit 1
}
grep -Eqi "fence.*regenerate.*gate.*score.*scoreboard" <<<"$(tr '\n' ' ' <<<"$page")" || {
  echo "the served page does not list the cycle stages fence, regenerate, gate, score, scoreboard in order"
  exit 1
}
echo "the served page names '$APP_MARKER' and lists the cycle stages in order: this harness"
