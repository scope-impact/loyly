# 0005 OpenTelemetry

## Status

Proposed (2026-11-13). The backend is open.

## Context

A run is watched today through `watch.sh` and the builder's output. Its slowest parts were invisible until read
by hand: cycle 11's suite started about six JVMs per test, about 50 s each, over 45 minutes a
suite. Each part of the harness can emit OpenTelemetry:

- ORC: `start-telemetry-exporter!` exports its durable events through an `export-fn`, bounded
  and retried; no OTel built in.
- pi: `pi-telemetry` is vendor-neutral and maps onto OpenTelemetry through a small bridge; the builder host does
  not wire it yet.
- Claude Code: native, through `CLAUDE_CODE_ENABLE_TELEMETRY` and the `OTEL_*` variables.

## Decision

- One trace per cycle; the stages are child spans; the builder's and grader's sessions hang
  under their stage, `TRACEPARENT` passed down to them.
- ORC: an `export-fn` that turns tick and leaf starts and outcomes, and judge scores, into OTLP
  spans (OTLP/HTTP JSON, no Java SDK).
- pi: an OpenTelemetry bridge in `.pi/harness/builder-host` (model calls, tool calls, tokens).
- Claude Code: its own exporter, by environment.
- Until `cycle.v1` runs, the legacy shell harness (ADR 0006) passes `TRACEPARENT` and opens a
  span per stage.
- The backend is one OTLP collector with a UI, run in a Gondolin VM, so nothing new runs on the
  host. Jaeger (one binary) or Langfuse (ORC already ships a `langfuse` component) is open.

## Consequences

- A seam line for the regenerated contexts: a run exports its ticks over OTLP to
  `OTEL_EXPORTER_OTLP_ENDPOINT` when it is set. Diagnostic only: no stamped item.
- `watch.sh` becomes a view over traces.

## Evidence

- ORC `components/orc-service/src/ai/obney/orc/orc_service/core/telemetry_exporter.clj` at
  `af086675`; `@earendil-works/pi-telemetry` 1.5.1 README.
- Prototype `proto/orc-harness`: a ~40-line `export-fn` wrote OTLP/HTTP JSON; one cycle was one
  trace (the execute's correlation id) of 19 spans: tick, node spans parented by the tree, judge
  scores as zero-length child spans. 36 events offered, 36 accepted, 0 dropped.
  - Node names, types and parents are not in the events; the `export-fn` reads them from
    `get-nodes-for-sheet`. A node run again (`map-each`) needs a counter in its span id.
  - Model and tool calls are not ORC events; their spans need the pi bridge or the shim.
