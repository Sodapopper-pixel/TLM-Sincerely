import { defineConfig } from "vitest/config";
import { resolve } from "node:path";

export default defineConfig({
  resolve: {
    alias: {
      "@tlm-harness/core": resolve(__dirname, "packages/core/src/index.ts"),
      "@tlm-harness/agent": resolve(__dirname, "packages/agent/src/index.ts"),
    },
  },
  test: {
    include: ["packages/*/test/**/*.test.ts"],
  },
});
