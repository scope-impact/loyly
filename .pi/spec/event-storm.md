# Pi harness: Big Picture EventStorming board

> A snapshot of round R0; later decisions are in [`decisions.md`](decisions.md).

One consolidated timeline of domain events for the Pi harness, merged from four participants'
stickies. ◆ marks a pivotal event; ✗ marks an unhappy branch, listed after the happy event it
diverges from. Hot spots are numbered H1–H35 (see below). The same board as data is
`event-storm.edn`.

```text
Prepare and approve → Submit and dispatch → Fence → Regenerate → Gate → Score → Scoreboard → Conclude and after the cycle
parallel: Specify (upstream of every cycle); Grader preparation (parallel stream, own clock); Out of band (any time during a cycle); Prompt optimisation (parallel stream, outside the cycle)
```

## Specify (upstream of every cycle)

| # | Event | Caused by | Policy / notes | Hot spots |
|---|---|---|---|---|
| 1 | **Scenario stamped** | actor: Operator (spec author), via the stamper | Item identity is never derived from wording; rewording keeps the cycle-to-cycle join. _Merged:_ “Scenarios stamped”; “Scenario stamped with item identity” _(operator, instrument)_ |  |
| 2 | ✗ **Unstamped scenario found** (diverges from: Scenario stamped) | policy: Whenever a checklist is derived, then every selected scenario must carry an item identity | Stops the cycle instead of shrinking the denominator. _Merged:_ “Unstamped scenario found”; “Selection found empty or unstamped (unstamped half)”; “Unstamped ... stops here” _(instrument, skeptic, operator)_ |  |

## Prepare and approve

| # | Event | Caused by | Policy / notes | Hot spots |
|---|---|---|---|---|
| 1 | ◆ **Source cut pinned** | actor: Operator | Everything later binds to the cut's commit and tree content. _Merged:_ “Source cut pinned” _(operator)_ |  |
| 2 | **Slice proposed** | system: Operator session (LLM) | A suggestion only; cannot approve itself or invent items. _Merged:_ “Slice proposed” _(operator)_ | H23 |
| 3 | **Selection contract presented** | system: Harness, resolving the proposal against the cut | The exact items, scenario digests and digest the operator accepts. _Merged:_ “Selection previewed” _(operator)_ |  |
| 4 | ◆ **Selection approved** | actor: Operator | One exact digest; fixes which scenarios the instrument may count. _Merged:_ “Selection approved” _(operator, instrument)_ | H10 |
| 5 | ✗ **Approval refused: digest not the one reviewed** (diverges from: Selection approved) | policy: Whenever an approval names a digest other than the presented one, then refuse it | _Merged:_ “Approval refused: digest not the reviewed one” _(skeptic)_ |  |
| 6 | **Checklist derived** | policy: Whenever a selection is approved, then derive its checklist | Sets the denominator; never hand-kept. Instrument marked it pivotal; facilitator kept it inside the phase (see disagreements). _Merged:_ “Checklist derived” _(operator, instrument)_ |  |
| 7 | ✗ **Empty selection refused** (diverges from: Checklist derived) | policy: Whenever a checklist would be empty, then refuse the selection | Nothing that could fail is no measurement. _Merged:_ “Empty selection refused”; “Selection found empty or unstamped (empty half)” _(instrument, skeptic, operator)_ |  |
| 8 | ◆ **Cycle preparation approved** | actor: Operator | Binds approved selection, approved grader digests, scope, stack, execution profile, budget. The one immutable hand-off to ORC. _Merged:_ “Cycle preparation approved”; “preparation approved” _(operator, skeptic)_ | H5, H10, H35 |

## Grader preparation (parallel stream, own clock)

| # | Event | Caused by | Policy / notes | Hot spots |
|---|---|---|---|---|
| 1 | **Grader requested** | policy: Whenever a selection holds scenarios no approved grader covers, then request a grader | Operator wrote 'Operator / policy'. _Merged:_ “Grader requested” _(operator)_ |  |
| 2 | **Instrument golden written** | actor: Instrument maintainer | Written from the scenario, never recorded from a run. _Merged:_ “Instrument golden written” _(instrument)_ |  |
| 3 | **Grader authored** | system: Grader author (builder session, blind) | Sees only the selected Gherkin and the public calibration protocol. _Merged:_ “Grader authored” _(instrument)_ | H20 |
| 4 | **Grader calibrated** | system: Calibration protocol | Carried behaviour passes, uncarried fails. _Merged:_ “Grader calibrated: carried passed, uncarried failed”; “Grader sealed (calibrated)” _(instrument, operator)_ |  |
| 5 | ✗ **Grader discarded: calibration failed** (diverges from: Grader calibrated) | system: Calibration protocol | _Merged:_ “Grader discarded: calibration failed” _(skeptic)_ | H18 |
| 6 | ✗ **Instrument declared unavailable** (diverges from: Grader calibrated) | policy: Whenever calibration fails three times, then declare the slice unscoreable | _Merged:_ “Instrument declared unavailable” _(operator)_ | H18 |
| 7 | **Grader sealed** | system: Instrument | Content becomes immutable and nameable by digest. _Merged:_ “Grader sealed”; “Grader sealed by digest” _(operator, instrument)_ |  |
| 8 | ◆ **Grader digest approved** | actor: Operator | Only this exact digest may file verdicts. _Merged:_ “Grader approved”; “Grader digest approved” _(operator, instrument)_ | H17 |
| 9 | **Grader reused** | policy: Whenever a cycle's scenarios are unchanged, then reuse the approved grader | Same ruler across cycles. _Merged:_ “Grader reused” _(instrument)_ |  |
| 10 | ✗ **Grader found stale for the selection** (diverges from: Grader reused) | policy: Whenever a selected scenario digest differs from the grader's, then mark it stale | _Merged:_ “Grader found stale for the selection” _(skeptic)_ | H12 |
| 11 | **Grader replaced for a recorded reason** | actor: Operator, with a reason record | Reasons: instrument behaviour changed, wording replaced, spec grew (additions only). Approving the successor retires the old one. _Merged:_ “Grader replaced”; “Grader replaced for a recorded reason” _(operator, instrument)_ | H19 |
| 12 | ✗ **Grader replacement refused: no recorded reason** (diverges from: Grader replaced for a recorded reason) | policy: Whenever a replacement has no recorded reason, then refuse it | Skeptic marked pivotal; facilitator kept it as an unhappy branch (no phase change). _Merged:_ “Grader replacement refused: no recorded reason” _(skeptic)_ |  |
| 13 | **Instrument golden moved, reason recorded** | actor: Instrument maintainer | Additions-only diff is the test for 'spec grew'. _Merged:_ “Golden moved and its reason recorded” _(instrument)_ | H19 |

## Submit and dispatch

| # | Event | Caused by | Policy / notes | Hot spots |
|---|---|---|---|---|
| 1 | ◆ **Cycle submitted** | actor: Operator | Approval starts nothing; submission does. _Merged:_ “Cycle submitted”; “Workflow task submitted”; “task submitted” _(operator, workflow, skeptic)_ | H8, H9 |
| 2 | **Receipt issued** | system: Harness | _Merged:_ “Receipt issued” _(operator)_ |  |
| 3 | ✗ **Duplicate submission answered with the original receipt** (diverges from: Receipt issued) | policy: Whenever the same identity and payload is submitted again, then return the original receipt | _Merged:_ “Duplicate submission answered with original receipt”; “Repeats return the same receipt” _(skeptic, operator)_ |  |
| 4 | ✗ **Submission refused: same identity, different payload** (diverges from: Receipt issued) | policy: Whenever one identity arrives with a different payload, then refuse it | Skeptic marked pivotal; facilitator kept it as an unhappy branch. _Merged:_ “Submission refused: same identity, different payload” _(skeptic)_ |  |
| 5 | **Cycle started by ORC** | system: ORC worker | Turned from ORC machinery into the fact it hides: the cycle's task left not-started. The tick id becomes each builder's request identity. _Merged:_ “ORC tick dispatched” _(workflow)_ |  |

## Fence

| # | Event | Caused by | Policy / notes | Hot spots |
|---|---|---|---|---|
| 1 | ◆ **Harness self-check passed** | system: Harness (ORC stage) | No room is built over an unchecked instrument; reused only for an identical harness digest. _Merged:_ “Harness self-check passed (or refused)”; “Harness self-check passed (or failed, naming the check)” _(workflow, instrument, skeptic)_ | H24 |
| 2 | ✗ **Harness self-check failed** (diverges from: Harness self-check passed) | system: Harness (ORC stage) | Names the failing check; stops the cycle. _Merged:_ “Harness self-check failed” _(workflow, instrument, skeptic)_ |  |
| 3 | ◆ **Clean room proved** | system: Fence | Physical: absent from working tree and object graph. 'Fenced' (ORC ran the stage) folded into 'proved' (the fact). _Merged:_ “Clean room proved”; “Clean room fenced”; “Clean room proved (fenced paths absent from tree and object graph)” _(operator, workflow)_ | H25 |
| 4 | ✗ **Fence refused: a fenced path matched nothing** (diverges from: Clean room proved) | system: Fence | Stops before builders spend. _Merged:_ “Fence refused: fenced path matched nothing”; “Fence refused” _(skeptic, operator)_ |  |
| 5 | ✗ **Fence refused: prior implementation reachable in the object graph** (diverges from: Clean room proved) | system: Fence probe | Skeptic marked pivotal; kept as an unhappy branch. _Merged:_ “Prior implementation found reachable in object graph” _(skeptic)_ |  |

## Regenerate

| # | Event | Caused by | Policy / notes | Hot spots |
|---|---|---|---|---|
| 1 | **Builder room started** | system: ORC (chooses the room) on Gondolin | Stack image, cut read-only, candidate scope writable, offline. The builder host cannot widen it. _Merged:_ “Builder room started” _(workflow)_ |  |
| 2 | **Builder session launched** | policy: Whenever a builder tick starts, then launch its builder session with the production prompt | Production prompt digest recorded; tick is the request identity. _Merged:_ “Builder session launched” _(workflow)_ |  |
| 3 | **Tool result committed** | system: Builder host (pi-durable) | A turn counts only once committed; it binds a workspace generation. _Merged:_ “Tool call committed”; “Workspace generation bound” _(workflow)_ | H1, H32 |
| 4 | ✗ **Out-of-scope read or write refused** (diverges from: Tool result committed) | system: Builder room (Gondolin) | Graders, host environment, egress. _Merged:_ “Out-of-scope read or write refused” _(skeptic)_ |  |
| 5 | ✗ **Builder session interrupted** (diverges from: Tool result committed) | system: Builder host or OS (crash, restart) | _Merged:_ “Builder process crashed / harness restarted” _(workflow)_ | H2 |
| 6 | **Builder session resumed** | policy: Whenever the same tick is relaunched, then resume its session from committed turns | Never a second session. Workflow and skeptic marked it pivotal; facilitator demoted it (no phase change). _Merged:_ “Builder session resumed”; “Builder session resumed for the same tick” _(workflow, skeptic)_ | H3, H2 |
| 7 | ✗ **Resume refused: transcript and workspace generation disagreed** (diverges from: Builder session resumed) | system: ORC | Workflow asked which event says so; skeptic supplied it. _Merged:_ “Resume refused: transcript and workspace generation disagreed” _(skeptic, workflow)_ | H1 |
| 8 | **Builder-session intervention recorded** | actor: Operator | Marks the candidate assisted. Observation alone is not an event. _Merged:_ “Builder intervened in”; “Builder session observed / intervened (steer, fork)”; “Intervention recorded; result marked assisted” _(operator, workflow, skeptic)_ | H14 |
| 9 | **Builder session ended** | system: Builder host | Session end is not done. _Merged:_ “Builder session ended” _(workflow)_ |  |
| 10 | ✗ **Builder session timed out** (diverges from: Builder session ended) | time: Builder session time limit | _Merged:_ “Builder session timed out” _(skeptic)_ | H4, H5 |
| 11 | **Slice built** | system: ORC stage | Carries candidate digest and the uncarried list, which is the builder's claim, not evidence. _Merged:_ “Slice build recorded (candidate digest, uncarried list)”; “Builder session ended with its slice uncarried” _(workflow, skeptic)_ | H33 |
| 12 | ✗ **Slice builder continued** (diverges from: Slice built) | policy: Whenever items stay uncarried and continuations < 3, then continue the same session | _Merged:_ “Slice builder continued”; “Builder session continued (max three)” _(workflow, skeptic)_ | H5 |
| 13 | ✗ **Continuation stalled: candidate unchanged** (diverges from: Slice builder continued) | policy: Whenever a continuation leaves the candidate digest unchanged, then stop continuing | _Merged:_ “Continuation stalled (candidate unchanged)” _(workflow)_ | H11 |
| 14 | ✗ **Slice left the candidate unchanged** (diverges from: Slice built) | policy: Whenever a slice build leaves the candidate digest unchanged, then flag it | Legacy driver treats it as failure. _Merged:_ “Slice left candidate unchanged” _(workflow)_ | H11 |
| 15 | ◆ **Candidate regenerated** | system: ORC stage | Each slice extends the previous slice's candidate. A claim, not done. _Merged:_ “Candidate regenerated”; “Candidate assembled (all slices, plan order)” _(operator, workflow)_ |  |

## Gate

| # | Event | Caused by | Policy / notes | Hot spots |
|---|---|---|---|---|
| 1 | **Candidate installed in the gate room** | system: ORC on Gondolin | The claimant never controls the judge. _Merged:_ “Candidate installed into gate room” _(workflow)_ |  |
| 2 | ◆ **Gate attempt passed** | system: Gate room | Booted fresh through the launch entrypoint, identity probed, suite green. Done is the gate's verdict. _Merged:_ “Gate passed”; “Gate attempt passed” _(operator, workflow)_ | H15 |
| 3 | ✗ **Gate attempt failed** (diverges from: Gate attempt passed) | system: Gate room | _Merged:_ “Gate failed”; “Gate attempt failed”; “Gate attempt failed; repair builder started (failure half)” _(operator, workflow, skeptic)_ |  |
| 4 | ✗ **Gate refused: tool missing or no-op suite** (diverges from: Gate attempt passed) | system: Gate room | Legacy failure. Skeptic marked pivotal. _Merged:_ “Gate refused: tool missing, no-op suite”; “gate that passed without exercising the product” _(skeptic, operator)_ | H15 |
| 5 | ✗ **Gate refused: suite discovery empty** (diverges from: Gate attempt passed) | system: Gate room | _Merged:_ “Gate refused: empty suite discovery” _(skeptic)_ | H15 |
| 6 | ✗ **Gate refused: seam answered before boot** (diverges from: Gate attempt passed) | system: Gate room | Grading an orphan (legacy). Skeptic marked pivotal. _Merged:_ “Gate refused: something already answered before boot” _(skeptic)_ |  |
| 7 | ✗ **Boot budget exhausted** (diverges from: Gate attempt passed) | time: Gate boot budget | _Merged:_ “Boot budget exhausted” _(skeptic)_ |  |
| 8 | ✗ **Identity probe failed** (diverges from: Gate attempt passed) | system: Gate room | A static fake (regen-3). _Merged:_ “Identity probe failed” _(skeptic)_ |  |
| 9 | ✗ **Repair builder launched** (diverges from: Gate attempt failed) | policy: Whenever a gate attempt fails and attempts < 3, then launch a fresh repair builder | Fresh session with only the previous candidate and gate evidence; re-enters the regenerate loop. _Merged:_ “Repair builder launched”; “repair builder started” _(workflow, skeptic)_ | H6, H34 |
| 10 | ◆ **Gate attempts exhausted** (diverges from: Gate attempt passed) | policy: Whenever the third gate attempt fails, then fail the cycle without a score | Pivotal: ends the build loop and skips the instrument. _Merged:_ “Gate attempts exhausted”; “Third gate attempt failed; cycle failed” _(operator, workflow, skeptic)_ | H8 |

## Score

| # | Event | Caused by | Policy / notes | Hot spots |
|---|---|---|---|---|
| 1 | **Validation room started** | system: ORC on Gondolin, with the approved sealed graders | Nothing flows back to the loop from here. _Merged:_ “Validation room started / grader run” _(workflow)_ | H13 |
| 2 | **Runtime signals collected** | system: Independent validator | Candidate-authored verdict fields are forbidden. _Merged:_ “Runtime signals collected, with collection completeness” _(instrument)_ | H22 |
| 3 | ✗ **Evidence refused: wrong cycle or candidate** (diverges from: Runtime signals collected) | system: Harness | _Merged:_ “Evidence refused: wrong cycle or candidate” _(skeptic)_ | H13 |
| 4 | **Evidence verdict reached** | system: Independent invariant evaluation | _Merged:_ “Evidence verdict reached (pass / fail / inconclusive)” _(instrument)_ |  |
| 5 | ✗ **Evidence found inconclusive** (diverges from: Evidence verdict reached) | system: Independent invariant evaluation | Incomplete collection is inconclusive, never pass; files as fail. _Merged:_ “Verdict found inconclusive” _(skeptic, instrument)_ | H7 |
| 6 | **Item verdicts filed** | system: Approved sealed grader, via the public seam | _Merged:_ “Item verdicts filed, one per checklist item” _(instrument)_ | H13 |
| 7 | ✗ **Unreached item recorded as fail** (diverges from: Item verdicts filed) | policy: Whenever a checklist item has no verdict, then record it as fail | Denominator never shrinks. Skeptic marked pivotal. _Merged:_ “Unreached item recorded as fail”; “Unreached items recorded as fail”; “Unreached items fail, stay counted” _(instrument, skeptic, operator)_ | H7, H21 |
| 8 | ✗ **Duplicate verdict superseded** (diverges from: Item verdicts filed) | system: Instrument | Contested: supersede or refuse (see disagreements). _Merged:_ “Duplicate verdict superseded”; “Duplicate verdicts, last wins silently” _(skeptic, instrument)_ | H26 |
| 9 | ✗ **Score refused** (diverges from: Measurement filed) | system: Instrument | Zero verdicts, unknown item, bad verdict value. Nothing filed; not scored is not all failed. _Merged:_ “Score refused”; “Score refused: zero verdicts / unknown ids / bad verdict value”; “Score refused: zero verdicts or unknown item” _(operator, instrument, skeptic)_ |  |
| 10 | ✗ **Score stage failed, not retried** (diverges from: Measurement filed) | policy: Whenever a score is refused, then fail the score stage and never retry it in the cycle | Score never feeds the build loop. _Merged:_ “Score stage failed, not retried” _(skeptic)_ |  |
| 11 | ◆ **Measurement filed** | system: Instrument (FileScore) | One per cycle; a false record cannot be untaken. _Merged:_ “Score filed”; “Measurement filed” _(operator, workflow, instrument)_ | H16 |

## Scoreboard

| # | Event | Caused by | Policy / notes | Hot spots |
|---|---|---|---|---|
| 1 | **Moved item set restated** | policy: Whenever the denominator differs from the previous cycle, then restate it on shared items | _Merged:_ “Moved item set restated”; “Is a moved denominator restated to me?” _(instrument, operator)_ |  |
| 2 | ◆ **Scoreboard reported** | system: Instrument, to a person | Newly passing and newly failing separately; new items are the untuned holdout; never a net. _Merged:_ “Scoreboard reported”; “Scoreboard reported: shared newly-passing, shared newly-failing, new items as holdout” _(operator, workflow, instrument)_ | H14, H21, H28 |

## Conclude and after the cycle

| # | Event | Caused by | Policy / notes | Hot spots |
|---|---|---|---|---|
| 1 | ◆ **Cycle passed** | policy: Whenever the scoreboard is reported, then conclude the cycle once | Recorded once, when ORC completes the task. _Merged:_ “Cycle passed / failed”; “Cycle concluded (passed / failed)” _(operator, workflow)_ |  |
| 2 | ✗ **Cycle failed** (diverges from: Cycle passed) | policy: Whenever gate attempts are exhausted or a stage fails, then conclude the cycle failed once | _Merged:_ “Cycle passed / failed (failed half)”; “cycle failed” _(operator, workflow, skeptic)_ | H4 |
| 3 | **Cycle continuation decided** | actor: Operator | Stop, correct the spec, or prepare a child cycle. _Merged:_ “Continuation decided” _(operator)_ | H29 |
| 4 | **Child cycle prepared** | actor: Operator | New cycle identity with lineage to its parent; loops back to Prepare. _Merged:_ “Child cycle prepared” _(operator)_ |  |

## Out of band (any time during a cycle)

| # | Event | Caused by | Policy / notes | Hot spots |
|---|---|---|---|---|
| 1 | **Cycle cancelled** | actor: Operator | Rooms must stop. _Merged:_ “Workflow task cancelled”; “Tick timed out / cancelled (cancelled half)” _(skeptic, workflow)_ | H4 |
| 2 | **Cycle timed out** | time: Task time limit | _Merged:_ “Tick timed out / cancelled (timed-out half)”; “Outer-cycle budget runs out” _(workflow, operator)_ | H4, H5 |
| 3 | **Operator session disconnected; cycle continued** | actor: Operator | A cycle never dies with a laptop. _Merged:_ “Operator session disconnected; cycle continued” _(skeptic)_ |  |
| 4 | **ORC worker replaced; task answered from durable state** | system: ORC | Skeptic marked pivotal; facilitator kept it out of band. _Merged:_ “ORC worker replaced; wait answered from durable state”; “Process restart of the harness”; “A replaced ORC worker” _(skeptic, workflow, operator)_ | H31 |
| 5 | **Model drift found; startup refused** | system: Harness process (event-model guard) | Code and model diverged; happens before any cycle. _Merged:_ “Model drift found; startup refused” _(skeptic)_ |  |

## Prompt optimisation (parallel stream, outside the cycle)

| # | Event | Caused by | Policy / notes | Hot spots |
|---|---|---|---|---|
| 1 | **Instruction candidate selected** | system: Prompt optimisation (GEPA, reflection model) | Facilitator added the happy counterpart from the glossary so the rejection has something to diverge from. _(skeptic)_ | H30 |
| 2 | ✗ **Instruction candidate rejected as overfit** (diverges from: Instruction candidate selected) | system: Prompt optimisation | _Merged:_ “Instruction candidate rejected as overfit” _(skeptic)_ | H30 |

## Hot spots

### Blocking: answer before the event model

| # | Question | Phase | Raised by |
|---|---|---|---|
| H1 | When ORC or the builder host dies after an effect (a tool result, a gate run) but before its fact is recorded, which side is truth, and does replay spend another of the three gate attempts? | Regenerate | workflow, skeptic |
| H2 | If a slow original builder host and a relaunch of the same tick are both alive, what fences the old writer? | Regenerate | workflow |
| H3 | On resume, is the builder room reattached or rebuilt, and if rebuilt, from which workspace generation? | Regenerate | workflow |
| H4 | Is a cancelled or timed-out cycle failed, unmeasured, or partly measured; who stops live rooms; and is a committed but unreported turn lost or replayed? | Out of band (any time during a cycle) | skeptic, workflow, operator |
| H5 | What is the outer-cycle budget (time, attempts, money), how do continuations times repairs add up, which limits sit in the approved preparation, and what happens when it runs out mid-regenerate? | Prepare and approve | operator, skeptic |
| H6 | Where exactly is the line between gate evidence (may reach a repair builder) and score evidence (never), and which part enforces and proves it: room policy, fence, or something else? | Gate | workflow, instrument |
| H7 | An item verdict is only pass or fail: where does the reason (unreached, inconclusive, outage) live so an outage reads differently from a regression? | Score | instrument, skeptic, operator |
| H8 | Is a cycle numbered at submission or when it is scored (legacy); is a cycle that failed the gate an unscored cycle or no cycle? | Submit and dispatch | workflow |
| H9 | If the builder host is absent or not built, is that a failed cycle or a refusal before any cycle exists? | Submit and dispatch | workflow |
| H10 | Is a whole-plan submission its own approval, and does it skip the selection and grader-digest reviews? | Prepare and approve | operator |
| H11 | Is a slice that leaves the candidate unchanged a failure (legacy) or a stall that just ends continuation? | Regenerate | workflow |
| H12 | Is grader staleness keyed per item scenario digest or per slice, so that growth does not stale a grader but a reword does? | Grader preparation (parallel stream, own clock) | skeptic, instrument |
| H13 | What proves the validation room scores the candidate the gate ratified, and is the grader digest re-checked at score time or only at preparation? | Score | skeptic |

### Can wait

| # | Question | Phase | Raised by |
|---|---|---|---|
| H14 | Is an assisted candidate's score in the same scoreboard series as unassisted ones, or a separate series? | Scoreboard | operator, skeptic |
| H15 | Beyond the three legacy refusals, who catches a gate that passed without exercising the product? | Gate | operator, skeptic |
| H16 | Can an outage leave a false 'scored' record, given a filed measurement cannot be taken back? | Score | operator, skeptic |
| H17 | What does the operator review before approving a grader digest without biasing later spec edits toward its tests? | Grader preparation (parallel stream, own clock) | operator |
| H18 | When calibration fails three times, is the fix in the spec wording or in the calibration protocol? | Grader preparation (parallel stream, own clock) | operator |
| H19 | Who checks that a recorded replacement reason is true, and is the additions-only diff the only enforceable test? | Grader preparation (parallel stream, own clock) | instrument, skeptic |
| H20 | How is grader-author blindness proven rather than asserted, and are the three sealed Python graders valid until they are ported to Elixir and re-approved? | Grader preparation (parallel stream, own clock) | instrument |
| H21 | With approved graders for about 17 of ~94 items, should the scoreboard also report graded coverage so a low rate is not read as the candidate's quality alone? | Scoreboard | instrument |
| H22 | The runtime-evidence contract has no collection-completeness accounting or inconclusive outcome yet; when is that gap closed? | Score | instrument |
| H23 | Can demoting a slice in the plan shrink the denominator with only a stderr warning? | Prepare and approve | instrument |
| H24 | Does self-check reuse keyed on the harness digest also cover grader digests and the capture policy? | Fence | instrument |
| H25 | Should scoring tools be fenced from the clean room? | Fence | operator, instrument |
| H26 | Should a grader that emits duplicate verdicts be refused instead of the last one silently winning? | Score | instrument, skeptic |
| H27 | Legacy files still say 'oracle' for three different things; when are they renamed to glossary terms? | Score | instrument |
| H28 | How do we stop ORC evaluation-judge scores (diagnostics only) being read as the measurement? | Scoreboard | instrument |
| H29 | After reading scores and editing the spec, where does learning from the spec end and tuning to the grader begin? | Conclude and after the cycle | operator |
| H30 | What does prompt optimisation measure on, and who keeps a set it has never seen? | Prompt optimisation (parallel stream, outside the cycle) | skeptic |
| H31 | Does a replacement ORC worker re-dispatch the builder launch for an in-flight tick, and does the operator see one task or two? | Out of band (any time during a cycle) | workflow, operator |
| H32 | Can tool output (logs, environment) leak host state or credentials into the builder transcript? | Regenerate | workflow |
| H33 | The uncarried list lives in the scope the builder writes and decides continuations: is the builder allowed to steer the loop's length? | Regenerate | workflow |
| H34 | The repair budget counts across the whole candidate: what catches a repair that regresses an earlier slice? | Gate | workflow |
| H35 | Does an unapproved or unsubmitted preparation expire if the operator walks away? | Prepare and approve | skeptic |

## Candidate bounded contexts

Language and ownership change at these lines. Provided systems stay outside (listed after).

### Specification

Open host with a published language (item identity, scenario digest, stamped item); every other context conforms.

- **Upstream of:** Cycle preparation (operator control), Grader lifecycle, Measurement (instrument: score and scoreboard)
- **Hand-off events:** Scenario stamped
- **Owns:** Scenario stamped; Unstamped scenario found

### Cycle preparation (operator control)

Customer-supplier to the regeneration cycle: the approved preparation is the only crossing, immutable. Downstream of the measurement only through a person reading the scoreboard.

- **Upstream of:** Regeneration cycle (harness: fence and gate), Measurement (instrument: score and scoreboard)
- **Hand-off events:** Cycle submitted, Selection approved
- **Owns:** Source cut pinned; Slice proposed; Selection contract presented; Selection approved; Approval refused: digest not the one reviewed; Cycle preparation approved; Cycle submitted; Receipt issued; Duplicate submission answered with the original receipt; Submission refused: same identity, different payload; Builder-session intervention recorded; Cycle cancelled; Operator session disconnected; cycle continued; Cycle continuation decided; Child cycle prepared

### Grader lifecycle

Own clock, no cycle. Supplies approved grader digests the preparation binds and the measurement uses; conforms to the specification.

- **Upstream of:** Cycle preparation (operator control), Measurement (instrument: score and scoreboard)
- **Hand-off events:** Grader digest approved
- **Owns:** Grader requested; Instrument golden written; Grader authored; Grader calibrated; Grader discarded: calibration failed; Instrument declared unavailable; Grader sealed; Grader digest approved; Grader reused; Grader found stale for the selection; Grader replaced for a recorded reason; Grader replacement refused: no recorded reason; Instrument golden moved, reason recorded

### Regeneration cycle (harness: fence and gate)

Owns stage order, room choice and the repair policy. Translates ORC facts through an anticorruption layer; hands the gated candidate to the measurement one-way (nothing flows back).

- **Upstream of:** Builder session (durable), Measurement (instrument: score and scoreboard)
- **Hand-off events:** Gate attempt passed, Builder session launched, Repair builder launched
- **Owns:** Cycle started by ORC; Harness self-check passed; Harness self-check failed; Clean room proved; Fence refused: a fenced path matched nothing; Fence refused: prior implementation reachable in the object graph; Builder room started; Builder session launched; Slice built; Slice builder continued; Continuation stalled: candidate unchanged; Slice left the candidate unchanged; Candidate regenerated; Candidate installed in the gate room; Gate attempt passed; Gate attempt failed; Gate refused: tool missing or no-op suite; Gate refused: suite discovery empty; Gate refused: seam answered before boot; Boot budget exhausted; Identity probe failed; Repair builder launched; Gate attempts exhausted; Cycle passed; Cycle failed; Cycle timed out; ORC worker replaced; task answered from durable state; Model drift found; startup refused

### Builder session (durable)

Joined to the regeneration cycle by the tick. Conforms to the cycle's production prompt and room; reports only committed turns and the session end back, never a verdict.

- **Upstream of:** Regeneration cycle (harness: fence and gate)
- **Hand-off events:** Builder session ended
- **Owns:** Tool result committed; Out-of-scope read or write refused; Builder session interrupted; Builder session resumed; Resume refused: transcript and workspace generation disagreed; Builder session ended; Builder session timed out

### Measurement (instrument: score and scoreboard)

Downstream of preparation (checklist), grader lifecycle (digest) and the cycle (gated candidate). Reports to a person only; never upstream of the build loop.

- **Upstream of:** Prompt optimisation
- **Hand-off events:** Scoreboard reported, Measurement filed
- **Owns:** Checklist derived; Empty selection refused; Validation room started; Runtime signals collected; Evidence refused: wrong cycle or candidate; Evidence verdict reached; Evidence found inconclusive; Item verdicts filed; Unreached item recorded as fail; Duplicate verdict superseded; Score refused; Score stage failed, not retried; Measurement filed; Moved item set restated; Scoreboard reported

### Prompt optimisation

Outside every cycle. Consumes independent measurements; supplies a selected builder instruction that a new approved preparation pins. Must stay away from the checklist it is judged on.

- **Upstream of:** Regeneration cycle (harness: fence and gate)
- **Hand-off events:** Instruction candidate selected
- **Owns:** Instruction candidate selected; Instruction candidate rejected as overfit

### Relationships

```text
Specification ──published language──▶ Cycle preparation, Grader lifecycle, Measurement
Grader lifecycle ──grader digest approved──▶ Cycle preparation, Measurement
Cycle preparation ──cycle submitted (immutable)──▶ Regeneration cycle
Cycle preparation ──selection approved──▶ Measurement (checklist)
Regeneration cycle ──launch / repair──▶ Builder session ──session ended──▶ Regeneration cycle
Regeneration cycle ──gate attempt passed (one-way)──▶ Measurement
Measurement ──scoreboard reported (to a person)──▶ operator ──▶ Cycle preparation
Measurement ──independent measurements──▶ Prompt optimisation ──instruction──▶ next preparation
ORC ──node-stage translation (anticorruption layer)──▶ Regeneration cycle
```

### External and provided systems

- ORC runtime (ORC worker) (workflow engine; provided)
- Builder host (pi-durable) (durable session runtime; provided)
- Gondolin (micro-VM rooms; provided)
- Builder model provider (Codex) (model provider)
- Reflection model provider (model provider; deferred)
- Project repository (git) (source of cuts)
- Grain event store (storage; provided)
- Candidate public seam (JSON-RPC over stdio) (the regenerated product)
- Legacy regeneration driver (legacy system)
- Operator session (Pi, Codex) (interactive agent acting for a person)
- Time (timeouts and budgets)

## Disagreements

- **A slice that leaves the candidate unchanged** _(workflow)_
  - Legacy driver: it is a failure of the slice.
  - Continuation policy: it is a stall; stop continuing and move on.
- **Duplicate verdicts from one grader** _(skeptic, instrument)_
  - Skeptic: the duplicate is superseded (last one stands); only double counting must be prevented.
  - Instrument: last-wins is silent; a grader emitting duplicates should be refused.
- **Whether scoring tools are fenced from the clean room** _(operator, instrument)_
  - Fence them: the clean room holds no grading harness.
  - Leave them: the legacy siblings did not fence them. Both participants report it undecided.
- **What is pivotal inside the regenerate and gate phases** _(workflow, skeptic, operator, instrument)_
  - Workflow and skeptic: session resume, worker replacement and several refusals are pivotal; they protect the cycle's integrity.
  - Operator and instrument: only approvals, the gate verdict, the filed measurement and the conclusion split the timeline. Facilitator followed this reading.
- **Is the checklist a pivotal event** _(instrument, operator)_
  - Instrument: yes, it sets the denominator.
  - Operator: no, it is a policy reaction to the selection being approved.
- **Who issues the self-check and the receipt** _(workflow, skeptic, operator)_
  - Workflow: ORC stages do.
  - Operator and skeptic: the harness does. Glossary: ORC reports execution facts; the harness records stage facts, so both may be right at different layers.

## How this board was made

A simulated Big Picture EventStorming workshop on 2026-11-06. Four roles wrote stickies
independently: the operator (product owner), the workflow and builder engineer, the measurement
scientist (instrument), and the skeptic (unhappy paths). A facilitator then enforced one timeline,
merged duplicates into glossary terms (the merged wordings are kept per event), turned commands
and ORC machinery into the facts they hide, placed pivotal events, attached hot spots, and drew
candidate contexts. Vocabulary follows `.pi/model/GLOSSARY.md`; structure and names do not follow
the current spec under `.pi/model/`. One event (“Instruction candidate selected”) was added by the
facilitator as the happy counterpart of a participant's unhappy sticky.

**Not yet reviewed by the product owner.**

## Proposed answers to the blocking hot spots (not yet decided by the product owner)

1. The recorded fact is truth; a gate attempt counts once recorded, so a replay spends none.
2. A relaunch fences the old host: the room accepts only the current launch; ORC stops the old room.
3. On resume ORC rebuilds the room from the last committed workspace generation; never reattached.
4. Cancelled or timed out: the cycle fails with its stage and reason; no score is filed; ORC stops live rooms.
5. Budget per preparation: maximum outer cycles, a timeout per stage, at most 3 continuations and 3 repairs; exhausted means failed with reason budget. Money is out of scope.
6. Gate evidence and score evidence live in separate stores; only a repair room mounts gate evidence; score evidence never reaches a builder room; ORC's room policy enforces it and a fence-like check proves it.
7. A `reason` beside each item's verdict (unreached, inconclusive, outage); the verdict stays pass or fail.
8. A cycle is numbered at submission; a cycle that fails the gate exists and shows as "no score".
9. A missing builder host refuses the submission before any cycle exists.
10. Whole-plan submission skips the selection review (submitting is the approval, the legacy shape), never the grader-digest review.
11. An unchanged candidate is a stall; it counts as a continuation, and after 3 the slice fails.
12. Staleness is per scenario digest: a reworded scenario makes its grader stale; new items do not.
13. The score binds the gated candidate's digest and re-checks the grader digest at score time.

## Legacy alignment

31 of the 82 events are what the legacy harness already does (`archive/legacy-harness/`): specify 2,
fence 5, gate 8, score 6, scoreboard 2, prepare 3, grader goldens 2, conclude 2, regenerate 1. They
are modeled first, as the core of the event model (R1).
