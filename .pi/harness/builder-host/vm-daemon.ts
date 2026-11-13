// The pi-env daemon inside a Gondolin VM, with this process's stdin and stdout passed through.
// pi-env's Connection starts it as `node vm-daemon.ts <room> serve --token <hex>`: the VM boots from
// GONDOLIN_GUEST_DIR (Gondolin reads it), mounts <room> at /workspace and nothing else of the host,
// gets pi-env's packaged static Linux daemon copied in, and runs it. Same shape as p2's sandbox
// launcher (earendil-works/pi apps/p2/plugins/envs/sandbox/p2-sandbox.mjs), without SSH or a supervisor:
// one VM per connection, closed when the connection closes (SIGTERM, or stdin's end).
// Network, mount and user follow policy.ts: HTTP(S) only to ALLOWED_HOSTS (none: no egress at all),
// the room alone at GUEST_WORKSPACE, and the daemon refuses to serve unless it runs as GUEST_USER.
import { readFileSync } from "node:fs";
import { basename, resolve } from "node:path";
import { createHttpHooks, type HttpHooks, RealFSProvider, VM } from "@earendil-works/gondolin";
import { packagedDaemon } from "@earendil-works/pi-env";
import { ALLOWED_HOSTS, GUEST_USER, GUEST_WORKSPACE } from "./policy.ts";
const GUEST_DAEMON = "/opt/pi-env/pi-env";

const [roomArg, ...daemonArgs] = process.argv.slice(2);
if (!roomArg || daemonArgs[0] !== "serve") {
	process.stderr.write("usage: vm-daemon.ts <room> serve --token <hex>\n");
	process.exit(2);
}
const room = resolve(roomArg);

// HTTP(S) only to ALLOWED_HOSTS, by exact name. The request check runs before the host resolves the
// name (Gondolin checks `allowedHosts` itself only after resolving, which would leak other names to
// the host's DNS); Gondolin's own check then still applies, with internal address ranges blocked.
const allowed = new Set(ALLOWED_HOSTS.map((host) => host.toLowerCase()));
const httpHooks: HttpHooks =
	allowed.size === 0
		? { isRequestAllowed: () => false, isIpAllowed: () => false }
		: createHttpHooks({
				allowedHosts: [...allowed],
				isRequestAllowed: (request) => allowed.has(new URL(request.url).hostname.toLowerCase()),
			}).httpHooks;

// The VM's size: 16G and 10 CPUs unless the environment (which pi-env passes through) says
// otherwise. Gondolin's defaults (1 GB, 2 CPUs) starved runner cycle 08's suite, and runner cycle
// 11's suite (six JVMs per test) was CPU-bound at 8G/6; the host has 48 GB and 14 cores.
//   LOYLY_VM_MEMORY  qemu syntax, a positive integer with an optional K/M/G/T suffix ("16G")
//   LOYLY_VM_CPUS    a positive integer ("10")
const vmMemory = process.env.LOYLY_VM_MEMORY?.trim() || "16G";
const vmCpus = process.env.LOYLY_VM_CPUS?.trim() || "10";
if (!/^[1-9]\d*[KMGT]?$/i.test(vmMemory)) {
	process.stderr.write(`vm-daemon: LOYLY_VM_MEMORY=${vmMemory}: expected a positive integer with an optional K/M/G/T suffix\n`);
	process.exit(2);
}
if (!/^[1-9]\d*$/.test(vmCpus)) {
	process.stderr.write(`vm-daemon: LOYLY_VM_CPUS=${vmCpus}: expected a positive integer\n`);
	process.exit(2);
}

const vm = await VM.create({
	httpHooks,
	sessionLabel: `loyly-builder ${basename(room)}`,
	vfs: { mounts: { [GUEST_WORKSPACE]: new RealFSProvider(room) } },
	memory: vmMemory,
	cpus: Number(vmCpus),
});

let closing: Promise<void> | undefined;
const close = (code: number) => {
	closing ??= vm.close().finally(() => process.exit(code));
};
process.once("SIGTERM", () => close(143));
process.once("SIGINT", () => close(130));

try {
	const user = (await vm.exec(["/usr/bin/id", "-un"])).stdout.trim();
	if (user !== GUEST_USER) throw new Error(`the VM runs as ${user || "?"}, not ${GUEST_USER}`);
	const arch = (await vm.exec(["/bin/uname", "-m"])).stdout.trim();
	const daemon = packagedDaemon({ platform: "linux", arch: arch === "aarch64" ? "arm64" : "x64" });
	await vm.fs.mkdir("/opt/pi-env", { recursive: true });
	await vm.fs.writeFile(GUEST_DAEMON, readFileSync(daemon));
	const chmod = await vm.exec(["/bin/chmod", "755", GUEST_DAEMON]);
	if (chmod.exitCode !== 0) throw new Error(`chmod pi-env: ${chmod.stderr.trim()}`);
	const proc = vm.exec([GUEST_DAEMON, ...daemonArgs], {
		stdin: process.stdin,
		stdout: "pipe",
		stderr: "pipe",
		cwd: GUEST_WORKSPACE,
	});
	proc.stdout?.pipe(process.stdout);
	proc.stderr?.pipe(process.stderr);
	const result = await proc.result;
	close(result.exitCode);
} catch (error) {
	process.stderr.write(`vm-daemon: ${error instanceof Error ? error.message : String(error)}\n`);
	close(1);
}
