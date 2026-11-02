# Löyly product model

This directory defines the product being regenerated. Product behavior and truth remain
separate from the Pi harness that builds and measures a candidate.

- `booking-core.allium` defines observable booking behavior and business invariants.
- `booking_core_event_model.clj` defines the Grain command, event, projection, and query topology.
- `booking-core-measurement.allium` binds product events and projections to language-independent
  runtime observations used by an external grader.
- `runtime-evidence-contract.json` is the project-owned input to the generic conformance
  evaluator. Its current contract captures facts for `@item-01-007`; it carries candidate
  facts and never a candidate verdict.
- `spec.feature` provides the executable product acceptance contract.

The measurement binding may accept observations transported through Playwright HAR,
OpenTelemetry, eBPF, or black-box queries. Those transports do not become product truth and
cannot author the independent verdict.
