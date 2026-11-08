# docs

The method as presented (`wow/`), the domain wiki (`wiki/`), the slices (`plan/`), and
findings brought in from outside.

`plan/` is the Löyly app's slice. Since 2026-11-08 it is also the benchmark the harness is
measured on, unchanged (`../.pi/spec/decisions.md`, "The switch to the new harness" 2). The
harness's own spec is not here: it is spec v2, under `../.pi/spec/`.

## Brought in from outside

- **[juxtcast-agentic-engineering-at-scale.md](juxtcast-agentic-engineering-at-scale.md)**:
  the JUXTCast episode "What Coding Agents Still Need From Us" with Chris Ford, mapped
  against this method under four headings: what resonates, what deepens it, what explains it
  better, and where we disagree. 107 cited timestamps, every quotation machine-checked
  against the transcript.

  The transcript itself lives at `~/Movies/transcripts/juxtcast-s7e1/`, outside any repo,
  because it is somebody else's material.

- **[harness-grid.html](harness-grid.html)**: Ford's two-axis harness taxonomy drawn as the
  2x2 it is, with his examples and our own mechanisms in each cell. Built for the
  presentation. Open it in a browser.

- **[ford-overlap.html](ford-overlap.html)**: his account against ours as a Venn, with the
  five places they actively conflict kept outside it, and a four-slot mark on each of our
  mechanisms saying which of the four implementations carries it. It is the page that holds
  the current, careful version of every claim below.

### The finding worth carrying into the presentation

Ford's axes are feed-forward against feedback, and normative against informative. Mapping
our own harness onto them says something we had not noticed: we are **normative-heavy and
perception-light**. We are strong at telling an agent whether it is right (checks, gates,
the oracle, the content probe) and weak where an agent is given senses and has to supply
its own judgment.

Every defect the sibling project kalibra found by looking at a running app, and could not
have found any other way, sits in that weak cell.

**Corrected 2026-09-01.** This section used to end with two larger claims. Both were
examined that day and both were cut back. Each is restated below before it is corrected,
because the presentation was built on them and a reader has to be able to see the position
move.

1. It said *the informative-feedback cell is empty* and that our only sensor is a person
   looking at a phone. Empty in what can be shown, not empty in what was built. The
   production pipeline this method was distilled from ran persona-driven exploratory
   missions: a plain-language user goal, a real interface driven through a browser toolbelt,
   a journey log and screenshots, and the result assessed by a **separate judging pass**
   rather than by the agent that produced it. It was retired on measured cost and not on
   principle, judging costing several times what generating cost against a weekly capacity
   limit that cannot be bought through. So our position there is a **loss, not an absence**,
   which is the harder thing to say and the more interesting one. It is also the one
   measured case we have against Ford's economics argument that the two-day improvement now
   costs an afternoon: this improvement was affordable to build and unaffordable to run.

2. It said the fence and the holdback *suggest a third axis that is ours, custody*. Half of
   that survives, and since the holdback was retired on 2026-09-02 the fence carries the
   visibility half alone. For **visibility** custody is derived rather than additional, because a
   previous run's output is feedback about one round that becomes feed-forward input to the
   next, so fencing it is the crossing noticing that a signal changed quadrant. For
   **independence** custody is genuinely additional, because neither axis can ask who
   authored the verdict, which is a question about provenance rather than about the timing
   or the bindingness of a signal. The claim is therefore **one axis and a half, not a third
   axis**, which is smaller and, in a room of Thoughtworks-adjacent people, more defensible.
   Argued in full in `~/common/projects/saunavuoro/docs/dtr/custody.md`, section "Is custody
   a third axis, or is it what the crossing requires?", and presented in
   [ford-overlap.html](ford-overlap.html).

Both corrections are argued at length, with the original claims left standing beside them,
in [juxtcast-agentic-engineering-at-scale.md](juxtcast-agentic-engineering-at-scale.md)
under "What deepens it", findings a and c.
