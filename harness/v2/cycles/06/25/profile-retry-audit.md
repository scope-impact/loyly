# Cycle 25 profile retry audit

The filed `profile.json` remains `failed`. Its sole problem is that the profile reporter
rejects captures whose case names are not checklist IDs. The grader used eight separate
fresh retry names: `06-001-retry`, `06-003-retry`, `06-017-retry`, `06-034-retry`,
`06-047-retry`, `06-053-retry`, `06-054-retry`, and `06-066-retry`. The raw
`work/profile-events.jsonl` and the original attempts remain in the ignored grading room.
The raw events SHA-256 is `355593f475ff4abd015758d62c4e3c294f9c101a21e54f951cf11d2fe540cf37`.
This audit does not alter the raw events, filed profile, verdicts, or score.

An independent read of the raw events found, for **each** retry: exactly one `before` and
one `after` capture in time order, every active capture between them, no owned Gondolin or
QEMU process before, no owned Gondolin, QEMU or runner process after, exactly one numeric
model-request mark, and either a Grain profile or a no-store reason. The four retries that
reached `grade.v1` (`001`, `053`, `054`, `066`) also had a live QEMU sample. All 61 canonical
checklist cases are marked clean in the filed report; a live JVM diagnostic was captured
for `06-050`. The report's only problem is its unknown-case set of those eight names.

The instrument should learn an explicit retry-attempt relation and validate retries instead
of treating them as checklist items or silently dropping them. Until an approved instrument
revision or explicit operator exception resolves this finding, the cycle-25 candidate is not
accepted under `harness/v2/REPAIR-06.md`'s clean-profile rule.
