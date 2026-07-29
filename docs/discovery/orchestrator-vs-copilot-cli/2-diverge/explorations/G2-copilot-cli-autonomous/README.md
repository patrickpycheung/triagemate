# G2 — Copilot CLI autonomous agent (high model)

**Bias**: Prior-art / innovation. **Verdict**: Higher raw autonomy + wow; lower control.

## What it is
GitHub's mature, terminal-native autonomous agent (M1): planning, multi-step tool use,
self-correction, `/fleet` parallel subagents, MCP tools, and **high models** (Opus 4.6 /
GPT-5.3-Codex, GPT-5 tuned for tool-heavy work — M2). This is a heavily-engineered agent
loop we'd otherwise never match in a hackathon week.

## Strengths
- **Better open-ended reasoning/adaptivity** than a hand-rolled loop — the operator's
  instinct is *directionally right here* (M2). Impressive "it figured it out itself" theatre.
- Turnkey: no loop to build/maintain.
- High-model tool-calling is genuinely strong.

## Limits (esp. for a demo)
- **Non-deterministic** → can take a different path each run; **compounding error** over
  many autonomous steps (M5) risks a wrong turn live.
- **Black box**: hard to enforce advisory-only / bounded behaviour; limited replay.
- **Headless+MCP fragility** (≥7-tool bug, M6) — our 4 systems exceed 7 tools.
- **Network + ToS dependent** (E3) — a live-demo failure surface.
- The reliability literature's warning lands squarely: *"match autonomy to failure cost,
  not to what the demo can get away with"* (M5) — this posts to real tickets.

## Trust / risk
📚 (capable) · 🟠 for an unattended/on-stage advisory task (control + determinism gaps).
