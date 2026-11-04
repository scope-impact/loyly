// Prove the VM side works before any cycle starts. Run from .pi/harness:
//   node scripts/verify-platforms.mjs --all               --archives, then every probe in Gondolin
//   node scripts/verify-platforms.mjs --archives          load every runtime archive in a deny-egress
//                                                         Gondolin grader VM and start each image offline
//   node scripts/verify-platforms.mjs <probe> gondolin    one platform probe inside the VM, images only
//                                                         from its archive
//   node scripts/verify-platforms.mjs <probe> host        same probe on the host Docker (development aid)
// <probe> is a directory under fixtures/platforms/, e.g. aws-serverless-typescript.
import { spawnSync } from "node:child_process";
import { readdir, realpath } from "node:fs/promises";
import path from "node:path";
import { ReadonlyProvider, RealFSProvider, VM } from "@earendil-works/gondolin";

const harnessRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const platformsRoot = path.join(harnessRoot, "fixtures/platforms");
const runtimeImagesPath = process.env.PI_HARNESS_RUNTIME_IMAGES_ROOT ?? path.join(harnessRoot, ".state/runtime-images");
const [target, backend = "gondolin"] = process.argv.slice(2);

const daemon = "mkdir -p /sys/fs/cgroup && (mountpoint -q /sys/fs/cgroup || mount -t cgroup2 none /sys/fs/cgroup) || exit 1; "
  + "dockerd --storage-driver=overlay2 >/tmp/dockerd.log 2>&1 & attempts=0; "
  + "until docker info >/dev/null 2>&1; do attempts=$((attempts + 1)); [ $attempts -lt 300 ] || { cat /tmp/dockerd.log >&2; exit 1; }; sleep 0.1; done";

// Gondolin answers blocked requests itself (403 "blocked by request policy"), so curl's exit
// status proves nothing; only a policy refusal or no connection at all counts as denied.
const egressCheck = "body=$(curl -s -m 5 https://registry-1.docker.io/v2/ 2>&1); code=$?; "
  + "case \"$body\" in *'blocked by request policy'*) echo DENIED;; *) [ $code -ne 0 ] && echo DENIED || echo REACHABLE;; esac";

async function withVm(label, body) {
  const started = Date.now();
  const vm = await VM.create({
    memory: process.env.PI_HARNESS_PROBE_MEMORY ?? "4G",
    cpus: Number(process.env.PI_HARNESS_PROBE_CPUS ?? 4),
    rootfs: { mode: "cow", size: process.env.PI_HARNESS_PROBE_ROOTFS_SIZE ?? "12G" },
    dns: { mode: "synthetic" },
    allowWebSockets: false,
    httpHooks: { isRequestAllowed: () => false },
    vfs: {
      mounts: {
        "/workspace": new ReadonlyProvider(new RealFSProvider(platformsRoot)),
        "/runtime-images": new ReadonlyProvider(new RealFSProvider(await realpath(runtimeImagesPath))),
      },
    },
    sandbox: { imagePath: process.env.PI_HARNESS_GRADER_IMAGE ?? path.join(harnessRoot, ".state/assets/grader") },
  });
  const stage = async (name, command) => {
    const t = Date.now();
    const result = await vm.exec(command);
    process.stdout.write(result.stdout);
    process.stderr.write(result.stderr);
    console.log(`[${name}] exit ${result.exitCode} in ${((Date.now() - t) / 1000).toFixed(1)}s`);
    if (result.exitCode !== 0) throw new Error(`${name} failed`);
  };
  let passed = false;
  try {
    const egress = await vm.exec(egressCheck);
    console.log(`[egress] ${egress.stdout.trim()}`);
    if (!egress.stdout.includes("DENIED")) throw new Error("probe VM can reach a registry; refusing to continue");
    await stage("dockerd", daemon);
    await body(stage);
    passed = true;
  } catch (error) {
    console.error(error.message);
  } finally {
    await vm.close();
    console.log(`[${label}] ${passed ? "PASSED" : "FAILED"} in ${((Date.now() - started) / 1000).toFixed(1)}s`);
  }
  return passed;
}

async function verifyArchives() {
  const archives = (await readdir(runtimeImagesPath)).filter((name) => name.endsWith(".tar")).sort();
  if (archives.length === 0) {
    console.error(`no runtime archives under ${runtimeImagesPath}; run make -C harness runtime-images`);
    return false;
  }
  return withVm(`${archives.length} runtime archives`, async (stage) => {
    for (const archive of archives) {
      // Every image an archive carries must load and start with networking disabled.
      await stage(`load ${archive}`, `docker load -i /runtime-images/${archive} | sed -n 's/^Loaded image: //p' > /tmp/images && cat /tmp/images`
        + ` && while read -r image; do docker run --rm --network none --entrypoint /bin/sh "$image" -c true`
        + ` || { echo "cannot start $image offline" >&2; exit 1; }; done < /tmp/images`);
    }
    await stage("disk", "df -h / | tail -1");
  });
}

async function verifyProbe(probe) {
  const project = `probe-${probe}`;
  const compose = `docker compose -p ${project}`;
  const steps = [
    `${compose} build --pull=false`,
    `${compose} up --detach --wait --wait-timeout 180 app`,
    `${compose} run --rm test`,
  ];
  const teardown = `${compose} logs --no-color --tail 40 app function 2>/dev/null; ${compose} down -v --remove-orphans`;
  const script = `set -e; trap '${teardown.replaceAll("'", "'\\''")}' EXIT; ${steps.join("; ")}`;

  if (backend === "host") {
    const result = spawnSync("bash", ["-c", script], { cwd: path.join(platformsRoot, probe, "impl"), stdio: "inherit" });
    return result.status === 0;
  }
  if (backend !== "gondolin") throw new Error(`unknown backend: ${backend}`);
  return withVm(`${probe} on gondolin`, async (stage) => {
    await stage("load", `docker load -i /runtime-images/${probe}.tar`);
    // Copy out of the read-only VFS so compose can build and bind-mount from a normal filesystem.
    await stage("workspace", "rm -rf /tmp/platforms && cp -r /workspace /tmp/platforms");
    await stage("compose", `cd /tmp/platforms/${probe}/impl && bash -c ${JSON.stringify(script)}`);
  });
}

async function probes() {
  const entries = await readdir(platformsRoot, { withFileTypes: true });
  return entries.filter((entry) => entry.isDirectory()).map((entry) => entry.name).sort();
}

if (target === "--archives") {
  process.exit((await verifyArchives()) ? 0 : 1);
}
if (target === "--all") {
  const results = [["runtime archives", await verifyArchives()]];
  for (const probe of await probes()) results.push([probe, await verifyProbe(probe)]);
  console.log("\nverify-platforms summary");
  for (const [name, passed] of results) console.log(`  ${passed ? "PASS" : "FAIL"}  ${name}`);
  process.exit(results.every(([, passed]) => passed) ? 0 : 1);
}
if (!target || !/^[a-z0-9-]+$/.test(target)) {
  console.error("usage: node scripts/verify-platforms.mjs --all | --archives | <probe> [gondolin|host]");
  process.exit(2);
}
process.exit((await verifyProbe(target)) ? 0 : 1);
