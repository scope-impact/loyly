"""The spec-v2 coverage gate: every must-cover Allium obligation of a context is tagged on a
scenario of that context's spec.feature or drafts.feature, and no tag names an obligation the
context does not have.

Ported from tools/spec/coverage.py (itself a port of Halla Health's harness/src/allium.ts),
adapted to the v2 layout: one context per folder, .pi/spec/<context>/allium/*.allium, each
file planned with `allium plan` in that folder so `use` resolves its siblings; the tags are
read from .pi/spec/<context>/spec.feature and drafts.feature.

Must-cover, per context:
  rule-success   each rule, as `allium plan` names it
  rule-failure   each `requires:` of a rule, less the unit-level ones: a clause that tests
                 only the rule's own aggregate (an entity its file declares) on its status,
                 stage or path (lifecycle order); one half of an `exists`/`not exists` pair
                 (a sibling rule of the same trigger requires the opposite of the same
                 thing, let-bound names expanded); or the guard of an idempotent creation
                 (`not exists X{..}` in a rule that creates X: a redelivery changes nothing).
                 A test of another aggregate's status (a concluded cycle) is behaviour.
  invariant      each top-level `invariant`
  boundary       each contract `@invariant`
  guarantee      each surface `@guarantee`, less those whose prose says "Verified by: gate
                 <name>": that gate checks them instead (listed as structural)
Everything else `allium plan` gives (fields, transitions, exposure, config defaults) is
structure, checked by `allium check` and unit tests, never by the black box.

A tag is `@allium-<obligation-id>` on a scenario's tag line; `@allium-context.<ctx>` marks a
journey scenario's contexts and is never unknown.

Usage: python3 tools/spec/v2/coverage.py [--list] [--report F] [context ...]
Prints `<context> must-cover N | covered N | drafted N | uncovered N | unit-level N` per
context and a total line, then every UNCOVERED obligation and UNKNOWN TAG (with --list also
the unit-level and structural ones). Exits 1 on any uncovered obligation or unknown tag.
"""
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = subprocess.run(["git", "-C", HERE, "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True).stdout.strip()
SPEC = os.path.join(ROOT, ".pi", "spec")

KIND = {"rule_success": "rule-success", "rule_failure": "rule-failure", "invariant": "invariant"}
OWN_STATE_FIELDS = ("status", "stage", "path")


def die(msg):
    sys.stderr.write(msg + "\n")
    sys.exit(2)


def rule_body(src, name):
    m = re.search(r"(?:^|\n)rule " + re.escape(name) + r" \{([\s\S]*?)\n\}", src)
    return m.group(1) if m else None


def clause_of(src, o):
    span = o.get("source_span")
    return src[span["start"]:span["end"]].strip() if span else ""


def rule_trigger(body):
    m = re.search(r"when:\s*(?:\w+/)?(\w+)", body or "")
    return m.group(1) if m else None


def lets(body):
    """let x = Expr bindings of a rule body, and the binding of a temporal or derived
    trigger (`when: x: Entity.field`)."""
    out = {k: v.strip() for k, v in re.findall(r"\n\s*let (\w+) = ([^\n]+)", "\n" + (body or ""))}
    m = re.search(r"when:\s*(\w+):\s*([\w.]+?)\.\w+", body or "")
    if m:
        out[m.group(1)] = m.group(2)
    return out


def expand(clause, body):
    """The clause with each let-bound name in an exists replaced by its expression."""
    b = lets(body)
    return re.sub(r"\b(exists) (\w+)$", lambda m: "%s %s" % (m.group(1), b.get(m.group(2), m.group(2))), clause)


def bare(expr):
    return re.sub(r"^\w+\.", "", expr.strip())


def classify(src, rules, o):
    """(kind, unit-reason or None), or None when the obligation is structure. `rules` maps
    each rule of the context to (its file's source, its body)."""
    kind = KIND.get(o["category"])
    if not kind:
        return None
    if kind != "rule-failure" or not o.get("source_span"):
        return kind, None
    clause = clause_of(src, o)
    name = o.get("source_construct")
    body = rule_body(src, name) if name else None
    # own-state: the rule's own aggregate (an entity declared in the rule's file) tested on
    # its status, stage or path alone
    m = re.match(r"^requires:\s*(\w+)\.(%s) = \w+$" % "|".join(OWN_STATE_FIELDS), clause)
    if m:
        ent = bare(lets(body).get(m.group(1), "")).split("{")[0].split(".")[0]
        if ent and re.search(r"(?:^|\n)entity %s \{" % re.escape(ent), src):
            return kind, "own-state"
    # one half of an exists / not exists pair: a sibling rule of the same trigger requires
    # the opposite of the same thing
    m = re.match(r"^requires:\s*(not )?exists (.+)$", expand(clause, body))
    if m:
        neg, b = m.group(1), bare(m.group(2))
        for other, (osrc, obody) in rules.items():
            if other == name or rule_trigger(obody) != rule_trigger(body):
                continue
            for oc in re.findall(r"\n\s*(requires:[^\n]+)", "\n" + obody):
                om = re.match(r"^requires:\s*(not )?exists (.+)$", expand(oc.strip(), obody))
                if om and bool(om.group(1)) != bool(neg) and bare(om.group(2)) == b:
                    return kind, "branch-pair"
    # the guard of an idempotent creation
    m = re.match(r"^requires:\s*not exists (\w+)\{", clause)
    if m and body is not None and (m.group(1) + ".created(") in body:
        return kind, "redelivery"
    return kind, None


def declared(src):
    """The guarantees and contract invariants a file declares (allium plan does not derive
    them), with the gate that verifies one when its prose names it."""
    out = []
    for k, name, prose in re.findall(r"@(guarantee|invariant) (\w+)\n((?:[ \t]+--[^\n]*\n?)+)", src):
        g = re.search(r"Verified by: gate ([\w-]+)", prose)
        out.append({"id": ("guarantee." if k == "guarantee" else "boundary.") + name,
                    "kind": "guarantee" if k == "guarantee" else "boundary",
                    "name": name, "gate": g.group(1) if g else None})
    return out


def plan(d, f):
    try:
        p = subprocess.run(["allium", "plan", f], cwd=d, capture_output=True, text=True)
    except FileNotFoundError:
        die("allium is not on PATH (allium 3.6 or later)")
    if p.returncode != 0:
        die("allium plan %s reports errors:\n%s%s" % (os.path.join(d, f), p.stdout, p.stderr))
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


def obligations(ctx):
    d = os.path.join(SPEC, ctx, "allium")
    files = sorted(f for f in os.listdir(d) if f.endswith(".allium"))
    sources = {f: open(os.path.join(d, f), encoding="utf-8").read() for f in files}
    rules = {n: (src, b) for src in sources.values()
             for n, b in re.findall(r"(?:^|\n)rule (\w+) \{([\s\S]*?)\n\}", src)}
    seen, out, planned = set(), [], set()
    for f in files:
        for o in plan(d, f):
            planned.add(o["id"])
            if o["id"] in seen:
                continue
            seen.add(o["id"])
            home = home_of(sources, o) or f
            c = classify(sources[home], rules, o)
            if not c:
                continue
            kind, unit = c
            out.append({"id": o["id"], "kind": kind, "file": home, "unit": unit, "gate": None})
        for g in declared(sources[f]):
            if g["id"] in seen:
                continue
            seen.add(g["id"])
            out.append({"id": g["id"], "kind": g["kind"], "file": f, "unit": None, "gate": g["gate"]})
    return out, planned


def tags_of(ctx, kind):
    path = os.path.join(SPEC, ctx, kind + ".feature")
    if not os.path.exists(path):
        return set()
    return set(re.findall(r"@allium-([\w.-]+)", open(path, encoding="utf-8").read()))


def contexts(argv):
    named = [a for a in argv if not a.startswith("--") and a not in argv_values(argv)]
    if named:
        return named
    return sorted(c for c in os.listdir(SPEC) if os.path.isdir(os.path.join(SPEC, c, "allium")))


def argv_values(argv):
    return [argv[i + 1] for i, a in enumerate(argv) if a == "--report" and i + 1 < len(argv)]


def main(argv):
    listing = "--list" in argv
    report = argv_values(argv)[0] if argv_values(argv) else None
    total = {"must": 0, "covered": 0, "drafted": 0, "uncovered": 0, "unit": 0}
    lines, tail, bad, rep = [], [], False, {}
    for ctx in contexts(argv):
        obs, planned = obligations(ctx)
        spec, drafts = tags_of(ctx, "spec"), tags_of(ctx, "drafts")
        for o in obs:
            o["status"] = ("unit" if o["unit"] else "gate" if o["gate"] else "covered" if o["id"] in spec
                           else "drafted" if o["id"] in drafts else "uncovered")
        must = [o for o in obs if not o["unit"] and not o["gate"]]
        n = {st: len([o for o in must if o["status"] == st]) for st in ("covered", "drafted", "uncovered")}
        unit = [o for o in obs if o["unit"]]
        known = set(o["id"] for o in obs) | planned
        unknown = sorted(t for t in spec | drafts if t not in known and not t.startswith("context."))
        lines.append("%-20s must-cover %3d | covered %3d | drafted %3d | uncovered %3d | unit-level %3d"
                     % (ctx, len(must), n["covered"], n["drafted"], n["uncovered"], len(unit)))
        tail += ["  UNCOVERED %s %s/allium/%s" % (o["id"], ctx, o["file"]) for o in must if o["status"] == "uncovered"]
        tail += ["  UNKNOWN TAG %s (%s)" % (t, ctx) for t in unknown]
        if listing:
            tail += ["  unit-level %s (%s) %s" % (o["id"], o["unit"], ctx) for o in unit]
            tail += ["  structural %s -> gate %s" % (o["id"], o["gate"]) for o in obs if o["gate"]]
        bad = bad or n["uncovered"] > 0 or bool(unknown)
        for k, v in (("must", len(must)), ("covered", n["covered"]), ("drafted", n["drafted"]),
                     ("uncovered", n["uncovered"]), ("unit", len(unit))):
            total[k] += v
        rep[ctx] = {"obligations": obs, "unknownTags": unknown}
    lines.append("%-20s must-cover %3d | covered %3d | drafted %3d | uncovered %3d | unit-level %3d"
                 % ("total", total["must"], total["covered"], total["drafted"], total["uncovered"], total["unit"]))
    print("\n".join(lines + tail))
    if report:
        with open(report, "w", encoding="utf-8") as fh:
            json.dump({"summary": total, "contexts": rep}, fh, indent=1)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
