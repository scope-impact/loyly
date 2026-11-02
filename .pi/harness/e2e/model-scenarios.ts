import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { generateMessages } from "@cucumber/gherkin";
import { IdGenerator, SourceMediaType, type Pickle } from "@cucumber/messages";

export type ModelScenario = {
  component: string;
  featureFile: string;
  pickle: Pickle;
  tags: string[];
};

const harnessRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const modelRoot = path.resolve(harnessRoot, "../../model/pi-harness");

export async function loadModelScenarios(): Promise<ModelScenario[]> {
  const featureFiles = (await readdir(modelRoot)).filter((name) => name.endsWith(".feature")).sort();
  const scenarios: ModelScenario[] = [];

  for (const featureFile of featureFiles) {
    const source = await readFile(path.join(modelRoot, featureFile), "utf8");
    const envelopes = generateMessages(
      source,
      path.join("model/pi-harness", featureFile),
      SourceMediaType.TEXT_X_CUCUMBER_GHERKIN_PLAIN,
      {
        includeSource: false,
        includeGherkinDocument: false,
        includePickles: true,
        newId: IdGenerator.incrementing(),
      },
    );

    for (const envelope of envelopes) {
      if (!envelope.pickle) continue;
      scenarios.push({
        component: featureFile.replace(/\.feature$/, ""),
        featureFile,
        pickle: envelope.pickle,
        tags: envelope.pickle.tags.map(({ name }) => name),
      });
    }
  }

  return scenarios;
}

export function validateScenarioClassifications(scenarios: ModelScenario[]): void {
  const errors = scenarios.flatMap(({ featureFile, pickle, tags }) => {
    const classifications = ["@e2e", "@skip"].filter((tag) => tags.includes(tag));
    return classifications.length === 1
      ? []
      : [`${featureFile}: ${pickle.name} must have exactly one of @e2e or @skip`];
  });

  if (scenarios.length === 0) errors.push("the Pi harness model must contain scenarios");
  if (errors.length > 0) throw new Error(errors.join("\n"));
}
