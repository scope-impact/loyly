# Pi harness regeneration context

The Pi harness turns a pinned specification cut into an independently measured
candidate implementation without exposing prior answers to the builder.

## Language

**Regeneration cut**:
An immutable, fenced repository snapshot identified by both commit and tree content.
_Avoid_: Checkout, clone, workspace

**Selection contract**:
The exact project, slice, item denominator, and cut identity reviewed for one regeneration scope.
_Avoid_: Filter, task list, scope file

**Approved selection**:
A selection contract whose exact digest has been explicitly accepted.
_Avoid_: Confirmed prompt, chosen tests

**Regeneration cycle**:
One idempotently identified attempt to build and independently measure an approved selection.
_Avoid_: Run, session, job

**Builder**:
A fresh agent that can read the regeneration cut and write only within the declared candidate scope.
_Avoid_: Root agent, implementer session

**Candidate**:
The generated application output produced by the builder for one regeneration cycle.
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
The independent trust binding composed from the calibrated instrument-oracle digest and the
resolved pinned product-contract digest.
_Avoid_: Grader, verifier

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

**Workflow task**:
An asynchronously executed cut preparation or regeneration cycle identified by a receipt and observed until one terminal outcome.
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
One ordered, ORC-owned transition in a regeneration cycle: cut, harness calibration, builder,
instrument, oracle, verify, repair verification, graders, instrument conformance, record cycle,
or result.
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

**Application contract**:
The runnable boundary a candidate must expose so an independent verifier can start, probe, and test it.
_Avoid_: Product specification, builder implementation plan

**Execution room**:
The capability boundary in which a builder or independent observer operates with only its declared mounts, writes, environment, and network access.
_Avoid_: Host shell, workflow stage

**Evidence projection**:
A report-format representation derived from a recorded observation without becoming the source of its verdict.
_Avoid_: Observation, event stream
