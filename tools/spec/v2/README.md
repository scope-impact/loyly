# Spec v2 gates

Checks spec v2: the Allium of each context (`.pi/spec/<context>/allium/*.allium`) and the
Grain Event Model (`.pi/spec/<context>/event-model/*.edn`) with its `:grain/allium` links.

```bash
tools/spec/v2/gates.sh              # every context
tools/spec/v2/gates.sh measurement  # one or more contexts
```

It fails closed (exit 1) unless every gate passes, and prints the gate lines themselves:

1. **Allium** (`allium-check.sh <context>`): `allium check` and `allium analyse` of each file
   with its siblings, since the CLI resolves `use` only among the files it is given and
   reports one `spec_file` per document. Only the file's own diagnostics are printed:
   `CHECK` for an error or a warning, `ANALYSE` for an analyse finding, `INFO` for an info
   diagnostic. **The gate: no `CHECK` and no `ANALYSE` line.** `INFO` lines are listed and
   allowed. Nothing is filtered: unlike Halla Health's script, the
   `externalEntity.missingSourceHint` warning is not dropped; each external entity sits in
   an aggregate file that imports its context, so it does not arise.
2. **Grain** (`grain-validate.clj`): every context's fragments merged into one Grain model,
   refusing a block defined in two fragments or two contexts (`load.clj`), then Grain's
   structural validator, strict (`{:structural-only true :strict true}`), and its Allium
   composition gate: every command links an Allium rule, every screen an Allium surface,
   and every link (`{:spec ".pi/spec/<context>/allium/<file>.allium" :kind :rule|:surface|:entity :name "X"}`)
   resolves with `allium check` and `allium parse`. **The gate: 0 errors, 0 warnings in
   both.** Run alone: `cd tools/spec/v2 && clojure -M -i grain-validate.clj`.
3. **Obligations** (`allium-plan.sh <context>`): the test obligations `allium plan` derives,
   counted per file and per context. Must-cover = `rule_success` + `rule_failure` (one per
   `requires`, numbered in order) + `invariant`. Reported, not a gate; a spec that does not
   parse fails here too.
4. **Coverage** (`coverage.py [context ...]`, ported from `tools/spec/coverage.py`): per
   context, the must-cover obligations (each rule's success; each `requires` less the
   unit-level ones: the rule's own aggregate tested on its status, stage or path, one half
   of an `exists`/`not exists` pair between sibling rules of one trigger, the guard of an
   idempotent creation; each invariant; each contract invariant; each guarantee not
   "Verified by: gate") against the `@allium-…` tags of `<context>/spec.feature` and
   `drafts.feature`. Prints `must-cover N | covered N | drafted N | uncovered N |
   unit-level N` per context and in total. **The gate: 0 uncovered and no unknown tag.**
   `--list` also lists the unit-level and gate-verified ones.
5. **Given/When/Then** (`gwt-lint.py`, ported from `tools/spec/gwt-lint.py`): every scenario
   and draft has a Given, a When and a Then. **The gate: 0 incomplete.**
6. **Glossary** (`glossary-lint.py`, ported from `tools/spec/glossary-lint.py`): no term an
   `_Avoid_` list of `.pi/model/GLOSSARY.md` names, in any file of `.pi/spec` (Allium, EDN,
   Markdown, Gherkin). The R0 board (`event-storm.md`, `event-storm.edn`) and
   `legacy-trace.md` keep other people's words and are reported, not counted; a line that
   says "legacy", "formerly" or "avoid" is exempt. **The gate: 0 findings.** `--file F` lints
   a draft before it lands.

Needs `allium` (3.6), `jq`, `python3`, the Clojure CLI and a JVM.

`deps.edn` points Grain at `/Users/sathish.narayanan/Documents/git/loyly/grain`
(`:local/root`), because the spec-v2 worktree has no submodules. In a checkout with the
`grain` submodule initialised, `../../../grain/projects/...` works the same.

Adapted from Halla Health's `tools/grain-specs/{allium-check.sh,gates.sh,load.clj,grain-validate.clj}`.
