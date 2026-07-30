# Phase 3: Dead Ends (and why)

- **Rovo triggered directly by ServiceNow** — ❌ Rovo agents have no external inbound endpoint/webhook. Would need an Automation bridge anyway. (Gemini + Exploration A)
- **M365 Copilot Graph API as a headless LLM** — ❌ delegated-permission only (needs a signed-in user context); not usable app-only/headless. (Gemini)
- **Pure naive grep correlation** — ❌ ~50% false positives on parameterized log messages; only acceptable as a last-resort fallback. (Exploration B)
- **Embedding/semantic correlation for the hackathon** — ⏸️ powerful but slower (200–500ms) and more setup; defer to a post-hackathon phase. (Exploration B)
- **Live Sumo Logic during the demo** — ⚠️ 4 req/s rate limit + network egress risk; pre-seed a JSON fixture instead. (Exploration C)
- **Multi-project ranking / "find the project among all projects"** — ⏸️ cut for MVP; demo a single known project. (Exploration D)
- **Assuming the corporate Copilot is a callable LLM API without checking** — ⚠️ this is the exact premise that must be spiked day 1; do not build the critical path on it. (Conflict resolution)
