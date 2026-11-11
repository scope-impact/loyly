# Pi harness architecture

The architecture of spec v2 ([`.pi/spec/`](spec/README.md)) and of the harness as it runs since
the switch (`spec/decisions.md`, "The switch to the new harness"): the slice-06 cycle runner (its
cycle 03 candidate) driving the five contexts of slices 01 to 05, with `pi` and its Gondolin
backend as the builder and the legacy harness kept as the reference. Every element below is
taken from `.pi/spec/<context>/` (README, `domain-design.md`, `seam.md`, `event-model/`),
`spec/decisions.md`, `harness/README.md` and AGENTS.md. Where this page and the spec disagree,
the spec wins.

## C4 views

Three levels, with one component view (level 3) per context. Every context is its own runnable JVM with its own store; ORC and Grain are
libraries inside each JVM, so they appear only as components. "Technology bindings" below
names the technology behind each term.

### Level 1: System Context

Who uses the harness and what it depends on.

```mermaid

flowchart TB
  operator(["<b>Operator</b><br/>[Person]<br/>A person, or the main session acting for one; the final judge of a shadow run"])
  harness["<b>Pi harness, spec v2</b><br/>[Software System]<br/>The cycle runner and the five contexts: runs one regeneration cycle, fence to scoreboard, and records each fact in the context that owns it"]
  repo["<b>Project repository</b><br/>[Software System, external: git]<br/>The cut's commit: the stamped plan, the harness configuration, the self-check and the identity probe"]
  builder["<b>Builder</b><br/>[Software System, provided]<br/>pi with its Gondolin backend, one VM room per run; launched and waited for"]
  model["<b>Model provider</b><br/>[Software System, external]<br/>The existing Codex login the builder uses"]
  candidate["<b>Candidate</b><br/>[Software System, regenerated]<br/>The product the builder left in its candidate scope; reached only over its public seam"]
  grading["<b>Grading agent</b><br/>[Software System, external]<br/>Legacy step 5: one grading subagent per candidate, black-box; writes one verdicts file"]
  legacy["<b>Legacy harness</b><br/>[Software System, reference]<br/>fence.sh, verify.sh, score.clj, scoreboard.clj; never deleted"]

  operator -- "Starts a run, hands in its verdicts, reads its progress<br/>[JSON-RPC over stdio]" --> harness
  operator -- "Launches one per candidate" --> grading
  harness -- "Reads the cut, fences a copy of it<br/>[git]" --> repo
  harness -- "Launches with the builder room and the instruction; waits" --> builder
  builder -- "Model turns" --> model
  builder -- "Writes the candidate scope" --> candidate
  harness -- "Gates a copy on the host<br/>(legacy verify.sh order)" --> candidate
  grading -- "Drives over its public seam" --> candidate
  operator -. "Shadow run: same cut, same candidate, same verdicts file" .-> legacy
  legacy -. "Fences the same cut" .-> repo

  class operator person
  class harness system
  class repo,builder,model,candidate,grading,legacy external
  classDef person fill:#08427b,stroke:#052e56,color:#fff
  classDef system fill:#1168bd,stroke:#0b4884,color:#fff
  classDef external fill:#8a8a8a,stroke:#6b6b6b,color:#fff
  classDef container fill:#438dd5,stroke:#2e6295,color:#fff
  classDef component fill:#85bbf0,stroke:#5d82a8,color:#000
  classDef boundary fill:none,stroke:#444,stroke-dasharray:6 4,color:#444
```

- The **operator** speaks only the runner's seam (`cycle-runner/seam.md`, "Methods"). The
  methods are synchronous: whoever watches a live run reads the runner's Grain store and the pi
  session directly (`spec/decisions.md`, "Spec round 7b" 5).
- The harness **never grades**: the verdicts file is handed in. In a shadow run the same file
  goes unchanged to the runner and to legacy `score.clj` (`cycle-runner/domain-design.md`,
  "Boundaries").
- The **builder's exit is not evidence**; the gate is.

### Level 2: Container

The runnable parts of the harness and the stores they use.

```mermaid

flowchart LR
  operator(["<b>Operator</b><br/>[Person]"])

  subgraph h ["Pi harness, spec v2 [Software System]"]
    runner["<b>Cycle runner</b><br/>[Container: Clojure on ORC and Grain; slice 06, start.sh]<br/>Performs the effects E1 to E8, records each through a context's seam, relays the handoffs"]
    rstore[("<b>Runner store</b><br/>[Container: Grain event-store-v3, SQLite, LOYLY_GRAIN_STORE]<br/>Its own events only; ORC on the same file and tenant")]
    spec["<b>Specification</b><br/>[Container: Clojure on ORC and Grain; slice 01]<br/>stamp-slice"]
    prep["<b>Cycle preparation</b><br/>[Container: Clojure on ORC and Grain; slice 05]<br/>pin-source-cut"]
    grader["<b>Grader lifecycle</b><br/>[Container: Clojure on ORC and Grain; slice 03]<br/>instrument goldens"]
    meas["<b>Measurement</b><br/>[Container: Clojure on ORC and Grain; slice 02]<br/>checklist, verdicts, score, scoreboard"]
    regen["<b>Regeneration cycle</b><br/>[Container: Clojure on ORC and Grain; slice 04]<br/>self-check, clean room, attempts, conclusion"]
    runs[("<b>Run directory</b><br/>[Container: files under LOYLY_RUNS_DIR]<br/>The five contexts' SQLite Grain stores, the clean room, builder rooms, gate rooms, gate evidence")]
    gate["<b>Gate room</b><br/>[Container: a directory on the host]<br/>A copy of the candidate; its test.sh and start.sh run here, writable"]
  end

  vm["<b>Builder VM</b><br/>[Container: pi with its Gondolin backend; provided]<br/>Mounts one builder room; writes the candidate scope"]
  repo["<b>Project repository</b><br/>[Software System, git]"]

  operator -- "start-run, hand-in-verdicts, run-progress<br/>[JSON-RPC over stdio]" --> runner
  runner -- "Appends its facts; ORC sheet facts" --> rstore
  runner -- "Starts for the run, records effects, hands in handoffs<br/>[JSON-RPC over stdio]" --> spec
  runner -- "[JSON-RPC over stdio]" --> prep
  runner -- "[JSON-RPC over stdio]" --> grader
  runner -- "[JSON-RPC over stdio]" --> meas
  runner -- "[JSON-RPC over stdio]" --> regen
  spec -- "Own store and tenant" --> runs
  prep -- "Own store and tenant" --> runs
  grader -- "Own store and tenant" --> runs
  meas -- "Own store and tenant" --> runs
  regen -- "Own store and tenant" --> runs
  runner -- "Reads each producer's handoffs from its store; fences the clean room" --> runs
  runner -- "git show, rev-parse, fence.sh" --> repo
  runner -- "LOYLY_BUILDER_CMD builder-room instruction; waits" --> vm
  vm -- "Reads the clean room, writes APP_DIR" --> runs
  runner -- "Installs a copy, runs the gate" --> gate

  class operator person
  class runner,spec,prep,grader,meas,regen,gate,vm container
  class rstore,runs container
  class repo external
  class h boundary
  classDef person fill:#08427b,stroke:#052e56,color:#fff
  classDef system fill:#1168bd,stroke:#0b4884,color:#fff
  classDef external fill:#8a8a8a,stroke:#6b6b6b,color:#fff
  classDef container fill:#438dd5,stroke:#2e6295,color:#fff
  classDef component fill:#85bbf0,stroke:#5d82a8,color:#000
  classDef boundary fill:none,stroke:#444,stroke-dasharray:6 4,color:#444
```

- **Six JVMs, six stores.** Each context is its own candidate, `harness-impl/NN-<context>/`,
  started by its `start.sh`, speaking JSON-RPC over stdio. Each keeps its own Grain store, and ORC
  shares that candidate's file and tenant; no store is shared across contexts (`spec/decisions.md`,
  "Spec round 5" 1).
- The runner starts the five named in `LOYLY_CONTEXTS` for each run, with a fresh store each in
  the run's directory and the fixtures it wrote there, and stops them when the run ends
  (`cycle-runner/seam.md`, "Handed in").
- The **gate room is the gate's**: a copy taken when the builder ended, out of the builder's
  reach, writable by the candidate's own run (`spec/decisions.md`, "Spec round 7d"). The gate runs
  on the host in stage 1; a Gondolin gate room is not in slice 06.

### Level 3: Component (cycle runner)

The responsibilities inside the cycle runner (`cycle-runner/event-model/`,
`cycle-runner/seam.md`).

```mermaid

flowchart TB
  operator(["<b>Operator</b><br/>[Person]"])

  subgraph p ["Cycle runner [Container]"]
    seam["<b>Seam</b><br/>[Component: JSON-RPC 2.0 over stdio]<br/>start-run, hand-in-verdicts, run-progress, runs-to-advance; a refusal is -32000 with data.refusal"]
    commands["<b>CycleRun commands</b><br/>[Component: Grain commands]<br/>Operator: start-run, hand-in-verdicts. Processor only: record-effect, relay-handoff, conclude-run, halt-run"]
    views["<b>Read models and queries</b><br/>[Component: Grain]<br/>runs, effect-results, relayed-handoffs, runs-to-advance; queries run-progress, runs-to-advance"]
    advance["<b>advance-run</b><br/>[Component: Grain todo processor]<br/>One node per effect; reads runs-to-advance"]
    flows["<b>Workflows</b><br/>[Component: ORC, one per flow]<br/>run-to-gate, verdicts-to-conclusion, cycle-failed-ends-run, effect-not-performed-halts-run"]
    cut["<b>Cut effects E1</b><br/>[Component]<br/>stamp a stripped copy, pin the tree digest, derive the checklist"]
    harnessfx["<b>Harness effects E2 to E6</b><br/>[Component]<br/>self-check, fence and probe, regenerate, install, gate, gate refusal, repair, fail-cycle"]
    score["<b>Score effects E7</b><br/>[Component]<br/>file the handed-in verdicts, file the score, fail the score stage"]
    relay["<b>Relay E8</b><br/>[Component]<br/>Each producer's new handoffs, from its store, in store order, to every consumer's hand-in method"]
    endrun["<b>Conclusion and halt</b><br/>[Component]<br/>Reads cycle-progress; concludes as the cycle ended, or halts"]
    guard["<b>Boot guard</b><br/>[Component: Grain verify-or-throw!]<br/>Refuses to start on drift from the registered event model"]
  end

  rstore[("<b>Runner store</b><br/>[Container: Grain, SQLite]")]
  contexts["<b>Five contexts</b><br/>[Containers: their seams]"]
  world["<b>Repository, builder VM, gate room</b><br/>[Containers]"]

  operator -- "One request per line [stdio]" --> seam
  seam -- "Issues" --> commands
  seam -- "Runs" --> views
  commands -- "run-started, verdicts-handed-in, effect-recorded, handoff-relayed, run-concluded, run-halted" --> rstore
  views -- "Fold from" --> rstore
  rstore -- "runs-to-advance" --> advance
  advance -- "Runs as" --> flows
  flows -- "Performs" --> cut
  flows -- "Performs" --> harnessfx
  flows -- "Performs" --> score
  flows -- "After every recording" --> relay
  flows -- "After every recording and relay" --> endrun
  cut -- "Records through a method" --> contexts
  harnessfx -- "Records through a method" --> contexts
  score -- "Records through a method" --> contexts
  relay -- "Hands in" --> contexts
  endrun -- "Reads regeneration-cycle/cycle-progress" --> contexts
  harnessfx -- "Acts on" --> world
  cut -- "Reads" --> world
  advance -- "record-effect, relay-handoff, conclude-run, halt-run" --> commands
  guard -. "Checks registered blocks at startup" .-> commands

  class operator person
  class seam,commands,views,advance,flows,cut,harnessfx,score,relay,endrun,guard component
  class rstore,contexts,world container
  class p boundary
  classDef person fill:#08427b,stroke:#052e56,color:#fff
  classDef system fill:#1168bd,stroke:#0b4884,color:#fff
  classDef external fill:#8a8a8a,stroke:#6b6b6b,color:#fff
  classDef container fill:#438dd5,stroke:#2e6295,color:#fff
  classDef component fill:#85bbf0,stroke:#5d82a8,color:#000
  classDef boundary fill:none,stroke:#444,stroke-dasharray:6 4,color:#444
```

- **It owns no rule of the five contexts.** A refusal it shows is the context's reply, recorded
  as an effect result (`outcome` `refused`); its run ends as its cycle ends
  (`cycle-runner/domain-design.md`, "Boundaries").
- Its own rules guard only its own facts: one run at a time, a cycle run once, the verdicts taken
  once and only for a gated candidate at an approved grader digest, each effect result and each
  relayed handoff recorded once.
- **A halt is never the cycle's conclusion**: a recording refused before the cycle opened (stamp,
  pin, checklist), a stamp whose identities are not the plan's own, a context that did not
  respond, or a handed-in command that could not be started.

### Level 3: Component (specification)

The responsibilities inside Specification, slice 01 (`specification/event-model/`,
`specification/seam.md`, `specification/allium/slice.allium`).

```mermaid

flowchart TB
  operator(["<b>Operator</b><br/>[Person]<br/>The spec author, through the stamper"])
  runner["<b>Cycle runner (relay)</b><br/>[Container]<br/>E1 stamp: offers a stripped copy of each selected slice; relays the handoff"]

  subgraph p ["Specification [Container]"]
    seam["<b>Seam</b><br/>[Component: JSON-RPC 2.0 over stdio]<br/>specification/stamp-slice, specification/stamped-items; a refusal is -32000 with data.refusal"]
    flow["<b>specification/stamping</b><br/>[Component: ORC workflow]<br/>The command method runs as this workflow on the context's own store"]
    stamp["<b>stamp-slice</b><br/>[Component: Grain command]<br/>Rules StampFirstScenarios and StampSlice: the next identity of the slice's ledger, in file order, never renumbered or reused"]
    reading["<b>Scenario reading</b><br/>[Component: contract ScenarioReading]<br/>The one reading of Gherkin: refuses no-spec-found, not-this-slice, unstamped-mismatch, empty-title-mismatch"]
    tagging["<b>Item tagging</b><br/>[Component]<br/>Writes @item-slice-number on its own line above each stamped heading; keeps every other line"]
    rm["<b>stamped-items</b><br/>[Component: Grain read model]<br/>Per slice: every identity ever stamped, its source and title, the next free number"]
    query["<b>stamped-items</b><br/>[Component: Grain query]<br/>One slice, or every slice"]
    guard["<b>Boot guard</b><br/>[Component: Grain verify-or-throw!]<br/>Refuses to start on drift from the registered event model"]
  end

  store[("<b>Specification store</b><br/>[Container: Grain event-store-v3, SQLite, LOYLY_GRAIN_STORE]<br/>Its own events; ORC on the same file and tenant")]
  specfile[("<b>Slice spec file</b><br/>[Container: file, NN-slice/spec.feature]")]
  meas["<b>Measurement</b><br/>[Container]<br/>Folds scenario-stamped into item-identities"]

  operator -- "Stamps a slice [JSON-RPC over stdio]" --> seam
  runner -- "specification/stamp-slice, per selected slice" --> seam
  seam -- "Runs" --> flow
  flow -- "Issues" --> stamp
  stamp -- "Checks the offer against" --> reading
  reading -- "Reads" --> specfile
  stamp -- "Then" --> tagging
  tagging -- "Writes the tag lines" --> specfile
  stamp -- "Reads the ledger" --> rm
  stamp -- "scenario-stamped, empty-title-headings-reported" --> store
  rm -- "Folds from" --> store
  seam -- "Runs" --> query
  query -- "Reads" --> rm
  runner -- "Reads scenario-stamped from this store" --> store
  runner -- "Hands in specification/scenario-stamped" --> meas
  guard -. "Checks registered blocks at startup" .-> stamp

  class operator person
  class seam,flow,stamp,reading,tagging,rm,query,guard component
  class runner,store,specfile,meas container
  class p boundary
  classDef person fill:#08427b,stroke:#052e56,color:#fff
  classDef system fill:#1168bd,stroke:#0b4884,color:#fff
  classDef external fill:#8a8a8a,stroke:#6b6b6b,color:#fff
  classDef container fill:#438dd5,stroke:#2e6295,color:#fff
  classDef component fill:#85bbf0,stroke:#5d82a8,color:#000
  classDef boundary fill:none,stroke:#444,stroke-dasharray:6 4,color:#444
```

- **It owns the published language**: item identity and the stamped scenario. An identity is
  handed out once, never renumbered, reused or derived from wording (`specification/event-model/slice.edn`).
- **It hands out one event**, `scenario-stamped`, to Measurement; it consumes none, so it has no
  hand-in method (`specification/event-model/handoffs.edn`).
- An empty-title heading is **not an item**: it is reported loudly by line, never stamped.

### Level 3: Component (measurement)

The responsibilities inside Measurement, slice 02 (`measurement/event-model/`,
`measurement/seam.md`, `measurement/allium/`).

```mermaid

flowchart TB
  operator(["<b>Operator</b><br/>[Person]<br/>The instrument desk"])
  runner["<b>Cycle runner (relay)</b><br/>[Container]<br/>E1 checklist, E7 file-verdicts, file-score, fail-score-stage; relays the handoffs"]

  subgraph p ["Measurement [Container]"]
    seam["<b>Seam</b><br/>[Component: JSON-RPC 2.0 over stdio]<br/>derive-checklist, file-item-verdicts, file-score, fail-score-stage, report-scoreboard; queries checklist, cycle-measurement, scoreboard, measurements-to-report"]
    handin["<b>Hand-in methods</b><br/>[Component: one per consumed handoff]<br/>specification/scenario-stamped, cycle-preparation/source-cut-pinned, regeneration-cycle/gate-attempt-passed; appended as given, no workflow"]
    folds["<b>Consumed read models</b><br/>[Component: Grain read models]<br/>item-identities, pinned-cuts, gated-cycles"]
    flows["<b>Workflows</b><br/>[Component: ORC, one per flow]<br/>stamping-to-checklist, scoring, scoreboard-restates-moved-item-set"]
    checklist["<b>derive-checklist</b><br/>[Component: Grain command]<br/>Rule DeriveChecklist: one item per stamped scenario of a pinned cut, in the offered order"]
    scoring["<b>Scoring commands</b><br/>[Component: Grain commands]<br/>file-item-verdicts, file-score, fail-score-stage: one measurement per cycle, unreached items failed and counted"]
    report["<b>report-scoreboard</b><br/>[Component: Grain command]<br/>Rule ReportScoreboard: against the previous measured cycle; restates a moved item set"]
    once["<b>report-scoreboard-once</b><br/>[Component: Grain todo processor]<br/>Subscribes to measurement-filed; reads measurements-to-report"]
    views["<b>Read models and queries</b><br/>[Component: Grain]<br/>checklists, item-verdicts, cycle-measurements, measurements-to-report, scoreboards; queries checklist, cycle-measurement, scoreboard, measurements-to-report"]
    guard["<b>Boot guard</b><br/>[Component: Grain verify-or-throw!]<br/>Refuses to start on drift from the registered event model and the three consumed handoffs"]
  end

  store[("<b>Measurement store</b><br/>[Container: Grain event-store-v3, SQLite, LOYLY_GRAIN_STORE]<br/>Its own events and the handed-in handoffs; ORC on the same file and tenant")]
  regen["<b>Regeneration cycle</b><br/>[Container]<br/>Folds the three handoffs only to conclude the cycle"]

  operator -- "Derives a checklist, reads a measurement and its scoreboard [JSON-RPC over stdio]" --> seam
  runner -- "Records E1 and E7 through the methods" --> seam
  runner -- "Hands in the producers' handoffs, in their order" --> handin
  handin -- "Appends as given" --> store
  folds -- "Fold from" --> store
  seam -- "Runs" --> flows
  flows -- "stamping-to-checklist" --> checklist
  flows -- "scoring" --> scoring
  flows -- "scoreboard-restates-moved-item-set" --> report
  checklist -- "Reads pinned-cuts, item-identities" --> folds
  scoring -- "Reads gated-cycles" --> folds
  checklist -- "checklist-derived" --> store
  scoring -- "item-verdicts-filed, measurement-filed, score-stage-failed" --> store
  report -- "scoreboard-reported, moved-item-set-restated" --> store
  views -- "Fold from" --> store
  seam -- "Runs queries" --> views
  store -- "measurement-filed" --> once
  once -- "Issues, before file-score responds" --> report
  runner -- "Reads measurement-filed, score-stage-failed, scoreboard-reported" --> store
  runner -- "Hands them in" --> regen
  guard -. "Checks registered blocks at startup" .-> flows

  class operator person
  class seam,handin,folds,flows,checklist,scoring,report,once,views,guard component
  class runner,store,regen container
  class p boundary
  classDef person fill:#08427b,stroke:#052e56,color:#fff
  classDef system fill:#1168bd,stroke:#0b4884,color:#fff
  classDef external fill:#8a8a8a,stroke:#6b6b6b,color:#fff
  classDef container fill:#438dd5,stroke:#2e6295,color:#fff
  classDef component fill:#85bbf0,stroke:#5d82a8,color:#000
  classDef boundary fill:none,stroke:#444,stroke-dasharray:6 4,color:#444
```

- **It is the instrument**: it reports to a person once a cycle and never into the build loop.
  No block produces a command of another context; its three handoffs only conclude the cycle
  (`measurement/event-model/handoffs.edn`).
- **The reading of the spec is the issuer's**: `derive-checklist` records the items it is
  offered, in that order; the checklist digest is the candidate's (`measurement/seam.md`).
- **The policy runs first**: a filed measurement's scoreboard is reported before `file-score`
  responds, so `measurements-to-report` is observed empty after every method.
- The approved grader digests arrive as params of the scoring commands; a verdict at another
  digest is refused `grader-not-approved`.

### Level 3: Component (grader-lifecycle)

The responsibilities inside the Grader lifecycle, slice 03 (`grader-lifecycle/event-model/`,
`grader-lifecycle/seam.md`, `grader-lifecycle/allium/instrument-golden.allium`).

```mermaid

flowchart TB
  maintainer(["<b>Instrument maintainer</b><br/>[Person]<br/>Writes and moves the instrument goldens"])
  runner["<b>Cycle runner (relay)</b><br/>[Container]<br/>Reads the goldens for the goldens digest; relays both golden events"]

  subgraph p ["Grader lifecycle [Container]"]
    seam["<b>Seam</b><br/>[Component: JSON-RPC 2.0 over stdio]<br/>write-instrument-golden, move-instrument-golden, instrument-goldens; a refusal is -32000 with data.refusal"]
    flow["<b>grader-lifecycle/golden-written-then-moved</b><br/>[Component: ORC workflow]<br/>Both command methods run as this workflow"]
    write["<b>write-instrument-golden</b><br/>[Component: Grain command]<br/>Rule WriteInstrumentGolden: written from its scenario, never recorded from a run"]
    move["<b>move-instrument-golden</b><br/>[Component: Grain command]<br/>Rule MoveInstrumentGolden: one reason, instrument-behavior-changed, wording-replaced or spec-grew (additions only)"]
    digests["<b>Scenario digests</b><br/>[Component: contract ScenarioDigests]<br/>digest_of_scenario from LOYLY_SCENARIO_DIGESTS, read on every check; never computed"]
    rm["<b>instrument-goldens</b><br/>[Component: Grain read model]<br/>Each golden's path, digest, scenario digest and its moves with their reasons"]
    query["<b>instrument-goldens</b><br/>[Component: Grain query]<br/>Every golden with its move history"]
    guard["<b>Boot guard</b><br/>[Component: Grain verify-or-throw!]<br/>Refuses to start on drift from the registered event model"]
  end

  store[("<b>Grader lifecycle store</b><br/>[Container: Grain event-store-v3, SQLite, LOYLY_GRAIN_STORE]<br/>Its own events; ORC on the same file and tenant")]
  file[("<b>Scenario digests file</b><br/>[Container: JSON, LOYLY_SCENARIO_DIGESTS]")]
  regen["<b>Regeneration cycle</b><br/>[Container]<br/>Folds both golden events into its own instrument-goldens"]

  maintainer -- "Writes, moves, reads [JSON-RPC over stdio]" --> seam
  seam -- "Runs" --> flow
  flow -- "Issues" --> write
  flow -- "Issues" --> move
  write -- "Compares the scenario digest" --> digests
  move -- "Compares the scenario digest" --> digests
  digests -- "Reads" --> file
  write -- "Reads" --> rm
  move -- "Reads" --> rm
  write -- "instrument-golden-written" --> store
  move -- "instrument-golden-moved" --> store
  rm -- "Folds from" --> store
  seam -- "Runs" --> query
  query -- "Reads" --> rm
  runner -- "grader-lifecycle/instrument-goldens" --> seam
  runner -- "Reads both golden events from this store" --> store
  runner -- "Hands them in" --> regen
  guard -. "Checks registered blocks at startup" .-> flow

  class maintainer person
  class seam,flow,write,move,digests,rm,query,guard component
  class runner,store,file,regen container
  class p boundary
  classDef person fill:#08427b,stroke:#052e56,color:#fff
  classDef system fill:#1168bd,stroke:#0b4884,color:#fff
  classDef external fill:#8a8a8a,stroke:#6b6b6b,color:#fff
  classDef container fill:#438dd5,stroke:#2e6295,color:#fff
  classDef component fill:#85bbf0,stroke:#5d82a8,color:#000
  classDef boundary fill:none,stroke:#444,stroke-dasharray:6 4,color:#444
```

- **The instrument's own material, on its own clock, outside every cycle.** Slice 03 holds the
  instrument goldens only; graders (requested, authored, calibrated, sealed, approved) come in a
  later round (`grader-lifecycle/event-model/area.edn`).
- **Without the digests file the context is inert**: every offered scenario digest differs, so
  every write is refused (`grader-lifecycle/seam.md`).
- It consumes no handoff, so it has no hand-in method; it hands both golden events to the
  Regeneration cycle, whose self-check must reproduce the current goldens exactly.

### Level 3: Component (regeneration-cycle)

The responsibilities inside the Regeneration cycle, slice 04 (`regeneration-cycle/event-model/`,
`regeneration-cycle/seam.md`, `regeneration-cycle/allium/`).

```mermaid

flowchart TB
  operator(["<b>Operator</b><br/>[Person]<br/>Observes a cycle's progress"])
  runner["<b>Cycle runner (relay)</b><br/>[Container]<br/>Stands as ORC runtime's nodes: E2 to E6; relays the handoffs"]

  subgraph p ["Regeneration cycle [Container]"]
    seam["<b>Seam</b><br/>[Component: JSON-RPC 2.0 over stdio]<br/>Eight command methods; queries cycle-progress, cycles-to-conclude; a refusal is -32000 with data.refusal"]
    handin["<b>Hand-in methods</b><br/>[Component: one per consumed handoff]<br/>source-cut-pinned, instrument-golden-written, instrument-golden-moved, measurement-filed, score-stage-failed, scoreboard-reported"]
    folds["<b>Consumed read models</b><br/>[Component: Grain read models]<br/>pinned-cuts, instrument-goldens: goldens_digest_of the current goldens"]
    flows["<b>Workflows</b><br/>[Component: ORC, one per flow]<br/>fence-to-scoreboard, gate-attempt-repaired, and the six that fail a cycle"]
    fence["<b>Self-check and fence</b><br/>[Component: Grain commands]<br/>record-harness-self-check, prove-clean-room; LOYLY_SCORING_TOOL_PATHS must be fenced"]
    attempts["<b>Attempts</b><br/>[Component: Grain commands]<br/>record-regenerated-candidate, install-candidate-in-gate-room; at most three per cycle"]
    gate["<b>Gate verdicts</b><br/>[Component: Grain commands]<br/>record-gate-attempt, record-gate-refusal; done is the gate's verdict"]
    conclude["<b>Conclusion</b><br/>[Component: Grain commands]<br/>pass-cycle, fail-cycle: once, naming the stage and the reason"]
    policy["<b>conclude-cycle</b><br/>[Component: Grain todo processor]<br/>Reads cycles-to-conclude; runs before the triggering method responds"]
    clock["<b>stage-clock</b><br/>[Component: Grain periodic task, every 30 s]<br/>Fails a cycle whose stage outlived its budget timeout"]
    views["<b>Read models and queries</b><br/>[Component: Grain]<br/>harness-self-checks, gate-attempts, regeneration-cycles, cycles-to-conclude; queries cycle-progress, cycles-to-conclude"]
    guard["<b>Boot guard</b><br/>[Component: Grain verify-or-throw!]<br/>Refuses to start on drift from the registered event model and the six consumed handoffs"]
  end

  store[("<b>Regeneration cycle store</b><br/>[Container: Grain event-store-v3, SQLite, LOYLY_GRAIN_STORE]<br/>Its own events and the handed-in handoffs; ORC on the same file and tenant")]
  meas["<b>Measurement</b><br/>[Container]<br/>Folds gate-attempt-passed into gated-cycles"]

  operator -- "cycle-progress [JSON-RPC over stdio]" --> seam
  runner -- "Records E2 to E6 through the methods; reads cycle-progress" --> seam
  runner -- "Hands in the producers' handoffs, in their order" --> handin
  handin -- "Appends as given" --> store
  folds -- "Fold from" --> store
  seam -- "Runs" --> flows
  flows -- "Issues" --> fence
  flows -- "Issues" --> attempts
  flows -- "Issues" --> gate
  flows -- "Issues" --> conclude
  fence -- "Reads pinned-cuts, the goldens digest" --> folds
  gate -- "Reads the goldens digest" --> folds
  fence -- "harness-self-check-passed or -failed, clean-room-proved" --> store
  attempts -- "candidate-regenerated, candidate-installed-in-gate-room" --> store
  gate -- "gate-attempt-passed, -failed, -refused" --> store
  conclude -- "cycle-passed, cycle-failed" --> store
  views -- "Fold from" --> store
  seam -- "Runs queries" --> views
  store -- "self-check failed, attempt failed or refused, score stage failed, scoreboard reported" --> policy
  policy -- "pass-cycle, fail-cycle" --> conclude
  clock -- "fail-cycle, reason budget" --> conclude
  runner -- "Reads gate-attempt-passed from this store" --> store
  runner -- "Hands it in" --> meas
  guard -. "Checks registered blocks at startup" .-> flows

  class operator person
  class seam,handin,folds,flows,fence,attempts,gate,conclude,policy,clock,views,guard component
  class runner,store,meas container
  class p boundary
  classDef person fill:#08427b,stroke:#052e56,color:#fff
  classDef system fill:#1168bd,stroke:#0b4884,color:#fff
  classDef external fill:#8a8a8a,stroke:#6b6b6b,color:#fff
  classDef container fill:#438dd5,stroke:#2e6295,color:#fff
  classDef component fill:#85bbf0,stroke:#5d82a8,color:#000
  classDef boundary fill:none,stroke:#444,stroke-dasharray:6 4,color:#444
```

- **It records the stage facts and guards them; ORC owns the order.** The nodes that act in
  rooms (the fence, the self-check run, the gate, the builder session) are not built here: they
  are the issuers of its commands (`regeneration-cycle/seam.md`, "Not in this slice").
- **The six failing workflows** are self-check-failed-fails-cycle, fence-refused-fails-cycle,
  gate-attempts-exhausted, gate-refusals-fail-cycle, stage-timeout-fails-cycle and
  refused-score-fails-cycle; `fail-cycle` runs as the one that names its issuer.
- **No score is not all failed**: it holds no item verdict. It hands out one event,
  `gate-attempt-passed`, to Measurement, one way; the score never flows back into the loop.

### Level 3: Component (cycle-preparation)

The responsibilities inside Cycle preparation, slice 05 (`cycle-preparation/event-model/`,
`cycle-preparation/seam.md`, `cycle-preparation/allium/source-cut.allium`).

```mermaid

flowchart TB
  operator(["<b>Operator</b><br/>[Person]<br/>The preparation view"])
  runner["<b>Cycle runner (relay)</b><br/>[Container]<br/>E1 pin: the commit's tree digest from git rev-parse; relays the handoff"]

  subgraph p ["Cycle preparation [Container]"]
    seam["<b>Seam</b><br/>[Component: JSON-RPC 2.0 over stdio]<br/>cycle-preparation/pin-source-cut, cycle-preparation/source-cuts; a refusal is -32000 with data.refusal"]
    flow["<b>cycle-preparation/cut-to-fence</b><br/>[Component: ORC workflow]<br/>The command method runs as this workflow on the context's own store"]
    pin["<b>pin-source-cut</b><br/>[Component: Grain command]<br/>Rule PinSourceCut: refuses cut-already-pinned, not-a-commit, tree-digest-not-the-commits"]
    repo["<b>Project repository</b><br/>[Component: contract ProjectRepository]<br/>is_commit and tree_of from LOYLY_PROJECT_COMMITS, read on every pin; runs no git"]
    rm["<b>source-cuts</b><br/>[Component: Grain read model]<br/>Every pinned cut, its commit and tree digest"]
    query["<b>source-cuts</b><br/>[Component: Grain query]<br/>The pinned cuts, newest first"]
    guard["<b>Boot guard</b><br/>[Component: Grain verify-or-throw!]<br/>Refuses to start on drift from the registered event model"]
  end

  store[("<b>Cycle preparation store</b><br/>[Container: Grain event-store-v3, SQLite, LOYLY_GRAIN_STORE]<br/>Its own events; ORC on the same file and tenant")]
  commits[("<b>Project commits file</b><br/>[Container: JSON, LOYLY_PROJECT_COMMITS]<br/>Commit to tree digest")]
  consumers["<b>Measurement, Regeneration cycle</b><br/>[Containers]<br/>Each folds source-cut-pinned into its own pinned-cuts"]

  operator -- "Pins a cut, reads the cuts [JSON-RPC over stdio]" --> seam
  runner -- "cycle-preparation/pin-source-cut" --> seam
  seam -- "Runs" --> flow
  flow -- "Issues" --> pin
  pin -- "Asks" --> repo
  repo -- "Reads" --> commits
  pin -- "Reads" --> rm
  pin -- "source-cut-pinned" --> store
  rm -- "Folds from" --> store
  seam -- "Runs" --> query
  query -- "Reads" --> rm
  runner -- "Reads source-cut-pinned from this store" --> store
  runner -- "Hands it in" --> consumers
  guard -. "Checks registered blocks at startup" .-> pin

  class operator person
  class seam,flow,pin,repo,rm,query,guard component
  class runner,store,commits,consumers container
  class p boundary
  classDef person fill:#08427b,stroke:#052e56,color:#fff
  classDef system fill:#1168bd,stroke:#0b4884,color:#fff
  classDef external fill:#8a8a8a,stroke:#6b6b6b,color:#fff
  classDef container fill:#438dd5,stroke:#2e6295,color:#fff
  classDef component fill:#85bbf0,stroke:#5d82a8,color:#000
  classDef boundary fill:none,stroke:#444,stroke-dasharray:6 4,color:#444
```

- **A cut is immutable**: one commit and its tree content; pinning it again, with the same
  content or another, is refused and records nothing (`cycle-preparation/seam.md`).
- **The repository's facts are handed in**: the candidate compares the offered tree digest as
  text, never computes one.
- Slice 05 holds the source cut only; selection, approval, submission and the receipt come in a
  later round. It consumes no handoff and hands out `source-cut-pinned`.

## The seam

Every context, the runner included, is reached only through its `seam.md`: `start.sh` reads
JSON-RPC 2.0 requests on stdin, one per line, and writes one response per request carrying its
`id`; diagnostics go to stderr. Errors are `-32700` (not JSON), `-32601` (unknown method),
`-32602` (params off the schema) and `-32000` (a refusal, with `data.refusal`).

- **One method per command and per query**, named exactly as the block without the leading
  colon, its params the block's `:schema` verbatim.
- **One hand-in method per consumed handoff**, named exactly as the event, its params the event's
  contract in the producer's `event-model/handoffs.edn`. The context appends the event as given
  and runs no ORC workflow for it; these methods are the only way another context's event enters
  its store (`measurement/seam.md`, `regeneration-cycle/seam.md`, "The consumed handoffs, handed
  in").
- **Each command method runs as the ORC workflow of its flow** (`event-model/area.edn`), on the
  context's own store; the gate's event-store check requires every event a command produced to
  lie inside a completed run of that workflow (`spec/decisions.md`, "First light" 4).
- **The runner's own commands are not methods**: `record-effect`, `relay-handoff`, `conclude-run`
  and `halt-run` are issued only by `advance-run`; a request naming one is `-32601`.

| Context | Command methods | Hand-in methods |
|---|---|---|
| Specification | `stamp-slice` | none |
| Cycle preparation | `pin-source-cut` | none |
| Grader lifecycle | `write-instrument-golden`, `move-instrument-golden` | none |
| Measurement | `derive-checklist`, `file-item-verdicts`, `file-score`, `fail-score-stage`, `report-scoreboard` | `scenario-stamped`, `source-cut-pinned`, `gate-attempt-passed` |
| Regeneration cycle | `record-harness-self-check`, `prove-clean-room`, `record-regenerated-candidate`, `install-candidate-in-gate-room`, `record-gate-attempt`, `record-gate-refusal`, `pass-cycle`, `fail-cycle` | `source-cut-pinned`, `instrument-golden-written`, `instrument-golden-moved`, `measurement-filed`, `score-stage-failed`, `scoreboard-reported` |
| Cycle runner | `start-run`, `hand-in-verdicts` | none |

## Context map

The handoffs, producer to consumer, as the `event-model/handoffs.edn` files define them. The
runner produces and consumes none: it carries the others'.

```text
specification ──── scenario-stamped ────────────────────────► measurement
cycle-preparation ─ source-cut-pinned ──────────────────────► measurement, regeneration-cycle
grader-lifecycle ── instrument-golden-written / -moved ─────► regeneration-cycle
regeneration-cycle  gate-attempt-passed (one way) ──────────► measurement
measurement ─────── measurement-filed, score-stage-failed,
                    scoreboard-reported (to conclude only) ──► regeneration-cycle

cycle-runner: reads each producer's store, hands each handoff in to every consumer (the relay)
```

## The relay, and the deferred shared store

After every recording the runner relays (E8), as `harness/v2/assemble.clj` does: the producer's
handoff events not yet relayed are read from its store file, within its tenant, in store order,
and each is handed in unchanged (its `type` the method, its other fields the params) to every
consumer whose event model consumes it; `handoff-relayed` records the consumer's reply. A
handoff a policy issued, such as Measurement's `scoreboard-reported` inside `file-score`, travels
like any other, because it is taken from the store, not from command results
(`spec/decisions.md`, "Spec round 5" 1 and 2).

Deferred, the end state (option A1): one shared store with one fixed tenant, a consumer's read
models projecting from it and its policies polling it, to be taken up when ORC hosts all
contexts. The assembly gate's experiment showed why it waits: each candidate wrote its own Grain
tenant, and a todo processor's `start` sees only its own JVM's appends.

## The cycle

```text
stamp, pin, checklist → self-check → fence → regenerate → install, self-check, gate
      → (failed attempt, cycle still running: repair, at most three attempts)
      → hand-in-verdicts → file verdicts, file score → scoreboard → conclude
```

| Effect | What the runner does | Recorded through |
|---|---|---|
| E1 | Stamps a copy of each slice's spec file with its `@item` tags removed and checks the identities come back; pins the commit's tree digest; derives the checklist with legacy `gen_checklist`'s reading | `specification/stamp-slice`, `cycle-preparation/pin-source-cut`, `measurement/derive-checklist` |
| E2 | Runs `LOYLY_SELF_CHECK_CMD` (legacy `check.sh --local`), before the fence and before each gate | `regeneration-cycle/record-harness-self-check` |
| E3 | Legacy `fence.sh` as-is, then probes one root commit, one commit, no fenced path in the tree or the object graph | `regeneration-cycle/prove-clean-room`, or `fail-cycle` |
| E4 | A fresh builder room; `LOYLY_BUILDER_CMD <builder-room> <instruction>` launched and waited for within the regenerate timeout | `regeneration-cycle/record-regenerated-candidate` |
| E5 | Installs a copy in a fresh gate room; the gate in legacy `verify.sh`'s order and arguments, on the host | `install-candidate-in-gate-room`, `record-gate-attempt`, `record-gate-refusal` |
| E6 | After a failed attempt with the cycle still running: a fresh builder room with the previous candidate and the gate evidence, read-only, and the repair instruction | `record-regenerated-candidate` (`verification-repair`) |
| E7 | Files the handed-in verdicts and the score, or fails the score stage with the refusal as heard | `measurement/file-item-verdicts`, `file-score`, `fail-score-stage` |
| E8 | The relay, after every recording | each consumer's hand-in method |

Exhaustion, refusals, the stage clock and the conclusion are the Regeneration cycle's: its
policy `conclude-cycle` issues `pass-cycle` or `fail-cycle`, and its clock fails a stage that
outlived its timeout (`regeneration-cycle/seam.md`, "The conclude-cycle policy and the stage
clock"). The runner reads `regeneration-cycle/cycle-progress` after every recording and relay and
records `conclude-run` once the cycle passed or failed.

The fence and the gate are the **harness** and feed the build loop. The score and the scoreboard
are the **instrument** and never do: no score evidence reaches a builder, and a failed score is
measured, not retried within the cycle (AGENTS.md).

## The instrument

- The **checklist** is one item per stamped scenario of the selection, derived by Measurement and
  never written; an unstamped scenario or an empty selection stops the cycle (the run halts,
  `refused-before-the-cycle`).
- The **score** is honest: zero verdicts are refused, so no score is filed; an unreached item
  fails and stays in the denominator.
- The **scoreboard** compares with the last measured cycle of the same selection on shared items,
  newly passing and newly failing apart, new items as the untuned holdout, and never a net.
  Measurement's policy `report-scoreboard-once` reports it.
- **Grading today is legacy step 5**: a black-box grading agent per candidate drives it over its
  seam and writes one verdicts file, handed in unchanged; the main session judges `@item-01-011`
  of the Löyly app and hands that verdict in with the others. Nothing is sealed, calibrated or
  approved for these runs (`harness/README.md`, "Scoring a spec-v2 cycle"). A sealed Löyly
  grader and the product Grader (OG1) are still open (`spec/decisions.md`, "The switch to the new
  harness" 7).

## How it is checked

- **One context per cycle, before the switch**: `harness/v2/gate.sh NN ROOM` runs legacy
  `verify.sh` with the slice's probe, then reads the Grain store it handed the candidate
  directly (`harness/v2/event-store-check.sh`); `harness/v2/score.sh` files a verdicts file with
  legacy `score.clj` and `scoreboard.clj` (`spec/decisions.md`, "First light").
- **The assembly gate**: `harness/v2/assemble.sh` wires the five gated candidates through the
  relay, one story end to end, and reports the shared-store experiment.
- **The switch**: in shadow runs 4 and 5 the runner agreed with the legacy harness at every stage,
  fence to scoreboard, on the full Löyly app (11 items); since then the runner runs the cycles and
  the legacy harness stays as the reference. Until a run can resume after a restart, the runner is
  kept alive from start to the filed score.

## Technology bindings

| Term | Technology |
|---|---|
| A context's candidate | Clojure on ORC, with Grain; one JVM per context, `harness-impl/NN-<context>/start.sh` |
| ORC | `obneyai/orc`, the `orc-service` pin `af086675`; each command runs as the ORC workflow of its flow, on the context's own store |
| Event store | Grain's `event-store-v3`, SQLite backend, at `LOYLY_GRAIN_STORE`; Grain pin `dbf5b522` (`harness/v2/deps.edn`) |
| Seam | JSON-RPC 2.0 over stdio, one JSON object per line |
| Builder | `pi` with its Gondolin backend on the existing Codex login, launched as `LOYLY_BUILDER_CMD`; provided, never regenerated. The durable host on `@earendil-works/pi-durable` is not built |
| Gate | On the host, in legacy `verify.sh`'s order and arguments, in a writable gate room |
| Harness configuration | Legacy `config.sh` as-is, handed in as `LOYLY_HARNESS_CONFIG` |
| Legacy tools, v2 cycle tools | Babashka and shell: `harness/` and `harness/v2/` |

## Where the spec states it

| Concern | Where |
|---|---|
| The runner: effects, handed-in values, the relay, the conclusion | `.pi/spec/cycle-runner/seam.md`, `allium/`, `event-model/` |
| Stamps and item identity | `.pi/spec/specification/` |
| The source cut | `.pi/spec/cycle-preparation/` |
| Instrument goldens | `.pi/spec/grader-lifecycle/` |
| Checklist, score, scoreboard | `.pi/spec/measurement/` |
| Self-check, clean room, gate attempts, the cycle's conclusion | `.pi/spec/regeneration-cycle/` |
| Each context's boundaries and decisions | `.pi/spec/<context>/domain-design.md` |
| The handoffs | `.pi/spec/<context>/event-model/handoffs.edn` |
| Every decision, in order | `.pi/spec/decisions.md` |
| The tools, the legacy reference | `harness/README.md` |

Each context's accepted scenarios are its `spec.feature`, a slice through
`.pi/plan/NN-<context>`. Validate with `tools/spec/v2/gates.sh`.

## History

Spec v1, in `.pi/model/` and in git history (this page described it up to `3da969e`),
described the harness as one JVM: one `:loyly.pi-harness` Grain service area with an operator
control plane (cuts, selections, preparations, task submission), runtime evidence in validation
rooms, builder-instruction optimization through GEPA and a Bedrock reflection model, and a
durable builder host on `pi-durable` that ORC launched once per tick. Spec v2 replaced it with
six contexts built one per cycle; none of those v1 parts is in spec v2, and the durable host
is not built.
