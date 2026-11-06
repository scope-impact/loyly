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

Needs `allium` (3.6), `jq`, the Clojure CLI and a JVM.

`deps.edn` points Grain at `/Users/sathish.narayanan/Documents/git/loyly/grain`
(`:local/root`), because the spec-v2 worktree has no submodules. In a checkout with the
`grain` submodule initialised, `../../../grain/projects/...` works the same.

Adapted from Halla Health's `tools/grain-specs/{allium-check.sh,gates.sh,load.clj,grain-validate.clj}`.
