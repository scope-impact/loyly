# Runtime evidence contract

Each project may define `model/<project>/runtime-evidence-contract.json`. The pinned
project model—not ORC or the evaluator—supplies every domain identity and expectation.
The document contains a version, the evidence-root environment variable exposed to the
candidate, and a `contracts` array with one entry per measurable item.

The generic evaluator accepts:

- `itemId` and an evidence-root-relative JSONL `relativePath`;
- a configurable discriminator field and allowed record kinds;
- exact record counts, required fields, fixed values, and exact per-field value sets;
- one declared anchor kind, member kinds, and correlation fields;
- named `fields-equal` invariants across declared record kinds;
- forbidden candidate-authored verdict fields and a success evidence message.

Its public command is:

```text
runtime-conformance.py --evidence-root <root> \
  --item <item-id> [--item <item-id> ...] \
  <pinned-project-contract>
```

It emits the existing JSON verdict list. Every requested item produces exactly one `pass`
or `fail`; missing contracts, evidence, kinds, fields, records, correlations, or invariants
fail closed. The evaluator must not contain project, event, projection, or domain-field names.

This contract generalizes deterministic JSONL conformance. It does not claim semantic OTLP,
collection-completeness accounting, or `inconclusive` outcomes.
