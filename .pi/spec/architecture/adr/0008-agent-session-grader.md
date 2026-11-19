# 0008: Grade through a bounded agent session

Status: Proposed (2026-11-18). [ADR 0003](0003-judge-scores-checklist-items.md) remains active until a replacement passes the instrument shadow and a fresh slice-06 cycle is accepted.

## Context

The accepted `cycle-runner/grade.v1` uses an ORC researcher and judge with a model endpoint. The operator wants ORC to launch both builder and grader agent processes through provider-neutral hosts while retaining Pi as the default. The legacy grading step already separates a fresh grading room from the builder and files item verdicts after grading. ORC must still own the cycle, checklist denominator, score events, deadlines, and durable evidence.

The `scope-impact/claude/beautiful-clarke-5778ir` branch demonstrates a resumable file-backed factory, a Claude worker, and a separate Claude grader command. Its useful part is the separate host lifecycle. It forked from old `master`, replaces the Clojure harness tools with TypeScript, and does not carry the accepted ORC/Grain runner. Its grader permits Bash on the host, so read-tool denials and an instruction do not physically prevent source or credential reads. The branch is evidence for a host command, not a tree to merge into the accepted runner.

## Options considered

| Option | Benefit | Cost or risk | Evidence or unknown |
| --- | --- | --- | --- |
| Keep `grade.v1` and bridge the model endpoint to Pi | Smallest change to the accepted runner | Keeps the researcher and judge coupled to one model provider | Current runner and ADR 0003 |
| ORC starts a grader host command through one provider-neutral contract | Codex, Pi, and Claude can grade under the same cycle rules | Requires a new workflow version and isolation verification for each provider host | `codex exec` is installed and supports noninteractive structured output; the legacy grader has a three-argument launcher |
| The operator launches a grader and hands in verdicts | Works for bootstrap cycles | An unattended ORC run cannot own the grading deadline or resume | Legacy `score.sh` and bootstrap cycles |

## Proposed decision

Introduce `cycle-runner/grade.v2` with one bounded grader session per checklist item. ORC creates the item room and hands the selected scenario, approved cut, public seam, rubric, model identity, deadline, and expected result path to a configured `LOYLY_GRADER_HOST`. Pi in Gondolin is the default host; another process host must keep the same contract. The host writes the legacy verdict row, a one-element `work/verdicts.json` array with `id`, `verdict` (`pass` or `fail`), and nonempty `evidence` describing what it did and observed. Its launch record separately names the model, instruction digest, session identity, and trace. ORC checks the item ID, shape, evidence, model independence, approved digest, completion, and deadline before it records `:judge/score-emitted` in the item's child tick: pass maps to score 1.0, fail to 0.0, and evidence becomes the score feedback. Missing or invalid results leave the item unreached; Measurement still files the full checklist and Allure projection. A command exit code or its final prose is never itself a verdict.

The provider host must demonstrate that the grader can drive the gated candidate through its public seam but cannot reach builder rooms, gate evidence, earlier verdicts, sibling grading rooms, unrelated repository files, or host credentials. The grader must not use candidate source to decide a verdict. The legacy and current VM grading rooms contain candidate files so the grader can launch the app; the source-use rule is enforced through instruction and trace review, not by denying reads of those files. A focused local `codex exec --sandbox workspace-write -C <empty room>` probe on 2026-11-18 found both an unrelated repository file and the Codex auth file readable to its shell (`test -r` exit 0 for each; neither file's contents were opened). Therefore that command alone cannot be the grader host. Codex can be the first provider only with a further isolation boundary; verification must include a denied forbidden read, a real seam probe, cancellation of the host command and its children, and a same-candidate shadow against the accepted grader and legacy score. The grader's model must differ from the builder's recorded model.

The sealed grader digest must cover the new workflow version, rubric, result schema, host policy, and selected model. Changing any of those requires a new approval. The builder command and build room remain as accepted while the grader boundary is changed and measured. The provided launch scripts dispatch to the existing Pi builder and bootstrap grader by default, or to an absolute executable host without shell evaluation. The builder launcher can already be supplied as `LOYLY_BUILDER_HOST`; the grader launcher does not change the accepted ORC grader until `grade.v2` is specified and regenerated. Dispatch is not an isolation boundary: each provider host must prove one.

## Rationale and consequences

| Driver | Evidence or constraint | Trade-off | Assessment |
| --- | --- | --- | --- |
| Agent choice | The operator wants Pi, Codex, and Claude to be interchangeable workers | Each provider host needs isolation and lifecycle verification | Proposed |
| Durable score | `cycle.v1` already owns the Grain run and Measurement receipt | ORC must validate external output before recording a score | Required |
| Black-box independence | ADR 0003 requires seam-only access and a different model | A local `codex exec` probe could read unrelated repo and auth paths | Further isolation required before acceptance |

For the first self-hosted successor, the accepted `cycle.v1` remains fixed while it builds the candidate; the candidate cannot score itself. The independent Pi black-box grader and a same-candidate shadow against the accepted or legacy instrument decide whether the new grader can be promoted. The earlier self-hosting stop in `decisions.md` is superseded only for this bounded run by the operator's 2026-11-18 request. This proposal does not change the accepted `grade.v1` or authorize a new cycle. Before regeneration, update the slice-06 seam, event model, and stamped scenarios together; compare the proposed grader with legacy behavior; then obtain approval of the exact selection and sealed grader digest required by `AGENTS.md`.
