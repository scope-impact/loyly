import { readFile } from "node:fs/promises";
import { loadModelScenarios, validateScenarioClassifications } from "./model-scenarios.ts";

const scenarios = await loadModelScenarios();
validateScenarioClassifications(scenarios);

const fileName = "model-conformance.e2e.vitest.ts";
const source = await readFile(new URL(fileName, import.meta.url), "utf8");
const errors: string[] = [];
const declarations = [...source.matchAll(/\btest(\.skip)?\(\s*("(?:[^"\\]|\\.)*")/g)].map((match) => ({
  skipped: Boolean(match[1]),
  title: JSON.parse(match[2]) as string,
  start: match.index,
}));

if (/\bdescribe\s*\(/.test(source)) {
  errors.push(`${fileName}: scenario tests must remain at module root for allure-vitest hooks`);
}
if (/\bexport\s+(?:async\s+)?function\s+then\b/.test(source)) {
  errors.push(`${fileName}: the step helper "then" must not make the ESM namespace thenable`);
}

for (const scenario of scenarios) {
  const matches = declarations.filter(({ title }) =>
    title === scenario.pickle.name || title.startsWith(`${scenario.pickle.name} [`)
  );
  const expectedCount = scenarios.filter(({ pickle }) => pickle.name === scenario.pickle.name).length;
  if (
    matches.length !== expectedCount &&
    scenarios.findIndex(({ pickle }) => pickle.id === scenario.pickle.id) ===
      scenarios.findIndex(({ pickle }) => pickle.name === scenario.pickle.name)
  ) {
    errors.push(
      `${fileName}: expected ${expectedCount} explicit test(s) for "${scenario.pickle.name}", found ${matches.length}`,
    );
  }
}

for (const declaration of declarations) {
  const baseTitle = declaration.title.replace(/ \[[^\]]+\]$/, "");
  const candidates = scenarios.filter(({ pickle }) => pickle.name === baseTitle);
  if (candidates.length === 0) {
    errors.push(`${fileName}: test "${declaration.title}" has no Gherkin scenario`);
    continue;
  }
  const shouldSkip = candidates.every(({ tags }) => tags.includes("@skip"));
  if (declaration.skipped !== shouldSkip) {
    errors.push(`${fileName}: "${declaration.title}" must use ${shouldSkip ? "test.skip" : "test"}`);
  }
}

for (const scenario of scenarios.filter(({ tags }) => tags.includes("@e2e"))) {
  const declaration = declarations.find(({ title }) => title === scenario.pickle.name);
  if (!declaration) continue;
  const next = declarations.find(({ start }) => start > declaration.start)?.start ?? source.length;
  const body = source.slice(declaration.start, next);
  if (!body.includes(`scenarioLabels(${JSON.stringify(scenario.component)},`)) {
    errors.push(
      `${fileName}: "${scenario.pickle.name}" is missing component label ${JSON.stringify(scenario.component)}`,
    );
  }
  for (const step of scenario.pickle.steps) {
    const literals = sourceStringLiterals(step.text);
    if (!literals.some((literal) => body.includes(literal))) {
      errors.push(`${fileName}: "${scenario.pickle.name}" is missing Allure step ${JSON.stringify(step.text)}`);
    }
  }
}

if (errors.length > 0) throw new Error(errors.join("\n"));
process.stdout.write(`Linted ${scenarios.length} explicit Gherkin→Vitest scenario tests.\n`);

function sourceStringLiterals(value: string): string[] {
  const singleQuoted = value
    .replaceAll("\\", "\\\\")
    .replaceAll("'", "\\'")
    .replaceAll("\n", "\\n")
    .replaceAll("\r", "\\r");
  return [JSON.stringify(value), `'${singleQuoted}'`];
}
