# Phase 4: Decision (Round 2 — Rovo-native, AI-first)

## Decision
Build the triage assistant as a **Rovo-native, AI-first agent**: a single Rovo agent (Rovo Studio + Forge Actions) invoked in Rovo chat, whose **own LLM** reasons over the ServiceNow ticket + GitLab `master` code + Sumo logs + native Confluence to localize the failing code and post a work-note back. Concepts RC1–RC6; standalone app demoted to alternative RC7.

## Why this changed from Round 1
Round 1 recommended a standalone orchestrator with a deterministic correlation core (optimizing for a robust, LLM-independent wow). The operator redirected at the Phase-4 checkpoint: **reconsider platform**, **AI-first pitch**, **"Rovo is the real AI."** We spiraled (Diverge Round 2) and re-optimized for an AI-agent wow on Rovo.

## Rationale (against success = demo wow + ease)
- **AI-first wow**: Rovo chat surfaces every tool call + reasoning narration → the audience watches the AI consult 4 systems and reason to a root cause. That visible reasoning is the pitch the operator wants.
- **Ease**: Rovo Studio gives chat UI + LLM orchestration free; only the Forge Actions (glue) are custom. No separate LLM to provision.
- **Blocker removed**: Rovo's built-in LLM eliminates the Round-1 Copilot-headless-auth risk entirely.
- **Confluence free**: native Rovo access = one of four sources costs zero integration.

## Key constraints accepted (from Gemini research + Exploration E)
- Rovo restricts autonomous headless writes → post-back is either in-chat (with confirm) or done by the Automation layer using `{{agentResponse}}`. Demo uses the chat path.
- <5 actions/agent, 5 MB/action, ~25–55 s Forge timeout → lean actions, filtered payloads, mock Sumo.
- No external API triggers a Rovo agent → demo is presenter-invoked in chat; autonomous on-ticket trigger is the narrated production vision.

## Honest caveat (must be said in the pitch)
The demo's "autonomy" is the agent's **reasoning + multi-system actions**, not literal auto-fire on ticket creation — the presenter triggers it in chat, because the only external-trigger path can't let the agent post the note itself. **Chat-invoked demo, webhook-triggered future.**

## Decision autonomy note (ADM)
Platform re-selection was an operator (ADM-4) call — taken at the checkpoint, then executed via a focused spiral. The remaining forks (chat-vs-automation trigger, deterministic tool optional) are ADM-2, decided here with rationale.
