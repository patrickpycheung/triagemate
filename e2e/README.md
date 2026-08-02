# TriageMate E2E (Playwright)

UI checks for J11 "live thinking trace" against the real, running app — the
deterministic (offline, mock-connector) engine only, no ADK/Copilot proxy
required.

## Run

```bash
cd e2e
npm install       # first time only
npm test
```

If `http://localhost:8080` isn't already serving (e.g. via
`./run-deterministic.sh`), Playwright starts `mvn spring-boot:run` itself
(see `playwright.config.js`'s `webServer` block) and waits for it to come up.
If a server is already running, tests attach to it instead of starting a
second one.

First run also needs Chromium's browser binary once:

```bash
npx playwright install chromium
```

## What's covered

- No dev/demo content is visible before "Diagnose" is clicked — regression
  guard against the TASK-013 sample-rows section (`.lt5-demo`) that was
  removed after it was mistaken for live output during a demo.
- Clicking Diagnose reveals trace steps progressively (staggered, not all at
  once) — the LT3 replay renderer's pacing floor.
- LT7 provenance renders as two independent chips, never collapsed into one.
- TRIAGEMATE (model-think) rows render subordinate, not as a real vendor.
