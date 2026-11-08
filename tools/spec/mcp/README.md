# spec-review: review the spec with an agent

> **Spec v1 only; it does not work with the current page tool.** This app reviews spec v1 in
> `.pi/model/` (kept as history). Since `b754644` the renderer it reuses
> (`tools/spec/views/extract.clj`, `render.clj`) reads spec v2 in `.pi/spec/`, so the app no
> longer works; it works from `6d42c8e`, the last commit whose renderer reads `.pi/model/`.

An [MCP App](https://github.com/modelcontextprotocol/ext-apps) (spec `2026-01-26`) for reviewing
the Pi harness spec in `.pi/model/` in a conversation. In a host that renders MCP Apps it shows
the event-model page with review controls; everywhere else every tool answers in text. It is a
spec tool for planning rounds: it never builds or ratifies an implementation.

Data comes from the spec tools themselves, never a copy of their logic:
`tools/spec/coverage.py --report` (the gate's classification of every obligation) and
`tools/spec/views/extract.clj` (the page's extraction, loaded by `bb/spec-data.clj`). The UI is the
page's own renderer in app mode (`render.clj --mode app --app-js dist/app.js --threads …`, the
`RENDER_APP` constant in `src/server.ts`); `src/app.ts` only wires the controls the renderer draws
(`data-review-action` comment / discuss / accept, `data-threads-for` badges) to the SDK.

## Tools

| Tool | What it does |
| --- | --- |
| `spec_review(rule?, module?)` | Opens the UI (`_meta.ui.resourceUri = ui://spec-review/event-model.html`) at a rule or module. Text: the coverage line, drafts and open threads counted, top uncovered |
| `spec_rule_card(rule)` | The rule card as Markdown: GIVEN, WHEN, ONLY IF (each `requires` with its `@allium-rule-failure.<Rule>.<n>` tag and status), THEN, EXAMPLES (item ids, drafts marked), open threads |
| `spec_uncovered(module?)` | Must-cover obligations nothing covers, and how many only drafts cover |
| `spec_drafts(module?)` | Draft scenarios with their obligations, the `Rule:` each goes under, the suggested tier |
| `spec_threads(target?, status?)` | Review threads |
| `spec_comment(target, text, author?)` | Adds to the open thread on a target, or starts one |
| `spec_resolve_thread(id, note)` | Resolves a thread with a note |
| `spec_accept_draft(slice, title, tier?)` | **Edits the spec** (annotated `destructiveHint: true`, so hosts ask first). Product owner only: moves the draft from `drafts.feature` to the end of its `# Goes under: Rule:` in `spec.feature` (another slice's when the comment names one, a new `Rule:` for `new Rule:`), drops `@draft`, keeps its `@allium-` tags, adds the tier, runs `harness/stamp_item_ids.clj`, puts the `@item` on the tag line, runs `coverage.py`, reports before and after |

Targets: `rule:<Name>`, `obligation:<id>`, `scenario:<item-id>` (e.g. `01-006`),
`draft:<slice>:<title>`. Threads live in `.pi/model/review.edn` (created on the first comment):
`{:threads [{:id "t1" :target "rule:ApproveSelection" :status :open :entries [{:author :at :text}]}]}`.
Comments never touch the spec; only `spec_accept_draft` does.

## Build and test

```bash
cd tools/spec/mcp
npm install && npm run build      # tsc for the server, esbuild for the wiring script
npm test                          # test/smoke.mjs (stdio) and test/http.mjs (HTTP mode)
```

`test/smoke.mjs` reads the real spec, writes threads only to a temporary store
(`SPEC_REVIEW_STORE`), and accepts drafts only in a temporary `git worktree` (`SPEC_REVIEW_ROOT`),
which it removes. Needs `node` 20+, `bb`, `python3`, `allium`; the UI also needs the Clojure CLI
(the renderer evaluates the Grain Event Model).

Environment: `SPEC_REVIEW_ROOT` (the repo whose spec the tools read and edit; default this
checkout), `SPEC_REVIEW_STORE` (default `<root>/.pi/model/review.edn`). The UI always renders this
checkout's spec.

## Add it to a host

All hosts start it over stdio: `node /abs/path/to/loyly/tools/spec/mcp/dist/server.js`.

**Claude Desktop** (renders MCP Apps): `claude_desktop_config.json`

```json
{ "mcpServers": { "spec-review": { "command": "node", "args": ["/abs/path/to/loyly/tools/spec/mcp/dist/server.js"] } } }
```

**claude.ai and Claude mobile** need a remote (HTTP) connector: see "Use from Claude mobile".

**VS Code** (Copilot agent mode; renders MCP Apps in recent versions): `.vscode/mcp.json`

```json
{ "servers": { "spec-review": { "type": "stdio", "command": "node", "args": ["${workspaceFolder}/tools/spec/mcp/dist/server.js"] } } }
```

**Pi**: `pi mcp add spec-review -- node /abs/path/to/loyly/tools/spec/mcp/dist/server.js`
(user level; `-l` writes the project's `.pi/mcp.json`), then `pi mcp list`. Pi uses the tools and
their text; it does not render the UI.

**Claude Code** (text only): `claude mcp add spec-review -- node tools/spec/mcp/dist/server.js`,
or a project `.mcp.json`:

```json
{ "mcpServers": { "spec-review": { "command": "node", "args": ["tools/spec/mcp/dist/server.js"] } } }
```

### Hosts without UI

A host that does not advertise the `io.modelcontextprotocol/ui` extension ignores `_meta.ui` and
shows each tool's text: `spec_review` returns a summary instead of the page, and the agent reads
cards with `spec_rule_card`, comments with `spec_comment`, and so on. Nothing needs the UI.

## The UI

The `ui://` resource (`text/html;profile=mcp-app`) is the page in app mode with the wiring script
inlined. Mermaid and the fonts load from CDNs, declared in `_meta.ui.csp.resourceDomains`
(`https://cdn.jsdelivr.net`, `https://fonts.googleapis.com`, `https://fonts.gstatic.com`); a host
that refuses them shows the page without diagrams and in fallback fonts. Comment opens a box under
the button and calls `spec_comment`; Discuss sends the rule card (or the target's threads) into the
conversation with `ui/message`; Accept needs a second click and calls `spec_accept_draft`.

## Use from Claude mobile

Claude mobile renders MCP Apps only for remote connectors added on claude.ai; it cannot reach a
local stdio server. So run the server in HTTP mode and expose it.

```bash
export SPEC_REVIEW_TOKEN=$(openssl rand -hex 24)
node tools/spec/mcp/dist/server.js --http 8765        # binds 127.0.0.1 only
```

- Streamable HTTP at `http://127.0.0.1:8765/mcp/<SPEC_REVIEW_TOKEN>`; every other path is 404. It
  refuses to start without a token (16+ characters of `[A-Za-z0-9_-]`).
- Remote-safe by default: `spec_accept_draft` is not registered unless `SPEC_REVIEW_ALLOW_EDITS=1`.
  Comments and resolutions stay; they write only `.pi/model/review.edn`.
- Every tool call is logged with a timestamp to stderr.

Expose it with Tailscale Funnel:

1. `tailscale up`, and enable Funnel for this machine in the tailnet policy (admin console).
2. `tailscale funnel 8765`: it serves `https://<machine>.<tailnet>.ts.net`.
3. On claude.ai in a browser: Settings → Connectors → Add custom connector, URL
   `https://<machine>.<tailnet>.ts.net/mcp/<SPEC_REVIEW_TOKEN>`.
4. In the Claude mobile app, enable the connector in a chat and ask for the spec review.

The Mac must be awake with the server and Funnel running. Stop Funnel (`tailscale funnel 8765 off`
or `tailscale funnel reset`) when you are not reviewing.

**Risk.** This is authless: the URL path is the only secret. Anyone with the URL can read the
whole spec and write review threads (and, with `SPEC_REVIEW_ALLOW_EDITS=1`, edit `spec.feature`).
The URL is stored by claude.ai and may appear in logs. Use a long random token, rotate it (restart
with a new one, update the connector) if it leaks, keep edits off, and keep Funnel off when idle.
