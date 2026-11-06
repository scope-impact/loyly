# Pi harness regeneration context

The Pi harness runs one regeneration cycle in the legacy shape: fence, regenerate, gate, score,
scoreboard. The harness feeds the build loop; the instrument reports to a person.

## Language

**Source cut**:
An immutable, fenced repository snapshot identified by source commit and tree content. A cut
is neutral input to a generation or regeneration workflow; preparing it is not itself either
operation.
_Avoid_: Regeneration cut, checkout, clone, workspace

**Slice**:
A named, coherent set of observable behavior contracts represented by Gherkin scenarios with
stable item identities. A slice does not contain or select a grader.
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

**Node-stage translation**:
The harness's anti-corruption layer over ORC: the Grain read model `node-stages`, which turns
ORC's `:sheet` node facts into the harness's terms, a cycle stage and a builder role per tick
and node. Processors that react to ORC nodes and the progress view read it, never a raw ORC
node id.
_Avoid_: Node mapping, sheet adapter

**Stage fact**:
A typed domain fact of one cycle stage (a harness self-check, a clean room proved, a slice
built, a candidate regenerated, a gate attempt, a grader sealed or discarded, a filed score, a
scoreboard reported, a cycle passed or failed), recorded as a harness event by a command an ORC
workflow node or a harness policy issues (ADR 0004). Unlike progress, it carries a schema and
the invariants its rule guards.
_Avoid_: Progress event, node output, write-key

**Policy**:
A rule that reacts to a fact or a state the harness already holds and issues a command, such as
routing a gate verdict or concluding a cycle's result. In Grain it is a todo processor reading a
to-do query.
_Avoid_: Event handler, saga

**Regeneration cycle**:
The one ORC-owned workflow, in the legacy shape: fence → regenerate → gate → score → scoreboard.
The fence and the gate are the harness and feed the build loop; the score and the scoreboard are
the instrument and report to a person, never into the loop. Adapter completion cannot reorder or
skip a stage. Anything not written into the spec dies with the implementation. It is one
aggregate, `RegenerationCycle` in `regeneration-cycle.allium`, with a status (submitted, running,
passed, failed) and a stage (fence, regenerate, gate, score, scoreboard, result); a terminal
cycle names its candidate digest. `execution-pipeline.allium` moves it through its stages; its
Grain read model is `regeneration-cycles`, folded from its stage facts.
_Avoid_: Pipeline, pipeline cycle, generation versus regeneration, routes, workflow task.

**Harness**:
The parts of the cycle that feed the build loop: the fence and the gate.
_Avoid_: Instrument, grader.

**Instrument**:
The parts of the cycle that report to a person and never feed the loop: the checklist, the sealed
grader, the score, and the scoreboard.
_Avoid_: Harness, oracle.

**Harness self-check**:
The proof, before any clean room is built, that the harness passes its own calibration fixtures,
reproduces every instrument golden exactly, and matches its committed revision and runtime assets. A passing self-check is reused only for an
identical harness digest; a failing one names the failing check and stops the cycle.
_Avoid_: Candidate verification, generated test suite.

**Fence**:
The stage that builds the clean room and proves it: a single-commit clone in which every fenced
path is absent from the working tree and the object graph. A fenced path that matches nothing
stops the fence. The fence is physical, never an instruction.
_Avoid_: Prompt rule, read-only reminder.

**Clean room**:
The fenced, read-only view a builder works from: the spec, the domain sources, and the public
application contract, with no prior implementation, measurement, report, grader, calibration
fixture, or grading harness.
_Avoid_: Source tree, workspace.

**Regenerate**:
The stage in which a fresh builder session rebuilds the selection from the clean room into the
declared workspace scope.
_Avoid_: Patch, continue the previous candidate.

**Gate**:
The ratifying stage: a room the builder does not control boots the candidate fresh and proves its
suite green. Done is the gate's verdict, never the builder's claim. A failed gate starts a fresh
builder session with only the previous candidate and the gate's evidence, at most three attempts.
_Avoid_: Verifier, builder self-test, grader.

**Gate attempt**:
One digest-bound run of the gate against one candidate, with its attempt number, target-stack
identity, result, and evidence root.
_Avoid_: Grader result, candidate test claim.

**Checklist**:
One item per stamped scenario of the approved selection, keyed by stable item identity and
scenario digest. It is derived, never written, and sets the denominator.
_Avoid_: Test list, hand-kept checklist.

**Instrument golden**:
The exact expected output, refusal reasons included, of one instrument scenario (stamping,
checklist derivation, a score refusal, the scoreboard's arithmetic), written from that scenario
and never recorded from an implementation. Instrument material: fenced from every clean room.
_Avoid_: Recorded output, snapshot.

**Grader**:
The sealed instrument that scores a checklist: written once from the selected Gherkin and the
public calibration protocol, calibrated so carried behavior passes and uncarried behavior fails,
sealed by digest, and used only after an operator approves that exact digest. It is reused until
its scenarios change and replaced only for a recorded reason. It carries the behavior, affordance,
and runtime-evidence evaluators. ORC's evaluation judges are not graders: their scores are
diagnostic runtime evidence only.
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
reviewed before ORC starts a regeneration cycle for the approved slice.
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

**Cycle preparation**:
The reviewed plan binding an approved selection, candidate scope, target stack, execution
profile, pinned inputs, diagram, and outer-cycle budget before a regeneration cycle starts.
_Avoid_: Submission payload, run configuration, prompt

**Approved cycle preparation**:
A cycle preparation whose exact digest has been explicitly accepted by an operator session.
_Avoid_: Approved selection, confirmed backend

**OrcRuntime**:
The actor that is ORC's runtime: the worker that runs every tick and the only one that reports
execution facts (a tick started, a builder completed, a gate returned, a grader returned its
verdicts, a task completed). Declared once in `regeneration-cycle.allium`; every surface that
receives ORC's facts faces it. An operator can never report these facts.
_Avoid_: Operator, builder host, adapter

**Operator session**:
The Codex, Pi, or other interactive session through which an operator prepares, approves,
observes, and evaluates regeneration cycles.
_Avoid_: Builder, ORC worker, root agent

**Builder**:
A fresh agent that can read the source cut and write only within the declared candidate scope.
_Avoid_: Root agent, implementer session

**Builder session**:
One separately identified builder conversation for a fresh build or verification-repair
attempt, isolated from the operator session and forbidden independent grader evidence. It is a
durable Pi session that ORC launches for one tick, in one of three roles: slice builder,
repair builder, or grader author. A relaunch of the tick resumes it. It is one aggregate,
`BuilderSession` in `execution-pipeline.allium`; the view an operator observes is a projection
of it (the Grain read model `builder-sessions`), never an authority.
_Avoid_: Operator session, regeneration cycle, builder worker, durable builder launch, builder
session reference

**Builder room**:
The Gondolin VM ORC chooses for one builder session: the image for the stack, the cut
read-only, only the declared scope writable, gate evidence read-only for a repair, no network
egress, and no host environment. The builder host forwards tool calls into it and cannot widen it.
_Avoid_: Sandbox, adapter

**Builder host**:
The provided process that runs a builder session's durable Pi loop for ORC: it submits ORC's
prompt with the tick as its request identity, keeps the model credentials on the host, and
reports committed tool results. It decides nothing about the cycle or the room.
_Avoid_: Builder worker, builder adapter

**Builder-session observation**:
Read-only inspection of a builder session's committed turns, tool calls, workspace generation,
and progress without changing the candidate.
_Avoid_: Steering, repair, transcript inheritance

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
An asynchronously executed source-cut preparation or regeneration cycle identified by a receipt
and observed until one terminal outcome (`HarnessTask`, `operator-control-plane.allium`). A task
is not a cycle: a task may prepare a cut, and a cycle's task is opened when the cycle is
submitted.
_Avoid_: Process, thread, future, regeneration cycle

**Builder instruction**:
The complete ORC-owned model-directed strategy given to a builder for exactly one purpose:
fresh generation or verification repair. GEPA mutates this complete strategy; an adapter cannot
append hidden behavioral instructions to it.
_Avoid_: User prompt, TypeScript prompt, runtime context, root transcript

**Regeneration instruction**:
The active builder instruction whose sole purpose is producing a fresh candidate from the clean
room without a parent candidate or score evidence.
_Avoid_: Gate-repair instruction, cycle preparation.

**Gate-repair instruction**:
The independently selected builder instruction whose sole purpose is repairing a preceding
candidate from that attempt's read-only gate evidence. Its measurement starts from fixed failed-gate
inputs so regeneration behavior cannot mask repair quality.
_Avoid_: Score feedback, regeneration instruction.

**Runtime task context**:
The deterministic cycle facts rendered beside a builder instruction, including the cut, approved
selection, scope, target stack, contract locations, and applicable read-only verification-evidence
location. It is not part of the GEPA candidate and contains no implementation strategy.
_Avoid_: Builder instruction, prompt suffix, optimization candidate

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
instruction, and deterministic runtime task context for a builder or grader-author
session. Its digest is recorded before an adapter transports it unchanged.
_Avoid_: TypeScript literal, ambient system prompt, operator transcript

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
the self-checked harness and mounted read-only for independent validation.
_Avoid_: Profiler installation, candidate telemetry

**Cycle evidence volume**:
A fresh writable directory mounted only for one regeneration cycle's independent grading,
while the candidate and runtime probe package remain read-only.
_Avoid_: Shared report directory, OCI layer

**Application contract**:
The runnable boundary a candidate must expose so the gate can start, probe, and test it.
_Avoid_: Product specification, builder implementation plan

**Execution room**:
The capability boundary in which a builder or independent observer operates with only its declared mounts, writes, environment, and network access.
_Avoid_: Host shell, workflow stage

**Evidence projection**:
A report-format representation derived from a recorded invariant evaluation without becoming the
source of its verdict (`EvidenceProjectionCreated`, owned by `runtime-evidence.allium`).
_Avoid_: Observation, event stream
