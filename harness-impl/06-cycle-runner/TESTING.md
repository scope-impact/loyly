# Builder verification — repair attempt 3

The supplied gate failed `@item-06-035`: its two reads saw 146 and 148 events.
The commands waited for the advance workflow's result but not all asynchronous
Grain checkpoints and ORC trace effects. A query could inherit those late writes.

`runtime.clj` now supplies a command-side completion barrier. From one snapshot
of the runner's own tenant and store, it requires every named processor's
checkpoint to cover its latest subscribed event, and every started ORC tick to
have both its completion and its trace. The trace requirement also covers ORC's
checkpoint-before effects. Both command methods await this barrier before
responding. Queries remain unchanged: no workflow, effect, or event append.
The barrier checks durable evidence, not a fixed settling sleep.

Two added seam-driven regression tests deliberately block a result-delivery
checkpoint and a verdict workflow's trace append. Each verifies that the command
cannot respond while the append is blocked, then verifies stable query results
and no delayed store writes. The checkpoint regression failed before the repair
(`work/checkpoint-red.log`). No existing test or assertion was removed or weakened.
The previous repair's portable permission fixture is retained.

Latest checks:

- Full `test.sh`: **58 tests, 198 assertions, zero failures or errors**.
- Test metadata matches all **52** accepted item identities; `UNCARRIED.md` is empty.
- Manual fresh-process identity replay: **15.81 seconds**. The invalid commit was
  refused before context calls; all five exiting contexts were named; Specification
  exiting at stamp halted the run with no effect result. Neither self-check nor
  builder launched.
- Direct audit of that new Grain file: **151 events**, **3 successful ORC ticks**,
  exactly **3 terminal events and 3 traces**, checkpoints present. Runner events
  have exactly their model fields and lie inside their named successful workflows.

One intermediate full-suite replay exceeded the unchanged 30-second identity
assertion (47.37 seconds); its log is retained as
`work/full-suite-timing-failure.log`. `start.sh` now selects tier-1 JIT compilation
for bounded startup rather than optimizing for peak JVM throughput. The latest
full suite and subsequent manual replay passed the original time budget. No
macOS host was available in this room.

Evidence: `work/full-suite.log`, `work/checkpoint-red.log`,
`work/checkpoint-green.log`, `work/trace-green.log`, `work/manual-identity.log`,
`work/manual-audit.log`, and `work/manual-store-path`. These are builder checks,
not independent gate or score verdicts.
