#!/usr/bin/env bash
# The identity probe: prove that the thing answering on URL is THIS application.
#
# Usage: probe.sh URL
#   exit 0, and print one line saying what was proved
#   exit non-zero, and print the reason, when the thing answering is not this application
#
# PER-REPOSITORY FILE, and the only one in harness/ that is not shared. Every other module
# file is byte identical across the repositories carrying this harness; this one is
# deliberately each repository's own, because what only the real product can produce is
# domain behaviour, and domain behaviour is code rather than a configuration value.
#
# It lives in harness/ and not beside the application on purpose. The probe decides
# whether the regenerated application is the right application, so it belongs to the
# measuring apparatus and not to the product. Inside the fenced tree it would sit where
# the agent regenerating the application could write the very thing that judges it.
#
# THIS COPY IS THE WEAK ONE. It greps the served page for a name, which the gate has
# already done through APP_MARKER, and that is the whole probe here. A name is not
# evidence: a sibling project served a framework's own starter template for a whole night
# while three suites stayed green and the page carried the product name in its title. The
# file exists in that thin form so the weakness is visible as a thin file rather than
# hidden as a missing mechanism. The two siblings show what a real one looks like: one
# books a slot and recomputes that three derived views moved together, the other reads the
# compiled bundle and requires the project's own vocabulary. When this repository has an
# application, this file is where its proof goes.
set -uo pipefail
URL="${1:?usage: probe.sh URL}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$ROOT/harness/config.sh"

curl -s "$URL" | grep -qi "${APP_MARKER:?probe.sh needs APP_MARKER while it is only a name check}" || {
  echo "the served page does not carry the name '$APP_MARKER'"
  exit 1
}
echo "the served page carries the name '$APP_MARKER', which is all this repository's probe can say"
