// @ts-check
const { defineConfig } = require("@playwright/test");

/**
 * Both example apps (Reagent on 8710, UIx on 8720) expose identical
 * UI and functionality. We run the same test suite against each.
 */
module.exports = defineConfig({
  testDir: "./e2e",
  timeout: 15_000,
  expect: { timeout: 5_000 },
  globalTimeout: 300_000, // 5 min max for entire suite
  fullyParallel: true,
  retries: 1,
  reporter: "list",
  use: {
    headless: true,
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "reagent",
      use: { baseURL: "http://localhost:8710" },
    },
    {
      name: "uix",
      use: { baseURL: "http://localhost:8720" },
    },
  ],
});
