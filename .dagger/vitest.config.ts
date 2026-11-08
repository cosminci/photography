import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["test/**/*.componentTest.ts"],
    coverage: {
      provider: "v8",
      include: ["src/**/*.ts"],
      exclude: ["src/index.ts", "src/buildEnv.ts", "src/hooks/**"],
      thresholds: {
        statements: 100,
        branches: 100,
        functions: 100,
        lines: 100,
      },
    },
  },
});
