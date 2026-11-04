# Pi harness regeneration context

The Pi harness runs one slice-cycle workflow for both first-time measurement with oracle
promotion and later oracle-comparison regeneration; the persisted route selects the
route-specific steps.

## Language

**Source cut**:
An immutable, fenced repository snapshot identified by source commit and tree content. A cut
is neutral input to a generation or regeneration workflow; preparing it is not itself either
operation.
_Avoid_: Regeneration cut, checkout, clone, workspace

**Slice**:
A named, coherent set of observable behavior contracts represented by Gherkin scenarios with
stable item identities. A slice does not contain or select an oracle.
_Avoid_: Model directory, grader bundle, implementation component

**System composition**:
The deterministic, cut-bound system view reconstructed by mapping every Gherkin behavior item
to its slice, component, and container, then reducing those mappings into one hierarchy. It is
complete only when every behavior item appears exactly once and decomposing the hierarchy
reproduces the same slice mappings.
_Avoid_: File concatenation, OCI assembly, candidate build

**Pi harness service area**:
The `:loyly.pi-harness` Grain service area running inside ORC's existing Grain runtime. It owns
durable regeneration-domain facts and projections but does not copy ORC's `:sheet/*` execution
facts or the generated product's events.
_Avoid_: Separate event system, ORC adapter, product service area

**ORC execution projection**:
A Pi-harness read model derived from authoritative ORC `:sheet/*` events for observation and
reconnection. It cannot advance execution and introduces no duplicate progress event.
_Avoid_: Harness progress event, workflow authority

**Slice cycle**:
The single ORC-owned workflow shared by the Fresh Slice Generation Cycle and the Measured Slice
Regeneration Cycle, with stages cut → harness-snapshot → oracle → builder → instrument → verify →
repair-verification → affordance-conformance → grade → instrument-conformance →
repair-evaluation (fresh-generation only) → conclude. Its route selects the oracle, grade,
repair-evaluation, and conclude behavior; every other stage is identical in both routes.
Adapter completion cannot reorder or skip a stage.
_Avoid_: Generation pipeline, regeneration pipeline, two workflows.
Superseded stage names: harness-calibration (now harness-snapshot); cycle-oracle (now the
fresh-generation oracle stage); collect-evidence, compare-oracle, graders, baseline-grade (now
grade); record-cycle, grade-gate, propose-oracle, materialize-allure (now conclude).

**Route**:
The `fresh-generation` or `measured-regeneration` value ORC persists for an approved slice:
fresh-generation when no measured parent candidate is named, measured-regeneration otherwise. It
is an attribute of one slice cycle that selects its route-specific steps, not a separate workflow.
_Avoid_: Mode inferred from candidate history, pipeline type

**Oracle stage**:
The slice-cycle stage after harness-snapshot and before builder that binds the candidate-blind
oracle used by Grade. On fresh-generation a fresh oracle author derives a cycle oracle from only
the selected Gherkin and public calibration protocol; ORC calibrates it carried and uncarried,
re-authors an uncalibrated one up to three attempts, and seals it. On measured-regeneration ORC
resolves the pinned accepted product oracle and its calibration by immutable digest and never
authors one.
_Avoid_: Cycle-oracle stage, harness calibration, grade

**Repair-evaluation stage**:
The fresh-generation-only stage after instrument-conformance in which a failed grade or
conformance may start one clean new sample from the approved cut. Measured regeneration skips it
and reports its single measurement.
_Avoid_: Verification repair, grader feedback

**Conclude stage**:
The final route-selected slice-cycle stage. On fresh-generation it materializes Allure evidence
and records the oracle proposal, which still needs manual approval of its exact digest. On
measured-regeneration it records the measured cycle and applies the grade gate, which passes only
when every independent gate passes.
_Avoid_: Record-cycle, grade-gate, propose-oracle, materialize-allure

**Fresh Slice Generation Cycle**:
An explicitly requested creation of a candidate from an approved fresh source cut and sealed
harness snapshot. Existing candidate history does not change this operation into regeneration.
It is the slice cycle on the fresh-generation route. It has no parent candidate or accepted
product oracle: before the builder, the oracle stage builds and seals a candidate-blind cycle
oracle; after verification, Grade measures the complete approved denominator with it, and
conclude proposes the normalized successful evidence as the first accepted-oracle candidate.
_Avoid_: Initial generation, baseline generation, regeneration, ungraded build

**Grade**:
The ORC-owned slice-cycle stage that black-box grades the complete approved denominator through
the public seam with the oracle bound in the oracle stage. For Fresh Slice Generation it uses the
sealed cycle oracle and normalizes the evidence as an oracle-proposal basis, not an accepted
oracle; for measured regeneration it compares fresh evidence with the accepted oracle's expected
evidence. It cannot feed a builder or verification-repair session.
_Avoid_: Candidate tests, verification, promoted oracle

**Cycle oracle**:
A provisional candidate-blind bundle built from the approved cut and public calibration
protocol in the fresh-generation oracle stage, before any builder starts. It contains the
behavioral grader and only the applicable affordance and runtime-conformance evaluators. It is
calibrated against carried and uncarried behavior and sealed to the cut, selection, harness
snapshot, and oracle-author model before grading the candidate.
_Avoid_: Accepted product oracle, external validator directory, candidate test suite

**Oracle proposal**:
The normalized evidence from one completely passing Grade, bound to the selection,
scenario digests, candidate subject, judge, denominator, and its own stable digest. It remains
non-authoritative until an operator approves that exact digest.
_Avoid_: Mutable golden, accepted oracle

**Oracle promotion**:
The manual approval and independent publication of one exact oracle proposal as an immutable
OCI descriptor. A stale or different proposal digest cannot be approved, and failed, missing,
or inconclusive grade evidence cannot be promoted.
_Avoid_: Automatic recording, re-grading

**Selection contract**:
The exact project, composed slice identity, item identities and scenario digests, and cut identity
reviewed before ORC routes the approved slice to generation or regeneration.
_Avoid_: Filter, task list, scope file

**Slice proposal**:
A non-authoritative LLM suggestion of an existing slice and item subset derived from operator
intent. ORC resolves every proposed identity against the source cut before presenting a selection
contract; the proposal cannot approve itself or invent specification items.
_Avoid_: Approved selection, generated specification

**Approved selection**:
A selection contract whose exact digest has been explicitly accepted.
_Avoid_: Confirmed prompt, chosen tests

**Scenario digest**:
The digest of one complete normalized Gherkin scenario contract, including its Rule and Scenario
names, Given/When/Then steps and arguments, and Examples. It changes when the observable behavior
changes even if the separately keyed stable item identity is retained.
_Avoid_: File hash, model-tree digest, item identity

**Approved-slice operation route**:
The Generate or Regenerate operation explicitly requested with an approved slice and pinned by
request identity. Generate forbids a parent candidate. Regenerate requires an explicitly named
parent candidate. ORC validates and persists the request; candidate history never changes its mode.
_Avoid_: Inferred workflow, automatic latest-candidate routing

**Cycle preparation**:
The reviewed plan binding an approved selection, candidate scope, target stack, execution
profile, pinned inputs, diagram, and outer-cycle budget before a regeneration cycle starts.
_Avoid_: Submission payload, run configuration, prompt

**Approved cycle preparation**:
A cycle preparation whose exact digest has been explicitly accepted by an operator session.
_Avoid_: Approved selection, confirmed backend

**Operator session**:
The Codex, Pi, or other interactive session through which an operator prepares, approves,
observes, and evaluates regeneration cycles.
_Avoid_: Builder, ORC worker, root agent

**Measured Slice Regeneration Cycle**:
One explicitly requested, idempotently identified, oracle-backed ORC execution that builds and
independently measures an approved selection from a named measured parent candidate. It is the
slice cycle on the measured-regeneration route: its oracle stage resolves the pinned accepted
oracle, and it never resamples. It is distinct from Fresh Slice Generation and owns its own
measurement.
_Avoid_: Fresh generation, run, session, job

**Builder**:
A fresh agent that can read the source cut and write only within the declared candidate scope.
_Avoid_: Root agent, implementer session

**Builder session**:
One separately identified builder conversation for a fresh build or verification-repair
attempt, isolated from the operator session and forbidden independent grader evidence.
_Avoid_: Operator session, regeneration cycle

**Builder-session observation**:
Read-only inspection of a builder session's committed turns, tool calls, workspace generation,
and progress without changing the candidate.
_Avoid_: Steering, repair, transcript inheritance

**Builder-session intervention**:
An explicit, provenance-recorded operator action that pauses, steers, or forks builder work and
therefore marks the resulting candidate as assisted.
_Avoid_: Observation, invisible edit

**Candidate**:
The generated application output bound to one fresh-generation or measured-regeneration
operation, its cut, selection, builder session, and subject digest. A fresh-generation candidate
becomes durable lineage only after its complete Grade evidence is manually promoted to an
accepted oracle.
_Avoid_: Implementation, answer, workspace

**Harness snapshot**:
A fresh immutable build of one committed harness revision, binding its verdict-affecting source,
runtime assets, public application contract, calibration fixtures, and generic execution
machinery in one manifest digest. It is calibrated and sealed locally before a builder starts;
remote publication is an optional cache, not a cycle prerequisite.
_Avoid_: Instrument oracle, product oracle, source cut, candidate result

**Harness calibration**:
The proof, made in the harness-snapshot stage before the oracle and builder stages, that the
fresh harness snapshot covers every required harness component, matches its committed revision
and runtime assets, and passes its calibration fixtures. A passing calibration may be reused only
for an identical snapshot digest. "harness-calibration" as a stage name is superseded by
harness-snapshot.
_Avoid_: Candidate verification, generated test suite

**Oracle**:
A whole-system catalogue of manually approved normalized expected evidence, published as an
immutable OCI descriptor. Each entry is keyed by stable Gherkin item identity and full scenario
digest. Fresh Slice Generation proposes it from one complete independent Grade; later cycles
collect fresh evidence and compare it with the selected entries. The oracle is not a grader,
source tree, candidate verdict, cycle oracle, or harness snapshot.
_Avoid_: Grader, verifier, raw transcript

**Oracle binding**:
The cycle-owned record of the approved item identities and scenario digests, selected product-
oracle descriptor when regeneration requires one, sealed cycle-oracle digest, and harness-
snapshot digest. Combining them in cycle evidence does not make one artifact authority for another.
_Avoid_: Slice, oracle catalogue

**Verifier**:
An independent check that the candidate satisfies the runnable application contract.
_Avoid_: Oracle, candidate test suite

**Grader**:
An independent observer that returns evidence-backed verdicts for approved item identities.
_Avoid_: Builder, judge prompt

**Observation**:
Evidence from one named producer about one approved item in one cycle.
_Avoid_: Score, log line

**Cycle measurement**:
The immutable reduction of the approved denominator and oracle-comparison verdicts, including explicit failures for unreached items.
_Avoid_: Test report, average score

**Cycle progress view**:
A reconstructable, non-authoritative view of the current ORC stage and associated builder
session, derived from durable execution state and live notifications.
_Avoid_: Workflow state, event store

**Cycle continuation decision**:
The operator session's bounded decision, after a terminal measurement, to stop, request
correction, or prepare a distinct child regeneration cycle.
_Avoid_: Verification repair, ORC retry

**Execution profile**:
The approved trust and capability level for cycle execution, such as host development or
Gondolin isolation.
_Avoid_: Shell command, VM configuration

**Workflow task**:
An asynchronously executed source-cut preparation, Fresh Slice Generation Cycle, or Measured
Slice Regeneration Cycle identified by a receipt and observed until one terminal outcome.
_Avoid_: Process, thread, future

**Builder instruction**:
The complete ORC-owned model-directed strategy given to a builder for exactly one purpose:
fresh generation or verification repair. GEPA mutates this complete strategy; an adapter cannot
append hidden behavioral instructions to it.
_Avoid_: User prompt, TypeScript prompt, runtime context, root transcript

**Runtime task context**:
The deterministic cycle facts rendered beside a builder instruction, including the cut, approved
selection, scope, target stack, contract locations, and applicable read-only verification-evidence
location. It is not part of the GEPA candidate and contains no implementation strategy.
_Avoid_: Builder instruction, prompt suffix, optimization candidate

**Generation instruction**:
The active builder instruction whose sole purpose is producing a fresh candidate from an approved
cut without a parent candidate or grader evidence.
_Avoid_: Verification-repair instruction, cycle preparation

**Verification-repair instruction**:
The independently selected builder instruction whose sole purpose is repairing a preceding
candidate from that attempt's read-only verifier evidence. Its measurement starts from fixed
failed-verification inputs so generation behavior cannot mask repair quality.
_Avoid_: Grader feedback, generation instruction, outer-cycle regeneration

**Prompt optimization**:
The durable selection of a complete purpose-specific builder instruction by comparing instruction
candidates with independent measurements over training and validation examples. Reflection uses
an explicitly pinned Bedrock provider and model; candidate execution remains a separate builder.
_Avoid_: Prompt editing, builder self-evaluation

**Instruction candidate**:
One complete generation or verification-repair strategy proposed by GEPA, bound to its Bedrock
reflection call and evaluated using the exact rendered prompt digest.
_Avoid_: Candidate application, generated implementation

**Reflection model binding**:
The immutable Bedrock provider and model identity used to propose instruction candidates in one
optimization. Its model-call evidence records sanitized provenance and usage but excludes AWS
credentials.
_Avoid_: Builder model, grader-author model, ambient provider default

**Production prompt**:
The exact ORC-rendered combination of an ORC-owned system instruction, an ORC-owned task
instruction, and deterministic runtime task context for a builder or cycle-oracle grader-author
session. Its digest is recorded before an adapter transports it unchanged.
_Avoid_: TypeScript literal, ambient system prompt, operator transcript

**Execution stage**:
One ordered, ORC-owned transition of the slice cycle. Both routes share every stage except the
route-selected oracle, grade, repair-evaluation, and conclude steps: Fresh Slice Generation seals
a cycle oracle before building, grades, may resample, and proposes accepted-oracle evidence;
measured regeneration resolves the accepted product oracle, grades by comparison against it, and
records its single gated measurement.
_Avoid_: Tool call, adapter process

**Verification attempt**:
One digest-bound Compose application-contract check against a candidate, with its own round,
target-stack identity, result, and evidence root.
_Avoid_: Grader result, candidate test claim

**Verification repair**:
The bounded ORC-owned loop that may give a fresh builder the previous candidate and the failed
verification evidence read-only, then re-instrument and re-verify the repaired candidate. It
permits at most three verification attempts and never exposes baseline-grade or oracle-comparison evidence to the builder.
_Avoid_: Grader feedback, unlimited retry, in-place host edit

**Instrumentation record**:
An immutable description of the candidate subject and evidence locations captured without changing the candidate.
_Avoid_: Candidate patch, verifier result

**Instrument conformance**:
An independent evaluation of required runtime signals after candidate execution, reported separately from the behavioral grade.
_Avoid_: Telemetry existence check, black-box verdict

**Measurement binding**:
A project-owned declaration connecting product events, projections, and invariants to facts an external observer can collect.
_Avoid_: Telemetry configuration, grader implementation

**Runtime signal**:
One producer's correlated observation about a candidate execution, including its collection completeness and subject identity.
_Avoid_: Proof, verdict, log file

**Independent invariant evaluation**:
An independent-validator-owned comparison of complete runtime signals against a named product invariant.
_Avoid_: Candidate assertion, telemetry status

**Collection completeness**:
The recorded boundary describing whether required signals were sampled, dropped, missing, or completely observed.
_Avoid_: Test coverage, confidence score

**Runtime probe package**:
The digest-bound probe plan, evidence schema, and language-agnostic probe programs supplied by
the sealed harness snapshot and mounted read-only for independent validation.
_Avoid_: Profiler installation, candidate telemetry

**Cycle evidence volume**:
A fresh writable directory mounted only for one regeneration cycle's independent grading,
while the candidate and runtime probe package remain read-only.
_Avoid_: Shared report directory, OCI layer

**Application contract**:
The runnable boundary a candidate must expose so an independent verifier can start, probe, and test it.
_Avoid_: Product specification, builder implementation plan

**Execution room**:
The capability boundary in which a builder or independent observer operates with only its declared mounts, writes, environment, and network access.
_Avoid_: Host shell, workflow stage

**Evidence projection**:
A report-format representation derived from a recorded observation without becoming the source of its verdict.
_Avoid_: Observation, event stream
