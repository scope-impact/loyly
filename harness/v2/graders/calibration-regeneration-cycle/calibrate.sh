#!/usr/bin/env bash
# calibrate.sh [GRADER] [OUT]: calibrate grade_regeneration_cycle.exs by the public protocol
# (AGENTS.md, "The instrument"): carried behaviour passes and uncarried behaviour fails, for
# every item. The fixtures are written from the spec alone (stub.py): the reference carries
# every scenario and must pass all 38 items; each mutant breaks one behaviour and must fail
# the item(s) it targets; a candidate that never answers is unreached and gets no verdict;
# one that answers once and exits is reached: every item gets a verdict, and fails where its
# scenario needs a second answer.
# Writes the calibration record to OUT (default: calibration.json beside this script) and
# exits non-zero when the grader is not calibrated.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
GRADER="${1:-$HERE/../grade_regeneration_cycle.exs}"
OUT="${2:-$HERE/calibration.json}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/calibrate-regeneration-cycle.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# mutant  the items it must fail
MUTANTS="
goldens-no-newline         04-001
event-drops-budget         04-001
ignore-moves               04-002
accept-stale-goldens       04-002
failing-check-dropped      04-003
gate-check-any-attempt     04-004
refuse-but-record-check    04-004
pass-with-missing-golden   04-005
golden-needs-cycle         04-006
skip-is-failure            04-007
drop-skips                 04-007
clean-room-stays-fence     04-008
prove-after-failed-check   04-009
no-unmatched-check         04-010
roots-not-checked          04-011
commits-not-checked        04-012
tree-not-checked           04-013
object-graph-not-checked   04-014
ignore-scoring-tools       04-015
accept-empty-fence         04-016
empty-fence-refusal        04-016
margin-from-seconds        04-017
passed-stays-gate          04-017
failed-stays-gate          04-018
mount-any-evidence         04-018
ignore-offered-reason      04-018 04-019
failed-needs-poll          04-019
any-harness-digest         04-020
refuse-but-spend           04-020 04-021 04-022
no-probe-check             04-021
any-probe-digest           04-022
no-policy                  04-023 04-024 04-026
score-in-view              04-023
fence-stage-on-gate-check  04-024
regen-before-room          04-025
exhausted-off-by-one       04-026
fail-cycle-no-record       04-027
fail-cycle-wrong-detail    04-027
accept-reworded-golden     04-028
install-drops-room         04-029
install-attempt-unbounded  04-030
replay-verdict             04-031
seam-before-boot-unchecked 04-032
gate-without-install       04-033
regen-beyond-budget        04-034
mount-other-cycle-evidence 04-035
ignore-score-stage-failed  04-036
scoreboard-not-concluded   04-037
filed-stays-score          04-038
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
N = 38
items = ["04-%03d" % i for i in range(1, N + 1)]
load = lambda n: {v["id"]: v["verdict"] for v in json.load(open(f"{work}/{n}.json"))}
ok, record, lines = True, [], []
ref = load("reference")
good = [i for i in items if ref.get(i) == "pass"]
ok &= len(good) == N and len(ref) == N
lines.append(f"reference                  {len(good)}/{N} pass" + ("" if len(good) == N else "   NOT CALIBRATED"))
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
    lines.append(f"{m:26} targets {','.join(targets)}: " + ("fails" if hit else "DOES NOT FAIL")
                 + f"; failing {len(failing)}/{N}: {','.join(failing)}")
    record += [{"item": t, "role": "uncarried", "fixture": m, "verdict": v.get(t, "unreached")} for t in targets]
ns = json.load(open(f"{work}/never-starts.json"))
ok &= ns == []
lines.append(f"never-starts               {len(ns)} verdicts filed, {N} unreached" + ("" if ns == [] else "   WRONG"))
eo = load("exit-after-one")
eo_fail = sorted(i for i, v in eo.items() if v == "fail")
ok &= sorted(eo) == items
lines.append(f"exit-after-one             {len(eo)} verdicts filed, none unreached; fail where a second answer was needed: {len(eo_fail)}/{N}"
             + ("" if sorted(eo) == items else "   WRONG"))
ok &= caught == set(items)
summary = {"grader_digest": digest, "entry": "grade_regeneration_cycle.exs", "items": items,
           "protocol": "AGENTS.md, The instrument: carried behaviour passes and uncarried behaviour fails for every item",
           "fixtures": "harness/v2/graders/calibration-regeneration-cycle/stub.py (reference and MUTANT=<name>), written from the spec alone",
           "calibrated": bool(ok), "summary": lines, "calibration": record}
with open(out, "w") as f:
    json.dump(summary, f, indent=1)
    f.write("\n")
print("\n".join(lines))
print(("CALIBRATED " if ok else "NOT CALIBRATED ") + digest)
sys.exit(0 if ok else 1)
PY
