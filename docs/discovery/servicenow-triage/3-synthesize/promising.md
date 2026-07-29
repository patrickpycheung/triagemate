# Phase 3: Promising Approach

> **⏸️ ROUND-1 SYNTHESIS — superseded twice.** The "convergent design" below (standalone
> **Python** orchestrator, deterministic correlation) was Round 1's answer; Round 2
> reversed it to Rovo-native (RC1/RC3 in `4-decide/concepts-extracted.md`), and the
> 2026-07-23 `PIVOT.md` reversed that to a local **Java** Spring Boot + ADK app.
> Historical record — not the current design.

## The convergent design (all explorations point here)

**A standalone Python orchestrator, triggered by a new ServiceNow ticket, that runs a deterministic log↔code correlation engine and posts an autonomous root-cause work-note back to the ticket — with a pluggable LLM reasoning layer on top.**

### Flow
```
New SN ticket ──(Business Rule/Flow → Outbound REST, or 30s poll)──▶ Orchestrator
   Orchestrator:
   1. Parse ticket → project code + human notes
   2. GitLab: fetch master source (LIVE) + build/load AST log-template index
   3. Sumo: fetch failure-window logs (MOCK fixture for demo)
   4. Confluence: pull relevant docs (CACHED at startup)
   5. Correlate: stack-trace → template match (Drain3) → grep fallback → ranked candidates + confidence
   6. LLM layer (pluggable; Copilot SDK headless candidate): synthesize root-cause narrative from top candidates + logs + docs
   7. Compose structured work-note → PATCH back to SN ticket (LIVE)
```

### Why it's promising
- **Robust wow**: deterministic core survives LLM-platform uncertainty.
- **Buildable in hackathon time**: 4 REST integrations + a correlation module; ~2–3 focused days (B, C estimates).
- **Credible**: every claim in the work-note is backed by a real, clickable artifact.
- **Demo-safe**: flaky sources pre-seeded, impressive parts live.

### Alternative concept (documented, not chosen)
**Rovo-native variant** — build the agent in Rovo with Forge Actions reaching GitLab/Sumo/ServiceNow, triggered via a Jira/Confluence Automation bridge. Pursue only if the team wants an in-Atlassian chat experience or if Rovo becomes the mandated LLM surface. Slower for the autonomous comment-back flow.
