#!/usr/bin/env bash
# calibrate.sh [GRADER] [OUT]: calibrate grade_measurement.exs by the public protocol
# (AGENTS.md, "The instrument"): carried behaviour passes and uncarried behaviour fails, for
# every item. The fixtures are written from the spec alone (stub.py): the reference carries
# every scenario and must pass all 31 items; each mutant breaks one behaviour and must fail
# the item(s) it targets; a candidate that never answers is unreached and gets no verdict;
# one that answers once and exits is reached: every item gets a verdict, and fails where its
# scenario needs a second answer.
# Writes the calibration record to OUT (default: calibration.json beside this script) and
# exits non-zero when the grader is not calibrated.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
GRADER="${1:-$HERE/../grade_measurement.exs}"
OUT="${2:-$HERE/calibration.json}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/calibrate-measurement.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# mutant  the items it must fail
MUTANTS="
digest-constant        02-001
sort-items             02-002
rule-empty             02-002
rule-null              02-002
drop-skipped           02-003
first-unstamped-only   02-004
allow-empty            02-005
no-identity-check      02-006
no-title-check         02-007
no-checklist-check     02-008
accept-zero            02-009
unknown-first          02-010
no-unknown-check       02-011
verdict-order          02-012
drop-unreached         02-013
drop-reason            02-014
first-wins             02-015
no-superseded          02-015
evidence-absent        02-016
score-zero             02-017
no-policy              02-018
report-unfiled         02-019
previous-minus-one     02-020
net-key                02-021
no-restatement         02-022
always-restate         02-022
half-up                02-023
zero-percent           02-024
filing-not-as-returned 02-025
any-candidate          02-026
any-grader             02-027
no-reason-check        02-028
refile-score           02-029
score-any-grader       02-030
stage-refusal-dropped  02-031
"

run() {  # run NAME START_BODY -> $WORK/NAME.json
  printf '#!/bin/sh\n%s\n' "$2" >"$WORK/$1.sh"; chmod +x "$WORK/$1.sh"
  elixir "$GRADER" "$WORK/$1.sh" >"$WORK/$1.json" 2>"$WORK/$1.err" || { echo "grader failed on $1:"; cat "$WORK/$1.err"; exit 1; }
}

run reference "exec python3 '$HERE/stub.py'"
while read -r m _; do
  [[ -n "$m" ]] && run "$m" "MUTANT=$m exec python3 '$HERE/stub.py'"
done <<<"$MUTANTS"
run never-starts "exit 3"
run exit-after-one "MUTANT=exit-after-one exec python3 '$HERE/stub.py'"

DIGEST="sha256:$(shasum -a 256 "$GRADER" | cut -d' ' -f1)"
python3 - "$WORK" "$OUT" "$DIGEST" "$MUTANTS" <<'PY'
import json, sys
work, out, digest, table = sys.argv[1:5]
N = 31
items = ["02-%03d" % i for i in range(1, N + 1)]
load = lambda n: {v["id"]: v["verdict"] for v in json.load(open(f"{work}/{n}.json"))}
ok, record, lines = True, [], []
ref = load("reference")
good = [i for i in items if ref.get(i) == "pass"]
ok &= len(good) == N and len(ref) == N
lines.append(f"reference              {len(good)}/{N} pass" + ("" if len(good) == N else "   NOT CALIBRATED"))
record += [{"item": i, "role": "carried", "fixture": "reference", "verdict": ref.get(i, "unreached")} for i in items]
caught = set()
for row in table.split("\n"):
    if not row.split():
        continue
    m, *targets = row.split()
    v = load(m)
    failing = sorted(i for i in items if v.get(i) != "pass")
    hit = all(v.get(t) == "fail" for t in targets)
    ok &= hit
    caught |= {t for t in targets if v.get(t) == "fail"}
    lines.append(f"{m:22} targets {','.join(targets)}: " + ("fails" if hit else "DOES NOT FAIL")
                 + f"; failing {len(failing)}/{N}: {','.join(failing)}")
    record += [{"item": t, "role": "uncarried", "fixture": m, "verdict": v.get(t, "unreached")} for t in targets]
ns = json.load(open(f"{work}/never-starts.json"))
ok &= ns == []
lines.append(f"never-starts           {len(ns)} verdicts filed, {N} unreached" + ("" if ns == [] else "   WRONG"))
eo = load("exit-after-one")
eo_fail = sorted(i for i, v in eo.items() if v == "fail")
ok &= sorted(eo) == items
lines.append(f"exit-after-one         {len(eo)} verdicts filed, none unreached; fail where a second answer was needed: {len(eo_fail)}/{N}"
             + ("" if sorted(eo) == items else "   WRONG"))
ok &= caught == set(items)
summary = {"grader_digest": digest, "entry": "grade_measurement.exs", "items": items,
           "protocol": "AGENTS.md, The instrument: carried behaviour passes and uncarried behaviour fails for every item",
           "fixtures": "harness/v2/graders/calibration-measurement/stub.py (reference and MUTANT=<name>), written from the spec alone",
           "calibrated": bool(ok), "summary": lines, "calibration": record}
with open(out, "w") as f:
    json.dump(summary, f, indent=1)
    f.write("\n")
print("\n".join(lines))
print(("CALIBRATED " if ok else "NOT CALIBRATED ") + digest)
sys.exit(0 if ok else 1)
PY
