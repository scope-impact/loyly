# Review and annotation for generated EventStorm HTML

Research date: 2026-11-05  
Scope: the generated Pi Harness EventStorm HTML on GitHub Pages, with comments comparable to Claude Artifacts but without weakening the separation between model data, presentation, review evidence, and the regeneration cycle.

## Decision

Build a small **semantic review layer** for the EventStorm page. Treat each review thread as a W3C Web Annotation, anchor it first to a generated stable model identity, and persist it in a separate **browser SQLite database**. Use the official `@sqlite.org/sqlite-wasm` package in a dedicated worker with SQLite's `opfs-sahpool` VFS. Keep JSON import/export for repository review snapshots and later synchronization.

This is a local-first decision: each browser profile and origin has its own review database. It provides durable offline drafts without an account or remote service, but it does not create shared live comments. Cross-reviewer collaboration must use explicit snapshot exchange, promotion to a GitHub issue, or a later synchronization service.

Do not make text selection, DOM position, screen coordinates, or a GitHub Discussion title the primary anchor. They are useful fallbacks or lower-cost alternatives, but the EventStorm page already has better identities in its source model:

```text
allium:<module>:rule:<rule-name>
allium:<module>:rule:<rule-name>:requires:<canonical-clause-digest>
gherkin:<feature>:item:<item-id>
grain:<block-kind>:<qualified-block-id>
c4:<view-id>:<model-element-id>
```

The review UI should copy Claude Artifacts' interaction shape, not its implementation:

1. A visible **Review** button turns comment mode on.
2. EventStorm targets become keyboard-focusable only in that mode.
3. Enter, Space, click, or tap opens a comment composer anchored to the selected target.
4. Numbered author-labelled markers and outlines appear without replacing the EventStorm color language.
5. A **Show all comments** drawer lists open/resolved threads and moves focus to the target.
6. Escape closes the composer and then exits review mode.
7. Mobile uses a bottom sheet; desktop uses a side panel.

Claude's own accessible workflow uses an explicit comment mode, keyboard traversal between commentable objects, Enter/Space to open markers, Escape to close/leave, author-named markers, an open-comment count, replies, and a “show all comments” list. Those are strong behavioral requirements for this page ([Claude keyboard and screen-reader guide](https://support.claude.com/en/articles/17457950-use-artifacts-with-a-keyboard-or-screen-reader)). Claude also distinguishes view, commenter, and edit access on shared artifacts ([Claude artifact sharing](https://support.claude.com/en/articles/9547008-share-artifacts)).

## Why semantic identity comes first

The W3C Web Annotation model supports a `CssSelector` for an HTML element, a `TextQuoteSelector` containing exact text plus prefix/suffix, and a `TextPositionSelector` containing character offsets. The specification explicitly warns that position selectors are brittle when content changes. CSS selectors can identify DOM elements, while quote selectors preserve human-readable context ([W3C Web Annotation Data Model](https://www.w3.org/TR/annotation-model/)).

For this generated page, a CSS selector such as `[data-review-id="allium:fresh-slice-generation:rule:ApproveExactOracleProposal"]` is substantially stronger than an automatically generated DOM path. The review ID is derived from model identity rather than layout. A quote selector should be stored alongside it as evidence of what the reviewer saw, not as permission to attach to any vaguely similar text.

Recommended target shape:

```json
{
  "@context": "http://www.w3.org/ns/anno.jsonld",
  "id": "urn:uuid:…",
  "type": "Annotation",
  "motivation": "commenting",
  "body": {
    "type": "TextualBody",
    "purpose": "commenting",
    "value": "Does operator approval bind this exact digest?"
  },
  "target": {
    "source": "urn:loyly:pi-harness:event-model",
    "selector": [
      {
        "type": "CssSelector",
        "value": "[data-review-id='allium:fresh-slice-generation:rule:ApproveExactOracleProposal']"
      },
      {
        "type": "TextQuoteSelector",
        "exact": "ApproveExactOracleProposal",
        "prefix": "…",
        "suffix": "…"
      }
    ]
  },
  "creator": { "id": "…", "name": "…" },
  "created": "2026-11-05T…Z",
  "loyly:sourceCommit": "…",
  "loyly:modelDigest": "…",
  "loyly:targetKey": "allium:fresh-slice-generation:rule:ApproveExactOracleProposal",
  "loyly:status": "open"
}
```

W3C motivations already cover `commenting`, `questioning`, `editing`, `moderating`, and `replying`; a reply can target the parent annotation rather than inventing a second thread format ([W3C model motivations](https://www.w3.org/TR/annotation-model/#motivation-and-purpose), [W3C vocabulary: replying](https://www.w3.org/TR/annotation-vocab/#replying)). The Web Annotation Protocol also defines interoperable containers and CRUD operations, but implementing the full Linked Data Platform protocol is unnecessary for the first version; preserving the data model is the valuable part ([W3C Web Annotation Protocol](https://www.w3.org/TR/annotation-protocol/)).

## Version drift and re-anchoring

Every generated `event-model.json` should expose a deterministic model digest and source commit. Every annotation records both plus a compact target snapshot.

On regeneration, classify threads rather than silently moving them:

1. **Attached, unchanged** — stable target key exists and its semantic digest matches.
2. **Attached, changed** — stable key exists but the source/rule/clause digest changed. Show the old snapshot and require reviewer acknowledgement.
3. **Candidate relocation** — stable key disappeared but an exact quote with prefix/suffix exists inside the same module. Offer a manual re-anchor; never accept automatically.
4. **Orphaned** — no unique target. Keep it in “Show all comments” with its source snapshot.
5. **Superseded** — reviewer explicitly links the thread to a replacement target or accepted commit.

This conservative policy matters. Hypothesis combines range, position, and quote selectors in its anchoring code, but its own tracker documents a recent case where fuzzy quote fallback can silently attach to a lookalike after editing ([Hypothesis anchoring source](https://github.com/hypothesis/client/blob/main/src/annotator/anchoring/html.ts), [Hypothesis issue #7571](https://github.com/hypothesis/client/issues/7571)). Apache Annotator's selector tools use exact matching and acknowledge that multiple matches remain possible; however, the Apache repository was archived in 2025, so its design is useful evidence but its package should not become a new dependency ([Apache Annotator overview](https://annotator.apache.org/docs/), [archived project issue list](https://github.com/apache/incubator-annotator/issues)).

Never use a `requires` array index as its only identity. Inserting an earlier clause would move every later comment. Until Allium has explicit clause IDs, use `rule identity + digest(canonical clause text)` and report a changed clause as changed/orphaned rather than guessing.

## Persistence and authentication

GitHub Pages is a static hosting service and does not support server-side languages, so private credentials cannot live in the page ([GitHub Pages documentation](https://docs.github.com/en/pages/getting-started-with-github-pages/creating-a-github-pages-site)). The annotation store must therefore be either an external authenticated service, a separate serverless endpoint, or a user-mediated GitHub workflow.

### Recommended: browser SQLite over OPFS

Use four isolated concerns:

```text
generated event-model.json ──read only──▶ EventStorm renderer
                                          │ stable target key
                                          ▼
                                   review web component
                                          │ worker messages
                                          ▼
                              review_threads / review_comments
                               SQLite WASM · OPFS persistence
```

Suggested tables:

```text
review_threads
  id, artifact_id, target_key, source_commit, model_digest,
  target_snapshot, status, created_by, created_at,
  resolved_by, resolved_at, accepted_commit

review_comments
  id, thread_id, parent_comment_id, body,
  author_id, created_at, edited_at
```

The official SQLite package supports browser use through WebAssembly and requires a worker for OPFS persistence ([SQLite WASM package](https://github.com/sqlite/sqlite-wasm)). The standard `opfs` VFS requires COOP/COEP response headers, which the current GitHub Pages response does not emit. SQLite recommends `opfs-sahpool` for clients unable to set those headers; it works across major browsers without cross-origin isolation and offers strong single-connection performance ([SQLite persistent storage options](https://www.sqlite.org/wasm/doc/trunk/persistence.md)).

Operational constraints:

- Run all SQL and OPFS access in one dedicated worker; keep the UI thread database-free.
- Open one database connection lazily and keep transactions short.
- `opfs-sahpool` is not a transparent multi-tab database. Detect a second active tab with `BroadcastChannel` or Web Locks and make it read-only or ask the user to close the first tab.
- Ask for persistent browser storage with `navigator.storage.persist()` and show whether it was granted. OPFS remains quota-managed; clearing site data deletes it ([OPFS](https://developer.mozilla.org/en-US/docs/Web/API/File_System_API/Origin_private_file_system), [storage eviction](https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria)).
- Version the schema with `PRAGMA user_version`; back up before migrations.
- Provide explicit JSON export/import and a database download. Local persistence is not a backup.
- Store a local reviewer identity as profile metadata, not as authentication. Do not imply that local author names prove identity.
- Nobody through the review component may mutate `event-model.json`, specifications, or accepted review snapshots.

Suggested local tables remain:

```text
review_threads
  id, artifact_id, target_key, source_commit, model_digest,
  target_snapshot, status, created_by, created_at,
  resolved_by, resolved_at, accepted_commit

review_comments
  id, thread_id, parent_comment_id, body,
  author_id, created_at, edited_at

review_meta
  key, value
```

This is the closest match to Claude's access model and gives the page enough data to render marker counts, open/resolved filters, author labels, and “show all” without querying hundreds of unrelated discussion objects.

### Low-operations alternative: giscus per semantic target

Giscus stores comments and reactions in GitHub Discussions, uses GitHub OAuth for commenters, requires no project database, and can be self-hosted ([giscus official site](https://giscus.app/)). Its web component supports `mapping="specific"` with a stable `term`, and changing attributes updates the underlying iframe configuration ([giscus component](https://github.com/giscus/giscus-component)). A single drawer could therefore mount one widget and switch its term to `loyly:<target-key>` when a reviewer selects a card.

Use this only if the team accepts these trade-offs:

- one Discussion per commented target can create a large, noisy Discussion inventory;
- the iframe owns the composer, so the page has limited control over anchoring metadata and workflow fields;
- global marker counts and a cross-target unresolved list require extra GitHub API work;
- the GitHub Discussions GraphQL API requires authenticated OAuth/App access for writes ([GitHub Discussions API](https://docs.github.com/en/graphql/guides/using-the-graphql-api-for-discussions));
- giscus is excellent for comments and replies, but it is not a W3C annotation store or a review-state machine.

Giscus is the best **zero-backend prototype** if comments must be collaborative immediately. It is not the best durable review model.

### Fastest generic web annotation: Hypothesis

Hypothesis already provides a browser overlay, text selection, highlights, page notes, authors, timestamps, tags, replies, share links, groups, search, and moderation. Its service stores annotations and manages users/groups, and pages can embed the client ([Hypothesis system overview](https://web.hypothes.is/help/overview-of-the-hypothesis-system/), [annotation basics](https://web.hypothes.is/help/annotation-basics/)). Private groups restrict reading to invited members; group members can reply to annotations ([Hypothesis groups](https://web.hypothes.is/help/annotating-with-groups/)).

It is a credible **research-review trial**, especially for prose and rule text. It is not the preferred EventStorm solution because reviewers need to comment on semantic objects—command cards, rules, refusal clauses, scenarios—not merely selected text, and because model regeneration creates anchoring risk. Hypothesis “Comments Mode” is page-level rather than text-attached, so it does not solve object anchoring ([Hypothesis Comments Mode](https://web.hypothes.is/help/hypothesis-comments-mode/)).

## Library and service comparison

| Candidate | Anchoring | Threads / identity / persistence | Static Pages fit | Decision |
|---|---|---|---|---|
| **Custom semantic layer + SQLite WASM/OPFS** | Stable `data-review-id` plus W3C CSS/text selectors | Full local control: replies, resolution, model digest, accepted commit; no shared identity | Excellent local-first fit; worker plus WASM assets; one-browser storage | **Recommended immediate design** |
| **Custom semantic layer + Supabase** | Same semantic anchors | Shared threads, authenticated identity, roles, notifications; Auth + RLS | Browser-safe publishable key; external managed service | Optional later sync/collaboration adapter |
| **giscus** | Stable target can map to a `specific` Discussion term; no native text-range model | GitHub identity, comments, replies, reactions, moderation; stored in Discussions | Excellent; no own server/database | **Best zero-backend prototype**, accept workflow limitations |
| **Hypothesis** | Mature text-range anchoring and page notes | Hosted accounts, groups, replies, search, moderation | Excellent embed | **Good short research trial**, not primary EventStorm object review |
| **Recogito Text Annotator** | Text quote + offsets inside a chosen container | Emits create/update/delete events; storage and thread UI remain application concerns | Can run client-side | Useful optional text-selection component, not the whole review system. Its current model aligns closely, but not exactly, with W3C ([official repository](https://github.com/recogito/text-annotator-js)). |
| **Liveblocks Comments** | Application-defined metadata can carry target keys; examples cover overlay and text comments | Rich threads, replies, resolution, mentions, reactions, notifications | Production authentication normally requires a backend token endpoint | Strong hosted product if a serverless auth boundary is acceptable, but excessive for the current framework-free Pages artifact ([Comments](https://liveblocks.io/docs/products/comments), [authentication](https://liveblocks.io/docs/api-reference/authentication)). |
| **Annotorious** | Image regions | Application storage integration | Client-side | Not applicable: its official scope is image annotation ([getting started](https://annotorious.dev/getting-started/)). |
| **Apache Annotator** | High-quality W3C CSS, quote, position and range utilities | Explicitly not a store or complete UI | Requires bundling | Do not adopt: project archived; retain its exact-anchor principles. |

## Review interaction specification

### Desktop

- Review toggle in the sticky header, labelled with open count: `Review · 7 open`.
- Review mode adds a neutral outline and small numbered pin to commentable elements. Do not recolor the stickies: blue/orange/red/green/violet already carry EventStorm meaning.
- Hover may preview a target, but every operation must work with keyboard and click.
- Selecting a target opens a right-side thread panel; the target remains visible and outlined.
- “Show all” lists threads grouped by `Open`, `Changed target`, `Orphaned`, and `Resolved`.
- Each thread exposes copy-link, reply, resolve/reopen, and “record decision” actions according to role.

### Mobile

- Review toggle stays in the top bar.
- Tapping a target opens a bottom sheet with the target breadcrumb, source snapshot, and thread.
- The sheet has a drag handle but does not require drag; Close and Back are real buttons.
- Markers are at least standard touch-target size and never depend on hover.
- Text-range annotation is secondary on mobile; whole-object comments are the reliable default.

### Accessibility

- On entering review mode, add `tabindex="0"` and `aria-describedby` to registered targets; remove them on exit so the normal page does not become a hundreds-stop tab sequence.
- Marker buttons are named `Comment by <author> on <target label>`.
- The composer is a labelled dialog or complementary panel with focus trapping and focus return.
- Enter/Space selects a target, Escape closes one layer at a time, and the all-comments list can move focus to the target.
- Announce thread creation, resolution, and orphaning through a polite live region.
- Respect reduced motion and never encode status by color alone.

These requirements follow the observable Claude Artifacts keyboard model while adapting it to EventStorm cards ([Claude accessibility guide](https://support.claude.com/en/articles/17457950-use-artifacts-with-a-keyboard-or-screen-reader)). Hypothesis publishes a VPAT and documents some remaining structural-navigation limitations, another reason to test the actual composite experience rather than assuming an embedded product makes the page accessible ([Hypothesis VPAT response](https://web.hypothes.is/vpat-response-doc/)).

## Repository handoff and authority

Annotations are **review evidence, not behavioral authority**. They must not enter a fresh builder or change the specification automatically.

Use this lifecycle:

```text
open comment
  → discussion / replies
  → proposed decision
  → maintainer accepts or rejects
  → accepted decision changes model/ through an ordinary reviewed commit
  → thread records that commit and resolves
  → regenerated EventStorm page shows the new authoritative model
```

Provide two export paths:

1. **Review snapshot** — download W3C-shaped JSON for audit or commit under a review/evidence directory, keyed by source commit and model digest.
2. **Promote to tracked work** — open a prefilled GitHub issue containing target key, source file, model digest, comment permalink, and proposed decision. GitHub officially supports prefilled issue title/body/labels through URL query parameters, so no browser token is required ([GitHub issue URL parameters](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/creating-an-issue#creating-an-issue-from-a-url-query)).

Do not export directly into `model/`, do not let “Send to agent” bypass a human decision, and do not include unresolved review comments in a clean-room cut. A later implementation may offer “Draft a model change,” but the output must remain a proposal for the operator to review and commit.

## Suggested implementation sequence

### Spike 1 — anchoring and local SQLite review

1. Add deterministic `data-review-id`, `data-review-kind`, `data-source-file`, and `data-source-digest` attributes during HTML rendering.
2. Add a repository interface with an in-memory implementation for deterministic UI tests.
3. Add an official SQLite WASM worker using the `opfs-sahpool` VFS and schema migrations.
4. Add review mode, focus behavior, marker UI, side panel/bottom sheet, replies, and open/resolved state.
5. Import/export W3C-shaped annotation JSON and provide a visible storage/persistence status.
6. Regenerate after controlled rule, clause, layout, and label changes; prove attached/changed/orphaned classifications.
7. Test first-tab/second-tab behavior, storage clearing, denied persistence, migrations, keyboard, screen reader, 390px mobile, and reduced motion.

Pass condition: layout-only changes preserve every semantic anchor; a changed rule is flagged; a removed target is orphaned; no comment silently moves.

### Spike 2 — repository handoff

1. Add review snapshot export keyed by source commit and model digest.
2. Add prefilled GitHub issue creation for promoted decisions.
3. Keep unresolved review evidence out of `model/` and builder inputs.
4. Add optional snapshot import/merge with explicit conflict reporting.

### Optional Spike 3 — shared collaboration

Only if reviewers need live shared threads, add a remote adapter behind the same repository interface. Supabase Auth and RLS are a reasonable managed choice; giscus remains the lowest-operations GitHub-native alternative. SQLite stays the local cache/draft store rather than pretending to be a multi-user server.

### Optional comparison spike

Mount one giscus drawer keyed by semantic target on a throwaway category, and embed Hypothesis on a test build. Use five reviewers to compare:

- time to first comment;
- correct-target rate after regeneration;
- mobile completion rate;
- keyboard completion rate;
- ability to find all open comments;
- ability to link a resolved comment to a specification commit.

Keep the comparison disposable. Select the backend only after the semantic anchor contract is proven.

## Bottom line

The annotation library is not the hard part. The durable value is the **anchor contract and authority boundary**:

> Reviewers comment on stable model identities. Regeneration may reveal that a target changed or disappeared, but it never silently moves a comment, and no comment becomes specification until a human accepts a model commit.

Implement that contract with a framework-free review web component, a W3C-shaped schema, and official SQLite WASM persisted through `opfs-sahpool`. Treat it as durable local storage with explicit export—not as shared collaboration or a backup. Add a remote adapter only when multiple reviewers genuinely need live shared threads, and never let the review store dictate the source-of-truth model.
