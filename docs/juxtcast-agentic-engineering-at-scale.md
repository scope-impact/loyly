# JUXTCast, "What Coding Agents Still Need From Us", mapped against this method

Source: JUXTCast S7E1, Henry Garner in conversation with Chris Ford (Technology
Director, Thoughtworks; author of *Agentic Engineering at Scale*, O'Reilly early
access). Roughly 62 minutes, 254 speaker turns.

Mapped against: `docs/wow/way-of-working.md`, `docs/wow/invariants.md`,
`docs/wow/harness.md`, `harness/README.md` and the scripts beside it; the workshop
archive at `~/common/projects/saunavuoro/workshop/2026-08-28/`; and kalibra
(`~/common/projects/kalibra/`) as a worked example built in the last 24 hours, in
particular `docs/plan/01-daily-edition/decisions.md` with its reality audit and
regeneration audit.

## About the transcript, and what that costs this document

These are YouTube auto-captions. There is no reliable punctuation, speaker turns are
inferred from `>>` markers rather than labelled by name, and proper nouns are
frequently mis-heard. Six garbles matter enough to name, because a reader who quotes
them onward would be quoting the captioner rather than the speaker:

- `[30:43]` "to use Brigitte Berglas' term" for the origin of "spec anchored". The
  name as rendered does not resolve to anyone; a Thoughtworks colleague is the
  obvious candidate, but the transcript does not support naming one.
- `[14:38]` "the architecture elevator as Gregor Hope talked about". The idea is
  Gregor Hohpe's; the caption spells it otherwise.
- `[09:49]` "I think Nikita uh Berklay, colleague of mine, talks about CPU versus
  GPU". Surname unreliable.
- `[22:45]` "finite state machines or schemas or uh Alium, which I know you've worked
  on". A modelling language addressed to Garner personally. The caption is not a word.
- `[43:02]` "guides as I would say in my taxonomy, which is I don't know, Cloud 9
  documentation on how a particular library works". "Cloud 9" is almost certainly not
  what was said; the sense is vendor documentation handed to an agent as context.
- `[24:47]` to `[24:50]` "might be to use a legacy code base as an article", split
  across two caption lines at "an" and "article". The
  sentence that follows is "it's it's literally telling you one version of the truth
  about what the new system should do", which is the job description of an oracle.
  The caption says "article" and this document does not repair it.

One further artefact worth flagging because it is *not* a garble: at `[58:06]` the
caption reads "Wirth's law", and Ford himself says "I think I've got to pronounce his
name wrong" before saying it. See the summary audit below.

Everything quoted here was checked against `transcript_lines.txt` for its `[MM:SS]`.
Where a quotation spans caption lines it is cited at the timestamp where it begins.
Anything not in quotation marks is this document's synthesis and is marked as such.

## The written summary, checked against the transcript

The operator has been working from a written summary listing eight items. Seven are
right, one is wrong, and several have the emphasis off in ways that matter.

**1. The Swiss Cheese Model of quality. Right, wrong emphasis.** Ford says it
verbatim: "the Swiss cheese model of quality applies here" `[25:28]`, "I have multiple
layers and they have holes in each layer of safety. But so long as those holes don't
line up, then something that goes through a hole at one layer will get caught at
another layer" `[25:34]`. But he is not applying it to *tests*, which is how the
phrase usually travels. He is applying it to **specs**: "when it comes to uh specs, I
think this combination of different ways of looking at the truth are both important
for resilience" `[25:44]`. The layers are a legacy codebase, a finite state machine, a
schema, TLA+, prose. This is the setup for plural specs, not for defence in depth in
QA. Reading it as layered testing loses the argument.

**2. Normative versus informative harnesses. Right, and understated.** Verbatim at
`[41:28]`. What the summary drops is that this is one of **two crossed distinctions**,
and the four cells are the actual claim: "those two distinctions together give you
four kinds of harness" `[41:55]`, and "my thesis is really these four things are all
important, and if you over-index on any subset of harness, you're likely to not get as
good results or as faithful results to your intention as you otherwise would"
`[43:32]`. Presented as a single binary it loses its teeth.

**3. Feed-forward versus feedback. Right.** "a feedback harness is one that is
actually pointed at the code that you generated, and gives you information back into
the coding agent of the generation process" `[39:44]`; "A feed forward harness is one
that gives context to the agent to make a good decision the first time around"
`[40:01]`. Add the provenance the summary omits: "which is a distinction that comes
from control theory" `[41:19]`.

**4. Worsley's Law. Wrong. It is Wirth's law.** `[58:06]`: "I think I've got to
pronounce his name wrong, but Wirth's law, right? So, computers don't get Well,
computers get faster, but computing doesn't get faster." No "Worsley" appears anywhere
in the transcript. The summary's gloss "applied to quality" is fair in substance: Ford
does apply it by analogy, and the load-bearing sentence is `[57:43]`, "the limit of
quality of our software was never about the maximum quality we could achieve, but
basically like the the minimum quality we would accept." He introduces the whole
passage with "I think quality is optional as part of the problem" `[56:54]`.

**5. Decision specs materialising merge conflicts. Right, and the reason is missing.**
"the way that I mostly recommend tackling this is with what I might call decision
specs" `[27:25]`; "you actually get the merge conflict, so to speak, uh materialized
uh, on disk" `[28:45]`. What the summary omits is the *purpose*, which is the single
most relevant sentence in the episode for this method: "if you want to repeat the
process of generating code again, you don't ask the coding agent to pick another
random decision amongst the ways it could resolve it" `[28:34]`. Decision specs exist
to make **regeneration deterministic**. That is our mechanism, arrived at from the
other direction.

**6. Starting with brownfield rather than greenfield. Right, and stronger than the
summary suggests.** "what happens when I'm not just doing greenfield, but I have a
large legacy code base" `[04:02]`. But Ford goes further than "start there": "agentic
engineering at scale seems to be more useful for legacy code where you have something
to get the agent to sink its teeth into" `[06:04]`, and against greenfield demos
directly, "I see a lot of people starting with a blank canvas, and I think that
falsely gives people an idea about what can be achieved with agentic engineering"
`[05:31]`. That last one is aimed squarely at what our demo is.

**7. Elicitation techniques from UX research. Right.** "a lot of the elicitation
techniques you need to work with those human beings is not what I've seen, which is
either ask them to write down everything they know or write down everything based on
what an LM says and then just ask them to review it" `[17:08]`, and "I'm really came
to use those kind of elicitation techniques that the user experience community knows
very well" `[19:10]`. The concrete alternative he gives: "shadow them while they use
the system or give them examples of key use cases and tell them what should happen in
this case" `[17:32]`.

**8. The risk of atrophying tacit knowledge. Right, and the mechanism is worth
carrying with it.** "there's an atrophy of knowledge that was actually important
because you didn't manage to get the balance right" `[51:46]`, ending in "you've kind
of legacified a lot of your estate" `[52:07]`. The summary presents this as a caution;
in the episode it is the second half of an argument whose first half is a business
case (the CTO liquidity story, `[50:30]` onward), and the pair is much stronger than
the caution alone.

**Missing from the summary and worth adding**, in rough order of usefulness to us:
the after-dinner mint, "outside-in constraints and inside-out latitude" `[22:21]`; the
durable-versus-temporary custody quadrants and the term "spec anchored" `[30:41]`;
"spec should not just mean markdown" `[24:03]`; Phoenix architecture and Ford's
scepticism about it `[32:06]`; his stated preference for XP-style thin slices that
change spec and code together `[32:57]`; regenerative engineering `[55:37]`; the
mutation-testing renaissance and the two-day barrier `[36:14]`; and CPU versus GPU,
"I'd get an LLM to help me create deterministic tools that then help it to chew
through the code base" `[10:19]`.

---

## What resonates

Graded honestly. "Close" means the same mechanism for the same reason; "partial" means
the same shape with a different motive; "loose" means an analogy worth one sentence and
no more.

### Decision records that materialise a pruned branch (close)

Ford at `[27:47]`: "where there are ambiguities, you rather than just leaving that
ambiguity to be resolved dynamically through the generation process, you create a
decision record for how you would resolve that." The worked example is a refund path
that contradicts the stated payment flow, where the agent asks "which do you want,
right?" `[28:13]`.

The method: "every divergence between a plan item and what actually happened is a
decision and is recorded in the slice's `decisions.md`, with the pruned branch named"
(`docs/wow/way-of-working.md` §6). Kalibra's slice 01 has fifteen of them, each with
its `*Pruned:*` line.

This is the closest match in the episode, and it is close on the *reason* as well as
the form. Ford wants the record so a second generation does not re-roll the die;
`way-of-working.md` §5 wants regeneration to be a measurement rather than a lottery.
One difference worth naming: Ford's trigger is a contradiction *between spec layers*,
ours is a divergence *between plan and outcome*. Kalibra D-5 happens to be Ford's case
exactly, a conflict between the founding interview and the literature, resolved into a
written decision rather than split silently, and kalibra's way of working §2.1 pre-ranks
the two authorities so that such conflicts have a resolution procedure rather than a
coin toss.

### Guides as feed-forward context (close)

Ford's example of a purely informative feed-forward harness at `[43:09]`: "it might
actually be um a map of the legacy code base, or a map of the current architecture, so
the agent can apply its own judgment." That is `docs/wiki/`, kalibra's
`docs/contexts/`, the glossary, and the read order in `harness.md` §4. His example of a
*normative* feed-forward harness at `[43:22]`, "it could be a list of uh ADRs, or it
could be a schema", is `docs/wow/invariants.md` almost exactly. Two of the four cells
in his taxonomy are things this method already builds deliberately.

### Elicitation by interaction rather than by document review (close)

Ford's complaint at `[18:31]` is specific: "the bottleneck might even be of legacy
modernization done naively as I've seen it done is just how much markdown a subject
matter expert is willing to chew through because we're just generating so much so much
of it", and at `[18:56]` "I also didn't really think it was somehow respectful", and
"it's not what you would do if you wanted them to hand over the system to a new human
being was coming on board. That would not That would involve metaphor and narrative and
examples" `[19:02]` (the stutter is the captioner's, or the speaker's; it is left in).

The workshop did the thing he recommends without having read him. Two recorded rounds
with three groups, transcribed the same afternoon, merged into `dtr.md` (how to build)
and `drt.md` (what to build), with conflicts between groups resolved toward the concept
and *stated in the files*. Nobody was asked to review a markdown file. This is the
method's best existing answer to a live complaint in the episode, and it should be said
that way in the presentation.

### Ratified DONE, and "mark its own homework" (close on mechanism, opposite in rhetoric)

Ford at `[42:11]` describes a normative feedback harness as the thing that lets "the
agent ... mark its own homework to get to a loop into iterate to have I got it right
yet? No, and to keep going." He means it approvingly: this is the inner loop working.

`harness/verify.sh` opens with the same words turned around: "a 'done' claim is
ratified by a script the claimant does not control." These are not in conflict. Ford is
describing the per-iteration check and we are describing the gate, and both are
feedback-normative in his scheme. But the rhetorical inversion is a good slide, because
it names the thing his sentence leaves implicit: marking your own homework is fine when
the marking scheme is not yours.

### Deterministic tools built by the model, run without it (close)

"I wouldn't just get an LLM to chew through the whole code base as it is. I'd get an
LLM to help me create deterministic tools that then help it to chew through the code
base" `[10:14]` to `[10:24]`, framed by a colleague's CPU-versus-GPU distinction
`[09:49]`. Every file in `harness/` is this: `gen_checklist.py` derives the item list so
no model has to be trusted to enumerate it, `scoreboard.py` compares two cycles on
the items both scored, `oracle.py` recomputes the arithmetic from the prose.
This is the single best one-line justification of the harness in the episode, and it
comes from outside the project.

### The fence versus Phoenix architecture (partial, and worth being careful about)

Ford at `[32:06]` names "Phoenix architecture ... the workflow is you change the spec
and you press the generation button again". `harness/README.md` describes a production
pipeline "that destroys and rebuilds a full application from its specification every
week, unsupervised". Those are the same practice.

The match is only partial because of what Ford immediately adds, and the presentation
should not quote the first half without the second. See the disagreements section.

### Modularity as a context-window constraint (partial)

`[14:07]`: "if you don't have any kind of modularity, this causes them to fill up their
token windows very quickly by them being unable to work out what they need to
understand in order to get their job done today", and the architecture elevator point
at `[14:35]`, that agents are worse than people at moving between altitudes, so "all
these details are just noise operating at the same volume" `[14:51]`. Kalibra's bounded
contexts and three tracks are the same instinct. Partial rather than close, because at
demo scale nothing has ever filled a context window, so we have the structure without
the evidence that motivates it.

---

## What deepens it

This is the section with work in it.

### 1. The harness taxonomy, and where our pieces actually fall

Ford's scheme, from `[39:41]` to `[43:48]`, is two crossed axes:

- **feed-forward or feedback**: is the artefact input to generation, or is it "pointed
  at the code that you generated" `[39:47]`?
- **normative or informative**: does it tell "the agent correct or incorrect" `[41:39]`,
  or does it leave the verdict open, as in "It lets the agent perceive what's going on
  and then leaves it up to the agent to know how to use that information" `[42:41]`, so
  that "the agent has to supply the judgment" `[42:49]`?

He names two of the four cells. Checks: "the test tests, or as I would call them checks
to be a little bit more generic" `[42:01]`, feedback and normative. Guides: "pure
guidance, guides as I would say in my taxonomy" `[42:57]`, feed-forward and informative.
He gives examples for the other two without naming them: Playwright as the informative
feedback case, "you give the agent eyes or ears or whatever whatever sensory organ"
`[42:28]`, and ADRs or a schema as the normative feed-forward case `[43:22]`. The word
"sensors" is Garner's, describing the diagram on Ford's wall at `[39:17]`: "I can see
the sensors and checks, I think." It is not established here as Ford's own term, so use
it as a convenient label rather than as a quotation.

Mapping our harness onto the grid, with the misfits kept rather than smoothed:

| Our piece | Ford's cell | Note |
|---|---|---|
| `spec.feature` scenarios, TDD tests | feedback, normative | his own example |
| `verify.sh` gate | feedback, normative | but not a loop signal; the agent cannot iterate against it |
| kalibra's compiled-bundle content probe | feedback, normative | and a lesson he does not have |
| `oracle.py` | feedback, normative | third-party, which his axes cannot express |
| RED/GREEN state instruction | feed-forward, normative | it dictates the next move |
| Spec-lock, scope fence | feed-forward, normative | prohibitions rather than intent |
| INV-1 to INV-12 | feed-forward, normative | his "list of uh ADRs, or ... a schema" |
| Orient step | feed-forward, informative | a forced re-read |
| `docs/wiki/`, `docs/contexts/`, glossary | feed-forward, informative | his "map" |
| Reality audit | informative feedback, judged by a human | the human supplies the judgment |
| Persona missions with a separate judge (retired) | informative feedback | the cell actually filled, once, in the production pipeline this method was distilled from; row added 2026-09-01, see the correction under a |
| `fence.sh` | no cell | it *removes* feed-forward context |
| Held-back items, the visible/held-back gap (retired 2026-09-02, see `harness/README.md`) | feedback, normative, and invisible | no cell for hiding a check |
| `score.py`, `scoreboard.py` | feedback, but not to the agent | it feeds the operator, over weeks |

Three findings fall straight out of that table.

**a. The informative-feedback cell is empty, and that is where our defects live.**
Ford's example is giving the agent eyes. Our only sensor is a person looking at a
phone. Look at what kalibra's reality audit found that way and nothing else did: a
range answer's high-bound field carrying the low bound over, a row overflowing by 98
points at phone width, a correctness label floating between two blocks, a crowd rule
stopping halfway across its card, the curve's answer counts colliding at the 100 rung,
the progress comb rendering an answered segment thinner than its neighbours, and the
Continue button sitting under the software keyboard. None of those is expressible as a
pass/fail against a spec line written in advance, which is exactly Ford's point about
what informative feedback is for.

*What would change:* add a sensor step to the build loop. Screenshots of the real
surface at two widths, handed back to the agent with **no verdict attached** and a
question rather than an assertion, so the agent supplies the judgment. Be honest about
the limit: the fourth-pass defect (a control under the keyboard) would still be missed,
because a screenshot harness has no software keyboard, which is precisely the note
already in kalibra's audit, "no test that inspects a rendered tree has a software
keyboard". A sensor closes some of the gap and not all of it.

**Correction, 2026-09-01, after this section was written.** The heading above is half
wrong, and the wrong half changes what the presentation should claim.

*What survives.* Nothing in this repository, in `saunavuoro`, or in the workshop archive
has ever driven a running interface. Every finding on record in those was found by reading
a document, which is the conclusion `saunavuoro/docs/dtr/four-kinds-of-harness.md` reaches
about itself: "Informative feedback holds nothing that has ever run." The defect list above
is still the evidence for why that matters, and it is still true that all four passes of
kalibra's reality audit were a person looking.

*What does not survive: "our only sensor is a person looking at a phone".* The production
pipeline this method was distilled from once ran persona-driven exploratory missions. An
agent was handed a plain-language user goal, phrased the way a user would phrase it and
naming no selector, endpoint or internal function, drove the real interface through a
browser toolbelt, and recorded a journey log and screenshots. That record was then assessed
by a **separate judging pass**, not by the agent that produced it. That is Ford's
informative-feedback cell built and staffed, with the independence the cell's value depends
on. It is the same shape the master reference this method was distilled from still carries
as a rule (human-realistic missions, where an unreachable path is a finding rather than a
workaround) and `saunavuoro/docs/dtr/gap-analysis.md` §G4 still carries as a degeneration
tell.

*It was retired, and the reason is the interesting part.* It was retired on measured cost,
not on principle: judging was measured at several times the cost of generating, against a
weekly capacity limit that cannot be bought through. So the sentence to say is the harder
one. Our position in this cell is not an absence, it is a **loss**. The one implementation
that filled it could afford to build it and could not afford to run it every week.

*Which cuts against the economics argument in section 6 below.* Ford's claim at `[37:18]`
is that the two-day improvement nobody could justify now costs an afternoon. Our one
measured case says a perception pass was affordable to build and unaffordable to run on a
cadence, which is the barrier moving rather than disappearing: from the cost of building
the thing to the cost of running it. That is not a refutation of his point, it is the
qualification a room of practitioners will supply for us if we do not supply it ourselves.

*What would change, restated.* The proposal above stands with its purpose changed. The job
is not to invent a sensor step, it is to rebuild one inside a cost envelope, which means
deciding in advance what share of a cycle's budget perception may take, and what gets
judged by machine, by a person, or not at all. The honest limit is unchanged: a screenshot
harness has no software keyboard.

*On naming.* That implementation is called "the production pipeline this method was
distilled from" here and nowhere else by any other name, which is the register
`saunavuoro/harness/README.md` already uses in its section "What the full pipeline has that
this demo elides". The shape of a mechanism crosses that boundary. Names, repositories,
paths, figures and cadence dates do not, per
`~/common/projects/assistant/wiki/environment.md`, "The two sides share insight and never
share artefacts".

**b. The grid is a better slide than the current guardrail table.** `harness.md` §3 is
ten rows with no organising principle; it reads as ten war stories, which is honest but
not memorable. Cross-tabulated on Ford's axes, the shape is stark: seven of the ten are
feed-forward normative, one is feed-forward informative (orient), one is feedback
normative (ratified DONE), and one is the human-realistic check. Drawn as a two-by-two
with our pieces dropped in, the empty cell is visible from the back of the room.

**c. Ford's two axes are not enough for what we built, and the third axis is ours.**
Two of our mechanisms have no cell because they are not classified by *what the harness
says* but by *who may see it and who may write it*:

- **Visibility.** `fence.sh` deletes context so it cannot be copied. `scoreboard.py`
  held back a quarter of the items and never listed them, until the holdback was retired
  on 2026-09-02 (`harness/README.md`).
- **Independence.** `verify.sh` is "a script the claimant does not control". `oracle.py`
  is a third implementation of the same arithmetic, written from the prose rather than
  from the results table, because as kalibra's `harness/README.md` puts it, "two tracks
  agreeing proves only that they agree".

Ford's taxonomy classifies harnesses by their *content*. Ours are largely about their
*custody*. The honest way to present this is not to claim he missed something, it is to
say: his two axes, plus a third that a single operator working with agents needs and a
team of humans mostly gets for free, which is who controls the harness. In a room of
Thoughtworks-adjacent people, "we added an axis to Chris Ford's grid" is a stronger and
more defensible claim than any of the resonances.

**Correction, 2026-09-01, examined the same day.** That last claim does not survive being
held against the grid, and it should not be made in that room. Custody was taken apart in
`saunavuoro/docs/dtr/custody.md`, section "Is custody a third axis, or is it what the
crossing requires?", and the answer there is **one axis and a half**.

**Visibility is derived, not additional.** A previous run's output, its failure notes and
the scoring criteria are all informative feedback about one round, and each becomes a
corruption of the measurement the moment it becomes feed-forward input to the next.
Fencing it is therefore not a new dimension. It is the crossing noticing that a signal has
changed quadrant, which makes `fence.sh` the grid's own boundary condition rather than
something outside the grid.

**Independence is genuinely additional.** Neither axis can ask who *authored* the verdict.
A check is normative feedback whether the agent wrote it or somebody else did, and that
difference is the entire content of `verify.sh:2`, a done claim ratified by a script the
claimant does not control. Authorship is a fact about provenance, not about the timing or
the bindingness of a signal, and no crossing of the two axes produces it.

So the line to use is not "we added an axis to Chris Ford's grid". It is: **his two axes,
plus half of one. Visibility falls out of the crossing once you take leakage seriously;
independence does not fall out of anything, and a single operator has to construct it,
because in a team it arrives for free with other people.** That is the smaller claim, and
in exactly the room this document is aimed at it is the stronger one: a Thoughtworks
audience will test a new axis against the grid in the ten seconds after the slide goes up,
and half of ours does not hold. Claiming the half that holds, and naming the half that does
not and why, survives the question from the floor. The presentable version of this position
is `docs/ford-overlap.html`, section "One axis and a half, not a third axis".

### 2. Feed-forward versus feedback: are we unbalanced?

Ford's warning, twice. `[40:33]`: "if you only operate with feedforward, which is
guidance about what you should do, you're not going to have that fine-grained feedback
control to really nail it." And `[41:12]`: "I also see teams communicating just good
intentions through feedforward without the feedback that allows the agent to really mark
its own homework."

The obvious reading is that this describes us: spec first, gate the work, feedback
concentrated at the gate and the reality audit. That reading is wrong, and getting it
right matters.

We are not short of feedback in *volume*. RED/GREEN TDD is as fine-grained as feedback
gets, one signal per test, and it runs inside the loop. What is unbalanced is something
else, and Ford's own axes name it: **we over-index on normative harnesses on both
axes.** Feed-forward informative is well served (the wiki, the contexts, the read
order, the orient step). Feedback normative is very well served (tests, gate, oracle,
content probe, checklist). Feed-forward normative is arguably over-served (RED/GREEN,
spec-lock, scope fence, twelve invariants). Feedback informative is empty. The method
does not lack signal; it lacks *unjudged* signal.

(**Corrected 2026-09-01:** feedback informative is empty in the implementations that can be
shown, and was filled and then retired on cost in the production pipeline this method was
distilled from. See the correction under finding a above. The claim it sits beside is
unaffected: the shortage is of unjudged signal either way.)

The sharpest evidence for this is in our own record, not in the episode. Kalibra's slice
01 ran an unattended night under the tightest feedback discipline available, and the
result was: "The build served the framework's own counter template, not kalibra, and it
had done so for the whole night ... Three test suites were green, the app answered on
its port, the served page said kalibra in its title, and none of that was evidence."
That is not too little feedback. That is a great deal of feedback pointed at something
that was not the product. The fix that followed is a better sentence than anything in
the episode on the same subject: "a probe should test for something only the real
product could produce, and a name is not that: names are carried by templates, error
pages and placeholders."

So: yes, unbalanced, but not along the axis the summary implies, and the presentation
should correct that. Say "we are normative-heavy and perception-light", not "we are
feed-forward heavy".

One more asymmetry the table above surfaces and Ford's frame does not: our most
valuable feedback does not reach the agent at all. `scoreboard.py` reports to a human,
once a cycle. All four passes of kalibra's reality audit were humans looking. Ford
defines a feedback harness as one that "gives you information back into the coding agent
of the generation process" `[39:50]`. By that definition our instrument is not a harness,
it is an instrument, which is what `harness/README.md` already calls it. Keeping those
two words distinct in the presentation is worth doing, because they are answerable to
different readers.

### 3. Plural, multimodal specs, and the Swiss cheese story we are not telling

`[24:32]`: "you need plural specs, you need specs that are multimodal, and you need to
kind of add them up." Then the reason at `[25:59]`: "it allows the human beings who are
working on it to have different affordances for how they might want to describe what
they want."

We already have four modalities and never say so: prose domain pages, Gherkin scenarios,
statecharts (kalibra), and a hand-worked golden fixture with an independent
recomputation. `way-of-working.md` presents these as a **pipeline**, each layer feeding
the next. Ford's frame presents them as **layers of cheese**, each with different holes.
Both are true, and the second is the better argument, because a pipeline implies that a
defect caught late means an earlier layer failed, while the cheese model says a defect
caught late is the system working as designed.

*What would change:* kalibra's "three lenses" section (structure, behaviour, increment)
is already Ford's affordances argument, unmade. State it. And add a sentence to
`harness/README.md` saying which holes each layer does not cover, because at the moment
that is only recoverable by reading the incident list at the bottom.

### 4. Custody: name our quadrant

`[29:50]` through `[31:24]` sets out four positions on two questions: is the code
durable, and is the spec durable. Vibe coding is neither: "you're not taking
responsibility for either side of the artifact" `[30:37]`. The "spec anchored world"
looks after both `[30:41]`. Caring for the spec and not the code is "spec as code kind
of uh uh kind of arrangement" `[30:57]`. And the recommendation is procedural: "one
decision that teams need to make is to be sure that everyone's on the same page as to
which thing they are taking responsibility for custodianship over time" `[31:06]`.

We are in the third quadrant and have never said so in one sentence. `way-of-working.md`
§0 says code is "a build product" and §5 says deleting `impl/` "is not a catastrophe, it
is a measurement", which is the same claim spread over two sections. Ford's grid makes
it a single slide, and it makes the workshop's naive-versus-grounded contrast land
better, because the naive run is his first quadrant exactly.

### 5. Organisational harnesses

`[38:13]`: "some organizations are starting to create organizational harnesses that
where they're sharing things", and the mechanism at `[38:41]`, "you create a skill that
helps the rest of your team to create new property tests in the future", with the
caveat that this "doesn't mean that you don't have to accompany that with human
communication alignment, the kind of socialized tacit tacit knowledge transfer of
explains them why they should care about it".

We have exactly one datapoint and it is better than nothing: the loyly harness was
ported to kalibra on 2026-08-30, and what changed was "the paths, the content probe and
the holdback seed". The mechanisms transferred unchanged, including the inherited
incident list, which is carried across explicitly "because they are the reason the
guards exist". That is a harness behaving like a shareable artefact, with its own
rationale attached, which is Ford's caveat satisfied by construction. It is n equals two
and one operator, and it should be presented as such.

### 6. The economics argument for the instrument

`[37:18]`: "you don't have that thing where you're like, well, I know it could improve
the project, but it's going to take me at least 2 days to figure this out. I don't
really have time this sprint", followed by the social cost, "my teammates are going to
be annoyed at me because I quote wasted time unquote on trying something out", and the
conclusion that "it's only the really passionate people who would make it past that
barrier."

This reframes the whole harness. Honest denominators, a derived checklist and a third
independent implementation of the core arithmetic are each a
two-day project that nobody used to be able to justify. Presenting them as *rigour*
makes them sound like discipline the audience does not have time for. Presenting them
as *things that now cost an afternoon* makes them sound like something to try on
Monday. Same content, and the second framing is Ford's, and it is better.

**Correction, 2026-09-01: we have one measured case where it does not hold.** The framing
holds for the mechanisms listed in the paragraph above, and it holds for a reason worth
naming: every one of them is deterministic, so once it is built, running it again costs a
script's runtime. It does not hold generally, and our own record is the counter-example.
The persona-driven exploratory missions described in the correction to finding 1a were
affordable to build and were retired because they were unaffordable to run every week:
judging cost several times what generating cost, against a weekly capacity limit that
cannot be bought through. Ford's argument is about the cost of *building* a thing that might
help. It says nothing about the cost of *running* it on a cadence, and for a harness that
asks a model for judgment the running cost is the one that decides. Present both: the build
barrier really has fallen, and the recurring bill is now the constraint that retires a
control.

---

## What explains it better

Phrasings to borrow, with what each replaces.

**"our theory of the system hasn't kept up with the system"** `[16:01]`, as the
definition of legacy. Replaces our vaguer talk about docs drifting. It also sets up
`[16:09]`, "now we're afraid to change it because we don't have a way of translating our
needs into intention", which is the cleanest statement of why the spec is the asset.

**"spec should not just mean markdown"** `[24:03]`, with the barb that follows:
"literally people think spec-driven development is a way of making markdown-driven
development sound cool" `[24:08]`. Use this early. The workshop's grounded run risks
being read as "we wrote more markdown", and this line lets us get ahead of that reading
by agreeing with it.

**"outside-in constraints and inside-out latitude"** `[22:21]`. Six words for what the
spec, the fence and the gate do together: fix the boundary, free the interior. We have
no phrase for this and it is the most quotable thing in the episode.

**"you actually get the merge conflict, so to speak, uh materialized uh, on disk"**
`[28:45]`. Better than "the pruned branch is named", because it says why anyone should
care: the ambiguity becomes a thing you can look at, rather than a thing the generator
resolves differently each time.

**"the agent has to supply the judgment"** `[42:49]`. Names what a reality audit is
asking of its reader, and what an informative harness is for.

**"the limit of quality of our software was never about the maximum quality we could
achieve, but basically like the the minimum quality we would accept"** `[57:43]`. The
best available argument for why an instrument exists. A harness does not raise the
ceiling, it raises the floor, and Ford's version of that sentence is sharper than ours.

**"you only realize you've gone through it when you kind of look back and say, 'Oh,
oh, that was a door I walked through'"** `[54:51]`, on tech debt as the worst kind of
one-way door. Pairs with `invariants.md`'s own opening, that a principle without an
enforcement point "stays in the document while quietly ceasing to be true, and nobody
notices". Same failure, two registers.

**"We know more than we can say"** `[49:18]`, Polanyi as quoted by Ford, followed by
"you might think you've got everything written down, but you don't, and that will look
fine until there's some kind of incident or something that unexpectedly stresses the
system" `[49:20]`. Quote this *against* the method, on the slide where documentation is
the source of truth. It is the strongest objection to our position, it is better said
here than we would say it, and putting it up ourselves is worth more than waiting for
the question.

**"regenerative engineering"** `[55:37]`, for the practice of keeping systems "fresh
and green over time" `[55:52]` using the techniques developed for legacy. A better name
for what the weekly rebuild is than "regeneration as a validation instrument".

**"hydrate enough understanding to work on it"** `[49:03]`. Names what the read order
buys a fresh agent.

### Stories worth retelling, with what each illustrates

- **The mainframe post** `[06:58]`: a vendor post on using a coding agent for mainframe
  modernisation where "the message was effectively our thing is so smart that it can
  handle it" `[07:04]`, against which Ford says he would "bet have not tried it because
  they wouldn't recommend that if they had tried it" `[06:45]`. Illustrates the method's
  founding premise: the structure has to be supplied. Retell with care, since it is
  Ford's characterisation of someone else's post.
- **CPU versus GPU** `[09:49]` to `[10:24]`. Illustrates every script in `harness/` in
  one sentence. Probably the single most useful thirty seconds of the episode for us.
- **After-dinner mints** `[21:28]` to `[23:03]`, greenfield inside brownfield, with the
  agent got "to operate within a self-verifiable loop" `[22:53]` inside a boundary described
  by "finite state machines or schemas". Illustrates both why the demo works and, if
  told honestly, why the demo is the easy case.
- **The refund contradiction** `[28:01]`: the agent notices that the stated payment
  flow and an actual code path disagree about whether a refund happens within 14 days,
  and asks which the operator wants. Illustrates a decision spec concretely, with money
  in it. Better than any abstract example we have.
- **Two teams, one red build** `[33:19]`: tests maintained by one team and production
  code by another, "the build would be red in weird ways, and then it would be the job
  of the end of the two-week sprint for the team to frantically reconcile the two"
  `[33:29]`. Illustrates what split custody costs. Ford's verdict, "that eventual
  consistency model I think is not very useful" `[33:44]`, is also, read carefully, an
  argument against one of our guardrails. See the next section.
- **The CTO with two thousand people and no liquidity** `[50:30]` to `[50:58]`: "you
  find that the amount of discretionary focus you have in your organization is
  shockingly low" `[50:53]`, because the people are pinned to the systems whose oral
  tradition they carry. The best non-technical slide available, and the reason a
  business audience should care about externalised intent at all.
- **Mutation testing's renaissance and the two-day barrier** `[36:14]` to `[37:46]`.
  Illustrates why a single operator can now afford an oracle and a derived
  checklist.
- **Wirth's law** `[58:06]` to `[58:26]`: "the faster the hardware gets, the more we
  waste. And the the limit is basically the point at which our users will refuse to
  engage with our software." Illustrates why measuring is the point, and it closes a
  presentation better than it opens one.

---

## Where we disagree, or it does not apply

### 1. Scale. Most of the episode is about a world we have no evidence about

Ford's stated target is "20 to 2,000 developers" `[03:45]`, and he deliberately excludes
what we are: "The thing I deliberately um tried to stay away from is the personal
productivity aspect of using coding agents" `[00:50]`, including "how do I structure my
agents MD better" `[01:05]`. Conway's law, team liquidity, oral tradition, cross-team
spec custody, organisational harnesses: none of these arise for one operator with one
repo. `way-of-working.md` already says the method is "a demo-sized distillation ... what
is missing is only scale". Ford's episode is a decent argument that scale is not "only",
because several of his mechanisms exist to solve coordination problems that a single
operator does not have, and a method that has never met those problems cannot claim to
have solved them. Say this from the stage rather than let the room find it.

### 2. Greenfield. He thinks demos like ours mislead people

`[05:31]`: "I see a lot of people starting with a blank canvas, and I think that falsely
gives people an idea about what can be achieved with agentic engineering." Löyly is
greenfield. Kalibra is greenfield in the strongest possible sense: "kalibra has no code
to describe. The spec leads from the first line." By Ford's argument that is the case
least likely to generalise, and the honest response is not a rebuttal, it is the
sentence kalibra already wrote for itself: "there is nothing to check the spec against
except the sources, and the regeneration instrument in `harness/` is the only thing that
will ever tell us whether the spec is really sufficient." Putting his objection and our
admission on the same slide is stronger than either alone.

### 3. Markdown. He would say the method scales worse than it looks

Under `[24:03]`, most of our specification surface is prose: wiki pages, context pages,
plans, tickets, decisions, runbooks. The non-prose parts (Gherkin, statecharts, the
golden fixture, `oracle.py`) are the minority by volume. Partial defence: the
load-bearing layer is not prose, because the checklist is derived mechanically from the
Gherkin and the arithmetic is pinned by an independent recomputation, so what actually
gates a build is executable. But the *domain model* is prose, and Ford's argument is
that prose is where it stops adding up at scale. We have no counter-evidence and should
not pretend to.

### 4. The fence has no place in his world, and points the other way

Ford's legacy programme is about harvesting what exists: knowledge graphs, parsers,
traces, "even call logs if it's a customer service uh system", and the people who
operate it `[16:25]` onward. The culminating move is to treat the existing system as a
source of truth, "use a legacy code base as an article" `[24:47]`. `fence.sh` does the
exact opposite: it deletes the previous implementation from the object graph so that
nothing can be copied. These are not contradictory, because he is talking about a
codebase you inherited and we are talking about one we generated last week, but the
presentation must not blur them. The one-line version: he mines the old implementation
for intent because nobody wrote the intent down; we delete ours because we did.

### 5. Phoenix architecture: he is sceptical, we do it, and our own instrument says he is right to be

`[32:28]`: "there are there are people who I think maybe talk about this a little bit
more than actually do it because it's it's definitely on the progressive and tricky
end." And the condition he attaches at `[32:38]`: "because you've got such a good set of
harnesses for both quality and preserving the intent that you have, that's fine. You
don't need to look after the code."

Do we have such a good set of harnesses? Kalibra asked and answered: **no.** The
regeneration audit of 2026-08-31 asked whether the app would come back right from the
spec alone and concluded "It would not have", because two of the four defects found by
using the app on a phone "were fixed only in the implementation and in the track's own
decision log, which `harness/fence.sh` removes from a clean room."

The honest position, and it is a good one to hold on stage: we are running the workflow
Ford calls tricky, without yet meeting the condition he says it requires, and we know
that because we built the instrument that measures it. That is a more credible claim
than "it works".

### 6. Spec-lock is a genuine disagreement with his recommendation

Ford's actual recommendation is not Phoenix, it is XP: "in XP style stories that are
thinly sliced that cut through both of them at the same time" `[32:57]`, so that a team
"we'll update both the spec and the code and tests at the same time and in sync in a
small way" `[33:52]`, with split custody dismissed as an "eventual consistency model"
that "is not very useful" `[33:44]`.

Our spec-lock guardrail reverts an agent's edits to spec and plan files during a build
round, on the principle that "specs change in planning rounds, on purpose, not
mid-build". That is deliberately *not* changing both together, and the stated reason is
agent-specific: "An agent that plans and builds in one breath will always produce a plan
that its code satisfies" (`harness.md` §1). Ford's argument is about humans, where that
failure mode is weaker.

Name the cost honestly, because kalibra paid it in the last two days. The research
corpus landed mid-build and disagreed with two decisions; the disagreement was recorded
in three places and deferred into two new planning rounds rather than applied, and D-8's
unsupported threshold of 20 answers stayed in the spec for the whole build. An XP-shaped
workflow would have fixed it where it was found. Ours produced a longer paper trail and
a later fix. Nothing in either project settles which is right, and it should be presented
as an open disagreement with a stated reason rather than as a solved question.

### 7. "quality is optional" cuts against the implicit sales pitch

`[56:54]`, and the pessimism at `[57:23]`: "we as an industry without a culture change at
least will not tolerate improvement in quality. That as soon as if we did sustainably
experience improved quality, we would compensate by doing more of a rush job or engaging
less." Our instrument only helps someone who wants to be measured. Nothing in the
harness creates that want, and a room of practitioners will mostly return to
organisations that behave exactly as Ford predicts. Name the limit; it costs nothing and
it is the difference between a method and a pitch.

### 8. His normative/informative axis has a shelf-life argument attached, and it points at our guardrails

`[44:28]`: "the smarter agents get, the more you can just give them a hint", so that with
an older model or an unfamiliar space "you need to be more prescriptive ... you have to
say, 'No, it has to pass these 500 tests'", while with a stronger model you can hand it
an informative harness and "you can supply the judgment" `[45:10]`. If he is right, some
of our guardrails are scaffolding with an expiry date, spec-lock and the RED/GREEN state
instruction most obviously.

Partial counter, from our own record: the failures kalibra logged were not failures of
intelligence. Serving a framework template for a whole night while three suites stayed
green is a gate-design failure, and a Continue button under a software keyboard is a
perception failure. Neither is fixed by a better model, and both sit in the cells Ford
would keep. So the likely shape is that our *normative feed-forward* guardrails are the
perishable ones and our *custody* mechanisms are not. Worth saying, because it tells an
audience which parts to copy and which to expect to outgrow.

### 9. Where we have an unusually strong answer: the regeneration audit

Ford's decision-spec argument presumes regeneration: "if you want to repeat the process
of generating code again" `[28:34]`. His Phoenix passage presumes the harnesses are good
enough `[32:38]`. Nowhere in the episode does anyone ask the obvious next question,
which is how you would *know* whether your spec can rebuild your system.

Kalibra asked it literally, on 2026-08-31, and wrote down a negative answer with the
mechanism attached. Two of four defects would have come back, because the spec knew that
a range answer claims 90 percent but never said the assessor must be **told** the number
at the moment of answering, and because "Nothing anywhere required that the control which
commits an answer stay reachable while the answer is being given." Both are now rules in
the model with scenarios in `spec.feature`, "so the claim is measured rather than
asserted". And the general lesson is stated in a form that survives the project: "a
defect found by looking at a running app gets fixed where it was found, which is the
implementation, and the spec never hears about it. Every future reality audit should end
with this question asked explicitly, because the answer was no and nobody would have
noticed."

That is a concrete, dated, negative answer to a question the episode raises and leaves
open. It is the strongest thing this method has to say back to Chris Ford, and it should
be the last slide.
