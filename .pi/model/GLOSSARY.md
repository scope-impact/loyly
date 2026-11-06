# Pi harness regeneration context

The Pi harness runs one regeneration cycle in the legacy shape: fence, regenerate, gate, score,
scoreboard. The harness feeds the build loop; the instrument reports to a person.

This glossary is the authority for the domain's words (ADR 0005). Allium and Grain identifiers,
`.pi/architecture.md`, and the ADRs use these terms. A value the seam (`affordances.yaml`) fixes
is the published language: the model uses it verbatim and the glossary names it. Each `_Avoid_`
list names synonyms the model must not use for that concept; `tools/spec/glossary-lint.py`
enforces it. An avoided word that is itself a term here is a cross-reference to that term.
Technology is bound to these terms in `.pi/architecture.md` ("Adapters"), not named by them.

## Language

**Source cut**:
An immutable, fenced repository snapshot identified by source commit and tree content
(`SourceCut`). A cut is neutral input to a generation or regeneration workflow; preparing it is
not itself either operation.
_Avoid_: Regeneration cut, checkout, clone, workspace

**Slice**:
A named, coherent set of observable behavior contracts represented by Gherkin scenarios with
stable item identities. A slice does not contain or select a grader.
_Avoid_: Model directory, grader bundle, implementation component

**Item identity**:
The stable `@item-NN-SSS` tag stamped on one scenario. It is never renumbered, re-derived from
wording, or reused. It is not the scenario digest, which changes when the behavior changes.
_Avoid_: Item number, test id

**Stamped item**:
One stamped scenario as a selection and a checklist carry it: its item identity, scenario
digest, Rule name (or none), source file and line, and title with surrounding whitespace
stripped (`StampedItem`, a value of `regeneration-cycle.allium`). The seam's "stamped items".
_Avoid_: Selection item, checklist entry, test case

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

**Event store**:
Grain's tenant-scoped store of facts, shared by the harness's and ORC's service areas; neither
area's facts are copied into the other's. It is storage, never a progress view.
_Avoid_: Database, log

**Harness process**:
The one runnable container of the harness: its launch entrypoint starts it, it serves the seam,
and it runs the `:loyly.pi-harness` service area and ORC's workflow. It is not a workflow task.
_Avoid_: Server, daemon

**ORC execution projection**:
A Pi-harness read model derived from authoritative ORC `:sheet/*` events for observation and
reconnection. It cannot advance execution and introduces no duplicate progress event.
_Avoid_: Harness progress event, workflow authority

**Node-stage translation**:
The harness's anti-corruption layer over ORC: the Grain read model `node-stages`, which turns
ORC's `:sheet` node facts into the harness's terms, a cycle stage and a builder role per tick
and node. Processors that react to ORC nodes and the progress view read it, never a raw ORC
node id.
_Avoid_: Node mapping, sheet adapter

**Stage fact**:
A typed domain fact of one cycle stage (a harness self-check, a clean room proved, a slice
built, a candidate regenerated, a gate attempt, a grader authored, sealed, or discarded, a filed
score, a scoreboard reported, a cycle passed or failed), recorded as a harness event by a
command an ORC workflow node or a harness policy issues (ADR 0004). Unlike progress, it carries
a schema and the invariants its rule guards.
_Avoid_: Progress event, node output, write-key

**Policy**:
A rule that reacts to a fact or a state the harness already holds and issues a command, such as
routing a gate verdict or concluding a cycle's result. In Grain it is a todo processor reading a
to-do query.
_Avoid_: Event handler, saga

**Regeneration cycle**:
The one ORC-owned workflow, in the legacy shape: fence → regenerate → gate → score → scoreboard.
The fence and the gate are the harness and feed the build loop; the score and the scoreboard are
the instrument and report to a person, never into the loop. Builder host completion cannot
reorder or skip a stage. Anything not written into the spec dies with the implementation. It is
one aggregate, `RegenerationCycle` in `regeneration-cycle.allium`, with a status (submitted,
running, passed, failed) and a stage (fence, regenerate, gate, score, scoreboard, result); a
terminal cycle names its candidate digest. The execution pipeline file moves it through its
stages; its Grain read model is `regeneration-cycles`, folded from its stage facts.
_Avoid_: Pipeline, pipeline cycle, pipeline stage, generation and regeneration routes, workflow task

**Execution pipeline**:
The name of slice `02-execution-pipeline` and its file `execution-pipeline.allium` only: the
rules that move the regeneration cycle through its stages. It names no concept; the concept is
the regeneration cycle and its stages. Other specs import the file `as execution`.
_Avoid_: Pipeline

**Harness**:
The parts of the cycle that feed the build loop: the fence and the gate.
_Avoid_: Instrument, grader.

**Instrument**:
The parts of the cycle that report to a person and never feed the loop: the checklist, the sealed
grader, the score, and the scoreboard.
_Avoid_: Harness, oracle.

**Harness self-check**:
The proof, before any clean room is built, that the harness passes its own calibration fixtures,
reproduces every instrument golden exactly, and matches its committed revision and runtime
assets. A passing self-check is reused only for an identical harness digest; a failing one names
the failing check and stops the cycle.
_Avoid_: Candidate verification, generated test suite.

**Fence**:
The stage that builds the clean room and proves it: a repository of the cut's one commit in
which every fenced path is absent from the working tree and the object graph. A fenced path that
matches nothing stops the fence. The fence is physical, never an instruction.
_Avoid_: Prompt rule, read-only reminder.

**Clean room**:
The fenced, read-only view a builder works from: the spec, the domain sources, and the public
application contract, with no prior implementation, measurement, report, grader, calibration
fixture, or grading harness. It is what a builder room mounts read-only; it is not a room ORC
starts.
_Avoid_: Source tree, workspace, sandbox.

**Candidate scope**:
The declared paths a builder may write: non-empty, relative, inside the candidate, and
non-overlapping (`CandidateScope`). Everything outside it is read-only or absent.
_Avoid_: Workspace, output directory

**Regenerate**:
The stage in which a fresh builder session rebuilds the selection from the clean room into the
declared candidate scope.
_Avoid_: Patch, continue the previous candidate.

**Uncarried list**:
A slice builder's own list of the items of its slice it has not carried yet. It bounds the
builder's continuation in the same session (at most three times); it is the builder's claim,
never a verdict.
_Avoid_: Uncarried report, progress file

**Room**:
An isolated capability boundary ORC starts for one step, with only its declared mounts, writes,
environment, and network access: a builder room, a gate room, or a validation room. Rooms are
isolated from each other; the builder controls none but its own, and that one only within its
candidate scope.
_Avoid_: Execution room, sandbox, host shell

**Builder room**:
The room ORC chooses for one builder session: provisioned for the target stack, the cut
read-only, only the declared candidate scope writable, gate evidence read-only for a repair
builder, only the selected Gherkin and the calibration protocol for a grader author, no network
egress, and no host environment (`BuilderRoomPolicy`). The builder host forwards tool calls into
it and cannot widen it.
_Avoid_: Sandbox, adapter

**Gate room**:
The room the builder does not control in which the gate boots a candidate fresh through its
launch entrypoint, from fresh state, and observes its suite run.
_Avoid_: Verifier, test sandbox

**Validation room**:
The room in which independent validation runs against the gated candidate: the candidate and
the capture policy read-only, one cycle evidence volume writable (`ValidationRoom`).
_Avoid_: Independent validation room, validator room

**Launch entrypoint**:
The one command a candidate's application contract exposes to start it speaking its seam. The
gate times it and fails a launch that exits before it responds.
_Avoid_: Start script, boot script

**Gate**:
The ratifying stage: a gate room boots the candidate fresh and proves its suite green. Done is the
gate's verdict, never the builder's claim. A failed gate starts a fresh repair builder with only
the previous candidate and the gate's evidence, at most three attempts.
_Avoid_: Verifier, builder self-test, grader.

**Gate attempt**:
One digest-bound run of the gate against one candidate, with its attempt number, target-stack
identity, result, and evidence root.
_Avoid_: Grader result, candidate test claim.

**Checklist**:
One stamped item per stamped scenario of the approved selection, keyed by item identity and
scenario digest. It is derived, never written, and sets the denominator.
_Avoid_: Test list, hand-kept checklist.

**Instrument golden**:
The exact expected output, refusal reasons included, of one instrument scenario (stamping,
checklist derivation, a score refusal, the scoreboard's arithmetic), written from that scenario
and never recorded from a run. Instrument material: fenced from every clean room.
_Avoid_: Recorded output, snapshot.

**Grader**:
The sealed instrument that scores a checklist: written once by a grader author from the selected
Gherkin and the public calibration protocol, calibrated so carried behavior passes and uncarried
behavior fails, sealed by digest, and used only after an operator approves that exact digest. It
is reused until its scenarios change and replaced only for a recorded reason (instrument
behavior changed, wording replaced, or spec grew, additions only); approving its successor
retires it. It carries the behavior, affordance, and runtime-evidence evaluators. ORC's
evaluation judges are not graders: their scores are diagnostic runtime evidence only.
_Avoid_: Oracle, per-cycle judge, LLM judge, builder.

**Score**:
The stage in which the approved grader files one verdict per checklist item through the
candidate's public seam. Zero verdicts mean no score was filed; an unreached item fails and stays
counted.
_Avoid_: Grade gate, oracle comparison.

**Scoreboard**:
The report of a cycle's pass rate and its comparison with the previous cycle of the same
selection, restated on shared items, with newly passing and newly failing items reported
separately and new items counted as the untuned holdout. A net is never computed.
_Avoid_: Net improvement, average score.

**Selection contract**:
The exact project, composed slice identity, item identities and scenario digests, and cut identity
reviewed before ORC starts a regeneration cycle for the approved slice (`SliceSelection`).
_Avoid_: Filter, task list, scope file

**Slice proposal**:
A non-authoritative LLM suggestion of an existing slice and item subset derived from operator
intent (`SliceProposal`). ORC resolves every proposed identity against the source cut before
presenting a selection contract; the proposal cannot approve itself or invent specification
items.
_Avoid_: Approved selection, generated specification

**Approved selection**:
A selection contract whose exact digest has been explicitly accepted.
_Avoid_: Confirmed prompt, chosen tests

**Scenario digest**:
The digest of one complete normalized Gherkin scenario contract, including its Rule and Scenario
names, Given/When/Then steps and arguments, and Examples. It changes when the observable behavior
changes even if the separately keyed stable item identity is retained.
_Avoid_: File hash, model-tree digest, item identity

**Cycle preparation**:
The reviewed plan binding an approved selection, candidate scope, target stack, execution
profile, pinned inputs, diagram, and outer-cycle budget before a regeneration cycle starts.
_Avoid_: Submission payload, run configuration, prompt

**Approved cycle preparation**:
A cycle preparation whose exact digest has been explicitly accepted by an operator session.
_Avoid_: Approved selection, confirmed backend

**Execution backend**:
Where a cut is prepared and a cycle's rooms run: `host` (the host, for development) or
`gondolin` (isolated rooms). These are the seam's `backend` values, so the model uses them
verbatim; `.pi/architecture.md` binds `gondolin` to its technology. It is the one execution
mode; the execution profile is a policy over it (`ExecutionBackend`).
_Avoid_: Sandbox mode, execution mode

**Execution profile**:
The approved set of execution backends a cycle preparation admits: `host-development` admits
`host`, `gondolin-isolated` admits `gondolin`, and `automatic` admits either. These are the
seam's `profile` values. It is not a second execution mode (`ExecutionProfile`).
_Avoid_: Shell command, VM configuration

**Operator**:
A person, or a Pi or Codex session acting for one, who prepares, approves, submits, observes, and
evaluates work (`actor Operator`, identified by its operator session). An operator never reports
an execution fact.
_Avoid_: User, requester

**OrcRuntime**:
The actor that is ORC's runtime: the ORC worker that runs every tick and the only one that
reports execution facts (a tick started, a builder completed, a gate returned, a grader returned
its verdicts, a task completed). Declared once in `regeneration-cycle.allium`; every surface that
receives ORC's facts faces it. An operator can never report these facts.
_Avoid_: Operator, builder host, adapter

**ORC worker**:
One running instance of ORC's runtime, the identity of `actor OrcRuntime` (`OrcWorker`). A
replacement worker responds for a task another worker started, from ORC's durable state.
_Avoid_: Operator session, executor

**Operator session**:
The Codex, Pi, or other interactive session through which an operator prepares, approves,
observes, and evaluates regeneration cycles.
_Avoid_: Builder, ORC worker, root agent

**Builder**:
A fresh agent that can read the source cut and write only within the declared candidate scope.
_Avoid_: Root agent, implementer session

**Builder session**:
One separately identified builder conversation for a fresh generation or a verification repair,
isolated from the operator session and forbidden independent grader evidence. It is a durable Pi
session that ORC launches for one tick, in one of three roles (`BuilderSessionRole`): slice
builder, repair builder, or grader author. A relaunch of the tick resumes it; it ends completed
or failed. It is one aggregate, `BuilderSession` in `execution-pipeline.allium`; the view an
operator observes is a projection of it (the Grain read model `builder-sessions`), never an
authority.
_Avoid_: Operator session, regeneration cycle, builder worker, durable builder launch, builder
session reference

**Slice builder**:
The builder session role (`slice_builder`) that builds one selected slice on the first regenerate
attempt, in plan order, from the clean room and this cycle's candidate so far. Its production
prompt carries the fresh-generation instruction.
_Avoid_: Regeneration builder, generator

**Repair builder**:
The builder session role (`repair_builder`) that repairs the previous candidate after a failed
gate, from only that candidate and the gate's evidence. Its production prompt carries the
verification-repair instruction.
_Avoid_: Gate-repair builder, fixer

**Grader author**:
The builder session role (`grader_author`) that writes a grader from only the selected Gherkin
and the public calibration protocol; it belongs to no cycle and reads no candidate.
_Avoid_: Grader builder, judge author

**Builder host**:
The provided runtime that runs a builder session's durable Pi loop for ORC: it submits ORC's
production prompt with the tick as its request identity, keeps the model credentials on the
host, and reports committed tool results and the session's end. It decides nothing about the
cycle or the room.
_Avoid_: Builder worker, builder adapter

**Workspace generation**:
The candidate scope's contents as they stood when one tool result of a builder session was
committed (`WorkspaceGeneration`; the seam's `workspaceGeneration`). A restored transcript is
paired only with the generation of its last committed result.
_Avoid_: Snapshot, checkpoint

**Builder-session observation**:
Read-only inspection of a builder session's committed turns, tool calls, workspace generation,
and progress without changing the candidate.
_Avoid_: Steering, transcript inheritance

**Builder-session intervention**:
An explicit, provenance-recorded operator action that pauses, steers, or forks builder work and
therefore marks the resulting candidate as assisted.
_Avoid_: Observation, invisible edit

**Candidate**:
The generated application output bound to one regeneration cycle, its cut, selection, builder
session, and subject digest. It dies with the cycle; only the spec and the instrument carry over.
_Avoid_: Implementation, answer, workspace

**Observation**:
Evidence from one named producer about one approved item in one cycle.
_Avoid_: Score, log line

**Measurement**:
The one filed score of one cycle: a pass or fail verdict per checklist item from the approved
sealed grader, the denominator of the approved selection, and explicit failures for unreached
items. It is one aggregate, `CycleMeasurement` in `instrument.allium`, filed only by `FileScore`;
a refused score files nothing and fails the score stage. The durable measurement receipt an
operator reads is a projection of it (the Grain read model `cycle-measurements`).
_Avoid_: Test report, average score, pipeline measurement, score verdict, observation

**Verdict**:
A judgment with a named owner: an item verdict (filed, pass or fail), an evidence verdict (pass,
fail, or inconclusive), or the gate's verdict (passed or not). A runtime signal is never a
verdict.
_Avoid_: Judgement, ruling

**Item verdict**:
A filed checklist verdict: pass or fail, nothing else (`ItemVerdict`, `instrument.allium`).
Inconclusive evidence, like no evidence, files as fail. Distinct from an evidence verdict, which
may be inconclusive.
_Avoid_: Evidence verdict, pipeline verdict

**Evidence verdict**:
The result of one independent invariant evaluation: pass, fail, or inconclusive
(`EvidenceVerdict`, `runtime-evidence.allium`). It is evidence, never a filed checklist verdict.
_Avoid_: Item verdict, score

**Cycle progress view**:
A reconstructable, non-authoritative view of the current ORC stage and associated builder
session, derived from durable execution state and live notifications (`CycleProgressView`). The
seam reports its status as `not-started` (a submitted task ORC has not started), `running`,
`completed`, or `failed`.
_Avoid_: Workflow state, event store

**Cycle continuation decision**:
The operator session's bounded decision, after a terminal measurement, to stop, request
correction, or prepare a distinct child regeneration cycle.
_Avoid_: Verification repair, ORC retry

**Workflow task**:
An asynchronously executed source-cut preparation or regeneration cycle identified by a receipt
and observed until one terminal outcome (`WorkflowTask`, `operator-control-plane.allium`). A task
is not a cycle: a task may prepare a cut, and a cycle's task is opened when the cycle is
submitted.
_Avoid_: Harness task, process, thread, future, regeneration cycle

**Builder instruction**:
The complete ORC-owned model-directed strategy given to a builder for exactly one purpose
(`BuilderInstructionPurpose`): fresh generation or verification repair. GEPA mutates this
complete strategy; the builder host cannot append hidden behavioral instructions to it.
_Avoid_: User prompt, TypeScript prompt, runtime context, root transcript

**Fresh generation**:
The purpose of a slice builder's work: producing a fresh candidate from the clean room, with no
parent candidate and no score evidence. The seam's purpose value `fresh-generation`.
_Avoid_: Regeneration purpose, first build

**Verification repair**:
The purpose of a repair builder's work: repairing the preceding candidate from that attempt's
read-only gate evidence. The seam's purpose value `verification-repair`; "verification" there
means the gate.
_Avoid_: Gate repair, score feedback

**Fresh-generation instruction**:
The active builder instruction whose purpose is fresh generation.
_Avoid_: Regeneration instruction, cycle preparation.

**Verification-repair instruction**:
The independently selected builder instruction whose purpose is verification repair. Its
measurement starts from fixed failed-gate inputs so fresh-generation behavior cannot mask repair
quality.
_Avoid_: Gate-repair instruction, score feedback, fresh-generation instruction.

**Runtime task context**:
The deterministic cycle facts rendered beside a builder instruction, including the cut, approved
selection, scope, target stack, contract locations, and applicable read-only gate-evidence
location. It is not part of the GEPA candidate and contains no implementation strategy.
_Avoid_: Builder instruction, prompt suffix, optimization candidate

**Prompt optimization**:
The durable selection of a complete purpose-specific builder instruction by comparing instruction
candidates with independent measurements over training and validation examples. Reflection uses
an explicitly pinned reflection provider and model; candidate execution remains a separate
builder.
_Avoid_: Prompt editing, builder self-evaluation

**Instruction candidate**:
One complete fresh-generation or verification-repair strategy proposed by GEPA, bound to its
reflection call and evaluated using the exact rendered prompt digest. It ends rejected or
selected; a selected candidate is superseded when a later winner for its purpose is selected.
_Avoid_: Candidate application, generated implementation

**Reflection model binding**:
The immutable reflection provider and model identity used to propose instruction candidates in
one optimization. Its model-call evidence records sanitized provenance and usage but excludes
provider credentials.
_Avoid_: Builder model, grader-author model, ambient provider default

**Production prompt**:
The exact ORC-rendered combination of an ORC-owned system instruction, an ORC-owned task
instruction, and deterministic runtime task context for one builder session role. Its digest is
recorded before the builder host transports it unchanged.
_Avoid_: TypeScript literal, ambient system prompt, operator transcript

**Measurement binding**:
A project-owned declaration connecting product events, projections, and invariants to facts an
external observer can collect.
_Avoid_: Telemetry configuration, grader implementation

**Runtime signal**:
One producer's correlated observation about a candidate execution, including its collection
completeness and subject identity.
_Avoid_: Proof, verdict, log file

**Independent invariant evaluation**:
An independent-validator-owned comparison of complete runtime signals against a named product
invariant.
_Avoid_: Candidate assertion, telemetry status

**Collection completeness**:
The recorded boundary describing whether required signals were sampled, dropped, missing, or
completely observed.
_Avoid_: Test coverage, confidence score

**Capture policy**:
The pinned, digest-bound, read-only declaration of which runtime signals a validation room
collects, supplied by the self-checked harness (`CapturePolicy`).
_Avoid_: OTel capture policy, telemetry configuration

**Runtime probe package**:
The digest-bound probe plan, evidence schema, and language-agnostic probe programs supplied by
the self-checked harness and mounted read-only for independent validation.
_Avoid_: Profiler installation, candidate telemetry

**Cycle evidence volume**:
A fresh writable directory mounted only for one regeneration cycle's independent grading,
while the candidate and runtime probe package remain read-only (`CycleEvidenceVolume`).
_Avoid_: Shared report directory, OCI layer

**Trace artifact**:
The complete trace an independent validator records of a baseline grade, bound to the passing
evaluation's evidence digest (`TraceArtifact`). A score is complete only with it.
_Avoid_: Browser trace, Playwright trace

**Application contract**:
The runnable boundary a candidate must expose so the gate can start it through its launch
entrypoint, probe it, and test it.
_Avoid_: Product specification, builder implementation plan

**Evidence projection**:
A report-format representation derived from a recorded invariant evaluation without becoming the
source of its verdict (`EvidenceProjectionCreated`, owned by `runtime-evidence.allium`).
_Avoid_: Observation, event stream
