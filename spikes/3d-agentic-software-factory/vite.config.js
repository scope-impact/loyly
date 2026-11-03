import { defineConfig } from "vite";

export default defineConfig({
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
