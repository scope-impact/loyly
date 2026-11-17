Cycle 25 bounded repair

Base: instrument-provided accepted-base, pinned digest
947d49609d24e2325fcb027a695b03c3b68c2b9cd9337a3401d39cf58df5231a.

Selected behaviors carried: @item-06-047 and @item-06-066.

- effects.clj retains the plan-facing source separately from the resolved cut
  path, and uses it in checklist items.
- main.clj supplies the selected cut’s public scenario, seam and event-model
  documents as researcher request context. The pinned ORC RLM keeps structured
  item values behind get-input; this context includes all selected public
  contracts while the item identifies the scenario being graded. The existing
  per-item Gondolin VM and shell remain in place.
- test/loyly/test_runner.clj checks checklist event sources, the first researcher
  request, and observed Linux grading-room contents and isolation through the
  public JSON-RPC seam, with the real Gondolin dependency.

Validation: test.sh: 45 tests, 406 assertions, 0 failures, 0 errors;
whole-suite reported runtime 164.577009458 seconds.
Fresh-store start.sh stdio identity: passed, 12.361866083 seconds. Observed
not-a-commit and builder-image-missing refusals, an unapproved-grader halt after
stamp/pin/checklist, then an approved fixture cycle ending passed with one score.

These are builder checks only. No independent gate or grader result is claimed.
The inherited UNCARRIED.md entry concerns an unselected fixture, not this repair.
