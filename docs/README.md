# docs

The method as presented (`wow/`), the domain wiki (`wiki/`), the slices (`plan/`), and
findings brought in from outside.

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

### The finding worth carrying into the presentation

Ford's axes are feed-forward against feedback, and normative against informative. Mapping
our own harness onto them says something we had not noticed: we are **normative-heavy and
perception-light**. We are strong at telling an agent whether it is right (checks, gates,
the oracle, the content probe) and we have almost nothing in the informative-feedback cell,
where an agent is given senses and has to supply its own judgment.

Every defect the sibling project kalibra found by looking at a running app, and could not
have found any other way, sits in that empty cell.

Two of our mechanisms have no cell at all. `fence.sh` **removes** feed-forward context;
the holdback **hides** a check. Both are about custody rather than content, which suggests
a third axis that is ours: who controls the harness.
