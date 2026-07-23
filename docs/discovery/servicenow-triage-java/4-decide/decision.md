# Decision — DDS follow-on (Java / Spring Boot pivot)

**Date**: 2026-07-23 · **Rigor**: Hackathon/RAPID · **Method**: ADM applied to each
fork; ADM-3+ items flagged for operator awareness (operator already directed the
overall pivot).

**Operator inputs (DDS Phase 1 checkpoint, 2026-07-23)**: problem framing confirmed
("Yes — proceed"); a **live enterprise OpenAI-compatible endpoint** is available for
the demo (agent runs live, not scripted); rigor = **Lean + spike**.

**Spike JS-1 result** (`../2-diverge/verification-js1/findings.md`): ADK-Java is **GA
1.x (1.4.0/1.7.0)**, not 0.8.0; `google-adk-langchain4j`, `google-adk-spring-ai`,
`google-adk-a2a` and `langchain4j-open-ai:1.0.0` all exist on Maven Central; Java 21
present. D1's only real risk (0.x immaturity) is removed → D1 is now **🟢 Low Risk**.

## Decisions

### D1 — Engine: **Google ADK-Java (GA 1.7.0)** ✅ 🟢 Low Risk
Build the agentic loop with **ADK-Java** (`com.google.adk:google-adk:1.7.0`), hosted
inside the Spring Boot process. Reach the **enterprise OpenAI-compatible endpoint**
via **`google-adk-langchain4j` + `langchain4j-open-ai:1.0.0`** (primary). Use ADK's
`SequentialAgent` for the macro-flow and `LlmAgent` + `FunctionTool` inside phases.
- **Why**: team's stated tool *and* it removes work — tool-call loop, iteration
  bounds, before/after-tool callbacks (guardrails), sessions + event stream
  (observability) come for free. Spike confirms it's **GA 1.x**, not experimental.
- **Fallback is now a backend swap, not a rewrite**: **`google-adk-spring-ai`** lets
  ADK run on a Spring AI `ChatModel`. If impl-time **JS-1b** (live round-trip) shows
  the LangChain4j route fighting the endpoint, switch the *model backend module* —
  orchestrator, tools, and report contract are untouched.
- **Residual → JS-1b (build day 1)**: pin exact 1.7.0 `FunctionTool`/`Runner`
  signatures; one live `LlmAgent`+tool round-trip against the enterprise endpoint.

### D2 — Connectors: **plain Java gateway services exposed as ADK FunctionTools** ✅
No MCP, no Rovo skills for the hackathon. Each connector is a Spring `@Service`
behind an interface, adapted to an ADK `FunctionTool`. MCP can wrap the same
gateways post-hackathon for cross-platform reuse.

### D3 — Reuse: **lift GitLab + Confluence integrations from `auspost-mcp`** ✅
Reuse `integrations/gitlab` (gitlab4j 6.0) and `integrations/confluence` as gateway
implementations; reuse `core/auth` + `core/common` patterns. **Skip** the MCP
server + sidecar modules.

### D4 — Autonomy: **bounded orchestration** ✅
Deterministic `SequentialAgent` phases; the app owns the tool allowlist, invocation
order, result limits, time windows, timeouts, and max tool calls via
`beforeToolCallback`. The model chooses search terms and interprets results only.

### D5 — RAG: **deferred** ✅
Live keyword/API search for the MVP; cite retrieved sources. No vector DB required.

## Guardrails (locked, from the analysis — apply across all concepts)
- Advisory only: **no** auto-reassign / close / priority change / remediation.
- Every conclusion: confidence (low/med/high) + supporting sources + contradicting
  evidence + missing info + recommended next check.
- All fetched content is **untrusted**; least-privilege; allowlist scopes; bounded
  searches; the model may not broaden its own permissions or exfiltrate data.
- Structured **JSON diagnosis contract**; work note is a rendering of it.

## What's carried vs superseded
- **Carried** (still valid): RC3 log↔code reasoning → J6; RC5 confirmed work-note
  write → J5; RC6 demo safety + S3′ fixture + seed-repo → J7; 1-elicit artifacts.
- **Superseded** (Rovo/Forge-specific, paused): RC2 rovo-agent, forge-actions, RC4
  chat trigger. Intent preserved in J1/J2/J3.

## Handoff
Concepts **J1–J8** in `4-decide/concepts-extracted.md` → CDS at `docs/design-java/`.
The first technical task is **Spike JS-1** (ADK-Java + enterprise LLM + one
FunctionTool round-trip), then a **connectivity spike** per the analysis: one
read-only call to each system + one controlled ServiceNow work-note write, mocks
until real access lands.
