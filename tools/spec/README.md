# Spec tools

The checks and the view for the Pi harness specs in `.pi/model/`: behaviour in Allium
(`*.allium`), topology in a Grain Event Model (`event-model/**/event_model.clj`,
`defeventmodel` forms), examples in Gherkin (`plan/NN-slug/spec.feature`, and
`drafts.feature` when there are drafts) tagged `@allium-<obligation>`. Ported from the Halla
Health `tools/grain-specs/` toolset and its `harness/src/allium.ts`. These are spec tools, run
in planning rounds; they never build or ratify an implementation, and they write nothing under
`.pi/`.

`.pi/model/` is spec v1, kept as history. Spec v2, the maintained spec in `.pi/spec/`, has its
own gates in [`v2/`](v2/README.md). The event-model page (`views/`) renders spec v2 since
`b754644`; up to `6d42c8e` it rendered spec v1.

| Tool | What it checks or makes |
| --- | --- |
| `gates.sh` | Every gate below, in order, failing closed; each prints its line even when an earlier one fails |
| `allium-check.sh` | `allium check` and `allium analyse` of `.pi/model` as one set (imports resolve only within the set). One `CHECK` line per error or warning, one `ANALYSE` line per finding; info is not tracked |
| `grain-validate.clj` | Loads every `defeventmodel` under `.pi/model/event-model/` and runs Grain's structural validator (`{:structural-only true :strict true}`) and its Allium composition gate (every `:grain/allium` link resolves). `--dump <file>` writes the evaluated model as EDN for the page |
| `coverage.py` | Every must-cover Allium obligation is tagged on a scenario of a `spec.feature` or `drafts.feature`, and no tag names an unknown obligation. Prints `must-cover N \| covered N \| drafted N \| uncovered N \| unit-level N`, then the uncovered ids. `--report <file>` writes the full classification as JSON |
| `gwt-lint.py` | Every scenario of every `spec.feature` and `drafts.feature` has a Given, a When and a Then |
| `glossary-lint.py` | No term an `_Avoid_` list of `.pi/model/GLOSSARY.md` names appears, as an identifier or in prose, in `.pi/model/*.allium`, the harness's Grain Event Model (`event-model/loyly/`), `.pi/architecture.md`, or the ADRs. Lists the Gherkin's hits in `plan/` for the product owner without failing |
| `views/build.sh [date]` | Builds the event-model page, `views/dist/event-model.html`, from spec v2 in `.pi/spec/` (`views/render.clj`) |
| `views/check.sh` | Checks the built page (`views/check.clj`): every view present and non-empty, ids unique, every internal `#` link resolves, and, against `.pi/spec`, every context, handoff edge and the item count shown; every Mermaid diagram renders (`mmdc`, skipped with a note if absent); every top-level address shows its view in headless Chrome with no JavaScript error (skipped if absent); with `npm install` in `views/`, every view fits a phone (Playwright) |

Gate 5 of `gates.sh` is the harness's own `bb harness/gen_checklist.clj`: the checklist derives
(every scenario stamped) from the plan root `harness/config.sh` names, `.pi/plan/` (spec v2's)
since 2026-11-06; it was 94 spec-v1 items. Gate 6 is the glossary lint.

## The glossary lint

`GLOSSARY.md` is the authority for the model's words (ADR 0005). `glossary-lint.py` reads every
entry's `_Avoid_:` list (a parenthetical in an item is a note) and splits each line of the
linted files into phrases at punctuation and `/`, and each phrase into lowercased words at
camelCase, `_`, and `-`, dropping a plural `s`: `RegenerationCut`, `regeneration_cut`,
`regeneration-cut`, and "regeneration cut" are the same words. An avoided term is a finding
unless one occurrence of a glossary term, or of a name the seam publishes (an action, rpc
method, tool, or type value of `affordances.yaml`), covers it whole: "workspace" inside
"workspace generation" passes, "harness" inside "harness task" does not excuse it. A line that
says "formerly" or "avoid" may name an old term. ORC's declared `:sheet` copy
(`event-model/orc/`) is ORC's published language and is not linted. It prints one `AVOIDED`
line per finding and one `PLAN` line per Gherkin hit (`--quiet` omits those), then
`glossary: N terms, M avoided | K findings | P plan/ hits`; it exits 1 on any finding.

Needs `allium` (3.6), `bb`, the Clojure CLI and a JVM (Grain from the `grain/` submodule),
and `python3`; `mmdc` and Chrome are optional, for `views/check.sh`.

## Obligations: what must be covered

`coverage.py` runs `allium plan` on each `.pi/model/*.allium` file (in that folder, so `use`
resolves) and keeps, once each:

- `rule-success.<Rule>`: each rule;
- `rule-failure.<Rule>.<n>`: each `requires`, numbered in order, **less the unit-level ones**:
  a clause testing only the entity's own `status` or `path` (`x.status = y`), one half of an
  `exists X` / `not exists X` pair between sibling rules, and the `not exists X{..}` guard of
  a rule that creates `X` (a redelivery). A unit test checks those, not the black box;
- `invariant.<Name>`: each top-level invariant;
- `boundary.<Name>`: each contract `@invariant`, and `guarantee.<Name>`: each surface
  `@guarantee`, less those whose prose says `Verified by: gate <name>`.

Everything else `allium plan` derives (fields, transitions, enums, surface exposure) is
structure, checked by `allium check`.

## The tag convention

- An accepted scenario carries its obligations on its existing tag line, after `@item-NN-SSS`
  and its build tier, so no line number moves:
  `@item-02-001 @minimum @allium-rule-success.FenceCleanRoom @allium-rule-failure.FenceCleanRoom.3`.
- A proposed scenario goes in the slice's `drafts.feature`, tagged `@draft` and its
  `@allium-…` tags, with a comment naming the rule and the `Rule:` it belongs under. Only the
  product owner accepts a draft by moving it into `spec.feature`, where
  `harness/stamp_item_ids.clj` gives it its `@item` id.
- Append a new `requires` after the existing ones, so failure tags keep their numbers.
- With no tags yet, every must-cover obligation reports as uncovered; that is the honest state.

## The event-model page

`views/build.sh` runs `views/render.clj` (Babashka). It reads spec v2 into one map
(`views/extract.clj`): per context in `.pi/spec/<context>/`, its Allium, its Grain Event Model
fragments (merged by `tools/spec/v2/load.clj`), its handoffs, its `spec.feature` and
`drafts.feature`, and its slice number from `.pi/plan/`; plus the coverage gate's own report,
`tools/spec/v2/coverage.py --report`. It renders every view from that map at build time: one
pure function per view or component, spec data → Hiccup data → HTML, by
[Replicant](https://github.com/cjohansen/replicant) (`views/bb.edn`), which escapes all spec text.
Each context's views come from `views/context.clj`, loaded once per context; ids carry the
context's name, so the six contexts share one page. The result is one static file: every view
is a `<div class="view" id="…">`, and `views/page.css` (inlined) shows the one the address
targets, or home. The page is a projection of the specs, and its coverage chips are the gate's
own report, so the page and the gate cannot disagree. **Never edit the built page**: change the
specs (or `render.clj`, `context.clj`, `page.css`) and rebuild. `dist/` is a build product.

The page needs no JavaScript to read: views, links and `<details>` folding are HTML and CSS.
`views/page.js` (inlined) only draws the Mermaid diagrams of the view on screen (Mermaid, from
its CDN, is the one library), draws the event-storm arrows, and toggles a diagram's actual size.

- **Home**: the six contexts as cards, the handoffs between them (producer → event →
  consumer), and the three layers (Allium, Event Model, Gherkin).
- **A context** (`#ctx-<context>`): one row per Allium file, its handoffs, policies, timers,
  read models and queries, every flow as a dynamic diagram, the boundary, open questions.
- **An aggregate** (`#mod-<context>--<file>`): the event-storming board, entities and
  lifecycles, every rule as a review card with its refusals and Given/When/Then, invariants and
  guarantees with their coverage.
- **A block** (every command, event, read model, query, policy, screen): its schema and links,
  the rule it carries out, what feeds it and what it feeds.
- **Review**: the coverage gate's result per context, drafts awaiting acceptance, uncovered
  obligations, the unit-level and gate-verified ones, open questions.

`b754644` dropped the C4 views of `.pi/architecture.md`, which then still described spec v1.
Spec v1 (`.pi/model/`) was rendered by this tool up to `6d42c8e`; check that commit out to
rebuild the v1 page.

```bash
tools/spec/views/build.sh && tools/spec/views/check.sh
open tools/spec/views/dist/event-model.html
```

### App mode, for the review MCP App

```bash
bb --config tools/spec/views/bb.edn tools/spec/views/render.clj --mode app --out <path> \
   [--app-js <wiring.js>] [--threads <review.edn>] [--coverage <coverage.json>]
```

renders the same views plus review controls, all as plain elements a script wires by
attribute: buttons with `data-review-action` (`comment` | `discuss` | `accept`) and
`data-review-target`, each followed by a `<span class="threads" data-threads-for="<target>">`
badge. `--threads` pre-fills the badges from `{"rule:Name" 2 …}` or a vector of
`{:target … :status …}` threads (counted unless `:resolved`). `--app-js` inlines a script into
`<script id="review-app">` (empty without it). Page mode, the default, has none of this.

The spec-review MCP App in [`mcp/`](mcp/README.md) was built against the spec-v1 renderer and
does not work with the v2 page tool; it works from `6d42c8e`.

### Publishing

`tools/spec/publish.sh [remote] [--dry-run]` rebuilds and checks the page, then commits it as
`index.html` (with `.nojekyll`) on top of the remote's `gh-pages` branch and pushes it; GitHub
Pages serves that branch at its root (`scope-impact`: https://scope-impact.github.io/loyly/).
Every published version stays in the branch's history. `--dry-run` shows the commit without
pushing.
