# Spec tools

The checks and the view for the Pi harness specs in `.pi/model/`: behaviour in Allium
(`*.allium`), topology in a Grain Event Model (`event-model/**/event_model.clj`,
`defeventmodel` forms), examples in Gherkin (`plan/NN-slug/spec.feature`, and
`drafts.feature` when there are drafts) tagged `@allium-<obligation>`. Ported from the Halla
Health `tools/grain-specs/` toolset and its `harness/src/allium.ts`. These are spec tools, run
in planning rounds; they never build or ratify an implementation, and they write nothing under
`.pi/`.

| Tool | What it checks or makes |
| --- | --- |
| `gates.sh` | Every gate below, in order, failing closed; each prints its line even when an earlier one fails |
| `allium-check.sh` | `allium check` and `allium analyse` of `.pi/model` as one set (imports resolve only within the set). One `CHECK` line per error or warning, one `ANALYSE` line per finding; info is not tracked |
| `grain-validate.clj` | Loads every `defeventmodel` under `.pi/model/event-model/` and runs Grain's structural validator (`{:structural-only true :strict true}`) and its Allium composition gate (every `:grain/allium` link resolves). `--dump <file>` writes the evaluated model as EDN for the page |
| `coverage.py` | Every must-cover Allium obligation is tagged on a scenario of a `spec.feature` or `drafts.feature`, and no tag names an unknown obligation. Prints `must-cover N \| covered N \| drafted N \| uncovered N \| unit-level N`, then the uncovered ids. `--report <file>` writes the full classification as JSON |
| `gwt-lint.py` | Every scenario of every `spec.feature` and `drafts.feature` has a Given, a When and a Then |
| `glossary-lint.py` | No term an `_Avoid_` list of `.pi/model/GLOSSARY.md` names appears, as an identifier or in prose, in `.pi/model/*.allium`, the harness's Grain Event Model (`event-model/loyly/`), `.pi/architecture.md`, or the ADRs. Lists the Gherkin's hits in `plan/` for the product owner without failing |
| `views/build.sh [date]` | Builds the event-model page, `views/dist/event-model.html`, from the specs |
| `views/check.sh` | Renders every view of the page (node smoke test), every Mermaid diagram (`mmdc`, skipped with a note if absent) and every top-level view in headless Chrome (skipped if absent) |

Gate 5 of `gates.sh` is the harness's own `bb harness/gen_checklist.clj`: the checklist derives
(every scenario stamped, 94 items today). Gate 6 is the glossary lint.

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
`python3`, and `node` for the views; `mmdc` and Chrome are optional.

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

`views/build.sh` evaluates the Event Model with Grain, runs `coverage.py --report`, extracts
the Allium, Gherkin and the C4 views of `.pi/architecture.md` (`views/extract.clj`), and
injects that JSON into `views/template.html`. The template holds layout only; the page is a
projection of the specs, and its coverage chips are the gate's own report, so the page and
the gate cannot disagree. Never edit the built page: change the specs (or the layout) and
rebuild. `dist/` is a build product.

The page follows the Halla Health event-model page, adapted to one bounded context:

- **Pi Harness** (home): the context card with its obligation coverage, the three layers, and
  C4 levels 1 and 2 from `.pi/architecture.md`.
- **The context**: one row per Allium file (aggregate) with its slice, actors, commands,
  events and counts; C4 level 3; policies and read models; every Grain flow as a numbered
  dynamic diagram; the actors and any boundary contract; open questions.
- **An aggregate** (`#mod-<file>`): the event-storming board (a cluster per rule, arrows by
  the state one rule sets and the next requires, the event that triggers, the policy that
  issues), the entity diagram and one state diagram per `transitions` block (arrows labelled
  with the rules, from any file), every rule as a review card, invariants and guarantees with
  their coverage, and the slice's scenarios not yet linked to a rule. A slice with no Allium
  file (`09-rpc-seam`) shows its scenarios by Gherkin `Rule:`.
- **A block** (every command, event, read model, query, policy, screen): its schema and links,
  the rule it carries out, what feeds it and what it feeds.
- **Review**: the coverage gate's line, unknown tags, drafts awaiting acceptance, uncovered
  obligations per file, the unit-level refusals set aside, open questions.

The rule card: **Given** (the facts that left the aggregate in the state the rule requires,
from the rules that ensure that state, or "nothing yet: this rule starts the lifecycle"),
**When** (the trigger), **Only if** (each `requires`, numbered, with its
`@allium-rule-failure.<Rule>.<n>` tag and its status: scenarios, draft, unit level or
uncovered), **Then** (creates, emits, sets), **Examples** (each tagged scenario, folded, with
its tags and build tier), and the source files. `#rule-<Rule>` opens a rule's aggregate.

```bash
tools/spec/views/build.sh && tools/spec/views/check.sh
open tools/spec/views/dist/event-model.html
```
