import { readFile, writeFile } from "node:fs/promises";

const [templatePath, outputPath] = process.argv.slice(2);
if (!templatePath || !outputPath) {
  throw new Error("usage: node build.mjs <template> <output.html>");
}

const template = await readFile(templatePath, "utf8");

const page = template
  .replace("/*BUILT*/", new Date().toISOString().slice(0, 10));

await writeFile(outputPath, page);
