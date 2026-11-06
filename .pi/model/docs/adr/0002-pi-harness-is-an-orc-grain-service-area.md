---
status: accepted
---

# Make the Pi harness a Grain service area inside ORC

ORC is already built on Grain. The Pi harness therefore participates in the same tenant-scoped
Grain runtime as the `:loyly.pi-harness` service area instead of maintaining a parallel event
system or bridging into ORC as though it were external.

Harness commands own cut composition, proposals, approvals, routing, candidate lineage, oracle
bindings, observations, and measurements. ORC's `:sheet/*` events remain the authoritative record
of workflow execution. Harness read models may consume those events to present a domain-specific
progress view, but they must not emit mirror progress facts. The generated product remains a
separate, language-independent subject and does not need to use Grain.

Full Grain adoption means using every applicable part of Grain's executable grammar and enforcing
the registered Event Model against the live runtime. It does not mean adding artificial periodic
tasks, Datastar, Postgres, distributed coordination, or retention merely to exercise optional
packages.

## Consequences

- All harness block names use the `:loyly.pi-harness/*` namespace.
- State changes use `defcommand`; immutable facts use `defevent`; projections use `defreadmodel`;
  public reads use `defquery`; asynchronous reactions use `defprocessor`.
- An approved preparation is handed to ORC through its Grain command boundary exactly once.
- ORC execution progress is projected from `:sheet/*` events, not copied into a harness event.
- ORC must register enough of its `:sheet` Event Model for strict cross-area edge validation.
- Strict Event Model validation runs at startup, and Allium trace-link validation runs in CI.
- A Grain construct with no real harness responsibility is omitted rather than represented by a
  no-op solely for coverage.

## Amendment, 2026-11-06

Two consequences above no longer hold; the decision itself stands.

- ORC registers no `:sheet` Event Model. The contract declares the ORC blocks the harness
  dispatches or projects in `event-model/orc/sheet/event_model.clj`, copied from ORC's pinned
  `orc-service` schemas, so strict cross-area edge validation resolves without an ORC change.
  The copy must stay in step with the pin.
- Approval alone starts nothing. A submitted task, not an approved preparation, is handed to
  ORC through its Grain command boundary exactly once (`DispatchSubmittedTaskToOrc`).

## Known risk, 2026-11-06

The declared `:sheet` copy in `event-model/orc/sheet/event_model.clj` is partial: it holds only
the command and five events the harness dispatches or projects. Inside ORC's process, Grain's
strict boot guard (`verify-or-throw!`) checks every live block against the registered model.
With this copy registered, ORC's other `:sheet` blocks would be live but unmodelled
(uncovered), and because `defeventmodel` registers by area, whichever `:sheet` registration
loads last replaces the other. The copy is therefore safe only while ORC registers no `:sheet`
model and strict boot runs without ORC's full catalog.

The fix belongs upstream: ORC registers its own complete `:sheet` Event Model, and the harness
`:require`s ORC's namespace instead of declaring a copy. The harness cannot make that change
here and does not assume it; until ORC ships it, the copy stays, pinned to `af086675`.
