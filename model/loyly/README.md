# Löyly product model

This directory defines the product being regenerated. Product behavior and truth remain
separate from the Pi harness that builds and measures a candidate.

- `booking-core.allium` defines observable booking behavior and business invariants.
- `booking_core_event_model.clj` defines the Grain command, event, projection, and query topology.
- `booking-core-measurement.allium` binds product events and projections to language-independent
  runtime observations used by an external grader.
- `spec.feature` provides the executable product acceptance contract.
- `booking-core-measurement.allium` defines the product-owned evidence requirements. It is
  the intended source of truth for evidence semantics, including correlation and candidate-
  fact-only authority. The current evaluator still reads `runtime-evidence-contract.json`;
  that JSON is a transitional adapter contract, not an independent source of truth, and
  should be generated from Allium or removed when the evaluator consumes Allium directly.

Stable `@item-*` identities in `spec.feature` select behavior from the whole-system product
oracle. Compatibility also requires the digest of the complete normalized Gherkin scenario
(Rule and Scenario names, steps, and Examples); an item identity alone is not a behavior
version. A measured slice therefore selects oracle entries without binding the oracle to every
file in this model directory.

Allium and the Grain event-model EDN remain richer design and composition views, traceable to
the Gherkin behavior they support. Refactoring those views without changing observable behavior
does not invalidate a product-oracle entry. If either view changes observable behavior, the
corresponding Gherkin scenario must change and therefore receives a new scenario digest. If the
views introduce observable behavior that has no Gherkin item, preflight reports specification
drift and refuses measured grading rather than silently expanding the slice.

Keep product specification separate from harness/oracle implementation:

- Evidence transport settings (ports, exporters, file formats) are harness runtime config,
  not product truth. Derive required evidence signals from the measurement model where
  practical; do not duplicate measurement rules in runtime config.
- The generic conformance evaluator is harness code. Any machine-readable contract it needs
  should be generated from the Allium measurement model, not maintained as a second spec.
- Grader implementations, grader calibration fixtures, and grader verdict logic are private
  black-box oracle assets. Do not commit them or put them in this product model tree. The
  harness consumes a pinned independently published oracle.
- Fixture servers are test infrastructure, not product behavior; keep them with the private
  oracle/test harness that owns their lifecycle.

The measurement binding may accept observations transported through Playwright HAR,
OpenTelemetry, eBPF, or black-box queries. Those transports do not become product truth and
cannot author the independent verdict.
