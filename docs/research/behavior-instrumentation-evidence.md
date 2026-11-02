# Behavioral instrumentation and correctness evidence

**Status:** research
**Date:** 2026-11-01
**Question:** What evidence, beyond Playwright and OpenTelemetry traces, can a regeneration
cycle collect to explain behavior or increase confidence in correctness?

## Executive conclusion

There is no single richer trace that proves a system correct. The useful evidence falls into
three different classes:

1. **Observation of one execution** — browser traces, distributed traces, logs, profiles,
   packets, system calls, database snapshots. These explain *what happened in this run*.
2. **Adversarial measurement** — black-box grading, generated operation sequences, mutations,
   fault injection, replay, and invariant checks. These try to produce a counterexample and
   show that an instrument can detect one.
3. **Proof relative to a model** — model checking, formal proof, or runtime verification. These
   establish a property only for the stated model or the observed trace, not automatically for
   the deployed implementation.

For Löyly, the highest-value next layer is not more passive telemetry. It is a correlated
**behavior history**: externally observed operations and results, domain events, and independent
post-state snapshots, checked against item-specific invariants. Add stateful/property tests and
fault schedules around that history, then use mutation testing to calibrate whether the grader
actually detects plausible defects. Profiling, eBPF, packet captures, and record/replay should be
retained primarily as diagnostic evidence when a behavioral observation fails.

```text
intent / item contract
        │
        ├── black-box operations + HTTP exchanges
        ├── domain events + independent state snapshots
        ├── invariant / history checker ─────────────── behavioral verdict
        ├── generated sequences + fault schedule ───── falsification strength
        ├── mutation / coverage ────────────────────── instrument calibration
        └── traces / logs / profiles / pcap / replay ─ diagnosis

all raw artifacts + explicit claim metadata
        → sealed evidence manifest
        → durable cycle measurement
        → Allure projection
```

## What each instrument can honestly claim

| Instrument | Claim it supports | Useful artifact | Important limitation |
|---|---|---|---|
| Distributed traces | A sampled request followed these instrumented spans with these timings and attributes | OTLP trace or trace export keyed by trace/span ID | Missing spans may mean sampling, dropped export, or missing instrumentation; a trace is not an acceptance oracle. OpenTelemetry defines traces as one signal among traces, metrics, logs, baggage, and emerging profiles ([OTel signals](https://opentelemetry.io/docs/concepts/signals/)). |
| Metrics | An aggregate changed over a window; latency/error/resource bounds held for measured samples | OTLP metrics plus query, window, labels, and threshold result | Aggregation loses individual histories and can hide rare failures. Preserve the query and sample/drop metadata, not only a dashboard screenshot. |
| Structured logs | The application or observer reported these events | JSON logs with trace ID, item ID, actor, operation ID, and event schema version | Application logs are self-reported, can be incomplete, and must not serve as their own oracle. |
| Continuous profiles | Sampled CPU, allocation, heap, lock, or off-CPU behavior came from these stacks | pprof/JFR/OTLP profile and optional flame graph | Profiles explain resource use, not domain correctness; short operations may not be sampled. OTel Profiles is currently Alpha and defines sampled stack/resource evidence ([OTel Profiles](https://opentelemetry.io/docs/concepts/signals/profiles/)); Pyroscope can correlate profiles with traces ([Pyroscope](https://grafana.com/docs/pyroscope/latest/)). |
| eBPF/kernel observation | The kernel observed selected syscalls, network, scheduling, file, or stack events for the target process/container | BPF event stream, counters, stack profile, program and kernel/BTF identity | Requires privilege and kernel support; probe coverage and kernel interfaces vary. Kernel documentation explicitly warns that tracing programs which walk kernel internals depend on non-stable details ([BPF design Q&A](https://docs.kernel.org/bpf/bpf_design_QA.html)). |
| Network capture/proxy | These bytes or protocol messages crossed the observed boundary | pcapng or structured HTTP/gRPC exchange | TLS hides payloads unless captured at a terminating proxy; capture location, loss, filters, secrets, and clock must be recorded. Wireshark distinguishes capture from display filters ([Wireshark guide](https://www.wireshark.org/docs/wsug_html_chunked/ChCapCaptureFilterSection.html)). |
| Deterministic record/replay | This recorded execution can be reproduced for debugging | replay bundle plus executable/build/environment identity | It reproduces one run, not all schedules. `rr` records process trees but emulates one core and has syscall/platform constraints ([rr](https://rr-project.org/)). |
| Property-based testing | No counterexample was found in these generated cases; or this minimized counterexample violates the property | property ID, seed, generator version, cases, shrunk counterexample | Finite randomized exploration is not proof. Reproducibility requires seed and generator/config digests. |
| Model/stateful testing | Generated action sequences conformed to an independent state-machine model, or produced a minimal failing sequence | commands, observed results, model states, seed, shrunk sequence | Confidence depends on independence and correctness of the model. Hypothesis generates sequences and can check invariants after every step ([stateful testing](https://hypothesis.readthedocs.io/en/latest/stateful.html)). |
| Metamorphic testing | Related executions preserve a stated relation even when an exact expected output is difficult to specify | source input/result, transformation, follow-up input/result, relation verdict | A bad or incomplete metamorphic relation misses defects. The original technique derives follow-up tests from successful cases ([Chen, Cheung, and Yiu](https://www.cse.ust.hk/faculty/scc/publ/CS98-01-metamorphictesting.pdf)). |
| API/schema/contract checks | Observed requests and responses conform to declared shapes and protocol rules | OpenAPI/JSON Schema digest, exchange, validator output | Shape compatibility does not prove business semantics. JSON Schema is a declarative structure/constraint validator ([JSON Schema specification](https://json-schema.org/specification)); consumer/provider tests only establish the interactions they describe. |
| State/data invariants | The independently read post-state satisfies a business invariant at the observation boundary | canonical snapshot, invariant ID/expression, query, result | Snapshot timing and transactional boundary matter; one snapshot does not establish every intermediate state. Database constraints are stronger when the database enforces them on every write. |
| Event/audit history | These operations/events were recorded in this order with these identities | append-only operation history, domain events, offsets, hashes, state reconciliation | An application-owned audit log is still self-reported. Reconcile it against independently read state and record gaps, duplicate IDs, and ordering assumptions. |
| Fault injection/chaos | The stated safety/liveness property held or failed under this exact fault and workload schedule | fault type, target, start/stop, workload history, recovery evidence | It covers only exercised schedules and can corrupt the measurement environment. Chaosd supports process, network, resource, disk, host, and JVM faults and records recovery data ([Chaosd](https://chaos-mesh.org/docs/chaosd-overview/)). |
| History/consistency checking | A concurrent operation history is or is not compatible with a named consistency model | invocation/completion history, model/checker version, counterexample | Model scope must be explicit. Jepsen represents invocation/completion histories and checks safety/liveness properties, while emphasizing that experiments find errors rather than prove their absence ([Jepsen analysis](https://jepsen.io/services/analysis), [consistency models](https://jepsen.io/consistency/models)). |
| Code coverage | These source regions/branches/conditions executed | versioned coverage export bound to the tested binary | Reachability says nothing about assertion quality. LLVM distinguishes function, line, region, branch, and MC/DC coverage and documents raw-format compatibility limits ([Clang coverage](https://clang.llvm.org/docs/SourceBasedCodeCoverage.html)). |
| Mutation testing | The test/grader detected these deliberate semantic changes; survivors identify weak sensitivity | mutation report, mutant diff/operator/location, killing test | Equivalent or irrelevant mutants exist; execution cost is high. A surviving mutant means tests did not detect that change, while no-coverage is distinct ([PIT](https://pitest.org/quickstart/basic_concepts/), [Stryker metrics](https://stryker-mutator.io/docs/mutation-testing-elements/mutant-states-and-metrics/)). |
| Runtime verification | This finite observed trace satisfies, violates, or cannot yet decide a formal trace property | property/spec digest, normalized events, verdict, counterexample prefix | Incomplete traces can make verdicts unsound or inconclusive, and not all properties are monitorable. Runtime verification evaluates actual traces rather than all possible executions ([Reger and Havelund](https://pure.manchester.ac.uk/ws/portalfiles/portal/56919083/main.4.pdf)). |
| Model checking/formal proof | The bounded model or formal specification satisfies a property under stated assumptions | spec, configuration/bounds, tool version, proof/counterexample trace | This proves the model, not that generated code implements it. Implementation-to-model conformance remains a separate measurement. TLA+ distinguishes safety and liveness checking ([TLA+ safety](https://docs.tlapl.us/creating%3Asafety), [liveness](https://docs.tlapl.us/codebase%3Aliveness)). |
| Provenance/attestation | The evidence refers to this immutable subject and was produced by this identified instrument | in-toto statement/envelope, subject digest, predicate, signature | Provenance prevents subject/evidence confusion; it does not show correct behavior. in-toto statements bind predicates to artifact subjects ([attestation specification](https://github.com/in-toto/attestation/blob/main/spec/README.md)). |

## Practical additions for a Löyly regeneration cycle

### 1. Capture a canonical black-box operation history

For each external operation, record an invocation and completion with:

- stable operation ID and `@item-*` ID;
- actor/tenant and logical input, with secrets redacted;
- start/end monotonic time and wall-clock mapping;
- HTTP method/path/status and canonical request/response digests;
- trace ID/span ID when available;
- whether the operation timed out or its outcome is unknown.

Use Allure's structured HTTP exchange attachment when possible. Allure has a versioned
`application/vnd.allure.http+json` viewer for a complete request/response exchange
([Allure attachments](https://allurereport.org/docs/attachments/)). For concurrent or distributed
tests, retain the operation history separately so a checker can reason about overlaps rather
than inferring order from logs.

### 2. Check domain events against independent post-state

For booking-core, the strongest low-cost behavioral evidence is a three-way reconciliation:

```text
black-box command/result
        ↕ correlation by booking/operation ID
domain event stream
        ↕ independent read after quiescence
schedule + heater + billing projections
```

Express the important properties as named invariants, for example:

- one accepted booking identity appears in each required projection;
- one release removes or supersedes it consistently;
- rejected commands produce neither a booking event nor a derived-state change;
- replaying the same idempotency key does not create a second booking;
- rebuilding projections from the accepted event history yields the independently read state.

The grader, not the candidate, should own the invariant definitions and canonicalization. Store
both raw state and the normalized comparison so a human can audit the verdict.

### 3. Generate stateful and metamorphic journeys

Add an independent action model over configure/request/read/release. Generate sequences, check
the invariant after each action, and retain the minimal failing sequence. Useful metamorphic
relations include actor isolation, harmless reordering of independent bookings, idempotent
retries, and equivalence between incremental projection and full rebuild. A passing run means
only that the recorded cases did not find a counterexample; report case count, seed, shrink
result, and model digest.

### 4. Calibrate the instrument with mutations

Mutate a small set of high-risk domain behaviors—omit billing emission, update only two views,
accept a closed day, duplicate an idempotent request—and require the independent grader to fail.
This answers a different and crucial question: *would our evidence layer notice the defect we
care about?* Treat surviving mutations as instrument gaps, not candidate failures. Run this on
the accepted reference or grader calibration job, not in every candidate cycle initially.

### 5. Exercise faults and preserve recovery evidence

Only after healthy-path invariants work, introduce bounded failures such as process termination,
network delay/loss, duplicate/retried requests, clock skew where relevant, and storage pressure.
Record the exact fault interval alongside the operation history. Check both safety during the
fault and recovery afterward; “the service restarted” is weaker than “acknowledged bookings were
not lost and projections converged within the declared bound.”

### 6. Collect deep diagnostics on failure or by sampling

Packet capture, eBPF events, profiles, and deterministic replay are high-volume or
privilege-sensitive. Enable them on a failed item, an explicit diagnostic run, or a small sample
of passing cycles. They should explain a behavioral verdict, not determine it. Within Gondolin,
run kernel-level observers at the VM boundary, record their privileges and kernel identity, and
do not give the candidate the observer's control channel.

## Evidence-manifest integration

The current manifest's `producer`, `kind`, media type, digest, and optional item ID are a good
file inventory. To make heterogeneous instruments scientifically interpretable, each artifact
or artifact group should also carry a small **claim envelope**:

```clojure
{:claim-type :state-invariant       ; not inferred only from filename
 :claim-id "booking-projections-agree"
 :verdict :pass                     ; :fail, :inconclusive, or absent for diagnostics
 :scope {:item-id "@item-01-007"
         :operation-ids ["op-..."]}
 :subject {:digest "..." :runtime-image-digests ["..."]}
 :observer {:name "booking-core-black-box" :version "..." :digest "..."}
 :correlation {:trace-ids ["..."] :event-offsets [12 15]}
 :time {:clock "monotonic" :start-ns 0 :stop-ns 0}
 :collection {:complete? true :sampled? false :dropped 0 :redacted? true}
 :assumptions ["projection reads taken after quiescence"]
 :artifacts [{:path "..." :sha256 "..." :media-type "application/json"}]}
```

Four rules matter:

1. **Never infer correctness from artifact presence.** A trace file is diagnostic until an
   independent checker emits a verdict and names the property it checked.
2. **Represent `inconclusive`.** Missing spans, unknown command outcomes, dropped packets, or an
   incomplete audit history must not silently become pass or fail.
3. **Bind observer and subject.** Preserve candidate/runtime image digests and the grader,
   property/model, generator, fault-plan, and tool versions/digests.
4. **Record collection quality.** Sampling, dropped events, clock source, redaction, capture
   boundary, and start/stop interval determine what absence of evidence means.

Seal raw artifacts and claim envelopes into the durable manifest before projecting them into
Allure. Allure is the human-facing index, not the canonical store: its result JSON points to
attachment files and uses media types for rendering ([Allure result format](https://allurereport.org/docs/how-it-works-test-result-file/)). Attach item-scoped evidence to the item result, and expose
cycle-wide logs/profiles/manifests as global attachments where supported. Keep raw evidence
content-addressed outside the report so multiple items can refer to it without duplication.

## Recommended order

```text
Now        operation history + structured HTTP exchanges
           independent state snapshots + named booking invariants
           domain-event/projection reconciliation

Next       stateful/property and metamorphic journeys
           grader mutation calibration
           bounded fault schedules + recovery checks

On failure network/eBPF/profile capture and deterministic replay

Selective  coverage and mutation reports for implementation/test quality
           runtime verification for precise temporal invariants
           TLA+/model checking for genuinely concurrent protocol design
```

The confidence statement should remain explicit: a cycle can demonstrate that a named set of
properties held for the recorded operations, states, instrumentation completeness, and fault
schedules. It should not claim that collected telemetry proves the whole system correct.
