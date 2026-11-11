# Spec views: the drill-down page of an Allium + Grain + Gherkin spec

One static page per project, built with Babashka and Replicant, that a person reviews top-down.
Its first screen is the title, one plain sentence of what the system does, and the event storm.
L0 the event storm (every domain event, in time order, one band per bounded context), L1 each
context's event model as swimlanes, L2 a spec card per rule (its title and plain sentence, with no source references; the
rule as Given / When / Refused if / Then, each refusal with a coverage chip after its words
(✓ the item that checks it, ◇ a unit test or gate, ○ a draft, ✗ no example) and each handoff
event marked with the context that reads it; then its examples as prose, the happy path first
and the rest folded),
L3 under the hood (Allium, Grain EDN, flows, handoffs), folded; the architecture (handoffs, C4)
beside it, and a Review view. Everything is derived from the spec files; nothing is hand-written.

## Use it in another project

```clojure
;; bb.edn of the other project
{:deps {loyly/spec-views {:git/url "https://github.com/scope-impact/loyly" :git/sha "<sha>"
                          :deps/root "tools/spec/views"}}
 :tasks {spec-page (apply (requiring-resolve 'specviews.main/-main) *command-line-args*)}}
```

```bash
bb spec-page --config spec-views.edn                  # dist/event-model.html, review.html, story.svg
bb spec-page --config spec-views.edn --base v1.2      # review.html marks what changed since v1.2
```

Here: `tools/spec/views/build.sh [--base <ref>]` (the Löyly config, `project.edn`), then
`tools/spec/views/check.sh`. `example/` is a minimal project; check.sh builds it too.

## project.edn

| Key | What it says |
|---|---|
| `:title` `:page-title` `:review-title` | crumb name; the page's title and h1; review.html's title (2 to 4 words) |
| `:root` | project root, relative to the config file (default: its git top level) |
| `:spec-root` | the directory holding one directory per bounded context |
| `:contexts` | `:discover` (every directory with an Allium dir) or a vector of names, in order |
| `:files` | per-context layout: `:allium` `:event-model` dirs, `:handoffs` `:spec` `:drafts` files |
| `:plan-dir` | optional: `NN-<context>` links that number and order the contexts |
| `:item-tag` `:tiers` | the stamped item tag prefix (`@item-`); the build-tier tags |
| `:acronyms` | words `human` keeps upper case (`{"orc" "ORC"}`) |
| `:glossary` | optional glossary path (`**Term**:` entries) |
| `:architecture` | optional markdown with `### Level N: …` C4 views in mermaid blocks |
| `:coverage` | optional command that writes the coverage JSON to the path appended |
| `:out-dir` `:outputs` | where the page, review page and story SVG go, and their names |
| `:publish` | `{:remote :branch}` read by `tools/spec/publish.sh` |
| `:site-pages` | optional: the other published pages, `[{:label :dir :href}]`; every `<dir>/*.html` is linked from the site menu above every view as `<href>/<file>`, labelled with its `<title>` (left out of review.html) |
| `:story` | `:after` event pairs the event models do not order; `:unhappy`, a regex of unhappy events (drawn in a lighter sub-row of their band); `:orchestrator`, the context that only relays, drawn as a thin band at the top |
| `:text` | the project's own sentences, with `{contexts}` filled in: `:summary`, the one plain sentence of what the system does under the title on the first screen; `:intro` and the notes, for "About this page" and the views below |

A missing optional file switches its section off: no C4 and no handoffs, no architecture view;
no coverage, no coverage figures.
