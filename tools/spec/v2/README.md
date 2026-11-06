# Spec v2 validator

Checks the Grain Event Model of spec v2 (`.pi/spec/<context>/event-model/*.edn`).

```bash
cd tools/spec/v2
clojure -M -i grain-validate.clj
```

It merges every context's fragments into one Grain model, refusing a block defined in two
fragments or two contexts (`load.clj`), then runs:

- **Grain's structural validator**, strict (`{:structural-only true :strict true}`): schemas
  parse, edges point at blocks of the right kind, flows follow the CQRS grammar and are
  continuous. **The gate: 0 errors, 0 warnings.** The script exits 1 otherwise.
- **Grain's Allium composition gate**, reported only. It fails until round R2 writes the
  Allium and adds the `:grain/allium` links (every command needs a rule, every screen a
  surface); it does not set the exit status yet.

Needs the Clojure CLI and a JVM; the composition gate also calls `allium`.

`deps.edn` points Grain at `/Users/sathish.narayanan/Documents/git/loyly/grain`
(`:local/root`), because the spec-v2 worktree has no submodules. In a checkout with the
`grain` submodule initialised, `../../../grain/projects/...` works the same.

Adapted from Halla Health's `tools/grain-specs/{load.clj,grain-validate.clj}`.
