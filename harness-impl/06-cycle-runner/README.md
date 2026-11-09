# Cycle runner — slice 06

Clojure CLI, the pinned ORC engine, and Grain SQLite. The authoritative event model
is loaded from `../../.pi/spec/cycle-runner/event-model/` and checked by Grain's
strict boot guard. No other context's implementation is included.

- `./test.sh` builds application classes and runs the entire suite (including a
  fresh-process stdio identity check). Nonzero means failure.
- `./start.sh` serves JSON-RPC over stdio. It works from any working directory;
  diagnostics use stderr. Configure it with the `LOYLY_*` variables in the seam.
- `bb test/manual-identity.clj` replays the gate's three identity cases against a
  new store. `clojure -M test/audit-manual.clj` audits that store directly.

`src/runner/model.clj` registers and projects Grain building blocks.
`runtime.clj` runs named ORC workflows on that same store and tenant, with every
processor named for checkpointing. Workflow roots are sequences; legacy ORC
routing avoids competing terminal emitters for a leaf root. Command responses
wait for durable processor checkpoints and all completed ticks' traces, so later
queries inherit no outstanding workflow or checkpoint writes.
`effects.clj` performs repository, stamping, fencing, builder, gate and filing
operations. `contexts.clj` owns stdio peers and reads/relays their Grain handoffs.
The runner stops at verdicts; only handed-in verdicts can enter Measurement.

The suite's context programs and Grain-writing companion are exclusively under
`test/`. Most scenarios exercise the serialized JSON-RPC handler in-process with
real child context programs, repository operations and effects; the separate
stdio test checks the actual entrypoint, timing and transport. Every accepted
item has test metadata checked against the stamped selection. Test fixtures,
compiled classes and diagnostic evidence are generated under this directory.
