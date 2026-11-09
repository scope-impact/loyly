# Replay: one harness run's Grain and ORC events, as a page

One self-contained HTML page per run. Its first screen is the run as a story: the cycle's stages
in order (stamp, pin, checklist, self-check, fence, regenerate, install, gate, score,
scoreboard), one plain line each, the outcome and the duration. Replay rewinds and plays the
merged events of every store in time order, with real time compressed (a gap over a second grows
only with its logarithm, so the builder's minutes take a couple of seconds). Below, one lane per
store: each domain event is a sticky (a burst of one type is one sticky), with the ORC tick that
ran its command and the Grain commits folded under it; a click opens the payloads. Each sticky
links to its event on the published event-model page, `../index.html#ev-<context>-<event>`
(the replay is published as `replay/<name>.html` beside that `index.html`).

```bash
tools/spec/replay/build.sh <run-dir> <out.html> [--title <title>] [--spec <spec-root>]
node tools/spec/replay/layout.mjs <out.html> [--shots <dir>]   # after npm install in tools/spec/views
```

Needs `bb` and `sqlite3`; `--spec` defaults to `.pi/spec` of this checkout (event descriptions,
the command that produces each event).

## How it finds the stores

- `<run-dir>/runner-store.sqlite`: the cycle runner's own Grain store; ORC's workflow events
  (`sheet/*`) are in the same store.
- `<run-dir>/runs/<run-id>/stores/<context>.sqlite`: each context's store for that run, the lane
  named after the file. With several `runs/*/stores`, the newest is used (and a note is printed).

Each store's `events` table (`id`, `time`, `type`, `data`) is read with `sqlite3 -readonly`; the
`data` column is Grain's Fressian encoding, decoded by `src/replay/fressian.clj` (Babashka
cannot load org.fressian). `event_tags` is not read: the tags are also inside each body.

## Grouping

Per store, a domain event (any type outside `sheet/` and `grain/`) inside an ORC tick's window
(`sheet/tree-tick-started` to `-completed`) carries that tick. A domain event of another context
in a store is a relayed copy (dashed). The workflow definitions of a store are one grey sticky;
a tick that produced no domain event is its own grey sticky. A `grain/tx` joins the sticky of the
events it committed, a processor checkpoint the sticky of the event it ran on. Every event lands
in exactly one sticky. The stage table (`STAGES` in `src/replay/main.clj`) names the owner event
types that mark each stage; relayed copies do not count.

## Scrubbing (the page is public)

Before the payloads go into the page: absolute host paths (`/Users/…`, `/private/…`, `/tmp/…`,
`/var/folders/…`, `/home/…`) become `<run>/…` (under the run directory's name), `<repo>/…` (under
this checkout) or `<host>/<file name>`; fields named like a token, secret, password, credential,
API key, cookie, environment or transcript are dropped; strings shaped like tokens are redacted;
strings over 1200 characters are trimmed, with a marker. The footer counts each. The build fails
if `/Users/` or `/private/` survives anywhere in the page.

## Publishing

`tools/spec/publish.sh` publishes every checked-in `tools/spec/replay/*.html` as
`replay/<name>.html` on gh-pages.
