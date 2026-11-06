# Regeneration cycle (harness: fence and gate): domain design

status: proposed, 2026-11-06 (spec v2, R1). From the storm (`../event-storm.md`: Fence,
Regenerate, Gate, Conclude) and the legacy `fence.sh`, `verify.sh`, `probe.sh` and
`check.sh`, in the glossary's words.

## Files

| Module | Event Model | Aggregate | Lives |
|---|---|---|---|
| Context | `event-model/area.edn` (screen `cycle-progress`; five flows) | none | |
| Harness self-check | `event-model/harness-self-check.edn` | **HarnessSelfCheck** | event store; one per cycle, naming its harness digest |
| Clean room | `event-model/clean-room.edn` | **CleanRoom** | event store; one per cycle |
| Regeneration cycle | `event-model/regeneration-cycle.edn` | **RegenerationCycle** (stage order, attempt count, the one conclusion) | event store; one per cycle identity |
| Gate attempt | `event-model/gate-attempt.edn` | **GateAttempt** | event store; one per cycle and attempt number |
| Handoffs produced | `event-model/handoffs.edn` (`gate-attempt-passed`) | | consumed by Measurement |

Actors: **ORC runtime** issues every command here except the two conclusions, from its
workflow nodes (fence, regenerate, gate); those enter the flows from `nil`. The
**conclude-cycle** policy issues `pass-cycle` and `fail-cycle`. The **operator** only
observes (`cycle-progress`). The builder host issues nothing in R1: the builder session
arrives in a later round and the gate is never the builder's.

## Design decisions

1. **ORC runs, the harness records.** ORC owns the execution order; each command records a
   stage fact from what a node did in a room, and the harness guards it (R2 `requires`).
   No harness policy starts a stage: the only policy here concludes the cycle.
2. **The cycle identity is given, not minted, in R1.** `record-harness-self-check` is the
   first fact naming a cycle and its cut. Submission (later) mints the identity at submit
   time (H8, proposed) and adds `cycle-submitted` to `regeneration-cycles`; the status enum
   already holds `submitted`.
3. **Four aggregates.** The self-check, the clean room and each gate attempt have their own
   one-fact-per-thing boundary; the cycle owns stage order, the attempt limit (3) and the
   single conclusion. `record-regenerated-candidate` carries `attempt` and `purpose`
   (`fresh-generation`, `verification-repair`), so the repair builder adds a producer
   later, not a new event.
4. **Self-check failed is a fact, a fence refusal is not.** A self-check is a run of the
   instrument with a result, and the failing check must be named and remembered; it is an
   event, and the conclude policy fails the cycle. A fence proof that does not hold (a
   fenced path matched nothing, the prior implementation reachable) is a refused
   `prove-clean-room`: no clean room exists, so nothing is recorded for it, and ORC's fence
   node fails the cycle (`fail-cycle`, stage `fence`, reason `fence-refused`, the refusal in
   `detail`).
5. **The legacy gate guards are reasons on `gate-attempt-failed`**, not events and not
   refusals. The gate ran in its room; its verdict is the fact and consumes an attempt. A
   refusal would record nothing, leave the attempt unspent and let a no-op suite be retried
   silently. Reasons: `contract-missing` (test or launch entrypoint missing; the no-op
   suite), `suite-discovery-empty`, `seam-answered-before-boot`, `suite-red`,
   `boot-budget-exhausted`, `identity-probe-failed`.
6. **The boot budget and its margin are recorded** on both verdicts (`boot-budget-seconds`,
   `boot-polls`, `answered-on-poll` when it answered), so a boot creeping toward the budget
   is visible on a green run (legacy 2026-09-08).
7. **Gate attempts exhausted is a read-model fact**, not an event: `cycles-to-conclude` sees
   a failed attempt 3 and the policy fails the cycle with reason `gate-attempts-exhausted`.
8. **One conclusion per cycle**: `cycle-passed` after the scoreboard is reported;
   `cycle-failed` with a stage and reason. A cycle failed before the score shows as "no
   score".

## Storm events here

| Storm event | Became |
|---|---|
| ◆ Harness self-check passed | event `harness-self-check-passed` |
| ✗ Harness self-check failed | event `harness-self-check-failed` (names the check); the conclude policy then records `cycle-failed` |
| ◆ Clean room proved | event `clean-room-proved` |
| ✗ Fence refused: a fenced path matched nothing | refusal (R2) of `prove-clean-room`; ORC records `cycle-failed` (stage fence) |
| ✗ Fence refused: prior implementation reachable in the object graph | refusal (R2) of `prove-clean-room`; ORC records `cycle-failed` (stage fence) |
| ◆ Candidate regenerated | event `candidate-regenerated` |
| Candidate installed in the gate room | event `candidate-installed-in-gate-room` |
| ◆ Gate attempt passed | event `gate-attempt-passed` (handoff) |
| ✗ Gate attempt failed | event `gate-attempt-failed` |
| ✗ Gate refused: tool missing or no-op suite | `gate-attempt-failed`, reason `contract-missing` |
| ✗ Gate refused: suite discovery empty | `gate-attempt-failed`, reason `suite-discovery-empty` |
| ✗ Gate refused: seam answered before boot | `gate-attempt-failed`, reason `seam-answered-before-boot` |
| ✗ Boot budget exhausted | `gate-attempt-failed`, reason `boot-budget-exhausted` |
| ✗ Identity probe failed | `gate-attempt-failed`, reason `identity-probe-failed` |
| ◆ Cycle passed | event `cycle-passed` |
| ✗ Cycle failed | event `cycle-failed` |

Out of R1 but touched: "Gate attempts exhausted" (read-model fact, decision 7). Not modeled:
builder room and session, slice built and continued, repair builder launched, cycle
started by ORC, timed out, worker replaced, model drift.

## Refusals for R2

- `record-harness-self-check`: cut not pinned; goldens digest not the current goldens;
  already recorded for the cycle; `failed` without a failing check.
- `prove-clean-room`: self-check not passed; a fenced path matched nothing; root commits or
  commits not 1; a fenced path left in the tree; a fenced path reachable in the object
  graph; already proved; the cut not the self-checked cut.
- `record-regenerated-candidate`: no clean room proved; attempt not the next one or over
  3; purpose not `fresh-generation` for attempt 1 and `verification-repair` after; cycle
  concluded.
- `install-candidate-in-gate-room`: no candidate regenerated for the attempt, or another
  digest; already installed.
- `record-gate-attempt`: not installed; already has a verdict (a replay spends no attempt,
  H1 proposed); failed without a reason or passed with one; passed without
  `answered-on-poll` and `identity-proof`; `answered-on-poll` over `boot-polls`.
- `pass-cycle`: scoreboard not reported; concluded. `fail-cycle`: concluded.

## Open questions

- **H1** (blocking, proposed answer taken): the recorded verdict is truth; a gate replay
  after a crash records nothing new and spends no attempt.
- **H5** (blocking): the attempt limit is 3 here (AGENTS.md); does it move into the
  approved preparation's budget?
- **H6** (blocking): `evidence-root` is gate evidence only. Which part proves score
  evidence never reaches it?
- **H8** (blocking, proposed answer taken): identity at submission; R1 takes it as given.
- **H15** (can wait): beyond the guards, who catches a gate that passed without exercising
  the product?
- **H24** (can wait): reuse of a passing self-check for an identical harness digest. R1
  records one self-check per cycle; reuse is an R2 rule, and whether the key covers grader
  digests and the capture policy is open.
- **H25** (can wait, disagreement): are scoring tools fenced? `fenced-paths` is data, so
  the model holds either answer.
- **New**: does `seam-answered-before-boot`, a fault of the room not the candidate, spend
  one of the three attempts and start a repair builder that cannot fix it?
- **New**: is `cycle-failed`'s stage `regenerate` (`candidate-not-regenerated`) enough for
  R1, given builder sessions, timeouts and continuations come later?
