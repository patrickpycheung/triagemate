# STATUS — DDS: Run TriageMate on a GitHub Copilot subscription

**Phase**: 4 COMPLETE — operator confirmed **E2** (2026-07-29). → ready for CDS.

## DECISION (operator-confirmed)
**E2 — use the Copilot subscription as a local OpenAI-compatible LLM backend (LiteLLM /
copilot-api proxy); keep TriageMate's existing orchestration + guardrails.** Do NOT
adopt Copilot CLI as orchestrator (E1) or build MCP servers now (E5) for the hackathon.
ToS handling: proceed at hackathon scale (human-present); **IT/legal ruling on
programmatic/unattended use is a required gate before any unattended/production rollout**
(if disallowed → interactive-only + manual trigger). Concepts: see `4-decide/concepts-extracted.md`.

**Rigor**: Hackathon / RAPID (Claude + WebSearch).
**Question**: Company provides **Copilot seats, not LLM API keys**. How do we power
TriageMate's reasoning off the Copilot subscription — is the answer "adopt Copilot CLI
+ build MCP servers for the 4 systems", or something lighter?

## Confirmed inputs (from prior work)
- Corp laptop reaches the ServiceNow instance over outbound HTTPS+basic-auth 🔬 (operator-tested).
- Trigger: **outbound polling (K1)** is viable → **headless stays in scope**.
- We ALREADY have a working agent layer (deterministic engine + ADK/LangChain4j loop,
  5 tests green) targeting an **OpenAI-compatible** endpoint via `LLM_BASE_URL/KEY`.

## The pivotal finding
There are **two very different architectures**, not one:
- **E1 — Copilot CLI as the orchestrator** (+ local MCP servers for the 4 systems). The
  user's proposed path. Copilot does planning/tool-calling; we write MCP servers.
- **E2 — Copilot as an LLM *backend*** via a local OpenAI-compatible proxy
  (LiteLLM `github_copilot` provider / `copilot-api`). Our EXISTING app points
  `LLM_BASE_URL` at `http://localhost:4000` and is otherwise unchanged. ⭐

Both draw on the same Copilot seat; **the ToS/licensing question (E3) gates both** and
is the real spike. E1 also inherits current `copilot -p`+MCP fragility.

## Explorations
- E1 Copilot-CLI-orchestrator · E2 Copilot-as-LLM-backend ⭐ · E3 ToS/licensing (gating)
  · E4 control/guardrails/determinism · E5 MCP build-out for the 4 systems

## Open (operator/IT-gated) spikes → see `4-decide/concepts-extracted.md`
1. **Does the corporate Copilot subscription permit programmatic/unattended use?** (IT/legal)
2. On the corp laptop: does a LiteLLM/copilot-api proxy authenticate with the corp
   Copilot seat and serve `/v1/chat/completions`? (and is the proxy binary allowed?)
