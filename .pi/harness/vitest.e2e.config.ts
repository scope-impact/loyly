import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["e2e/**/*.e2e.vitest.ts"],
    setupFiles: ["allure-vitest/setup"],
    reporters: [
      "default",
      [
        "allure-vitest/reporter",
        {
          resultsDir: "allure-results/e2e",
          globalLabels: { layer: "pi-harness-e2e" },
        },
      ],
    ],
    fileParallelism: false,
    hookTimeout: 30_000,
    testTimeout: 900_000,
  },
});
