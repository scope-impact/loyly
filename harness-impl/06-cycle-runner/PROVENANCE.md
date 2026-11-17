# Provenance: cycle runner, accepted repair

The complete generated cycle-25 repair candidate replaces the accepted cycle-23 runner.
The operator accepted it on 2026-11-17 with the explicit profile exception below. The
source was copied byte for byte from the fenced repair room; generated source was not edited
during promotion.

| Evidence | Result |
| --- | --- |
| Spec and repair cut | `3d58399`, current slice-06 spec and ADR 0007 repair contract |
| Accepted base | cycle 23 attempt 2, 39-file SHA-256 `947d49609d24e2325fcb027a695b03c3b68c2b9cd9337a3401d39cf58df5231a` |
| Repair selection | `06-047`, `06-066` |
| Repair source diff | `harness/v2/REPAIR-06.md`; `src/loyly/effects.clj`, `src/loyly/main.clj`, `test/loyly/test_runner.clj`, and new `REPAIR.md` inside the candidate |
| Candidate source digest | SHA-256 `2b4bad62bdbfdd7c752761df3971f18cd044716266b2d020d99917090089e13f` over 40 files, excluding caches and this provenance |
| Independent gate | `.pi/harness/.state/logs/gate-c25-slice06.log`, passed: 45 tests, 406 assertions, identity over stdio including the built-in HTTP gate, and Grain store check |
| Installed-copy gate | `.pi/harness/.state/logs/gate-promoted-repo-slice06-c25.log`, passed after the byte-identical copy |
| Independent score | `harness/v2/cycles/06/25/`, 61/61; versus accepted cycle 23, 047 and 066 newly passing, no newly failing item |
| Legacy instrument shadow | `harness/v2/shadow/06/25/comparison.md`, same clean tree, app bytes, gate and ordered 11 verdicts as legacy |
| Profile | Filed as `failed`; sole problem is eight separately named fresh retries outside the checklist |

## Explicit profile exception

The raw retry captures and the filed failed profile are retained. An independent audit
(`harness/v2/cycles/06/25/profile-retry-audit.md`) found that each retry has ordered
before/after captures, accounted model and store evidence, and no owned runner or VM left
after shutdown. Graded retries have a live QEMU sample, and the canonical 61 cases are
clean. The operator explicitly chose "Accept audited exception" rather than revising the
profile instrument before this acceptance. The profile reporter's retry model remains open;
this exception does not rewrite its `failed` status or any verdict.

The accepted build is the running build. Future changes use a new approved regeneration or
repair cycle; do not edit this generated source by hand.
