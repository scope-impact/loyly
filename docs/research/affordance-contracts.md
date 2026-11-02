# Affordance contracts for Löyly

## Decision

Löyly's UX is specified as data in `model/loyly/affordances.yaml`. An agent may choose any
concrete UI design that realizes the contract. The same contract drives three renderers:

```text
affordance contract
  ├── human UI
  ├── WebMCP tools
  └── test-step vocabulary
```

The contract is framework-neutral. It names abstract components and accessibility requirements;
a design-system binding identifies the concrete implementation.

## Why this exists

A regenerated UI may change layout, framework, and visual design. Screenshot tests would reject
valid redesigns, while builder-authored tests can exercise APIs that the UI never exposes. The
contract instead fixes discoverability, audience, actions, inputs, outcomes, refusals, navigation,
and API calls, while leaving layout and styling free.

The contract therefore distinguishes:

- **fixed:** actions, audience, inputs, outcomes, refusals, screen reachability, required text,
  tools, and calls;
- **free:** layout, visual design, navigation presentation, non-required wording, and concrete
  widgets within the declared abstract component.

## Contract sections

| Section | Purpose |
|---|---|
| `audience` | Who may perform each action |
| `actions` | Intent, inputs, submission, outcomes, refusals, API call, WebMCP tool |
| `screens` | Where actions are offered, links, reachability, required visible text |
| `ui` | Abstract component and token requirements |
| `conformance` | Rules every generated UI must satisfy |
| `steps` | Mapping from behavioral test language to actions |

## Löyly actions in slice 01

- `ConfigureDays` — manager configures dressing-room days and slot starts.
- `RequestStandingSlot` — resident requests a weekly slot.
- `GiveUpStandingSlot` — resident releases a weekly slot.
- `ReadWeeklySchedule` — resident or manager reads availability.

The contract explicitly makes refusals actionable: a closed-day or held-slot refusal offers a
next action or alternative, rather than ending in an unexplained error.

## ORC integration

Affordance validation is an independent instrument stage after the builder:

```text
cut
→ builder
→ instrument
  ├── Grain Event Model validation
  ├── behavioral verifier
  └── affordance conformance
→ oracle
→ result
```

ORC owns the conformance algorithm, evidence, retries, and stage result. Gondolin only provides
the isolated candidate runtime and primitive browser/process capabilities. The builder must not
modify the affordance contract in the candidate workspace.

Allure measurements should identify:

- `slice_id`;
- `action_id`;
- `screen_id`;
- `tool_name`;
- `component`;
- `binding`;
- `conformance_rule`;
- `cycle_id` and `cut_digest`.

## Relationship to Grain and Allium

```text
Allium                 → domain behavior
Grain Event Model      → CQRS topology
Affordance contract    → user/agent interaction surface
Löyly feature specs    → executable behavioral measurements
ORC                    → regeneration and conformance workflow
```

Affordances are not a replacement for the Grain Event Model or Allium. They describe how actors
can reach behavior through a generated interface. Their API calls should map to Grain commands or
queries, while their outcomes and refusals trace back to domain rules and events.

## Conformance invariants

At minimum, a generated UI must prove:

1. every offered action has a matching form and declared tool;
2. every screen is reachable from `/` through declared links;
3. every control has the declared accessible name and abstract component;
4. required headings, buttons, outcomes, and refusals appear in the required location;
5. forms and tools make exactly the declared API call;
6. refusals offer their declared alternatives;
7. outcomes and refusals use an alert role inside the action card;
8. styling uses the active design-system binding and declared tokens only.

Human usability remains a separate, explicitly sampled judgment for behavior the contract leaves
as `seen` rather than `must`.

## Migration note

The YAML contract is new and should be validated before it becomes a hard gate. First implement a
parser/schema check and a black-box conformance probe, then add it to the ORC instrument stage.
Keep the original Löyly harness as the behavioral parity reference while this check is introduced.
