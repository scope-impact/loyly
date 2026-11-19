# Proposed stamped-plan import for a fresh ORC store

The failed parent compares its fresh sequential stamp (`06-001` through `06-064`) with the
approved cut (`06-001` through `06-076`, with retired gaps). Legacy
`harness/stamp_item_ids.clj` does not restamp an already tagged scenario and computes a new
number from the highest tag in the file. Legacy `gen_checklist.clj` reads the current tags.
The new contract must keep that distinction.

The smallest compatible extension is an optional `stamped` vector on
`specification/stamp-slice`, containing `{line, title, item-id}` for already tagged
scenarios. The command reads the source file and verifies that vector against its actual
tag lines in file order. It rejects an unstamped scenario, duplicate identity, wrong slice,
or a vector that differs from the file. It records `specification/scenario-stamped` for IDs
missing from its ledger with the **given** identity, leaving existing IDs alone. The current
`unstamped` path keeps its sequential behavior for authoring. The cycle runner supplies the
approved cut's tagged scenarios to this new path, then Measurement checks that every
checklist ID is known in the same store. No synthetic retired item is fabricated.

The successor's stamp effect can still be one leaf and one `stamp-slice` command per selected
slice. Its source file is the cut's stamped copy, not a stripped copy. Its identity comparison
uses the current cut's IDs, allowing gaps while refusing changed, missing or duplicate tags.
A prior ledger retains its historical IDs, including retired items, but the current checklist
contains only items in the selected cut. New IDs are imported only when the source file
actually carries them. The operator's approved commit and checklist digest remain the
selection authority.

Required behavior probes before acceptance:

1. Fresh store, 64 current slice-06 tags with retired gaps: stamp, pin, checklist and gate
   continue; the emitted/current IDs match the cut exactly.
2. Fresh store with one unstamped, duplicate or wrong-slice tag: halt before self-check or
   builder, with no checklist silently shortened.
3. Existing store with retired IDs and a newly added highest ID: preserve old ledger rows,
   import only the new ID, and checklist only current cut items.
4. Independent legacy shadow on the same cut: ordered checklist IDs, clean room, candidate,
   gate, verdict rows, denominator and scoreboard match. The candidate's new Pi grader host
   remains separately black-box graded.

This changes the Specification and Cycle runner contracts, so it needs a new spec round and
exact selection approval. The accepted cycle-26 code remains untouched. A legacy-driven
bootstrap can regenerate the corrected successor; its next cycle can then self-host.
