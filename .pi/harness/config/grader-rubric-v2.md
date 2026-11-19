# One-item black-box grader rubric

Grade only the selected stamped scenario against the gated candidate. Read the scenario,
public seam and event model. Drive the candidate from fresh state through the public seam.
Do not inspect candidate source, tests, builder output, gate evidence or earlier verdicts.
Record the setup, request and observed response in evidence. Pass only when the observed
behavior satisfies every assertion of the scenario; otherwise fail with the observed
counterexample. If the item cannot be reached or the seam cannot be exercised, report the
failure honestly. The host writes a singleton `work/verdicts.json` row with `id`, `verdict`
(`pass` or `fail`) and nonempty `evidence`; final prose is not a verdict.
