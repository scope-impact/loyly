"""Every scenario has a Given, a When and a Then; And/But continue the keyword before them.
In event-modelling terms: Given the events that already happened (the facts that left the
aggregate in the state the rule requires), When one command and who issues it, or the time
passing, Then the events it records, or the refusal and the state left unchanged.

Ported from tools/spec/gwt-lint.py to the v2 layout: reads every
.pi/spec/<context>/spec.feature and drafts.feature.
Usage: python3 tools/spec/v2/gwt-lint.py [spec-dir]
Prints each incomplete scenario and exits 1 if there is one."""
import os
import re
import subprocess
import sys

here = os.path.dirname(os.path.abspath(__file__))
root = subprocess.run(["git", "-C", here, "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True).stdout.strip()
spec = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, ".pi", "spec")
files = []
for d in sorted(os.listdir(spec)):
    for k in ("spec", "drafts"):
        f = os.path.join(spec, d, k + ".feature")
        if os.path.exists(f):
            files.append(f)
bad = n = 0
for f in files:
    t = open(f, encoding="utf-8").read()
    for m in re.finditer(r"\n\s*Scenario(?: Outline)?:\s*(.*?)\n(.*?)(?=\n\s*(?:@|Scenario|Rule:|Feature:)|\Z)", t, re.S):
        n += 1
        k = {l.strip().split()[0] for l in m.group(2).split("\n") if re.match(r"\s*(Given|When|Then)\b", l)}
        missing = [x for x in ("Given", "When", "Then") if x not in k]
        if missing:
            bad += 1
            print(f"  INCOMPLETE {os.path.relpath(f, spec)}: {m.group(1).strip()[:80]} (no {', '.join(missing)})")
print(f"gwt-lint: {n} scenarios in {len(files)} files, {bad} incomplete")
sys.exit(1 if bad else 0)
