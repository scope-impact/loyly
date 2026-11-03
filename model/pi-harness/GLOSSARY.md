# Pi harness regeneration context

The Pi harness separates first-time baseline measurement and oracle promotion from
later oracle-comparison regeneration.

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

**Generation**:
An explicitly requested creation of a baseline candidate from an approved source cut. Existing
candidate history does not change Generation into Regeneration. Generation has no parent
candidate or accepted product oracle: after verification it independently grades the complete
approved denominator and proposes the normalized successful evidence as the first oracle.
_Avoid_: Regeneration, ungraded build

**Baseline grade**:
The first complete independent black-box measurement of a generated candidate against the
approved scenarios through the public seam. It produces proposed oracle evidence, not an
accepted oracle, and it cannot feed a builder or verification-repair session.
_Avoid_: Candidate tests, promoted oracle

**Oracle proposal**:
The normalized evidence from one completely passing baseline grade, bound to the selection,
scenario digests, candidate subject, judge, denominator, and its own stable digest. It remains
non-authoritative until an operator approves that exact digest.
_Avoid_: Mutable golden, accepted oracle

**Oracle promotion**:
The manual approval and independent publication of one exact oracle proposal as an immutable
OCI descriptor. A stale or different proposal digest cannot be approved, and failed, missing,
or inconclusive baseline evidence cannot be promoted.
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

**Regeneration cycle**:
One explicitly requested, idempotently identified, oracle-backed ORC execution that builds and
independently measures an approved selection from a named parent candidate. It is distinct from
Generation and owns its own measurement.
_Avoid_: Generation, run, session, job

**Builder**:
A fresh agent that can read the source cut and write only within the declared candidate scope.
_Avoid_: Root agent, implementer session

**Builder session**:
One separately identified builder conversation for an initial build or verification-repair
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
The generated application output bound to one generation or regeneration operation, its cut,
selection, builder session, and subject digest. A generation candidate becomes durable lineage
only after its complete baseline evidence is manually promoted to an accepted oracle.
_Avoid_: Implementation, answer, workspace

**Instrument oracle**:
A pinned OCI artifact that binds the complete verdict-affecting harness source digest,
required logical components, and normalized carried and uncarried Allure goldens.
_Avoid_: Product contract, candidate result

**Harness calibration**:
The pre-builder proof that the instrument oracle resolves, covers every required harness
component, matches the checked-in harness source, and passes its calibration fixtures.
_Avoid_: Candidate verification, generated test suite

**Oracle**:
A whole-system catalogue of manually approved normalized expected evidence, published as an
immutable OCI descriptor. Each entry is keyed by stable Gherkin item identity and full scenario
digest. The first generation proposes it from a complete independent baseline grade; later
cycles collect fresh evidence and compare it with the selected entries. The oracle is not a
grader, source tree, candidate verdict, or instrument oracle.
_Avoid_: Grader, verifier, raw transcript

**Oracle binding**:
The cycle-owned record of the approved item identities and scenario digests, selected product-
oracle descriptor, and independently selected instrument-oracle descriptor. Combining them in
cycle evidence does not make either OCI artifact depend on the other.
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
The immutable reduction of the approved denominator and grader verdicts, including explicit failures for unreached items.
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
An asynchronously executed source-cut preparation, initial generation, or regeneration cycle identified by a receipt and observed until one terminal outcome.
_Avoid_: Process, thread, future

**Builder instruction**:
The ORC-owned direction given to a fresh builder for one approved regeneration scope.
_Avoid_: User prompt, TypeScript prompt, root transcript

**Prompt optimization**:
The selection of a builder instruction by comparing instruction candidates with independent regeneration measurements over training and validation examples.
_Avoid_: Prompt editing, builder self-evaluation

**Instruction candidate**:
One proposed builder instruction evaluated during prompt optimization.
_Avoid_: Candidate application, generated implementation

**Execution stage**:
One ordered, ORC-owned transition. Initial generation builds, verifies, baseline-grades, proposes
an oracle, waits for exact manual approval, publishes it, and only then records the candidate;
regeneration performs fresh evidence collection and comparison against that accepted oracle.
_Avoid_: Tool call, adapter process

**Verification attempt**:
One digest-bound Compose application-contract check against a candidate, with its own round,
target-stack identity, result, and evidence root.
_Avoid_: Grader result, candidate test claim

**Verification repair**:
The bounded ORC-owned loop that may give a fresh builder the previous candidate and the failed
verification evidence read-only, then re-instrument and re-verify the repaired candidate. It
permits at most three verification attempts and never exposes grader evidence to the builder.
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
A grader-owned comparison of complete runtime signals against a named product invariant.
_Avoid_: Candidate assertion, telemetry status

**Collection completeness**:
The recorded boundary describing whether required signals were sampled, dropped, missing, or completely observed.
_Avoid_: Test coverage, confidence score

**Runtime probe package**:
The digest-bound probe plan, evidence schema, and language-agnostic probe programs supplied by
the pinned product-oracle OCI artifact and mounted read-only for independent grading.
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
