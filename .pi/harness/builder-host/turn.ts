// One model turn as a durable pi session, with no tools and so no VM: for ../bin/improve-instruction.sh,
// whose GEPA proposer (ORC's components/gepa) takes any prompt -> response function.
//
//   node turn.ts <session.sqlite> <request-id> <prompt-file>     (LOYLY_PI_MODEL: provider/modelId)
//
// The model runs through pi's own ModelRuntime and auth store, as host.ts does (the existing login;
// nothing logs in). The answer's text goes to stdout. Rerunning with the same store and request id
// finds the settled submission instead of asking again. Exit 0 answered, 1 unanswered, 2 setup error.
import { readFileSync } from "node:fs";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { AssistantEntry, createRegistry, Harness } from "@earendil-works/pi-durable";
import { openNodeSqliteStorage } from "@earendil-works/pi-durable/storage/sqlite/node";
import { modelFromEnv } from "./host.ts";

const context = BACKGROUND_CONTEXT;
const [store, requestId, promptFile] = process.argv.slice(2);
if (!store || !requestId || !promptFile) {
	process.stderr.write("usage: turn.ts <session.sqlite> <request-id> <prompt-file>\n");
	process.exit(2);
}
const model = modelFromEnv();
const { ModelRuntime } = await import("@earendil-works/pi-coding-agent");
const models = await ModelRuntime.create({ refreshOnCreate: false });
if (!models.getModel(model.provider, model.modelId)) {
	process.stderr.write(`model not found: ${model.provider}/${model.modelId}\n`);
	process.exit(2);
}
const harness = await Harness.open(await openNodeSqliteStorage(store), { models, registry: createRegistry() }, context);
let code = 1;
try {
	harness.resume();
	const root = await harness.root(context, { agent: { model, thinkingLevel: "high" } });
	const submission = await root.submit({ type: "input", content: readFileSync(promptFile, "utf8"), requestId }, context);
	const settled = await submission.wait(context);
	if (settled.status === "done" && settled.type === "input") {
		const entry = await root.commit((tx) => tx.entry(AssistantEntry, settled.answer), context);
		const text = (entry?.model ?? [])
			.flatMap((m: { content?: unknown }) => (Array.isArray(m.content) ? m.content : []))
			.filter((c: { type?: string }) => c.type === "text")
			.map((c: { text?: string }) => c.text ?? "")
			.join("\n");
		process.stdout.write(`${text}\n`);
		code = 0;
	} else {
		process.stderr.write(`status=${settled.status} reason=${"reason" in settled ? settled.reason : "?"}\n`);
	}
} finally {
	await harness.close(context);
	await models.settleCredentialWrites();
}
process.exit(code);
