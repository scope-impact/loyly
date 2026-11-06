"""The glossary lint: no term a GLOSSARY.md entry says to avoid is used in the model.

The glossary (.pi/model/GLOSSARY.md) is the authority for the domain's words (ADR 0005). Each
entry is `**Term**:` followed by its definition and an `_Avoid_:` list (which may wrap onto
the next lines, up to a blank line). An avoided term is a synonym the model must not use for
that concept, as an identifier or in prose.

How a line is read:
  - it is split into phrases at punctuation, and each phrase into words: identifiers split at
    camelCase, `_`, and `-` (a `/`, as in a namespaced keyword or a path, ends a phrase), so `RegenerationCut`, `regeneration_cut`,
    `regeneration-cut`, and "regeneration cut" are the same two words; words are lowercased
    and a plural `s` is dropped;
  - an avoided term found there is a finding unless one occurrence of a glossary term (the
    bold names) covers it whole: an avoided word inside a defined term ("workspace" in
    "workspace generation") and an avoided term that is itself a defined term (a
    cross-reference, such as Harness and Instrument) are not findings, while a defined word
    inside a longer avoided term ("harness" in "harness task") does not excuse it;
  - a parenthetical in an `_Avoid_` item is a note, not part of the avoided words.
The seam (.pi/model/affordances.yaml) is the published language: its action names, rpc
methods, tool names, and type values are terms too, so quoting them verbatim is never a
finding (the glossary names the ones that are domain values).
A line that says "formerly" or "avoid" is exempt: it names an old or avoided term on purpose.

Scope, enforced (exit 1 on any finding): .pi/model/*.allium, the harness's Grain Event Model
(.pi/model/event-model/loyly/), .pi/architecture.md, and the ADRs (.pi/model/docs/adr/). The
declared copy of ORC's `:sheet` blocks (event-model/orc/) is ORC's published language, which the
harness conforms to, so it is not linted.
Reported only: the Gherkin in .pi/model/plan/ (accepted scenarios change only with the product
owner's say-so); its findings are listed for the product owner and never fail the lint.

Usage: python3 tools/spec/glossary-lint.py [--quiet]
Prints one line per finding, then `glossary: N terms, M avoided | K findings | P plan/ hits`.
"""
import glob
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = subprocess.run(["git", "-C", HERE, "rev-parse", "--show-toplevel"], capture_output=True, text=True,
                      check=True).stdout.strip()
MODEL = os.path.join(ROOT, ".pi", "model")
GLOSSARY = os.path.join(MODEL, "GLOSSARY.md")

ENFORCED = (sorted(glob.glob(os.path.join(MODEL, "*.allium")))
            + sorted(glob.glob(os.path.join(MODEL, "event-model", "loyly", "**", "*.clj"), recursive=True))
            + [os.path.join(ROOT, ".pi", "architecture.md")]
            + sorted(glob.glob(os.path.join(MODEL, "docs", "adr", "*.md"))))
REPORTED = sorted(glob.glob(os.path.join(MODEL, "plan", "*", "*.feature")))

PHRASE_BREAK = re.compile(r"[,;:.!?()\[\]{}\"`'|<>=+*#&/]")
EXEMPT = re.compile(r"formerly|avoid", re.I)


def stem(w):
    w = w.lower()
    if len(w) > 4 and w.endswith("ies"):
        return w[:-3] + "y"
    if len(w) > 4 and w.endswith("sses"):
        return w[:-2]
    if len(w) > 3 and w.endswith("s") and not w.endswith("ss") and not w.endswith("us"):
        return w[:-1]
    return w


def words(phrase):
    phrase = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", phrase)
    phrase = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1 \2", phrase)
    return [stem(w) for w in re.split(r"[\s_/\-\\]+", phrase) if re.match(r"^[A-Za-z][A-Za-z0-9]*$", w)]


def phrases(line):
    return [ws for ws in (words(p) for p in PHRASE_BREAK.split(line)) if ws]


def glossary():
    """[(term words, term)], [(avoided words, avoided text, term)]"""
    text = open(GLOSSARY, encoding="utf-8").read()
    terms, avoided = [], []
    for block in re.split(r"\n(?=\*\*[^*\n]+\*\*:)", text):
        m = re.match(r"\*\*([^*\n]+)\*\*:", block)
        if not m:
            continue
        term = m.group(1).strip()
        terms.append((words(term), term))
        a = re.search(r"_Avoid_:\s*((?:.+\n?)+?)(?:\n\s*\n|\Z)", block + "\n\n")
        if not a:
            continue
        for item in re.split(r",", " ".join(a.group(1).split())):
            item = re.sub(r"\([^)]*\)", "", item).strip().rstrip(".").strip()
            if item:
                avoided.append((words(item), item, term))
    return terms, avoided


def seam_terms():
    """The seam's published names: action keys, rpc methods, tool names, and type enum values."""
    path = os.path.join(MODEL, "affordances.yaml")
    if not os.path.exists(path):
        return []
    text = open(path, encoding="utf-8").read()
    names = set(re.findall(r"(?m)^  ([A-Z]\w+):\s*$", text))
    names |= set(re.findall(r"rpc:\s*([\w-]+)", text))
    names |= set(re.findall(r"tool:\s*\{name:\s*(\w+)\}", text))
    for vals in re.findall(r"(?m)^  \w+:\s*\{enum:\s*\[([^\]]*)\]\}", text):
        names |= {v.strip() for v in vals.split(",")}
    return [(words(n), n) for n in sorted(names) if words(n)]


def find(seq, ws, start=0):
    n = len(ws)
    for i in range(start, len(seq) - n + 1):
        if seq[i:i + n] == ws:
            yield i


def scan(path, terms, avoided):
    out = []
    for no, line in enumerate(open(path, encoding="utf-8").read().splitlines(), 1):
        if EXEMPT.search(line):
            continue
        for ws in phrases(line):
            spans = [(i, i + len(tws)) for tws, _ in terms for i in find(ws, tws)]
            for aws, text, term in avoided:
                if not aws:
                    continue
                for i in find(ws, aws):
                    j = i + len(aws)
                    if not any(a <= i and j <= b for a, b in spans):
                        out.append((os.path.relpath(path, ROOT), no, text, term, line.strip()))
    return out


def main(argv):
    quiet = "--quiet" in argv
    terms, avoided = glossary()
    terms += seam_terms()
    found = [f for p in ENFORCED if os.path.exists(p) for f in scan(p, terms, avoided)]
    plan = [f for p in REPORTED for f in scan(p, terms, avoided)]
    for f, no, text, term, line in found:
        print("AVOIDED %s:%d '%s' (avoid for %s): %s" % (f, no, text, term, line[:120]))
    if not quiet:
        for f, no, text, term, line in plan:
            print("PLAN    %s:%d '%s' (avoid for %s): %s" % (f, no, text, term, line[:120]))
    print("glossary: %d terms, %d avoided | %d findings | %d plan/ hits (reported, for the product owner)"
          % (len(terms), len(avoided), len(found), len(plan)))
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
