# Diverge — Option analysis for the pivot forks (D1–D5)

Independent reads: **Claude** (this analysis) + the **ChatGPT "AI Incident Triage
Copilot" analysis PDF** (`/volume1/torrent/nn/tmp/`) + Context7 docs for
`/google/adk-java` v0.8.0. Where they agree is noted; where they diverge is the
signal.

---

## D1 — Agent engine: ADK-Java vs Spring AI vs manual loop

| Option | Strengths | Weaknesses |
|---|---|---|
| **A. ADK-Java** (`com.google.adk`) | Team's stated choice. Supplies the tool-call loop, `LlmAgent` + `FunctionTool`, `SequentialAgent`/`LoopAgent` for deterministic phase ordering, **before/after-tool callbacks** (= our guardrails), sessions + event stream (= observability). Provider-neutral via **LangChain4j** wrapper (OpenAI-compatible, Ollama). | v0.8.0 (young); RxJava (`Flowable`/`Single`) learning curve; docs thinner than Spring AI. |
| **B. Spring AI** | First-class Spring; `@Tool` methods; mature docs; the ChatGPT PDF's pick. | **Not** the team's stated tool; we'd still hand-build phase orchestration + guardrail hooks; another abstraction to learn. |
| **C. Manual loop** | Full control; no SDK risk. | Re-implements tool-calling, iteration bounds, tracing, guardrail hooks from scratch — most engineering, least demo. |

**Divergence**: team brief + user lean **ADK**; ChatGPT PDF leans **Spring AI**
(it wasn't told ADK was mandated). Both agree the engine must be **bounded** and
**provider-neutral** — which ADK satisfies. → lean **A**, keep **B** as fallback.

**Key ADK-Java facts verified (Context7):**
- `LlmAgent.builder().model(adkModel).tools(FunctionTool.create(Cls.class,"m")).build()`
- Non-Google model: wrap with `LangChain4j.builder().chatModel(openAiModel)…` →
  works against any **OpenAI-compatible** enterprise endpoint.
- `Runner.runAsync(userId, sessionId, msg, runConfig)` → `Flowable<Event>` loop.
- `SequentialAgent` / `LoopAgent` / `ParallelAgent` exist for deterministic macro-flow.

## D2 — Connector shape: Rovo skills vs MCP vs plain Java tools

| Option | Fit |
|---|---|
| Rovo skills | ✗ Rovo is deferred; couples us to the paused track. |
| **MCP servers** | ✗ Value is cross-platform reuse we don't need for one self-contained demo; adds a transport + process boundary. Can wrap the same gateways **later**. |
| **Plain Java tools** (gateway `@Service` → `FunctionTool`) | ✓ Simplest; deterministic; mockable; exactly the PDF's "ordinary Spring services, MCP later." |

Both reads **agree**: plain tools now, MCP later. → **plain Java tools**.

## D3 — Build fresh vs reuse `~/work/auspost-mcp`

`auspost-mcp` is Java 21 / Spring Boot 3.4 / Maven multi-module with **working
GitLab (gitlab4j 6.0) and Confluence integrations** (`integrations/gitlab`,
`integrations/confluence`) plus `core/auth` + `core/common` patterns. Reuse those
as gateway implementations / reference; **do not** reuse its MCP server + sidecar
(that's the layer we're explicitly not building). → **reuse integrations, skip MCP layer**.

## D4 — Agent autonomy: free-roam vs bounded

The PDF is emphatic (and Claude concurs): a mostly **deterministic** workflow where
the app controls *which tools are permitted, invocation order, result limits, time
windows, timeouts, max tool calls, what can be written back*. Let the **model**
choose search terms and interpret results; let the **app** hold the leash. ADK maps
this cleanly: `SequentialAgent` for the fixed phases, `LlmAgent` + tools inside each
phase, `beforeToolCallback` enforcing allowlist/limits. → **bounded**.

## D5 — RAG / vector DB now or later

Enterprise-wide indexing brings permissions, staleness, re-index, citation and
access-control problems. For the MVP: **live keyword/API search → retrieve a few
highly-relevant docs → pass to the model → cite sources**. A tiny curated vector
index of demo docs is acceptable but not required. → **later**.
