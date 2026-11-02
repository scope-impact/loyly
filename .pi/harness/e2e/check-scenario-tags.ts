import { loadModelScenarios, validateScenarioClassifications } from "./model-scenarios.ts";

const scenarios = await loadModelScenarios();
validateScenarioClassifications(scenarios);
process.stdout.write(`Validated ${scenarios.length} model scenario instances as @e2e or @skip.\n`);
