# STATUS — DDS: ServiceNow Triage Copilot (Java / Spring Boot pivot)

**Current Phase**: 🔒 **DDS COMPLETE & LOCKED** (2026-07-23). Both mandatory
checkpoints passed — Phase 1 "Yes — proceed"; Phase 4 concepts J1–J8 "Yes — lock &
proceed". Spike JS-1 executed. Handoff to CDS (`docs/design-java/`) is live; build
may begin (J4 → J3 → J1 → J2), with JS-1b + JS-2 as build-day-1 spikes.
**Operator inputs**: live enterprise OpenAI-compatible endpoint; Lean+spike rigor.
**Spike JS-1**: ADK-Java is **GA 1.7.0** (not 0.8.0); langchain4j + spring-ai + a2a
modules all on Maven Central → D1 🟢 Low Risk. (`2-diverge/verification-js1/`.)
**Started**: 2026-07-23
**Relationship**: A **follow-on round** on the suspended DDS
(`docs/discovery/servicenow-triage/`), triggered by the requirements change to a
**local Spring Boot + Google ADK** POC. Reuses that DDS's 1-elicit artifacts.
**Rigor**: Hackathon / RAPID — optimize demo-wow + build ease; single-worktree,
no external multi-agent fan-out (Claude carried it with the ChatGPT analysis PDF
as an independent second read).

## Scope of this round

Not a full re-exploration. Three carried-over concepts stay decided; this round
resolves **only the forks the pivot introduced**:

| # | Fork | Decision | Class |
|---|---|---|---|
> ⚠️ **`D1`–`D5` here are THIS workspace's decision forks** (FND-6). A different, unrelated
> `D1`–`D4` exists in DDS [[orchestrator-vs-copilot-cli]] meaning **demo paths** (D1 = our
> orchestration on a high model, D2 = deterministic fallback, D3 = Copilot CLI contrast,
> D4 = evidence trail) — that is the namespace used by `DEMO-RUNBOOK.md` and the J-cards.
> The two sets are not related; check which workspace a `Dn` came from before resolving it.

| D1 | Agent engine: ADK SDK vs Spring AI vs manual loop | **ADK-Java** | ADM-3 |
| D2 | Connector shape: Rovo skills vs MCP vs plain tools | **Plain Java tools (ADK FunctionTools)** | ADM-2 |
| D3 | Build fresh vs reuse `auspost-mcp` | **Reuse GitLab/Confluence integrations** | ADM-1 |
| D4 | Agent autonomy: free-roam vs bounded | **Bounded (SequentialAgent + callbacks)** | ADM-2 |
| D5 | RAG/vector DB now vs later | **Later — live keyword/API search for MVP** | ADM-1 |

## Outputs

- `1-elicit/problem-statement.md` — revised framing (local POC, advisory copilot).
- `2-diverge/decisions-brief.md` — the option analysis for D1–D5.
- `4-decide/decision.md` — resolved decisions + rationale.
- `4-decide/concepts-extracted.md` — J1–J8 seeds for CDS.

## Stop condition

**CONVERGED** ✅ — decisions are unblocking; remaining detail is CDS-level (handled
in `docs/design-java/`). No day-1 spike is blocked on an unanswered question; the
one hard external unknown (ADK-Java + enterprise LLM endpoint wiring) is captured
as **Spike JS-1** in CDS, with Spring AI as the documented fallback.
