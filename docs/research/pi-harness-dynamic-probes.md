# Language- and framework-agnostic dynamic probes for the Pi harness

**Status:** research
**Date:** 2026-11-02
**Question:** Which additional dynamic observations can the Pi harness capture without
depending on the candidate's implementation language or application framework, and how should
they appear beside `browser_exchange`, `trace_span`, `domain_event`, `projection_snapshot`,
`invariant_result`, and `runtime_diagnostic`?

## Conclusion

The useful dividing line is not “which tool produced this?” but **which externally observable
fact does the record claim?** A BPF program, for example, is a Linux-specific capture mechanism;
its output may be a process lifecycle, network flow, filesystem change, resource sample, or
kernel event. Encoding `ebpf_event` as a signal kind would leak one operating system into product
contracts and would say little about the evidence's meaning.

Add a small set of semantic signal kinds and make capture mechanism, platform, protocol, scope,
and loss accounting explicit metadata. The highest-value first wave is:

```allium
enum RuntimeSignalKind {
    browser_exchange |
    trace_span |
    domain_event |
    projection_snapshot |
    invariant_result |
    runtime_diagnostic |

    process_lifecycle |
    standard_stream_record |
    filesystem_change |
    network_flow |
    protocol_exchange |
    resource_sample |
    persistence_snapshot |
    log_record |
    metric_point |
    browser_performance_entry |
    accessibility_snapshot |
    visual_snapshot |
    collection_status
}
```

Two narrower kinds are worth adding only when the harness will actively use them:

```allium
    kernel_event |       -- syscall, scheduler, signal, OOM, security/audit event
    profile_sample       -- sampled stack/resource profile
    container_lifecycle  -- OCI create/start/stop/delete and namespace/cgroup identity
```

`protocol_exchange` should carry a typed `protocol` attribute such as `http`, `dns`, `tls`,
`postgresql`, or `mysql`; separate `dns_exchange`, `tls_handshake`, and `database_exchange` enum
members would create an ever-growing protocol taxonomy. `browser_exchange` remains useful because
it represents a user-agent-mediated operation and its DOM/navigation context, not merely HTTP.

No one probe is both portable, complete, high-semantic, and independent. The strongest Pi
instrument combines:

1. verifier-owned boundary observations (`browser_exchange`, `protocol_exchange`);
2. process, state, and resource facts collected outside the candidate;
3. optional candidate telemetry for semantic detail; and
4. an independent invariant evaluation that becomes `inconclusive` whenever required collection
   is sampled, lost, unbounded, or outside the declared observation scope.

That final rule is already present in the Pi [runtime evidence model](../../.pi/model/runtime-evidence.allium)
and should remain non-negotiable.

## Candidate additions

| Candidate `RuntimeSignalKind` | Dynamic probe examples | What it can establish | Portability and trust limit |
|---|---|---|---|
| `process_lifecycle` | Harness spawn/wait; process/container init PID; child create/exec/exit observation | The bound subject started, spawned a process, exited, was signaled, timed out, or was killed; exact exit status | Spawn/wait is broadly portable and verifier-owned. Complete descendant tracking is OS/container-specific; PID alone is reusable and is not a stable subject identity. |
| `standard_stream_record` | Capture candidate stdout/stderr at the process or container boundary, with stream, byte offset, and timestamps | The subject emitted these bytes on this stream | Highly portable and external, but self-reported, often unstructured, buffering-sensitive, and not proof that the stated event occurred. A merged stream cannot prove cross-stream ordering. |
| `filesystem_change` | Before/after digest tree; overlay upper-layer diff; filesystem notification; kernel audit | A path appeared, disappeared, changed metadata, or had different final content within a declared writable scope | Snapshot diff is portable and strong for final state but misses transient writes and read attempts. Notifications/audit are OS-specific, may overflow, and path aliases/hard links require care. |
| `network_flow` | Capture connection/open/close, direction, 5-tuple, byte/packet counts, resets, retransmits; verifier network namespace or pcap | The subject exchanged transport traffic with an endpoint during the interval | Language-neutral, but platform capture differs; NAT/proxies change identity, UDP has no connection, passive capture can drop packets, and TLS hides application content. |
| `protocol_exchange` | Verifier-owned HTTP reverse proxy; DNS stub resolver; TLS terminator/handshake observer; PostgreSQL/MySQL proxy | A protocol request/response or handshake crossed a named boundary with these decoded semantics | Strong at that boundary, not proof of internal processing or commit. It is protocol-specific, encrypted payload requires termination or keys, and an intermediary can perturb timing/behavior. |
| `resource_sample` | Wall/monotonic duration; CPU time; RSS/working set; I/O counters; open handles; cgroup CPU/memory/I/O/pressure; OS job accounting | Resource use or a declared resource bound over a specified interval | External and implementation-neutral. Semantics, units, update cadence, and accounting scope differ by OS; samples can miss peaks. Linux cgroup data is not a cross-platform API. |
| `persistence_snapshot` | Read-only database query; object-store listing; queue/topic offsets; canonical filesystem snapshot after quiescence | Point-in-time durable state, independently read and canonicalized | High semantic value when the contract owns the query/schema. It proves only the observed boundary and instant; replicas, isolation level, quiescence, and hidden state matter. Database adapters are product/protocol-specific. |
| `log_record` | Stdout/file/system-log parser; OTLP log receiver | A producer reported a timestamped body, severity, attributes, and optionally trace context | Collection can be external, but content is producer-authored. Parsing unstructured logs is fragile; filters, rotation, severity thresholds, and exporter queues can lose records. |
| `metric_point` | OTLP receiver; Prometheus/OpenMetrics scrape; cgroup/OS counter converted to a common envelope | A gauge, sum, histogram, or counter covered a declared time interval | Aggregation gives trends/bounds but loses individual history. Temporality, reset, scrape gaps, cardinality filtering, and aggregation must be retained. Candidate metrics are self-reported; OS metrics are independent but less semantic. |
| `browser_performance_entry` | Injected `PerformanceObserver`; Navigation Timing and Resource Timing capture; browser protocol performance events | Browser-measured navigation, resource, paint, layout, or long-task timing entries | Web-platform based and framework-neutral, but supported entry types vary. Buffers can fill and entries can be dropped; browser version, viewport, cache, network profile, and clock origin are part of the scope. |
| `accessibility_snapshot` | Browser accessibility tree; WebDriver accessibility locator; OS accessibility API adapter; W3C ACT-rule execution | Exposed role/name/state relationships or a named automated rule result at a particular UI state | Framework-neutral, but browser/OS accessibility APIs differ and Chromium's CDP Accessibility domain is experimental. Automated checks cannot establish complete WCAG conformance; ambiguous/manual criteria remain inconclusive. |
| `visual_snapshot` | WebDriver viewport/element screenshot; optional pixel/perceptual comparison | What the browser rendered for a fixed viewport/state, or a visual difference from a pinned baseline | Portable through WebDriver, but diagnostic unless a grader owns normalization and thresholds. Fonts, GPU, antialiasing, animation, locale, and device scale make pixels environment-sensitive. |
| `collection_status` | Observer start/stop markers; configured filter and scope; pcap/ETW/BPF/OTLP rejection counters; sequence gaps; buffer overflow events | Whether a required collection interval was armed, scoped to the subject, drained, and loss-free | This is evidence about the instrument, not the candidate. It is essential because a missing event is meaningful only when the source can demonstrate collection completeness. |
| `kernel_event` *(selective)* | Linux tracepoint/eBPF/kprobe, Windows ETW kernel providers, DTrace-style OS tracing | Syscall, scheduling, signal, OOM, security/audit, file, or socket activity attributed to the subject | Language-neutral but emphatically **not OS-neutral**. Usually privileged; schemas and attachment points vary; buffers lose events; kprobes and uprobes are more brittle and invasive than stable tracepoints. Mostly diagnostic. |
| `profile_sample` *(selective)* | OS sampler, `perf`, ETW sampling, browser/VM profiler, OTLP Profiles | Sampled CPU/off-CPU/allocation stacks or resource attribution | Statistical and diagnostic, not a complete event history. Symbols/unwinding vary and managed runtimes may need cooperation; OTLP Profiles is not yet as mature as traces/metrics/logs. |
| `container_lifecycle` *(selective)* | OCI runtime state and lifecycle hooks; runtime events; cgroup/namespace binding | The lifecycle and runtime identity of the isolated subject that produced other records | OCI gives a common runtime model on supported implementations, but hooks can alter execution and host support differs. Container identity does not itself prove process descendants or workload behavior. |

### Mapping common probes to the enum

| Probe/mechanism | Primary kind(s) | Existing kind when appropriate |
|---|---|---|
| Harness HTTP client/reverse proxy | `protocol_exchange` | `browser_exchange` when the operation is driven through a browser and needs page/navigation context |
| Packet capture/socket observer | `network_flow`; decoded traffic may additionally become `protocol_exchange` | `runtime_diagnostic` for raw pcap retained only to debug another claim |
| DNS stub or capture | `protocol_exchange {protocol: "dns"}` | — |
| TLS handshake/terminator | `protocol_exchange {protocol: "tls"}` | — |
| PostgreSQL/MySQL proxy | `protocol_exchange {protocol: "postgresql" | "mysql"}` | Use `persistence_snapshot` for independently queried post-state, not for traffic |
| eBPF/tracepoint/ETW/DTrace adapter | `kernel_event`, or a normalized `process_lifecycle`, `filesystem_change`, `network_flow`, or `resource_sample` | `runtime_diagnostic` if its schema is unstable or it only explains a failure |
| `/proc`, cgroup v2, Windows Job/ETW accounting | `resource_sample`, `process_lifecycle`, `collection_status` | — |
| OTLP receiver | `trace_span`, `log_record`, `metric_point`, `profile_sample` | A span event carrying an actual domain occurrence may also be normalized to `domain_event` only under a pinned mapping |
| Browser Performance APIs | `browser_performance_entry` | `browser_exchange` for the causal user operation |
| Accessibility tree/rule engine | `accessibility_snapshot` | `invariant_result` only for the independent grader's named rule evaluation |
| Screenshot | `visual_snapshot` | `runtime_diagnostic` when no independent comparison consumes it |

## Probe layers and realistic portability

### 1. Harness boundary: most portable and most independent

These probes require no candidate library: process execution, stdout/stderr pipes, verifier-owned
HTTP/browser actions, explicit health checks, controlled DNS, a reverse proxy, mounted writable
state, and before/after snapshots. HTTP is especially useful because its method, target, status,
headers, content, and framing have standard semantics independent of server implementation
([RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html)). WebDriver is explicitly a
platform- and language-neutral browser-control interface and standardizes screenshots
([WebDriver](https://www.w3.org/TR/webdriver2/)); WebDriver BiDi adds event-driven browser
observation over a WebSocket session ([WebDriver BiDi](https://www.w3.org/TR/webdriver-bidi/)).

This is the default acceptance layer. It has high authority for “the verifier sent/received this
at this boundary” but cannot by itself prove a database commit, internal event emission, or the
reason for a result.

### 2. Protocol boundary: portable across implementations, not across protocols

A protocol probe is language/framework agnostic because it observes a published wire contract,
but it needs one adapter per protocol. DNS, TLS, HTTP, PostgreSQL, and MySQL are not one generic
semantic stream. PostgreSQL documents its frontend/backend message flow
([PostgreSQL protocol flow](https://www.postgresql.org/docs/current/protocol-flow.html)); MySQL's
official protocol describes connection/authentication and command phases and supports TLS and
compression ([MySQL client/server protocol](https://dev.mysql.com/doc/dev/mysql-server/latest/PAGE_PROTOCOL.html)).

Prefer a verifier-controlled endpoint, proxy, or terminating gateway over blind packet decoding:

- a passive network probe can establish endpoints, timing, size, and transport outcomes;
- TLS 1.3 protects application data, so passive capture normally cannot establish HTTP or
  database semantics ([TLS 1.3, RFC 8446](https://www.rfc-editor.org/rfc/rfc8446.html));
- DNS may itself use encrypted transports such as DoT or DoH, again reducing passive visibility
  ([DoT, RFC 7858](https://www.rfc-editor.org/rfc/rfc7858.html),
  [DoH, RFC 8484](https://www.rfc-editor.org/rfc/rfc8484.html)); and
- termination exposes secrets and changes the path, so record proxy configuration/digest,
  certificate trust, redaction, and whether bypass routes were possible.

### 3. OS/kernel boundary: implementation-neutral but platform-specific

There is no universal cross-platform syscall or kernel-event probe. Use an adapter family with a
common Pi envelope:

- Linux: stable tracepoints where possible; eBPF/ftrace for filtering and attribution; `/proc`,
  cgroup v2, and Pressure Stall Information for resource facts. Linux calls tracepoints static,
  typed probe points ([tracepoint API](https://docs.kernel.org/core-api/tracepoint.html)); kprobes
  can miss nested hits and have architecture/function limitations
  ([Kprobes limitations](https://docs.kernel.org/trace/kprobes.html)).
- Windows: ETW separates controllers, providers, and consumers and can collect kernel or
  application events dynamically. Microsoft explicitly documents lost events caused by event or
  buffer size, slow consumers, and slow disks
  ([ETW overview and missing events](https://learn.microsoft.com/en-us/windows/win32/etw/about-event-tracing)).
- macOS/BSD/Solaris-family systems need their own supported tracing and accounting adapters; do
  not claim that a Linux BPF program is portable merely because the candidate binary is language
  agnostic.

Linux cgroup v2 exposes per-cgroup CPU, memory, I/O, and pressure/accounting files
([cgroup v2](https://docs.kernel.org/admin-guide/cgroup-v2.html)); PSI quantifies time lost to CPU,
memory, and I/O contention ([PSI](https://docs.kernel.org/accounting/psi.html)). These are strong
for isolation and resource-bound checks when the harness owns the cgroup, but their semantics are
Linux-specific. The OCI Runtime Specification provides common container states and a runtime PID,
and defines lifecycle hooks with their namespace context
([OCI runtime](https://specs.opencontainers.org/runtime-spec/runtime/),
[OCI configuration](https://specs.opencontainers.org/runtime-spec/config/)); it does not make
cgroup or kernel telemetry cross-platform.

### 4. Browser boundary: framework-neutral, browser-capability-dependent

Use standards first, then browser-specific extensions only when pinned and declared:

- WebDriver/WebDriver BiDi for navigation, user input, script evaluation, logs/network events
  exposed by the implementation, and screenshots;
- Performance Timeline/`PerformanceObserver` for typed performance entries. The standard keeps a
  dropped-entry count and Resource Timing specifies finite buffers and buffer-full handling
  ([Performance Timeline](https://www.w3.org/TR/performance-timeline/),
  [Resource Timing](https://www.w3.org/TR/resource-timing/));
- a browser accessibility tree or ACT rules for `accessibility_snapshot`. W3C ACT defines a
  standard format for accessibility test rules, but automated implementations may validly report
  “cannot tell,” and WCAG conformance combines automated and human evaluation
  ([ACT overview](https://www.w3.org/WAI/standards-guidelines/act/),
  [ACT implementations](https://www.w3.org/WAI/standards-guidelines/act/implementations/),
  [WCAG conformance](https://www.w3.org/WAI/WCAG22/Understanding/conformance));
- Chromium DevTools Protocol only as a pinned adapter. CDP can expose Network and Accessibility
  domains, but its tip-of-tree protocol explicitly offers no backward-compatibility guarantee and
  the Accessibility domain is experimental
  ([CDP](https://chromedevtools.github.io/devtools-protocol/),
  [CDP Accessibility](https://chromedevtools.github.io/devtools-protocol/tot/Accessibility/)).

Running page JavaScript to register `PerformanceObserver` is not candidate source instrumentation,
but it is still in-page observation and may be blocked, detected, or affect timing. Record the
injected observer digest and collect before application navigation when completeness matters.

### 5. Telemetry boundary: common wire format, usually producer-dependent

OTLP makes the receiver language-neutral; it does **not** make the signal independent of the
candidate. `trace_span`, `log_record`, `metric_point`, and `profile_sample` normally require the
process, an agent, or auto-instrumentation to emit meaningful data. Treat them as correlated
self-report unless an external observer created them.

OTLP is stable for traces, metrics, and logs, while profiles remain in development
([OTLP specification](https://opentelemetry.io/docs/specs/otlp/),
[OTel status](https://opentelemetry.io/docs/specs/status/)). The protocol reports partial success
and rejected record counts, but its acknowledgements cover only one sender/receiver hop, not
end-to-end delivery; retries can also create duplicates. Therefore:

- ingest OTLP at a harness-owned receiver and persist raw envelopes before further processing;
- use resource identity plus cycle/item/operation and W3C `traceparent` where available;
- carry OTLP rejected counts and exporter/queue loss into `dropped_count`;
- deduplicate by stable signal identity/content digest without erasing evidence of retries; and
- never interpret “no span/log” as “the action did not happen” unless instrumentation coverage and
  collection completeness are independently established.

W3C Trace Context standardizes `traceparent`/`tracestate` for distributed correlation, not proof
of origin, and permits sampling-bit changes and trace restarts
([Trace Context](https://www.w3.org/TR/trace-context/)). OpenTelemetry logs can contain trace and
span IDs, but its own specification notes that legacy log correlation is often incomplete
([OTel logging](https://opentelemetry.io/docs/specs/otel/logs/)).

## Evidence strength and intended use

| Evidence class | Authority | Semantic value | Best Pi use |
|---|---:|---:|---|
| Verifier-owned request/response and post-state read | High for the named boundary | High when the product contract defines the operation/query | Acceptance inputs to independent invariants |
| Process exit, filesystem diff, container/cgroup accounting | High for the isolated subject/scope | Low to medium | Safety/resource invariants and provenance |
| Protocol proxy/terminator | High for traffic that cannot bypass it | Medium to high | Acceptance when the protocol contract is pinned |
| Passive flow/packet/kernel event | High for events actually captured | Low without decoding/model | Failure diagnosis; narrow security/resource invariants |
| Browser performance/accessibility/visual state | High for the pinned browser environment | Medium; rule and environment dependent | UI/performance/accessibility-specific contracts |
| Candidate logs, metrics, traces, domain events | Producer-authored | Potentially very high | Correlated semantic input, never sole verdict authority |
| Independent `invariant_result` | Grader-owned | As high as its declared model and complete inputs | The verdict; retain all input signal identities |

Kernel/syscall volume is often seductive but semantically weak: `write()` proves bytes were passed
to a file descriptor, not that an invoice was durably committed or that a business invariant held.
Conversely, a candidate-authored `domain_event` has rich meaning but weaker authority. Pi should
prefer **independent low-level facts plus independently read semantic state**, using self-reported
telemetry to explain and correlate rather than to award a pass.

## Required envelope metadata

The existing `RuntimeSignal` fields are a good base. Add or require these in the signal content or
an associated capture descriptor:

- **subject:** candidate digest, runtime image digest, process start identity, container ID,
  cgroup/namespace identity, and executable digest where obtainable;
- **observer:** probe name/version/digest, configuration/filter digest, authority, privilege set,
  capture location, and perturbation class (`passive`, `active`, or `mediating`);
- **scope:** cycle/item/operation, start/stop monotonic timestamps, wall-clock mapping, host/VM,
  PID lineage, mount/network namespace, interfaces/ports/paths/protocols included and excluded;
- **correlation:** harness-generated operation ID first; then trace/span ID, browser navigation or
  request ID, network connection/stream ID, event ID/offset, transaction/snapshot ID as available;
- **ordering:** per-source monotonically increasing sequence and clock domain; never manufacture a
  total order across clocks without measured synchronization/error bounds;
- **collection quality:** expected/observed counts when knowable, sampled flag/rate, source and
  pipeline drop/reject counts, buffer size/high-water mark, parse failures, redactions, truncation,
  observer start/stop/drain success, and whether bypass was possible;
- **artifacts:** raw-content digest, normalized-content digest, media/schema version, and the
  normalizer/query/rule digest.

Identity must not rely on PID or 5-tuple alone. Bind PID to process start time and the harness-owned
process/container/cgroup; bind network records to capture namespace/interface and interval. Use a
harness-generated `operation_identity` at stimulus time. Accept `trace_identity` only as additional
correlation: trace context can be absent, restarted, sampled, or supplied by an untrusted client.

## Completeness rules by probe

1. **Arm before stimulus and drain after quiescence.** Emit verifier-owned `collection_status`
   start/stop records. A probe started after process launch cannot claim complete startup coverage.
2. **Account for source loss and pipeline loss separately.** Linux tracing buffers are finite;
   BPF ring-buffer reservation can fail even when not full in some contexts
   ([BPF ring buffer](https://docs.kernel.org/bpf/ringbuf.html)). ETW exposes lost-event summaries;
   OTLP exposes rejection/partial-success but not multi-hop end-to-end delivery.
3. **Do not overclaim snapshot completeness.** A before/after tree can completely cover the final
   contents of declared paths while remaining incomplete for transient access history.
4. **Treat unsupported visibility as unknown.** Encrypted payload, missing symbols, a browser entry
   type not implemented, inaccessible child namespace, or an unobservable replica is not zero.
5. **Make sampling explicit.** Profiles, trace sampling, metric aggregation, and periodic resource
   samples cannot support event-existence invariants unless the contract explicitly reasons about
   sampling.
6. **Fail closed on gaps.** A required source with any unbounded drop, sequence gap, overflow,
   parse failure, bypass path, or failed drain gives `complete = false`; the current Pi rule then
   makes the invariant `inconclusive`, not pass.

## Isolation and privilege

Run observers in a harness-owned execution room outside the candidate's writable and control
boundary. The candidate must not be able to stop the observer, rewrite raw artifacts, change
filters, access termination keys, or forge `collection_status`. Mount evidence output write-only
from the observer and absent/read-only from the candidate, then seal it by digest before grading.

Use the least privileged probe that answers the contract:

1. verifier HTTP/browser/database reads and process pipes;
2. container/runtime/cgroup read-only accounting and scoped network proxy;
3. scoped OS event session or namespace capture;
4. privileged kernel probes only for a named requirement or failure diagnosis.

Kernel probes expand the trusted computing base and attack surface. Prefer documented stable
tracepoints over arbitrary kprobes, filter by the harness-owned cgroup/process lineage, pin kernel
and probe identities, and record every attachment failure. A privileged observer sharing the host
with unrelated workloads also creates confidentiality risk; the strongest isolation is a dedicated
VM with evidence extraction through a narrow harness channel.

Protocol and browser evidence can contain credentials, cookies, request bodies, database values,
and personal data. Redact deterministically in a verifier-owned normalizer while preserving a
digest of restricted raw evidence when policy permits. Redaction itself is a transformation whose
version/digest and truncation effects belong in the evidence envelope.

## Recommended Pi sequence

**First:** add `process_lifecycle`, `standard_stream_record`, `protocol_exchange`,
`persistence_snapshot`, `resource_sample`, and `collection_status`. They are cheap, externally
owned, and immediately strengthen subject binding and completeness.

**For web items:** add `browser_performance_entry`, `accessibility_snapshot`, and
`visual_snapshot`, always pinned to browser/environment and consumed only by named product rules.

**For semantic telemetry:** add `log_record` and `metric_point`; keep `trace_span`, `domain_event`,
and `projection_snapshot`. Collect OTLP at a harness-owned endpoint, but preserve producer
authority and loss accounting.

**On failure or for explicit non-functional contracts:** enable `network_flow`, `kernel_event`,
and `profile_sample`. They are excellent explanation and narrow resource/security evidence, but
their privilege, volume, platform coupling, and weak business semantics make them poor default
acceptance oracles.

This produces a language- and framework-neutral instrument interface without pretending the
underlying capture implementations are universally portable. Product contracts name semantic
facts; platform adapters capture them; independent graders decide only from complete, digest-bound
evidence.
