import { readFile, writeFile } from "node:fs/promises";

const [templatePath, modelPath, outputPath] = process.argv.slice(2);
if (!templatePath || !modelPath || !outputPath) {
  throw new Error("usage: node build.mjs <template> <model.json> <output.html>");
}

const [template, model] = await Promise.all([
  readFile(templatePath, "utf8"),
  readFile(modelPath, "utf8"),
]);

const page = template
  .replace("/*DOMAIN*/null", model)
  .replace("/*BUILT*/", new Date().toISOString().slice(0, 10));

await writeFile(outputPath, page);
