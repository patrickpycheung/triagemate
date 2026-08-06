// @ts-check
const { defineConfig } = require('@playwright/test');

/**
 * Drives the real static/index.html against a real (deterministic-engine,
 * mock-connector) TriageMate backend — see README.md in this directory for
 * how to run it. `webServer` starts `mvn spring-boot:run` itself if nothing
 * is already listening on :8080, so
 * `npm test` works standalone with zero setup.
 */
module.exports = defineConfig({
  testDir: './tests',
  timeout: 30_000,
  fullyParallel: false, // shared backend state (writeback dedup) — keep runs serial
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:8080',
    trace: 'retain-on-failure',
  },
  webServer: {
    // --server.port=8080 explicitly: application.yml now defaults to 80, which is
    // privileged on macOS/Linux. The test suite must never need root, so it pins an
    // unprivileged port rather than inheriting the demo-facing default.
    // post-to-live-servicenow=false: mocked runs otherwise comment on the REAL ticket, and a
    // test suite must not mutate a live instance — this suite drives a dozen diagnoses, some
    // against invented numbers. The flag is the demo's, not the tests'.
    command: 'mvn -q -o spring-boot:run -Dspring-boot.run.arguments='
           + '"--server.port=8080 --triage.writeback.post-to-live-servicenow=false"',
    cwd: '..',
    url: 'http://localhost:8080',
    timeout: 90_000,
    reuseExistingServer: true,
  },
});
