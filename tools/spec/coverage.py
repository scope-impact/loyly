"""The coverage gate: every must-cover Allium obligation of .pi/model is tagged on a scenario of
a slice's spec.feature or drafts.feature, and no tag names an obligation that does not exist.

A port of halla-health harness/src/allium.ts. The obligations are what `allium plan` derives
from each .pi/model/*.allium file (run in that folder so `use` resolves its siblings), less
what a black box cannot see:

  rule-success   each rule, as `allium plan` names it
  rule-failure   each `requires:` of a rule, less the unit-level ones: a clause that tests
                 only the entity's own status or path (lifecycle order, or which step
                 applies), one side of a branch pair (one rule requires `exists X`, a sibling
                 `not exists X`), or the guard of an idempotent creation (`not exists X{..}`
                 in a rule that creates X: a redelivery changes nothing)
  invariant      each top-level `invariant`
  boundary       each contract `@invariant`, and
  guarantee      each surface `@guarantee`, less those whose prose says
                 "Verified by: gate <name>": that gate checks them instead
Everything else `allium plan` gives (fields, transitions, surface exposure, values) is
structure, checked by `allium check` and unit tests, never by the black box.

A tag is `@allium-<obligation-id>` on a scenario's tag line (its `@item-NN-SSS` line in
spec.feature, or the `@draft` line in drafts.feature).

Usage (from anywhere in the repo):
  python3 tools/spec/coverage.py              the gate: prints the counts, exits 1 on any
                                              uncovered obligation or unknown tag
  python3 tools/spec/coverage.py --report F   also writes the full classification as JSON to
                                              F (the event-model page reads it, so the page
                                              and the gate cannot disagree)
"""
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = subprocess.run(["git", "-C", HERE, "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True).stdout.strip()
MODEL = os.path.join(ROOT, ".pi", "model")
PLAN = os.path.join(MODEL, "plan")

KIND = {"rule_success": "rule-success", "rule_failure": "rule-failure", "invariant": "invariant"}
# The fields whose test makes a refusal "the entity's own state" (allium.ts: status or path).
OWN_STATE_FIELDS = ("status", "stage", "path")  # stage: the cycle's lifecycle order, like status


def die(msg):
    sys.stderr.write(msg + "\n")
    sys.exit(2)


def rule_body(src, name):
    m = re.search(r"(?:^|\n)rule " + re.escape(name) + r" \{([\s\S]*?)\n\}", src)
    return m.group(1) if m else None


def clause_of(src, o):
    span = o.get("source_span")
    return src[span["start"]:span["end"]].strip() if span else ""


def classify(src, o):
    """(kind, unit-reason or None), or None when the obligation is structure."""
    kind = KIND.get(o["category"])
    if not kind:
        return None
    if kind != "rule-failure" or not o.get("source_span"):
        return kind, None
    clause = clause_of(src, o)
    if re.match(r"^requires:\s*\w+\.(%s) = \w+$" % "|".join(OWN_STATE_FIELDS), clause):
        return kind, "own-state"
    m = re.match(r"^requires:\s*(?:not )?exists (.+)$", clause)
    if m:
        b = m.group(1)
        if ("requires: exists " + b) in src and ("requires: not exists " + b) in src:
            return kind, "branch-pair"
    m = re.match(r"^requires:\s*not exists (\w+)\{", clause)
    if m and o.get("source_construct"):
        body = rule_body(src, o["source_construct"])
        if body is not None and (m.group(1) + ".created(") in body:
            return kind, "redelivery"
    return kind, None


def declared(src):
    """The guarantees and contract invariants a file declares, with the gate that verifies one
    when its prose names it. `allium plan` does not derive these."""
    out = []
    for k, name, prose in re.findall(r"@(guarantee|invariant) (\w+)\n((?:[ \t]+--[^\n]*\n?)+)", src):
        g = re.search(r"Verified by: gate ([\w-]+)", prose)
        text = " ".join(l.strip()[2:].strip() for l in prose.splitlines() if l.strip())
        out.append({"id": ("guarantee." if k == "guarantee" else "boundary.") + name,
                    "kind": "guarantee" if k == "guarantee" else "boundary",
                    "name": name, "gate": g.group(1) if g else None, "text": text})
    return out


def plan(file):
    try:
        p = subprocess.run(["allium", "plan", file], cwd=MODEL, capture_output=True, text=True)
    except FileNotFoundError:
        die("allium is not on PATH (allium 3.6 or later)")
    if p.returncode != 0:
        die("allium plan %s reports errors:\n%s%s" % (os.path.join(MODEL, file), p.stdout, p.stderr))
    return json.loads(p.stdout)["obligations"]


def home_of(sources, o):
    name = o.get("source_construct")
    if not name:
        return None
    decl = re.compile(r"(?:^|\n)(?:rule|invariant) " + re.escape(name) + r" \{")
    for f, src in sources.items():
        if decl.search(src):
            return f
    return None


def obligations():
    """Every behavioural obligation, each once, in file order, with its classification."""
    files = sorted(f for f in os.listdir(MODEL) if f.endswith(".allium"))
    sources = {f: open(os.path.join(MODEL, f), encoding="utf-8").read() for f in files}
    seen, out, planned = set(), [], set()
    for f in files:
        for o in plan(f):
            planned.add(o["id"])
            if o["id"] in seen:
                continue
            seen.add(o["id"])
            home = home_of(sources, o) or f
            c = classify(sources[home], o)
            if not c:
                continue
            kind, unit = c
            ob = {"id": o["id"], "kind": kind, "file": home, "construct": o.get("source_construct"), "unit": unit, "gate": None}
            if kind == "rule-failure":
                ob["rule"] = o.get("source_construct")
                ob["n"] = int(o["id"].rsplit(".", 1)[1])
                ob["clause"] = re.sub(r"^requires:\s*", "", clause_of(sources[home], o))
            if kind == "rule-success":
                ob["rule"] = o.get("source_construct")
            out.append(ob)
        for d in declared(sources[f]):
            if d["id"] in seen:
                continue
            seen.add(d["id"])
            out.append({"id": d["id"], "kind": d["kind"], "file": f, "construct": d["name"], "unit": None,
                        "gate": d["gate"], "text": d["text"]})
    return out, planned


def scenarios():
    """Each scenario of every slice's spec.feature and drafts.feature: file, line, its tags."""
    out = []
    if not os.path.isdir(PLAN):
        return out
    for d in sorted(os.listdir(PLAN)):
        for kind in ("spec", "drafts"):
            path = os.path.join(PLAN, d, kind + ".feature")
            if not os.path.exists(path):
                continue
            tags = []
            for i, line in enumerate(open(path, encoding="utf-8").read().splitlines(), 1):
                s = line.strip()
                if s.startswith("@"):
                    tags += s.split()
                elif re.match(r"^Scenario(?: Outline)?:", s):
                    item = next((t[6:] for t in tags if t.startswith("@item-")), None)
                    out.append({"file": os.path.relpath(path, ROOT), "line": i, "kind": kind, "item": item,
                                "title": re.sub(r"^Scenario(?: Outline)?:\s*", "", s),
                                "alliumTags": [t[8:] for t in tags if t.startswith("@allium-")]})
                    tags = []
                elif s.startswith(("Rule:", "Feature:")):
                    tags = []
    return out


def tags_of(kind):
    """allium.ts reads every `@allium-…` in the file; so does this."""
    out = set()
    if os.path.isdir(PLAN):
        for d in sorted(os.listdir(PLAN)):
            path = os.path.join(PLAN, d, kind + ".feature")
            if os.path.exists(path):
                out.update(re.findall(r"@allium-([\w.-]+)", open(path, encoding="utf-8").read()))
    return out


def main(argv):
    report = argv[argv.index("--report") + 1] if "--report" in argv else None
    obs, planned = obligations()
    spec, drafts = tags_of("spec"), tags_of("drafts")
    sc = scenarios()
    for o in obs:
        o["scenarios"] = [{k: s[k] for k in ("file", "line", "kind", "item", "title")} for s in sc if o["id"] in s["alliumTags"]]
        if o["unit"]:
            o["status"] = "unit"
        elif o["gate"]:
            o["status"] = "gate"
        elif o["id"] in spec:
            o["status"] = "covered"
        elif o["id"] in drafts:
            o["status"] = "drafted"
        else:
            o["status"] = "uncovered"
    must = [o for o in obs if not o["unit"] and not o["gate"]]
    by = lambda st: [o for o in must if o["status"] == st]
    covered, drafted, uncovered = by("covered"), by("drafted"), by("uncovered")
    unit = sorted(o["id"] for o in obs if o["unit"])
    gated = sorted("%s -> gate %s" % (o["id"], o["gate"]) for o in obs if o["gate"])
    known = set(o["id"] for o in obs) | planned
    unknown = sorted(t for t in spec | drafts if t not in known and not t.startswith("context."))
    lines = ["must-cover %d | covered %d | drafted %d | uncovered %d | unit-level %d"
             % (len(must), len(covered), len(drafted), len(uncovered), len(unit))]
    lines += ["  drafted   %s" % o["id"] for o in drafted]
    lines += ["  UNCOVERED %s %s" % (o["id"], o["file"]) for o in uncovered]
    lines += ["  UNKNOWN TAG %s" % t for t in unknown]
    lines += ["  structural %s" % g for g in gated]
    lines += ["unit-level: " + ", ".join(unit)]
    print("\n".join(lines))
    if report:
        with open(report, "w", encoding="utf-8") as fh:
            json.dump({"summary": {"mustCover": len(must), "covered": len(covered), "drafted": len(drafted),
                                   "uncovered": len(uncovered), "unitLevel": len(unit), "gated": len(gated)},
                       "ownStateFields": list(OWN_STATE_FIELDS),
                       "obligations": obs, "unknownTags": unknown, "planned": sorted(planned)}, fh, indent=1)
    return 1 if uncovered or unknown else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
