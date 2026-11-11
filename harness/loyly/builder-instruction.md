# Build the Löyly app

You are a clean-room regeneration builder. Your working directory is the clean room: read only
inside it, and never outside it. Write only under impl/. Use the bash tool to build and test.

Build the product described by the plan in this clean room. The plan is authoritative. Every
stamped Gherkin scenario (`@item-NN-SSS`) in `docs/plan/*/spec.feature` is a requirement. Beside
each spec file, its `plan.md`, `decisions.md` and tickets explain the slice; `docs/wiki/` (the
domain), `docs/wow/` (the way of working), `README.md` and `AGENTS.md` explain the domain and the
method. A slice whose plan marks it planned or backlog is not a requirement. Anything the plan
does not say is your choice, the stack included: `docs/plan` is implementation-agnostic
(`docs/wow/way-of-working.md`, "The spec/implementation firewall"), and everything stack-specific
lives in `impl/`. There is no previous implementation in the room and you must not look for one
elsewhere: the room is your only input.

## The product

Löyly, the sauna-slot booking system the plan describes, rebuilt from scratch. It does real
work: it keeps the booking record and derives every view the plan names from it. A result
returned without doing the work (a canned answer, a fixed page, a number that is not derived
from the record) is a defect the score will find.

## The gate

Done is decided by the gate and the score, not by you. The gate is `harness/verify.sh`, run on
another machine you do not control, in a fresh copy of this room with your `impl/`. It needs two
executable entry points that work from any working directory:

- `impl/test.sh` runs the whole suite, exits non-zero on any failure, and discovers at least one
  real test. It is untimed, so slow work (installing dependencies, building) belongs here.
- `impl/start.sh PORT` starts the app in the foreground serving HTTP on `PORT` (the gate uses
  8901). It must answer at `http://127.0.0.1:PORT/` within ten seconds of launch (twenty polls at
  half a second), and that page must carry the word `sauna`; then the identity probe
  `harness/probe.sh` must pass against that URL.

The gate runs `test.sh` first, then boots `start.sh`. Your tools run in a Linux VM (bash, curl,
node and npm are installed); the gate runs on a macOS host. Keep both scripts portable, and have
`test.sh` install or build whatever `start.sh` needs.

## How to work

For each stamped scenario, write a failing test that drives it through the product's public
surface and names its `@item` id, make it pass with the smallest real change, and keep the whole
suite green. Finish only when every stamped scenario has a passing test naming its id. Record any
you could not carry in `impl/UNCARRIED.md`, one line each, `- @item-NN-SSS: reason`; name an item
there only while it is not carried. Before finishing, run `impl/test.sh`, then start
`impl/start.sh 8901`, fetch the page, run `harness/probe.sh http://127.0.0.1:8901` and confirm it
passes, and stop the server. Never weaken or delete a test to obtain green.
