#!/usr/bin/env bash
# calibrate.sh [GRADER] [OUT]: calibrate grade_specification.exs by the public protocol
# (AGENTS.md, "The instrument"): carried behaviour passes and uncarried behaviour fails, for
# every item. The fixtures are written from the spec alone (stub.py): the reference carries
# every scenario and must pass all 15 items; each mutant breaks one behaviour and must fail
# the item(s) it targets; a candidate that never answers is unreached and gets no verdict;
# one that answers once and exits is reached: every item gets a verdict, and fails where its
# scenario needs a second answer.
# Writes the calibration record to OUT (default: calibration.json beside this script) and
# exits non-zero when the grader is not calibrated.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
GRADER="${1:-$HERE/../grade_specification.exs}"
OUT="${2:-$HERE/calibration.json}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/calibrate-specification.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# mutant  the items it must fail
MUTANTS="
reverse-order          01-001
no-file-write          01-001
notes-slice            01-002
slice-int              01-002
reuse-deleted          01-003
idempotence-broken     01-004
wording-identity       01-005
no-spec-fully-stamped  01-006
missing-after-ledger   01-007
no-offer-check         01-008
no-report              01-009
no-empty-check         01-010
template-yes           01-011
trim-whitespace-title  01-012
no-trailing-trim       01-013
pending-tag            01-014
any-tag-stamps         01-015
drop-tags              01-015
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
items = ["01-%03d" % i for i in range(1, 16)]
load = lambda n: {v["id"]: v["verdict"] for v in json.load(open(f"{work}/{n}.json"))}
ok, record, lines = True, [], []
ref = load("reference")
good = [i for i in items if ref.get(i) == "pass"]
ok &= len(good) == 15 and len(ref) == 15
lines.append(f"reference              {len(good)}/15 pass" + ("" if len(good) == 15 else "   NOT CALIBRATED"))
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
                 + f"; failing {len(failing)}/15: {','.join(failing)}")
    record += [{"item": t, "role": "uncarried", "fixture": m, "verdict": v.get(t, "unreached")} for t in targets]
ns = json.load(open(f"{work}/never-starts.json"))
ok &= ns == []
lines.append(f"never-starts           {len(ns)} verdicts filed, 15 unreached" + ("" if ns == [] else "   WRONG"))
eo = load("exit-after-one")
eo_fail = sorted(i for i, v in eo.items() if v == "fail")
ok &= sorted(eo) == items
lines.append(f"exit-after-one         {len(eo)} verdicts filed, none unreached; fail where a second answer was needed: {len(eo_fail)}/15"
             + ("" if sorted(eo) == items else "   WRONG"))
ok &= caught == set(items)
summary = {"grader_digest": digest, "entry": "grade_specification.exs", "items": items,
           "protocol": "AGENTS.md, The instrument: carried behaviour passes and uncarried behaviour fails for every item",
           "fixtures": "harness/v2/graders/calibration/stub.py (reference and MUTANT=<name>), written from the spec alone",
           "calibrated": bool(ok), "summary": lines, "calibration": record}
with open(out, "w") as f:
    json.dump(summary, f, indent=1)
    f.write("\n")
print("\n".join(lines))
print(("CALIBRATED " if ok else "NOT CALIBRATED ") + digest)
sys.exit(0 if ok else 1)
PY
