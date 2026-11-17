#!/usr/bin/env node
// Drive one build cycle of docs/wow/harness.md, durably. Kill it at any point and run the
// same command again: it continues the round it was in. See agent/README.md.
//
//   node agent/run.mjs [--model provider/modelId] [--rounds N] [--review-every N]
//                      [--max-turns N] [--store FILE]
//   node agent/run.mjs status [--store FILE]
//   node agent/run.mjs reset  [--store FILE]     forget the cycle; the next run starts anew
//
// Slack (agent/slack.mjs): set SLACK_BOT_TOKEN and SLACK_CHANNEL for a thread per cycle,
// or SLACK_WEBHOOK_URL for plain messages; --no-slack turns it off.

import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

import { createModels } from "@earendil-works/pi-ai";

import { openLoop, resetStore } from "./loop.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");

const { values, positionals } = parseArgs({
	allowPositionals: true,
	options: {
		model: { type: "string", default: process.env.LOYLY_AGENT_MODEL ?? "anthropic/claude-opus-5-5" },
		rounds: { type: "string", default: "20" },
		"review-every": { type: "string", default: "4" },
		"max-turns": { type: "string", default: "40" },
		store: { type: "string", default: path.join(root, ".loyly-agent", "cycle.sqlite") },
		"no-slack": { type: "boolean" },
		help: { type: "boolean", short: "h" },
	},
});

if (values.help) {
	console.log(
		"usage: node agent/run.mjs [status|reset] [--model provider/modelId] [--rounds N] [--review-every N] [--max-turns N] [--store FILE] [--no-slack]",
	);
	process.exit(0);
}

const command = positionals[0] ?? "drive";
const store = path.resolve(values.store);

if (command === "reset") {
	await resetStore(store);
	console.log(`removed ${path.relative(process.cwd(), store) || store}; the next run starts a new cycle`);
	process.exit(0);
}

if (command === "status" && !existsSync(store)) {
	console.log("no cycle in this store; `node agent/run.mjs` starts one");
	process.exit(0);
}

const slash = values.model.indexOf("/");
if (slash < 1) {
	console.error(`--model must be provider/modelId, got "${values.model}"`);
	process.exit(2);
}
const model = { provider: values.model.slice(0, slash), modelId: values.model.slice(slash + 1) };

const models = createModels();
if (command !== "status") {
	let providerModule;
	try {
		providerModule = await import(`@earendil-works/pi-ai/providers/${model.provider}`);
	} catch {
		console.error(`pi-ai has no provider "${model.provider}"`);
		process.exit(2);
	}
	const factory = Object.entries(providerModule).find(([name, value]) => name.endsWith("Provider") && typeof value === "function");
	if (!factory) {
		console.error(`pi-ai's provider module "${model.provider}" exports no provider factory`);
		process.exit(2);
	}
	models.setProvider(factory[1]());
}

const loop = await openLoop({
	root,
	store,
	models,
	model,
	maxRounds: Number(values.rounds),
	reviewEvery: Number(values["review-every"]),
	maxTurns: Number(values["max-turns"]),
	readOnly: command === "status",
	slack: values["no-slack"] ? false : undefined,
	log: (line) => console.log(`[loyly-agent] ${line}`),
});

let stopping = false;
for (const signal of ["SIGINT", "SIGTERM"]) {
	process.on(signal, () => {
		if (stopping) process.exit(130);
		stopping = true;
		console.log(`[loyly-agent] ${signal}: closing; run again to continue where this stopped`);
		loop.close().finally(() => process.exit(130));
	});
}

try {
	if (command === "status") {
		printStatus(await loop.read());
		const outbox = await loop.outbox();
		if (outbox)
			console.log(
				`slack: ${outbox.sent} sent, ${outbox.queue.length} queued, ${outbox.dropped} dropped${outbox.threadTs ? `, thread ${outbox.threadTs}` : ""}${outbox.lastError ? `; last error: ${outbox.lastError}` : ""}`,
			);
	} else if (command === "drive") {
		const cycle = await loop.drive();
		printStatus(cycle);
		const outbox = await loop.flush();
		if (outbox?.queue.length) console.log(`[loyly-agent] ${outbox.queue.length} Slack message(s) not yet delivered; the next run sends them`);
		process.exitCode = cycle.phase === "done" ? 0 : 1;
	} else {
		console.error(`unknown command "${command}"`);
		process.exitCode = 2;
	}
} finally {
	if (!stopping) await loop.close();
}

function printStatus(cycle) {
	console.log(`phase ${cycle.phase}, round ${cycle.round}, TDD ${cycle.tdd}`);
	if (cycle.pending) console.log(`in flight: ${cycle.pending.requestId} (stage ${cycle.pending.stage}); run again to continue it`);
	if (cycle.ratified) console.log(`RATIFIED at ${cycle.ratified.at}: ${cycle.ratified.summary}`);
	for (const h of cycle.history) {
		const notes = h.notes.length ? `  [${h.notes.join("; ")}]` : "";
		console.log(`  ${String(h.round).padStart(3)} ${h.kind.padEnd(6)} ${h.tddBefore}->${h.tddAfter} ${h.outcome}${notes}`);
	}
}
