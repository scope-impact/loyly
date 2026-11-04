import { readFile, mkdir } from "node:fs/promises";
import path from "node:path";
import { chromium } from "playwright-core";

const options = parseArgs(process.argv.slice(2));
const source = await readFile(required(options, "contract"), "utf8");
const declaredSlice = source.match(/^slice:\s*([^\s#]+)/m)?.[1];
if (declaredSlice && declaredSlice !== options.slice) {
  process.stdout.write(JSON.stringify({ passed: true, applicable: false, checks: [] }) + "\n");
  process.exit(0);
}

const screens = parseScreens(source);
if (!screens.length) {
  process.stdout.write(JSON.stringify({ passed: true, applicable: false, checks: [] }) + "\n");
  process.exit(0);
}

const evidenceRoot = required(options, "evidence-root");
await mkdir(evidenceRoot, { recursive: true });
const browser = await chromium.launch({ executablePath: "/usr/bin/chromium-browser", headless: true });
const context = await browser.newContext();
await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
const checks = [];
try {
  const page = await context.newPage();
  for (const screen of screens) {
    await page.goto(new URL(screen.path, required(options, "base-url")).toString(), { waitUntil: "networkidle" });
    checks.push(await check(screen.name, "heading", screen.heading,
      async () => Boolean(await page.getByRole("heading", { name: screen.heading, exact: true }).count())));
    for (const button of screen.buttons) {
      checks.push(await check(screen.name, `button:${button}`, button,
        async () => Boolean(await page.getByRole("button", { name: button, exact: true }).count())));
    }
  }
} finally {
  await context.tracing.stop({ path: path.join(evidenceRoot, "trace.zip") });
  await context.close();
  await browser.close();
}
const passed = checks.every((value) => value.passed);
process.stdout.write(JSON.stringify({ passed, applicable: true, checks }) + "\n");
process.exitCode = passed ? 0 : 1;

async function check(screen, kind, expected, predicate) {
  try {
    const passed = await predicate();
    return { id: `screen:${screen}:${kind}`, passed, evidence: passed ? `found ${kind} ${expected}` : `missing ${kind} ${expected}` };
  } catch (error) {
    return { id: `screen:${screen}:${kind}`, passed: false, evidence: error instanceof Error ? error.message : String(error) };
  }
}

function parseScreens(text) {
  const block = text.match(/^screens:\s*\n([\s\S]*?)(?=^[A-Za-z][\w-]*:\s*(?:\n|$))/m)?.[1] ?? "";
  const matches = [...block.matchAll(/^  ([A-Za-z][\w-]*):\s*\n([\s\S]*?)(?=^  [A-Za-z][\w-]*:\s*\n|\s*$)/gm)];
  return matches.map((match) => ({
    name: match[1],
    path: match[2].match(/^    path:\s*["']?([^\s"']+)/m)?.[1] ?? "/",
    heading: match[2].match(/^      heading:\s*["']?(.+?)["']?\s*$/m)?.[1],
    buttons: (match[2].match(/^      buttons:\s*\[([^\]]*)\]/m)?.[1] ?? "")
      .split(",").map((value) => value.trim().replace(/^['"]|['"]$/g, "")).filter(Boolean),
  })).filter((screen) => screen.heading);
}

function parseArgs(args) {
  const result = {};
  for (let index = 0; index < args.length; index += 2) result[args[index].replace(/^--/, "")] = args[index + 1];
  return result;
}

function required(value, key) {
  if (!value[key]) throw new Error(`missing --${key}`);
  return value[key];
}
