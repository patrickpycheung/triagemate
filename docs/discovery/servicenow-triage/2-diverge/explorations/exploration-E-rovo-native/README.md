# Exploration E — Rovo-Native, AI-First Triage Agent

**Biases**: Innovation 🚀 + User-Centric/Demo 🎬 + Prior-Art 📚
**Question**: Design an AI-first ServiceNow triage agent that visibly *lives in Atlassian Rovo* — the agent's own LLM fetches code/logs/docs, reasons over them to localize the failing code, and posts a root-cause work-note. Rovo is the demo star; deterministic log-matching is at most an optional tool.

## Architecture (one paragraph)

A single custom **Rovo agent** ("Triage Sherlock") defined by a Forge `rovo:agent` module — a **prompt** plus a set of **Actions**. Four Actions are Forge functions that call external REST APIs via `api.fetch` with `permissions.external.fetch.backend` egress in `manifest.yml`: (1) **read ServiceNow ticket**, (2) **fetch GitLab file/tree at `ref=master`**, (3) **query Sumo Logic** (mocked for demo reliability), (4) **post ServiceNow work-note** (PATCH `work_notes`). **Confluence is native** — no Action needed; Rovo searches the Teamwork Graph out of the box. An optional 5th Action wraps a deterministic log-template matcher (Drain3/AST) the agent *may* call for precision, but it is not the headline. The agent's instructions drive it to: read ticket → extract project code + time window → search Confluence (native) → fetch master code → query logs → **reason (LLM) to match a runtime log line to its emitting `log(...)` statement, citing file:line** → post the work-note. In Rovo chat, every tool call and the reasoning narration stream visibly.

## Recommended trigger

**(a) Presenter invokes the agent in Rovo chat** — `"Triage INC0012345"`. This is the easiest to build AND gives the fullest autonomous-agent wow: the agent's LLM autonomously calls each Action and posts the work-note itself, live, in front of the audience. Options (b) webhook→Automation→"Invoke Rovo Agent" and (c) standalone shim are honestly weaker: in Automation, current Rovo "agents only return a response, they don't perform actions autonomously," so the write-back moves *outside* the agent (extra REST step) — and there is **no external API to invoke a Rovo agent headlessly**, so (c) collapses into (b). Keep (b) as the "future fully-autonomous" narrative, demo with (a).

## Promising? **YES**

Rovo-native is genuinely achievable for the demo and directly serves the operator's "AI visibly lives in Rovo" goal. The wow is watching an agent think and act. The honest cost: it's a *chat-invoked* demo (a human types the trigger), not literal auto-on-ticket-creation, and Forge iteration is slower than a local backend.

## Trust levels

- 🔬 Rovo reaches external REST via Forge Actions + manifest egress — **HIGH**
- 🔬 No external/headless API to invoke a Rovo agent; webhook→Automation is the only external bridge — **HIGH**
- 📚 Agent autonomously chains Actions + posts externally **in chat** — **MEDIUM-HIGH**
- 📚 In Automation, agent "only returns a response" (write must be done by the rule) — **MEDIUM-HIGH**
- 🤔 Forge 25s function timeout vs Sumo async polling → mock Sumo — **MEDIUM (technical gotcha)**
- 🔍 Build effort ~2–3 days, dominated by Forge learning curve — **MEDIUM**
