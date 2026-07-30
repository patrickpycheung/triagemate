# Dead ends — ruled out (and why)

- **Manual agent loop** — rebuilds tool-calling, iteration bounds, tracing, guardrail
  hooks that ADK ships. Most engineering, least demo. Killed by D1 + spike (ADK is
  GA, low-risk).
- **MCP servers for the connectors** — value is cross-platform reuse we don't need for
  one self-contained demo; adds transport + process boundary. Deferred: MCP can wrap
  the same gateways later. (`auspost-mcp` shows the pattern when we want it.)
- **Rovo/Forge as the build foundation** — source of truth is ServiceNow (not Jira);
  Rovo Agent Connector is Early-Access/experimental; programmatic control is
  constrained; internal GitLab reach is hard. Paused to the post-hackathon deployment
  discussion (A2A path stays open via `google-adk-a2a`).
- **Multiple autonomous agents ("Service Now agent", "GitLab agent" …)** — four
  investigators shouting findings; harder to debug, less reliable. Replaced by one
  bounded investigator holding tools (P1).

- **Hierarchical multi-agent (supervisor + per-system sub-agents) — explored &
  deferred (2026-07-23).** Revisited P1 with a real motivation: *context management*.
  A supervisor agent owning the case + one specialist agent per external system, each
  with its own context and returning only a **summary**, keeps the supervisor lean; the
  bounded, traceable way to do it in ADK is **agent-as-tool** (specialist-calls-specialist
  = nested tools, still a tree). Genuinely stronger for scale, but its costs — latency,
  spend (many LLM round-trips), debuggability, and unspiked ADK multi-agent surface —
  make it wrong for a hackathon *pitch*. Verdict: **the right post-acceptance rewrite**,
  not the demo. The pitch stays a simple, mostly-linear single investigator; the
  deterministic engine remains the safe path.
- **Enterprise-wide RAG / vector index** — permissions, staleness, re-index, citation,
  access-control burden; out of scope for MVP (D5).
- **Broad autonomous source-code investigation** — expensive, unreliable; GitLab is
  targeted + last, only with a concrete search term (P3/J6).
- **Exposing internal GitLab to the internet to make the agent work** — security
  anti-pattern; it's a deployment-placement problem. Mock a repo snapshot for the demo.
