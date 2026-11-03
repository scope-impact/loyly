import { defineConfig } from "vite";

export default defineConfig({
  base: "/loyly/",
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes("@dimforge/rapier") || id.includes("@react-three/rapier") || id.includes("ecctrl")) return "physics";
        },
      },
    },
  },
});
