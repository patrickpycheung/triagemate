# DIVERGE Round 2 — Rovo-native, AI-first (spiral)

## Why we spiraled
Phase 4 checkpoint redirect from operator:
- "Reconsider platform" — revisit standalone-vs-Rovo.
- "No — AI-first pitch" — foreground the AI agent's reasoning, not a deterministic algorithm.
- "Rovo is the real AI" — elevate the Rovo-native variant (was C7) to PRIMARY.

Round-1 optimized for a robust *deterministic* wow (standalone + Drain3). Operator wants an **AI-agent wow on Rovo**. Re-weighting the whole design around Rovo-native + AI-first.

## New crux question (must resolve this round)
Can a **Rovo agent run autonomously** — triggered by a new ServiceNow ticket (not a human chatting), orchestrate multiple Actions (fetch GitLab master, query Sumo, read/write ServiceNow), reason over logs+code with its own LLM to localize the failure, and post a work-note back — and if the fully-autonomous trigger is hard, what's the easiest **chat-invoked** demo that still shows AI-first wow?

## Re-framing the correlation (C3)
AI-first: instead of Drain3/AST as the star, let the **Rovo agent's LLM reason** over the fetched log window + source to localize the code, citing the matched log line. The deterministic matcher becomes an OPTIONAL Action/tool the agent may call for precision — not the headline.

## Round-2 inputs
- Gemini research (web): Rovo autonomous invocation / external trigger / multi-Action orchestration / autonomous external writes (2026).
- Exploration E: design the Rovo-native AI-first architecture + trigger options + AI-first demo storyboard.
