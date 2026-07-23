# Phase 3: Trade-offs & Conflict Resolution

## The one conflict that matters: can "corporate Copilot" be a backend LLM?

| Source | Claim | Confidence given |
|---|---|---|
| Gemini (web-aware) | GitHub Copilot has **NO** backend completions API; M365 Copilot Graph API is delegated-perms only (not headless). | "Absolute 100%" |
| Exploration-A agent | **GitHub Copilot SDK** ships a **headless server mode** (`copilot --headless --port`), Node/Python/.NET, GA mid-2026. | claimed "🔬 Spiked HIGH" |
| Claude verification spike (WebSearch + WebFetch) | A `@github/copilot-sdk` with `--headless` CLI server mode **appears** to exist in 2026 docs; **but** headless/non-interactive auth is repeatedly flagged as a limitation, and the exact GH doc URL fetched inconclusively. | ⚠️ Uncertain |

**Resolution**: Downgrade Agent-A's "Spiked HIGH" to **⚠️ Uncertain**. The agent could not have tested the corporate Copilot; the label was an overclaim. Net position: a headless Copilot SDK *probably* exists, but **whether it authenticates headlessly with the company's approved Copilot credentials is unproven and environment-specific**. This is the **#1 day-1 spike** — and only the hackathon team can run it (I have no corporate creds).

**Why it no longer blocks the design**: Pattern P5 — the LLM is a pluggable layer, and Pattern P2 — the wow is deterministic. Worst case (Copilot headless auth fails), the correlation engine still produces the file/line + matched log line; the LLM narrative degrades to a templated summary, or swaps to Rovo/another approved engine.

## Platform trade-off: Standalone orchestrator vs Rovo-native

| Axis | Standalone (Python) + pluggable LLM | Rovo-native (Forge Actions) |
|---|---|---|
| Fits our consumer model (autonomous work-note on ticket) | ✅ Direct | ⚠️ Rovo is chat-first; needs Automation bridge to be triggered |
| External trigger from ServiceNow | ✅ Own webhook/poll | ⚠️ Jira/Confluence Automation bridge required |
| Reach GitLab / Sumo / ServiceNow | ✅ Plain REST calls | ✅ via Forge `api.fetch` + manifest egress perms |
| Confluence access | plain REST | ✅ native (free context) |
| Orchestration control (multi-step correlation) | ✅ full | ⚠️ constrained to Action steps |
| Build friction in hackathon time | Low–medium (4 REST integrations) | Medium–high (Forge learning curve + bridge) |
| Best when | our case: autonomous, externally triggered | a chat/human-in-loop triage experience |

**Verdict**: **Standalone orchestrator** is the faster path for THIS flow (autonomous, ticket-triggered, comment-back). Rovo stays as a documented alternative concept (better if the team later wants an in-Atlassian chat experience, or if it becomes the approved LLM surface).

## Secondary trade-offs
- **Correlation depth vs time**: AST-template + Drain3 is the sweet spot; embeddings/call-chain reconstruction are Phase-2 (post-hackathon).
- **Live vs mock**: more live = more impressive but more fragile. P3 policy balances it.
- **Multi-project ranking**: cut for MVP — demo a single project.
