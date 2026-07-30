# Phase 2 — Diverge (Java pivot DDS)

Lean divergence: the pivot introduced five bounded forks rather than an open solution
space, so exploration is scoped to those forks, read from two independent
perspectives (ChatGPT analysis PDF + Claude/Context7), plus one auto-executed spike.

- `decisions-brief.md` — option analysis for the five forks:
  **D1** engine (ADK vs Spring AI vs manual) · **D2** connector shape (skills vs MCP
  vs plain tools) · **D3** reuse `auspost-mcp` · **D4** autonomy (bounded vs free) ·
  **D5** RAG now vs later.
- `verification-js1/findings.md` — **Spike JS-1** (auto-run): ADK-Java is GA 1.7.0;
  `google-adk-langchain4j`/`-spring-ai`/`-a2a` + `langchain4j-open-ai:1.0.0` exist on
  Maven Central; Java 21 present → D1 risk removed.

→ Phase 3 synthesize.
