// Offline smoke of the builder host: no live model. Run from .pi/harness/builder-host with
//   GONDOLIN_GUEST_DIR=<builder image> node smoke.ts <smoke-dir> [term|crash]
// Default: 1. the generated prompt against the VM it describes: root, only the allowed hosts, only the room;
// 2. a faux-model run that calls every tool pi's settings select, each in the VM, then the same
//    store and request id again, which finds the settled submission without another model call.
// `term`: a run whose bash call sleeps, for a SIGTERM from outside (settles unanswered, aborted).
// `crash`: the same, killed with SIGKILL; the second call resumes the store and finishes.
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { Message } from "@earendil-works/pi-ai";
import { createModels } from "@earendil-works/pi-ai/models";
import { fauxAssistantMessage, fauxProvider, fauxToolCall, type FauxResponseStep } from "@earendil-works/pi-ai/providers/faux";
import { environmentText, gondolinEnv, runBuilder, toolsFromPiSettings } from "./host.ts";

const context = BACKGROUND_CONTEXT;
const dir = resolve(process.argv[2] ?? "../.state/builder-host-smoke");
const mode = process.argv[3];
const settings = resolve(import.meta.dirname, "../config/pi.builder.settings.json");
const tools = await toolsFromPiSettings(settings);
const check = (ok: boolean, what: string) => {
	console.log(`${ok ? "ok  " : "FAIL"} ${what}`);
	if (!ok) process.exitCode = 1;
};
const fauxModels = (steps: FauxResponseStep[]) => {
	const faux = fauxProvider();
	const models = createModels();
	models.setProvider(faux.provider);
	faux.setResponses(steps);
	return { faux, models, model: { provider: faux.provider.id, modelId: faux.getModel().id } };
};
const sleepCall = fauxAssistantMessage(fauxToolCall("bash", { command: "sleep 120" }), { stopReason: "toolUse" });

if (mode === "term" || mode === "crash") {
	const room = join(dir, `${mode}-room`);
	mkdirSync(room, { recursive: true });
	const store = join(dir, `${mode}.sqlite`);
	const again = existsSync(store);
	const { models, model } = fauxModels(mode === "crash" && again ? [fauxAssistantMessage("Resumed.")] : [sleepCall]);
	writeFileSync(join(dir, `${mode}-instruction.md`), "Sleep.\n");
	const { settled, answer } = await runBuilder({ room, instruction: join(dir, `${mode}-instruction.md`), store, requestId: `${mode}-1`, models, model, tools });
	const reason = "reason" in settled ? settled.reason : "-";
	if (mode === "term") check(settled.status === "unanswered" && reason === "aborted", `after SIGTERM: ${settled.status} ${reason}`);
	else check(again && settled.status === "done" && answer === "Resumed.", `resumed after SIGKILL: ${settled.status} ${JSON.stringify(answer)} (submission ${settled.id})`);
	process.exit();
}

rmSync(dir, { recursive: true, force: true });
const room = join(dir, "room");
mkdirSync(join(room, "src"), { recursive: true });
writeFileSync(join(room, "seed.txt"), "seed\n");
writeFileSync(join(room, "src", "app.clj"), "(ns app)\n(defn hello [] \"hello\")\n");

// 1. The prompt, and the VM it describes.
const prompt = environmentText(tools);
console.log(`--- generated environment prompt (pi settings: ${settings}) ---\n${prompt}\n---`);
{
	const { env, close } = gondolinEnv(room, (t) => process.stderr.write(`[pi-env] ${t}`));
	const run = async (cmd: string) => {
		let out = "";
		const r = await env.exec(cmd, { onOutput: (t: string) => void (out += t) }, context);
		if (!r.ok) throw new Error(`${cmd}: ${JSON.stringify(r.error)}`);
		console.log(`$ ${cmd}\n${out.trimEnd()}\n[exit ${r.value.exitCode}]`);
		return { out, code: r.value.exitCode };
	};
	try {
		const who = await run("id -u; whoami");
		check(prompt.includes("You run as root") && who.out === "0\nroot\n", "prompt says root; id -u is 0, whoami is root");
		const code = (url: string) => run(`curl -sS -m 20 -o /dev/null -w '%{http_code}\\n' ${url} 2>&1`);
		const passed = (o: string) => /^[23]\d\d$/m.test(o.trim());
		const npm = await code("https://registry.npmjs.org/");
		const maven = await code("https://repo1.maven.org/maven2/");
		const hasGit = (await run("command -v git || echo no-git")).out.trim() !== "no-git";
		const git = hasGit ? await run("GIT_TERMINAL_PROMPT=0 git ls-remote https://github.com/ObneyAI/orc.git HEAD 2>&1 | head -3") : undefined;
		const example = await code("https://example.com");
		const plain = await code("http://example.com");
		const ip = await code("http://1.1.1.1");
		const dns = await run("getent hosts example.com; nslookup example.com 2>&1 | tail -3");
		check(prompt.includes("only to registry.npmjs.org, repo1.maven.org") && prompt.includes("dl-cdn.alpinelinux.org"), "prompt names the allowed hosts");
		check(passed(npm.out), "https://registry.npmjs.org/ is reachable");
		check(passed(maven.out), "https://repo1.maven.org/maven2/ is reachable");
		check(!!git && /^[0-9a-f]{40}\tHEAD$/m.test(git.out), "git is in the image, and git ls-remote https://github.com/ObneyAI/orc.git works through Gondolin's HTTP path");
		const apk = await run("command -v jq || echo no-jq; apk add --no-cache jq 2>&1 | tail -2; echo '{\"a\":1}' | jq .a; mkdir -p /workspace/.builder && echo 'apk add --no-cache jq' >> /workspace/.builder/image-additions.txt");
		check(prompt.includes("install it with `apk add` as root") && /^1$/m.test(apk.out), "apk add jq works as root through the Alpine mirror, and jq runs");
		check(readFileSync(join(room, ".builder", "image-additions.txt"), "utf8") === "apk add --no-cache jq\n", "the install command recorded in /workspace/.builder/image-additions.txt is in the host room");
		check(!passed(example.out) && !passed(plain.out) && !passed(ip.out), "https://example.com, http://example.com and http://1.1.1.1 are refused");
		check(!/93\.184\.|23\.\d+\.\d+\.\d+|96\.7\./.test(dns.out), "DNS gives no real answer (Gondolin's synthetic resolver)");
		const where = await run("pwd; ls -A /workspace; test -e /Users && echo HOST-VISIBLE || echo no-/Users");
		check(prompt.includes("/workspace: the builder room") && where.out.includes("seed.txt") && where.out.includes("no-/Users"), "prompt says only the room at /workspace; the room is there, the host's /Users is not");
		const outside = await run(`test -e ${JSON.stringify(dir)} && echo VISIBLE || echo HIDDEN`);
		check(outside.out.includes("HIDDEN"), "the smoke directory around the room is not visible");
	} finally {
		close();
	}
}

// 2. Every selected tool, called by a faux model, in the VM.
{
	const calls: Record<string, Record<string, unknown>> = {
		ls: { path: "." },
		find: { pattern: "*.clj" },
		grep: { pattern: "defn", path: "." },
		read: { path: "seed.txt" },
		write: { path: "made.txt", content: "one\n" },
		edit: { path: "made.txt", edits: [{ oldText: "one", newText: "two" }] },
		bash: { command: "id -u; cat made.txt; ls /" },
	};
	const results: Message[] = [];
	const order = ["write", "edit", "bash", "read", "ls", "find", "grep"].filter((name) => tools.includes(name));
	const steps: FauxResponseStep[] = order.map((name) =>
		fauxAssistantMessage(fauxToolCall(name, (calls[name] ?? {}) as never), { stopReason: "toolUse" }),
	);
	steps.push((ctx) => {
		results.push(...ctx.messages.filter((m) => m.role === "toolResult"));
		return fauxAssistantMessage("Built.");
	});
	const { faux, models, model } = fauxModels(steps);
	const instruction = join(dir, "instruction.md");
	writeFileSync(instruction, "Use every tool.\n");
	const store = join(dir, "session.sqlite");
	const first = await runBuilder({ room, instruction, store, requestId: "smoke-1", models, model, tools });
	check(first.settled.status === "done" && first.answer === "Built.", `run: ${first.settled.status}, answer ${JSON.stringify(first.answer)}`);
	const text = (name: string) => {
		const r = results.find((m) => m.role === "toolResult" && m.toolName === name);
		if (!r || r.role !== "toolResult") return undefined;
		return { error: r.isError, text: r.content.map((c) => ("text" in c ? c.text : "")).join("") };
	};
	for (const name of order) {
		const r = text(name);
		console.log(`--- ${name} ${JSON.stringify(calls[name])}${r?.error ? " (error)" : ""} ---\n${r?.text.trimEnd()}`);
	}
	const ok = (name: string) => (text(name)?.error ? undefined : text(name)?.text);
	check(results.length === tools.length, `every selected tool answered (${results.length}/${tools.length})`);
	check(!!ok("ls")?.includes("seed.txt") && !!ok("ls")?.includes("src/"), "ls lists the room");
	check(ok("find")?.trim() === "src/app.clj", "find finds src/app.clj");
	check(!!ok("grep")?.includes("src/app.clj:2: (defn hello"), "grep finds the defn");
	check(!!ok("read")?.includes("seed"), "read reads seed.txt");
	check(readFileSync(join(room, "made.txt"), "utf8") === "two\n", "write then edit left made.txt = two, in the host room");
	check(!!ok("bash")?.startsWith("0\ntwo\n") && !ok("bash")?.includes("Users"), "bash runs as root in the VM, sees made.txt, no host /Users");
	const second = await runBuilder({ room, instruction, store, requestId: "smoke-1", models, model, tools });
	check(second.settled.id === first.settled.id && second.settled.status === "done", "rerun with the same store and request id finds the same settled submission");
	check(faux.getPendingResponseCount() === 0 && faux.state.callCount === tools.length + 1, `no extra model call on rerun (calls ${faux.state.callCount})`);
	console.log(`store: ${store}`);
}
