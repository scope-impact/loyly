"""The named structural checks of spec v2: each verifies a guarantee or a contract invariant
whose prose says "Verified by: gate <name>", so that no draft scenario has to (decisions.md,
"Calls after R3", 3). A check reads the spec itself (Allium and the event model), never an
implementation.

  budget-in-seconds-and-polls   contract GateRoom, @invariant BudgetInSecondsAndPolls
                                (regeneration-cycle): the boot budget is declared once, as
                                seconds and polls in one config block of one file; every
                                gate verdict carries both, in the Allium emission and in the
                                event's schema; a pass carries the poll that answered and the
                                margin is derived from it, with no clock

Usage: python3 tools/spec/v2/structural.py [--names]
Prints `structural <name> ok` or `structural <name> FAIL: <why>` per check, and exits 1 on
any failure. `--names` prints the check names only (coverage.py reads CHECKS directly).
"""
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = subprocess.run(["git", "-C", HERE, "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True).stdout.strip()
SPEC = os.path.join(ROOT, ".pi", "spec")


def read(*p):
    with open(os.path.join(SPEC, *p), encoding="utf-8") as fh:
        return fh.read()


def budget_in_seconds_and_polls():
    d = os.path.join(SPEC, "regeneration-cycle", "allium")
    configs = []
    for f in sorted(os.listdir(d)):
        if not f.endswith(".allium"):
            continue
        for block in re.findall(r"\nconfig \{([\s\S]*?)\n\}", read("regeneration-cycle", "allium", f)):
            names = set(re.findall(r"^\s*(boot_budget_seconds|boot_polls):", block, re.M))
            if names:
                configs.append((f, names))
    if len(configs) != 1 or configs[0][1] != {"boot_budget_seconds", "boot_polls"}:
        return "the boot budget is not declared once as seconds and polls together: %s" % configs
    src = read("regeneration-cycle", "allium", "gate-attempt.allium")
    for ev in ("GateAttemptPassed", "GateAttemptFailed"):
        emits = re.findall(r"\b%s\(([^\n]*)\)" % ev, src)
        if not emits:
            return "no %s emission in gate-attempt.allium" % ev
        for e in emits:
            for field in ("boot_budget_seconds:", "boot_polls:") + (("answered_on_poll:",) if ev == "GateAttemptPassed" else ()):
                if field not in e:
                    return "%s is emitted without %s" % (ev, field.rstrip(":"))
    if not re.search(r"\n\s*boot_margin_polls: boot_polls - answered_on_poll\n", src):
        return "the margin is not derived from the poll that answered (GateAttempt.boot_margin_polls)"
    schemas = read("regeneration-cycle", "event-model", "handoffs.edn") + read("regeneration-cycle", "event-model", "gate-attempt.edn")
    for ev, extra in (("gate-attempt-passed", ["answered-on-poll"]), ("gate-attempt-failed", [])):
        m = re.search(r":regeneration-cycle/%s\n([\s\S]*?)\]\}" % ev, schemas)
        if not m:
            return "no event regeneration-cycle/%s in the event model" % ev
        for field in ["boot-budget-seconds", "boot-polls"] + extra:
            if "[:%s " % field not in m.group(1):
                return "event %s has no %s in its schema" % (ev, field)
    return None


CHECKS = {"budget-in-seconds-and-polls": budget_in_seconds_and_polls}


def main(argv):
    if "--names" in argv:
        print("\n".join(sorted(CHECKS)))
        return 0
    bad = False
    for name, check in sorted(CHECKS.items()):
        why = check()
        print("structural %s %s" % (name, "ok" if why is None else "FAIL: " + why))
        bad = bad or why is not None
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
