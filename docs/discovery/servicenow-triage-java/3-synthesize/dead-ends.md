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
- **Enterprise-wide RAG / vector index** — permissions, staleness, re-index, citation,
  access-control burden; out of scope for MVP (D5).
- **Broad autonomous source-code investigation** — expensive, unreliable; GitLab is
  targeted + last, only with a concrete search term (P3/J6).
- **Exposing internal GitLab to the internet to make the agent work** — security
  anti-pattern; it's a deployment-placement problem. Mock a repo snapshot for the demo.
