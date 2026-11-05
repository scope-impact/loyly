import { defineConfig } from "vite";

export default defineConfig({
  base: "/loyly/",
  optimizeDeps: {
    exclude: ["@sqlite.org/sqlite-wasm"],
  },
});
