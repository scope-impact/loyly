import { mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { openOracleFixtureRoom, type OracleFixtureRoom } from "./oracle-fixture-room.ts";

type FixtureCase = { itemId: string; role: string; graderPath: string };
type FixtureRequest = { fixtureSource: string; graderRoot: string; cases: FixtureCase[] };
type FixtureResult = FixtureCase & { exitCode: number; stdout: string; stderr: string };
type FixtureDirectory = { fixtureRoot: string; cleanup(): Promise<void> };

const exitMarker = "__LOyly_GRADER_EXIT__=";

function shellQuote(value: string): string {
  return `'${value.replaceAll("'", `'\"'\"'`)}'`;
}

function validateCase(value: FixtureCase): void {
  if (!/^@item-[0-9]+-[0-9]+$/.test(value.itemId)) throw new Error(`invalid item id: ${value.itemId}`);
  if (!new Set(["carried", "uncarried"]).has(value.role)) throw new Error(`invalid fixture role: ${value.role}`);
  if (!/^[A-Za-z0-9._/-]+$/.test(value.graderPath)
      || value.graderPath.startsWith("/")
      || value.graderPath.split("/").includes("..")) {
    throw new Error(`invalid grader path: ${value.graderPath}`);
  }
}

async function writeFixture(fixtureSource: string): Promise<FixtureDirectory> {
  const fixtureRoot = await mkdtemp(path.join(os.tmpdir(), "loyly-oracle-fixture-"));
  await writeFile(path.join(fixtureRoot, "fixture-server.mjs"), fixtureSource);
  return { fixtureRoot, cleanup: () => rm(fixtureRoot, { recursive: true, force: true }) };
}

function commandFor(value: FixtureCase): string {
  const itemId = shellQuote(value.itemId);
  const role = shellQuote(value.role);
  const grader = shellQuote(`/grader/${value.graderPath}`);
  const graderRunner = value.graderPath.endsWith(".mjs") ? "node" : "bash";
  return [
    "set -uo pipefail",
    "url_file=$(mktemp)",
    "server_err=$(mktemp)",
    "cleanup() { kill \"$server_pid\" 2>/dev/null || true; rm -f \"$url_file\" \"$server_err\"; }",
    "trap cleanup EXIT",
    `node /oracle/fixture-server.mjs ${itemId} ${role} >\"$url_file\" 2>\"$server_err\" &`,
    "server_pid=$!",
    "for _attempt in 1 2 3 4 5 6 7 8 9 10; do test -s \"$url_file\" && break; sleep 0.1; done",
    "url=$(head -n 1 \"$url_file\")",
    "case \"$url\" in http://127.0.0.1:*) ;; *) cat \"$server_err\" >&2; exit 70 ;; esac",
    "set +e",
    `${graderRunner} ${grader} \"$url\" --item ${itemId}`,
    "grader_exit=$?",
    "set -e",
    `printf '\n${exitMarker}%s\n' \"$grader_exit\"`,
  ].join("\n");
}

export async function runOracleFixtures(
  request: FixtureRequest,
  dependencies: {
    writeFixture?: (source: string) => Promise<FixtureDirectory>;
    openRoom?: (options: { fixtureRoot: string; graderRoot: string }) => Promise<OracleFixtureRoom>;
  } = {},
): Promise<{ backend: "gondolin"; cases: FixtureResult[] }> {
  if (typeof request.fixtureSource !== "string" || request.fixtureSource.length === 0) {
    throw new Error("fixture source is required");
  }
  request.cases.forEach(validateCase);
  const fixture = await (dependencies.writeFixture ?? writeFixture)(request.fixtureSource);
  let room: OracleFixtureRoom | undefined;
  try {
    room = await (dependencies.openRoom ?? openOracleFixtureRoom)({
      fixtureRoot: fixture.fixtureRoot,
      graderRoot: request.graderRoot,
    });
    const cases: FixtureResult[] = [];
    for (const value of request.cases) {
      const result = await room.exec(commandFor(value), { signal: AbortSignal.timeout(180_000) });
      if (result.exitCode !== 0) {
        throw new Error(`Gondolin fixture execution failed for ${value.itemId} ${value.role}: ${result.stderr}`);
      }
      const marker = result.stdout.match(/\n?__LOyly_GRADER_EXIT__=([0-9]+)\s*$/);
      if (!marker) throw new Error(`Gondolin fixture returned no grader exit for ${value.itemId} ${value.role}`);
      cases.push({
        ...value,
        exitCode: Number(marker[1]),
        stdout: result.stdout.slice(0, marker.index).trim(),
        stderr: result.stderr,
      });
    }
    return { backend: "gondolin", cases };
  } finally {
    try { await room?.close(); } finally { await fixture.cleanup(); }
  }
}
