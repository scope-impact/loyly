# Legacy trace

> A snapshot of round R2; later decisions are in [`decisions.md`](decisions.md).

Every behaviour of the legacy harness (`archive/legacy-harness/`) against spec v2 R2: kept
(**preserved**), kept as the product owner changed it (**changed-as-decided**,
`decisions.md`), written in R2 (**added**, `decisions.md` "to add" 1-8), or left out with
the reason (**dropped-with-reason**).

The source is the legacy spec `archive/legacy-harness/spec.feature` (all 70 `@harness-NNN`
items are cited below) and the scripts it does not specify (`fence.sh`, `verify.sh`,
`probe.sh`, `check.sh`, README). The alignment review's own list of 52 was not in the
repository, so the rows are rebuilt at the grain of one observable behaviour each; every
behaviour the review named (the 13 changes and 8 additions of `decisions.md`) is a row.

Allium paths are under `.pi/spec/<context>/allium/`. "Refusal n" is the n-th `requires` of
the rule.

| # | Legacy behaviour | Source | Status | Allium rule / requires / event |
|---|---|---|---|---|
| 1 | An untagged scenario gets the next free `@item-<slice>-<NNN>`: slice from the directory name verbatim, number zero padded to three digits, tag line above the heading | `@harness-001`, `002` | preserved | specification `StampFirstScenarios`, `StampSlice` (`item_identity`); `ScenarioReading.SliceFromTheDirectory`; event `scenario-stamped` |
| 2 | `Scenario Outline:` is stamped like `Scenario:`; `Scenario Template:` is not a scenario | `@harness-003` | added (to add 1) | specification `ScenarioReading.OutlineYesTemplateNo` |
| 3 | The next number is one past the highest in the file, so deleting the highest scenario frees its number | `@harness-004`, `010` | changed-as-decided (ids from a ledger that never goes down) | `Slice.next_number`; `StampSlice` ensures; invariant `NumbersBelowTheLedger`; guarantee `NeverRenumberedNeverReused` |
| 4 | Only an item tag in the contiguous run of tag lines above a heading stamps it; other tags do not; the last item tag wins | `@harness-005`, `019` | added (to add 1) | `ScenarioReading.ItemTagFromTheContiguousTagRun`, `OtherTagsDoNotStamp` |
| 5 | The stamper prints each changed file and the total | `@harness-006` | dropped-with-reason: a CLI report; v2 records one `scenario-stamped` per stamp and the `stamped-items` query shows them | (query `specification/stamped-items`) |
| 6 | Stamping is idempotent: a fully stamped file is not rewritten; stamping twice changes nothing | `@harness-007`, `008` | preserved | `StampSlice` refusal 4 (offered scenarios are exactly the unstamped ones); guarantee `NeverRenumberedNeverReused` |
| 7 | Rewording a scenario keeps its id | `@harness-009` | preserved | guarantee `NeverDerivedFromWording` |
| 8 | "No spec found" names where it looked and differs from "fully stamped" | `@harness-011` | added (to add 2) | `StampFirstScenarios`/`StampSlice` refusal 1; guarantee `NoSpecIsNotFullyStamped` |
| 9 | The checklist is one item per stamped scenario; the spec is its only author | `@harness-012`, README | preserved | measurement `DeriveChecklist` refusal 4; event `checklist-derived`; guarantee `DerivedNeverWritten` |
| 10 | The checklist goes to `--out` or stdout; the counts and skip lines go to stderr so stdout pipes | `@harness-013`, `028` | dropped-with-reason: file and stream plumbing of a CLI; the checklist is an event and a query | (query `measurement/checklist`) |
| 11 | Items are ordered by file, then position; never by id | `@harness-014` | preserved | `Checklist.items` (List, in file order); `DeriveChecklist` |
| 12 | An item carries the Rule above it, null when none | `@harness-015`, `016` | preserved | measurement value `StampedItem.rule_name` (optional) |
| 13 | The source is the file and line of the heading itself | `@harness-017` | preserved | measurement `StampedItem.source` |
| 14 | The name is the title with surrounding whitespace stripped | `@harness-018` | added (to add 1) | `ScenarioReading.TitleIsTrimmed` |
| 15 | An empty title is no item; a whitespace-only title is an item | `@harness-021`, `049` | added (to add 1) | `ScenarioReading.EmptyTitleIsNoScenario`, `WhitespaceTitleIsAScenario`; `DeriveChecklist` refusal 7 |
| 16 | The checklist's pending tag survives any line until a heading consumes it (a wart) | `@harness-020` | dropped-with-reason: a second reading of one file; the one reading keeps the stamper's tag run, so stamper and checklist cannot disagree | `ScenarioReading.ItemTagFromTheContiguousTagRun` |
| 17 | A spec file with no scenario yields an empty checklist (named a hole) | `@harness-022` | changed-as-decided (storm ✗ "Empty selection refused") | `DeriveChecklist` refusal 3; invariant `ChecklistNeverEmpty`; a slice with no scenario is still not an error for the stamper (`StampFirstScenarios`) |
| 18 | One unstamped scenario refuses the whole checklist; the answer names the stamper | `@harness-023` | preserved | `DeriveChecklist` refusal 2 |
| 19 | The refusal lists only the first file with an offender | `@harness-024` | changed-as-decided (every unstamped file listed) | `DeriveChecklist` refusal 2 |
| 20 | No spec, or every slice filtered out, refuses rather than emits an empty checklist | `@harness-025`, `026` | preserved | `DeriveChecklist` refusal 3 |
| 21 | A `planned` or `backlog` slice is skipped out loud | `@harness-027` | changed-as-decided (skipped slices stored) | `skipped-slices` on `checklist-derived`; guarantee `ShrunkDenominatorIsOnRecord` |
| 22 | Scores hold every checklist item in checklist order with verdict and evidence | `@harness-029` | preserved | `FileScore`; `Scoring.EveryChecklistItemInChecklistOrder`; event `measurement-filed` |
| 23 | Evidence is optional and defaults to empty | `@harness-030` | preserved | `Scoring.EvidenceDefaultsEmpty` |
| 24 | Duplicate verdicts: the last wins, silently | `@harness-031` | changed-as-decided (H26: superseded listed) | `Scoring.LastVerdictWinsAndTheRestAreListed`; `superseded` on `measurement-filed` |
| 25 | Zero verdicts (empty list or empty file) records nothing | `@harness-032`, `033` | preserved | `FileItemVerdicts` refusal 4; `FileScore` refusal 2 |
| 26 | A verdicts file that is not JSON, or not a list, is refused | `@harness-034`, `035` | dropped-with-reason: file parsing; verdicts arrive as a typed command whose schema refuses a malformed payload | (`file-item-verdicts` schema) |
| 27 | An unreached item is filed as fail with the reason in the evidence, and counted | `@harness-036` | changed-as-decided (H7: a `reason` field beside the verdict) | `Scoring.UnreachedIsAFailAndStaysCounted`, `ReasonBesideTheVerdict`; `reason unreached` |
| 28 | The report says how many were unreached | `@harness-037` | preserved | `unreached` on `measurement-filed` |
| 29 | A verdict for an id not on the checklist is fatal | `@harness-038` | preserved | `FileItemVerdicts` refusal 6 |
| 30 | A verdict neither pass nor fail stops the run; unknown ids are checked first | `@harness-039` | changed-as-decided (a bad verdict value stops it before an unknown item) | `FileItemVerdicts` refusal 5 before 6 |
| 31 | Scoring a cycle with no checklist is refused | `@harness-040` | preserved | `FileItemVerdicts` refusal 3 |
| 32 | A false record cannot be untaken: a refusal writes nothing | README (score.clj) | changed-as-decided (a refused score is never retaken within the cycle) | `FailScoreStage`; `FileItemVerdicts` refusals 7-8, `FileScore` 3-4; event `score-stage-failed`; policy `ConcludeOnFailedScoreStage` |
| 33 | The filed record repeats the checklist's item keys | `@harness-029` (shape) | changed-as-decided (joins the checklist by its digest) | `checklist-digest` on `measurement-filed` |
| 34 | The scoreboard rates every item the checklist carried; a cycle without a previous one is the baseline | `@harness-041` | preserved | `ReportScoreboard`; `previous_cycle_id` null |
| 35 | The previous cycle is the cycle number minus one | `@harness-042` | changed-as-decided (g: the last measured cycle of the same selection) | `Comparison.LastMeasuredCycleOfTheSameSelection`; `selection-id`; guarantee `ComparedWithTheLastMeasuredCycle` |
| 36 | A cycle with no record (or an empty one) is refused | `@harness-043` | preserved | `ReportScoreboard` refusal 1 |
| 37 | The cycle argument is required | `@harness-044` | dropped-with-reason: a CLI argument; `report-scoreboard`'s schema requires `cycle-id` | (`report-scoreboard` schema) |
| 38 | Both directions on the shared items, never a net | `@harness-045` | preserved | `Comparison.BothDirectionsOnTheSharedItemsNeverANet`; guarantee `BothDirectionsNeverANet`; `shared` on `scoreboard-reported` |
| 39 | A rate has one decimal rounded half to even; a zero denominator is "n/a" | `@harness-045` (format), `046`, `048` | added (to add 6) | `RateFormat.OneDecimalHalfToEven`, `ZeroDenominatorIsNotApplicable`; `rate` on `scoreboard-reported` |
| 40 | The moved item set is restated only when it moved | `@harness-047` | preserved | `ReportScoreboard` ensures `MovedItemSetRestated` if `item_set_moved` |
| 41 | The report's printed columns and line wraps | `@harness-045`, `047` | dropped-with-reason: presentation of a CLI's stdout; the event carries the numbers and the rendered rates | (`scoreboard-reported`) |
| 42 | Shared module files are byte identical across sibling repositories (the list, the vacuous skip, a fenced file noted, an undeclared file refused, a local file) | `@harness-050`, `059`, `062`, `064`, `068`, `069`, `070` | dropped-with-reason (to add 8): one harness generated from this spec, run by ORC; the harness digest names the exact harness a self-check ran | regeneration-cycle `domain-design.md`, "to add" 8 |
| 43 | Configuration complete: every variable in every repository, none empty but `APP_MARKER`, `PLAN_DIR` agrees with the tools, differing values tabled | `@harness-051`, `052`, `054`, `055` | dropped-with-reason (to add 8): the values are the approved preparation's and the room policy's, typed in their commands | regeneration-cycle `domain-design.md`, "to add" 8 |
| 44 | The identity probe must exist (and be executable): the gate refuses without it | `@harness-053`, verify.sh | added (to add 3) | `RecordGateAttempt` refusal 8 |
| 45 | The probe belongs to the instrument: it lives in `harness/`, outside the builder's reach, and is never compared | probe.sh, README, `@harness-053` | added (to add 4) | `RecordGateAttempt` refusal 9 (the probe the self-check checked); `GateRoom.IdentityProbeIsTheInstruments`; guarantee `ProbeOutOfReach` |
| 46 | A golden is required; a cycle with scores needs its scoreboard golden | `@harness-060`, `061` | added (to add 7) | `RecordHarnessSelfCheck` refusal 8; `HarnessSelfCheckRun.GoldenRequiredWhereItsCycleExists` |
| 47 | Boot budget of 10 s as 20 polls, declared together; the margin is reported on a green run | `@harness-056`, verify.sh [3] | preserved | gate-attempt config; boot fields on both verdicts; `GateAttempt.boot_margin_polls`; guarantee `TheMarginIsSaidOutLoud` |
| 48 | Not answering inside the budget fails the gate, naming the budget | `@harness-057` | changed-as-decided (3 gate attempts plus a repair builder) | `gate-attempt-failed` reason `boot-budget-exhausted` |
| 49 | The boot clock starts at launch, so a build inside the launch entrypoint counts | `@harness-058` | added (to add 5) | `GateRoom.BootClockStartsAtLaunch` |
| 50 | A missing test or launch entrypoint is refused before anything runs (the no-op suite) | verify.sh, README | changed-as-decided (3 gate attempts plus a repair builder) | `gate-attempt-failed` reason `contract-missing` |
| 51 | A port already answering is refused: nothing is booted or graded | verify.sh [1], README | preserved (R1 made it a failed attempt; reverted by b) | `RecordGateAttempt` refusal 10; guarantee `ARefusalSpendsNoAttempt` |
| 52 | A red suite is not ratified | verify.sh [2] | preserved | `gate-attempt-failed` reason `suite-red` |
| 53 | An empty suite discovery is refused | README (failure modes) | preserved | `gate-attempt-failed` reason `suite-discovery-empty` |
| 54 | A cheap name check (`APP_MARKER`) precedes the probe; an empty marker skips it out loud | verify.sh [4] | dropped-with-reason: a name is not evidence (legacy's own words); the required, instrument-owned probe is the identity proof | `RecordGateAttempt` refusals 8-9 |
| 55 | A probe that fails means the thing answering is not this application | verify.sh [4], probe.sh | preserved | `gate-attempt-failed` reason `identity-probe-failed` |
| 56 | The self-check runs before the fence; no room over a red instrument | fence.sh, `@harness-065` | preserved | `RecordHarnessSelfCheck` (before-fence); `ProveCleanRoom` refusal 1 |
| 57 | The self-check runs before the gate; nothing booted over a red instrument | verify.sh [0], `@harness-065` | preserved (R1 dropped it; reverted by H24) | `RecordHarnessSelfCheck` (before-gate) refusals 5-6; `RecordGateAttempt` refusal 7 |
| 58 | A commit hook: a red oracle refuses the commit, a fork fails only its commit, an existing hook is not overwritten | `@harness-063`, `064`, `067` | dropped-with-reason: repository workflow around hand-copied siblings; ORC runs the self-check on the cycle's own occasions and the harness digest replaces the fork check | `RecordHarnessSelfCheck` |
| 59 | A tool the check cannot find is a loud skip, not a failure | `@harness-066` | added (to add 7) | `skipped-checks` on the self-check; `HarnessSelfCheckRun.MissingToolIsALoudSkip` |
| 60 | The self-check reproduces every golden byte for byte | oracle.sh, README | preserved | `RecordHarnessSelfCheck` refusal 2; `HarnessSelfCheckRun.ReproducesEveryGolden` |
| 61 | The clean room is fresh: one room per run | fence.sh | preserved | `ProveCleanRoom` refusal 7; invariant `OneCleanRoomPerCycle` |
| 62 | An empty fenced-path list is refused | fence.sh | preserved | `prove-clean-room` schema (`:min 1`); invariant `AFenceHidesSomething` |
| 63 | A fenced path that matches nothing stops the fence | fence.sh | preserved | `ProveCleanRoom` refusal 2 |
| 64 | The room is one root commit and one commit | fence.sh | preserved | `ProveCleanRoom` refusals 3, 4 |
| 65 | Every fenced path is absent from the tree and unreachable in the object graph | fence.sh | preserved | `ProveCleanRoom` refusals 5, 6; `FenceProbe.ObjectGraphNotWorkingTree` |
| 66 | A fence failure exits 1 | fence.sh | changed-as-decided (a fence failure fails the cycle) | `FailCycle` stage fence, reason `fence_refused` |
| 67 | Scoring tools fenced in one sibling, not the others (left open) | README, `@harness-068` | changed-as-decided (H25: fenced) | `ProveCleanRoom` refusal 9 |
| 68 | A golden moves for one of three reasons; spec-grew is tested by an additions-only diff | README (goldens) | preserved | `MoveInstrumentGolden` refusals 3-4; enum `MoveReason`; invariant `SpecGrewIsAdditionsOnly` |
| 69 | A golden is re-recorded from the tools (`oracle.sh record`) | README, oracle.sh | changed-as-decided (written from the scenario; carries its scenario digest, h) | `WriteInstrumentGolden` refusal 2; `MoveInstrumentGolden` refusal 5; guarantee `WrittenFromTheScenarioNeverFromARun` |
| 70 | One gate run per verify; a red gate ends the build | verify.sh | changed-as-decided (3 gate attempts plus a repair builder) | `RecordRegeneratedCandidate` refusals 3-5, 7-9; policy `ConcludeOnExhaustedGateAttempts`; flow `gate-attempt-repaired` |

## Summary

| Status | Rows |
|---|---|
| preserved | 33 |
| changed-as-decided | 16 |
| added | 11 |
| dropped-with-reason | 10 |
| **total** | **70** |

The eight "to add" items of `decisions.md`: 1 rows 2, 4, 14, 15; 2 row 8; 3 row 44; 4 row
45; 5 row 49; 6 row 39; 7 rows 46, 59; 8 rows 42, 43 (dropped, reason in
`regeneration-cycle/domain-design.md`).
