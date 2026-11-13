// Offline proof of the steer path, no live model: a faux-model builder run whose first tool call
// sleeps in the Gondolin VM while ../bin/steer.sh drops a message into its steer inbox. The host
// (host.ts, runBuilder with steerDir) submits it as a pi-durable steer; it must land in the store
// verbatim as a pi.user entry after that tool round, and the faux model's next turn must see it.
// Also proves ../bin/watch.sh counts it and ../bin/steer.sh refuses a grader and an ended launch.
//   GONDOLIN_GUEST_DIR=<image> node steer-smoke.ts <smoke-dir>
import { execFileSync, spawn } from "node:child_process";
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { createModels } from "@earendil-works/pi-ai/models";
import { fauxAssistantMessage, fauxProvider, fauxToolCall } from "@earendil-works/pi-ai/providers/faux";
import { runBuilder, toolsFromPiSettings } from "./host.ts";

const dir = resolve(process.argv[2] ?? "../.state/steer-smoke");
const bin = resolve(import.meta.dirname, "../bin");
const check = (ok: boolean, what: string) => {
	console.log(`${ok ? "ok  " : "FAIL"} ${what}`);
	if (!ok) process.exitCode = 1;
};
rmSync(dir, { recursive: true, force: true });
const room = join(dir, "room");
const launch = join(dir, "evidence", "launch-1");
const steerDir = join(launch, "steer");
mkdirSync(room, { recursive: true });
mkdirSync(steerDir, { recursive: true });
const store = join(launch, "session.sqlite");
writeFileSync(join(dir, "instruction.md"), "Sleep, then answer.\n");
writeFileSync(
	join(launch, "launch.txt"),
	[`room=${room}`, `store=${store}`, `request_id=launch-1`, `steer_dir=${steerDir}`, `host_pid=${process.pid}`, `start=${new Date().toISOString()}`, ""].join("\n"),
);
const message = "Steer from the supervisor: the suite has been red for 40 minutes; run one failing test alone first.\n";
writeFileSync(join(dir, "steer.md"), message);

let seenByModel = false;
const faux = fauxProvider();
const models = createModels();
models.setProvider(faux.provider);
faux.setResponses([
	fauxAssistantMessage(fauxToolCall("bash", { command: "sleep 20" }), { stopReason: "toolUse" }),
	(context) => {
		seenByModel = JSON.stringify(context.messages ?? context).includes("run one failing test alone first");
		return fauxAssistantMessage("Done.");
	},
]);
const tools = await toolsFromPiSettings(resolve(import.meta.dirname, "../config/pi.builder.settings.json"));
const runP = runBuilder({ room, instruction: join(dir, "instruction.md"), store, requestId: "launch-1", models, model: { provider: faux.provider.id, modelId: faux.getModel().id }, tools, steerDir });

// While the bash call sleeps: steer.sh, as the supervisor would call it (it waits for the host's answer).
await new Promise((r) => setTimeout(r, 8000));
const steer = new Promise<{ code: number | null; out: string }>((done) => {
	const p = spawn(join(bin, "steer.sh"), [join(dir, "evidence"), join(dir, "steer.md")]);
	let out = "";
	p.stdout.on("data", (d) => (out += d));
	p.stderr.on("data", (d) => (out += d));
	p.on("close", (code) => done({ code, out }));
});
const { settled, answer } = await runP;
const s = await steer;
console.log(s.out.trim());
check(s.code === 0, "steer.sh: steered");
check(settled.status === "done" && answer === "Done.", `run settled ${settled.status} ${JSON.stringify(answer)}`);
check(seenByModel, "the model's next turn saw the steer");
const db = new DatabaseSync(store, { readOnly: true });
const users = (db.prepare("select id, record from entries order by id").all() as { id: number; record: string }[])
	.map((r) => ({ id: r.id, rec: JSON.parse(r.record) }))
	.filter((r) => r.rec.kind === "pi.user");
db.close();
const steered = users.find((u) => JSON.stringify(u.rec.model).includes("run one failing test alone first"));
const text = steered ? (steered.rec.model[0].content as string | { text?: string }[]) : "";
const verbatim = typeof text === "string" ? text : text.map((c) => c.text ?? "").join("");
check(!!steered && verbatim === message, `the steer is pi.user entry #${steered?.id} in the store, verbatim`);
const log = readFileSync(join(dir, "evidence", "steers.jsonl"), "utf8").trim().split("\n").map((l) => JSON.parse(l));
check(log.length === 1 && log[0].message === message && log[0].outcome.startsWith("steered"), "steers.jsonl holds it verbatim, with its outcome");
writeFileSync(join(launch, "launch.txt"), `${readFileSync(join(launch, "launch.txt"), "utf8")}end=${new Date().toISOString()}\nexit=0\n`);
const digest = execFileSync(join(bin, "watch.sh"), [join(dir, "evidence")], { encoding: "utf8" });
check(/later inputs \(steers, grader rounds\) 1/.test(digest), "watch.sh counts one steer");
const refuse = (args: string[]) => {
	try {
		execFileSync(join(bin, "steer.sh"), args, { stdio: "pipe" });
		return 0;
	} catch (e) {
		return (e as { status: number }).status;
	}
};
check(refuse([join(dir, "evidence"), join(dir, "steer.md")]) === 2, "steer.sh refuses an ended launch");
const grader = join(dir, "grader", "launch-1");
mkdirSync(grader, { recursive: true });
writeFileSync(join(grader, "launch.txt"), `kind=grader\nstore=${store}\nsteer_dir=${steerDir}\n`);
check(refuse([join(dir, "grader"), join(dir, "steer.md")]) === 2, "steer.sh refuses a grader");
process.exit();
