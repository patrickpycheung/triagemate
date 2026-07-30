# E1 — Copilot CLI as the orchestrator (+ local MCP servers)

**Bias**: Prior-art / innovation (the operator's hypothesis). **Verdict**: 🟠 Viable but
currently risky; strongest as an *interactive* tool, weak for headless automation now.

## Shape
- Build **local STDIO MCP servers** for ServiceNow, Sumo, Confluence, GitLab (each
  wrapping the read/query + advisory-write calls our gateways already implement).
- Register them in `~/.copilot/mcp-config.json`.
- Invoke `copilot -p "Triage INC0012345 …"` (headless) or interactively; Copilot's
  built-in agent loop does the planning + tool-calling; it writes the advisory comments
  via the ServiceNow MCP tool.

## Attractions
- **No agent/planning loop to build** — Copilot supplies it (the operator's main draw).
- MCP servers are reusable across Copilot IDE/CLI and other MCP clients.
- Uses the Copilot seat directly; no proxy.

## Real problems (today)
- **Headless + MCP is buggy** (L5): prompt fires before MCP connects (empty tools first
  turn), **empty-message injection at ≥7 tools** corrupts output, workspace config
  sometimes skipped. Our 4 systems easily exceed 7 tools → directly in the danger zone.
- **Org policy gates MCP** (L4, off by default) and enterprises can allowlist servers —
  an **IT ask**, not self-serve.
- **We lose our guardrails**: J8 bounded-tool-calls, advisory-only enforcement, and the
  deterministic offline demo don't exist inside Copilot's black-box loop — we'd re-impose
  them via MCP-side checks + prompt only (weaker).
- **Determinism/control**: Copilot picks the model + plan; harder to make the demo repeatable.
- Throws away a **working, tested** layer (L8) to depend on a fast-moving CLI.

## Trust / risk
📚 Documented · 🟠 Uncertain for headless automation now (bug-dependent), 🟢 fine as an
engineer-driven interactive assistant.

## Fit
Best if the goal shifts to *interactive* triage where an engineer runs Copilot and
watches. For unattended polling-driven triage, E2 is safer today.
