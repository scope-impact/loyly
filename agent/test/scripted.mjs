// A driver process whose model is pi-ai's faux provider, answering from a script, so the
// loop's durability can be tested by killing a real process and starting another one.
//   node scripted.mjs ROOT STORE SCRIPT_JSON OPTIONS_JSON
// SCRIPT_JSON: [{ "tools": [{ "name": "write", "args": {...} }] } | { "text": "..." }, ...]
import { createModels, fauxAssistantMessage, fauxProvider, fauxText, fauxToolCall } from "@earendil-works/pi-ai";

import { openLoop } from "../loop.mjs";

const [root, store, script, extra] = process.argv.slice(2);
const faux = fauxProvider();
const models = createModels();
models.setProvider(faux.provider);
const model = faux.getModel();
faux.setResponses(
	JSON.parse(script).map((step) =>
		step.tools
			? fauxAssistantMessage(step.tools.map((t) => fauxToolCall(t.name, t.args)), { stopReason: "toolUse" })
			: fauxAssistantMessage([fauxText(step.text)]),
	),
);
const loop = await openLoop({
	root,
	store,
	models,
	model: { provider: model.provider, modelId: model.id },
	...JSON.parse(extra || "{}"),
	log: (line) => console.log(line),
});
const cycle = await loop.drive();
const outbox = await loop.flush(20_000);
console.log(`OUTBOX ${JSON.stringify(outbox ?? null)}`);
console.log(`FAUX_CALLS ${faux.state.callCount}`);
console.log(`CYCLE ${JSON.stringify(cycle)}`);
await loop.close();
