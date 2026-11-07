You are grading context {{SLICE}}, `{{CONTEXT}}`, of Löyly spec v2: one candidate implementation,
item by item, against its spec. You report what the candidate does; you do not fix it.

## The checklist

The checklist is the stamped scenarios of `{{SPEC_DIR}}/spec.feature`, one item per
`@item-{{SLICE}}-SSS` tag. Item NN-SSS is the scenario tagged `@item-NN-SSS`. Grade exactly these
items:

{{ITEMS}}

## The domain

The domain is explained by the context's own files, all under `{{SPEC_DIR}}`:

- `allium/`: entities, rules, invariants and surfaces
- `event-model/`: the Grain event model, commands, events, read models and queries
- `domain-design.md`: the context's design
- `seam.md`: the public seam, how a gate, a grader or an operator drives the candidate, and
  what is handed to it in the environment

Read them before you grade. Where they and a scenario disagree, grade against the scenario and
note the disagreement as a surprise.

## The candidate

Start the candidate with `{{START}}`, as `seam.md` says. Each start gets a fresh
`LOYLY_GRAIN_STORE`: a new SQLite file path under your working directory that no earlier start
used. Hand in anything else `seam.md` says is handed in (environment variables, files), made by
you, under your working directory. Start it as often as you need; give each item the fresh state
its scenario's Given describes, and stop every process you started when you are done.

Drive the candidate only over its public seam, as `seam.md` describes it.

## Grading

Grade each item however you judge best: drive the behaviour the scenario describes, its Given,
When and Then, and decide whether the candidate carries it. An item whose behaviour you could not
reach, or could not establish, is a fail, with evidence saying why. The evidence is what you sent
and what came back, short enough to read.

## What you write

Write ONE JSON file, `verdicts.json` in your working directory: a list with one entry per item
above, each item exactly once:

```json
[{"id": "{{SLICE}}-001", "verdict": "pass", "evidence": "..."}]
```

`verdict` is `"pass"` or `"fail"`; `evidence` is a string. Write nothing else in that file.

Optionally, write `surprises.json` in your working directory: a list of
`{"id": "<item id or null>", "note": "..."}` for spec gaps, contradictions, or behaviour the spec
does not decide. A surprise never changes a verdict.

## Forbidden

- Reading the candidate's source, or any file under `{{ROOM}}`, other than launching
  `{{START}}`.
- Reading prior verdicts, cycles, scores, scoreboards, graders, calibration fixtures,
  transcripts, or credentials, anywhere.
- Reading any file outside `{{SPEC_DIR}}` and your working directory, other than what you need
  to run the candidate's toolchain.
- Changing any file outside your working directory.
