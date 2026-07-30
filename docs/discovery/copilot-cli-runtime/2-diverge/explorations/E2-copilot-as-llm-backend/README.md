# E2 — Copilot as an LLM *backend* via a local OpenAI-compatible proxy ⭐

**Bias**: Minimum-viable / constraint-driven. **Verdict**: ✅ Strongest fit for the
stated goal — smallest change, keeps everything we value.

## Shape
Run a **local proxy** that presents the Copilot subscription as an OpenAI-compatible
endpoint, and point our existing app at it:
- **LiteLLM** `github_copilot` provider (OAuth device-flow, no API key) → serves
  `http://localhost:4000/v1/...`; or **`copilot-api`** (`npx copilot-api`) for a
  built-in usage/quota dashboard + rate-limit controls.
- Set `triage.integrations.llm.base-url=http://localhost:4000/v1`, any dummy api-key,
  model e.g. `gpt-4.1`. **The `-Padk` ADK/LangChain4j path runs unchanged.**

## Why it wins against the goal
- **Solves "subscription not API key" directly** (L1, L7) with ~zero app rewrite.
- **Keeps our non-negotiables**: J8 bounded tool-calls, advisory-only, the tool-call
  trace, AND the offline deterministic demo — all still ours (L8).
- Keeps our **tests** and the gateway design; no MCP servers required.
- Model/control stays with us (repeatable demo); proxy adds a usage dashboard + rate-limit
  guard (`--rate-limit`, `--wait`).
- Reconsiders the operator's premise: we don't actually need Copilot's planning loop —
  **we already built and tested one**. The real want is the *subscription as the token
  source*, which this delivers.

## Costs / risks
- **Unofficial** access path (reverse-engineered / community provider) → same **ToS gray
  zone as E1** (see E3); rate limits still apply (L7).
- One extra local process (the proxy) to run/supervise on the corp laptop.
- Corp laptop must permit running the proxy (Node/Python) and the Copilot OAuth device-flow.

## Trust / risk
📚 Documented (LiteLLM first-class provider) · 🟢 Low technical risk, 🟠 ToS-gated (shared
with E1). The gap to close is L7 auth working **on the corp laptop** with the corp seat.
