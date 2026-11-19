# Cycle 26 grading profile retry audit

The independent grader filed 62/62 behavioral passes. Two initial fixture captures were
retained rather than erased:

| Item | Initial attempt | Fresh attempt |
| --- | --- | --- |
| 06-070 | The VM watcher was given 40 seconds, above its 30-second limit. Re-running the profile reporter on `profile-events-original.jsonl` reports `failed`, with one problem: `grade.v1 ran but no live Gondolin VM was sampled`. | `profile-retry-070.jsonl` has a 30-second watcher that observed QEMU; the restarted run reused the builder session identity and filed its score. |
| 06-061 | The initial fixture allowed only one gate attempt, so it was not used for the verdict. | `profile-retry-061.jsonl` records the corrected two-attempt fixture and its Grain trace. |

`profile-events-reconciled.jsonl` contains the exact fresh retry events for 06-061 and
06-070. A filtered hash comparison shows every other case's event lines are identical
to `profile-events-original.jsonl` (`c7e2b961bd1bc6eb413fd5961b275b0cfaab346b28c860c15bf90e187c5f3db5`).
The filtered 06-061 lines hash to `dcc8c20806f0a3216b5bd00fe52c40cc0d37cfcb71932b1dcb931b6ddce5e391`,
the same as `profile-retry-061.jsonl`; 06-070 hashes to
`f107b3f0ff889632441b3f9be94ae096217789997168fbaee9fb1d657e6fdf81`,
the same as `profile-retry-070.jsonl`.

The filed `profile.json` binds the reconciled events hash
`a06f1c315934e551ea16e8bc602a60810124808ab3b9b3f647fae0ddcd8a9b8f`
and reports `clean`, 62 cases, zero problems, with a live JVM diagnostic. The original
events and their failed report remain under `original-profile/`; `surprises.json` names
both retries. Allure was verified against the final verdicts and clean filed profile.
This is a fresh successful capture, not an exception that changes a failed profile to
passed or changes a behavior verdict.
