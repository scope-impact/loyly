# impl/

Intentionally empty: the implementation is a build product, and you generate it from the
docs. The way in is `docs/wow/harness.md` §4 (the read order) and
`docs/plan/01-booking-core/` (the slice to build).

Whatever you build, the gate's contract is two executables here:

- `test.sh`: runs the whole suite, exits non-zero on any failure
- `start.sh PORT`: serves the app over HTTP on PORT

Then `../harness/verify.sh` ratifies the claim.
