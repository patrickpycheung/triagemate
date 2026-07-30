# Concepts extracted (Phase 4 → CDS handoff)

## Recommended decision
**Adopt E2 — use the Copilot subscription as a local OpenAI-compatible LLM backend, and
keep TriageMate's existing orchestration + guardrails.** Prefer this over the original
"adopt Copilot CLI + build MCP servers" (E1) because we already own a working, tested,
bounded agent layer; E2 delivers the real requirement (subscription-not-API-key) with a
one-line config change and no loss of control. Keep E1 as an *interactive* option only.

## Concepts for design (CDS)
- **C1 — Copilot LLM proxy sidecar**: run LiteLLM (`github_copilot` provider) or
  `copilot-api` locally, exposing `http://localhost:4000/v1`. Auth = Copilot OAuth
  device-flow (no API key). Config how it starts on the corp laptop.
- **C2 — Point existing engine at the proxy**: set `triage.integrations.llm.{base-url,
  api-key,model}` to the proxy; `-Padk` path unchanged. (Our recent secrets.properties
  work already makes this a config-only change.)
- **C3 — Rate-limit & quota guard**: use the proxy's `--rate-limit`/`--wait` (copilot-api)
  or LiteLLM limits; keep J8 max-tool-calls. Protects the seat from "excessive automated
  use" flags.
- **C4 — Trigger stays ours (K1 poll or manual)** from the [[servicenow-local-trigger]]
  DDS. Headless via polling is **contingent on C6**.
- **C5 (optional, deferred) — MCP surface (E5/E1)**: only if we later want Copilot IDE /
  Copilot CLI to drive the same tools; wrap existing gateways once. Not on the hackathon
  path.
- **C6 (operator/IT/legal — ADM-4)**: get an explicit ruling on whether the **corporate
  Copilot agreement** permits programmatic (and specifically **unattended**) use, incl.
  via a local proxy. Interactive use is the safe fallback if the answer is "no".

  **Operating position (operator, 2026-07-29) — scope this precisely, it governs D1:**
  - **Human-present hackathon demo** (D1 driving the proxy, D3 invoking Copilot CLI):
    **proceed**, as an accepted, time-boxed risk. This is *not* "C6 does not apply" —
    C6 does apply, and the risk is knowingly taken.
  - **Unattended / production** (K1 poller running without a human): **BLOCKED** until
    the ruling lands. This is the hard gate.
  - If the ruling is "no programmatic use at all", **D1 is off**: fall back to D2
    (deterministic, no LLM) or an authorised enterprise LLM endpoint.

## Spikes to run (operator-gated; can't be done from the dev box)
1. **[C6] ToS/licensing** — ask IT/legal: may we drive the corporate Copilot seat from an
   app/proxy? unattended? This decides headless-vs-interactive. *(ADM-4 — the operator's.)*
2. **[C1] Proxy-on-laptop spike** — ✅ **RESOLVED 2026-07-30 on the real corp laptop.**
   `copilot-api` (not LiteLLM) serves `http://localhost:4000/v1` off the corporate Copilot
   seat, and **`bin/e2-proxy-spike.sh` passed 4/4** — including check 4, tool-calling:
   the proxy returns `tool_calls`, so **D1's ADK agent loop works and the evidence trail
   survives**. This was the single highest-risk unknown in the demo; it is now closed.
   Raw evidence: `bin/spike-output.log`. Setup encoded in `bin/setup-copilot-api.sh`.

   **The seat exposes 31 models, including frontier tiers** — `claude-opus-4.6`,
   `claude-sonnet-5`, `gpt-5.3-codex`, `gpt-5.4`, `gemini-3.5-flash`. So D1's "high
   Copilot-served model" premise and D3's "same frontier model" contrast both hold as
   written; no amendment needed. **Demo on `claude-opus-4.6`** (the id D1 named).
   ⚠️ `gpt-4o-mini` was the untouched placeholder in `secrets.properties.example`, not a
   ceiling — that default has since been changed to `claude-opus-4.6`, because leaving it
   on a mini model would silently falsify both D1's and D3's on-stage claims.

   **Corp-laptop friction worth knowing** (all encoded into the spike's failure message):
   plain `npx copilot-api@latest` fails **E401** because npm defaults to the internal Nexus
   registry — needs `--registry=https://registry.npmjs.org/`
   `--cafile=/etc/ssl/certs/ca-certificates.crt`; `--proxy-env` must come **after** `start`
   (citty parses it as a subcommand option, not a global flag); and a 403 *"No access to
   GitHub Copilot found"* means the **seat isn't assigned**, not a proxy problem.

   <details><summary>Original spike (superseded)</summary>

   on the corp laptop: `npx copilot-api@latest` (or
   LiteLLM), complete the Copilot OAuth device-flow, then
   `curl http://localhost:4000/v1/chat/completions` with a trivial prompt → expect a
   completion. Confirms the seat serves an OpenAI-compatible endpoint there **and** that
   the proxy binary is permitted by endpoint policy.

   </details>

3. **[C2] End-to-end** — ⚠️ **ATTEMPTED 2026-07-30, DID NOT VALIDATE. Still open.**

   The run returned HTTP 200 with a complete, correct report (`Payments Platform Support`,
   real log↔code citation, two comments posted) — but **the ADK engine never executed**. It
   threw immediately on a blank `triage.integrations.llm.api-key` and the FND-7 fallback
   degraded to the **deterministic engine**. No LLM was called. Evidence:
   `bin/spike-output.log`, trace line 1:
   `⚠ primary engine did not converge (IllegalStateException: Missing required config:
   LLM_API_KEY …) — degraded to the deterministic engine`.

   Two consequences, both now handled: the blank-api-key trigger is fixed
   (`secrets.properties.example` ships a placeholder — the proxy ignores the value, but
   `AdkModelFactory.require()` rejects *blank*), and the "a degraded run looks like a
   successful one" hazard this exposed is logged as **FND-8** with a UI banner mitigation.

   **To actually complete C2**: ensure `llm.api-key` is **non-empty**, then
   `./run-adk.sh` → POST one incident → **confirm no degradation banner / no `degraded`
   line in the trace**, and that the trace shows real `adk:` tool calls.

   Still unanswered until then: does a **real** frontier model converge inside the 14-call
   budget (`maxToolCalls + 5` — was `+ 4`; FND-42's repair retry raised it 2026-07-30, so
   the real ceiling at the default `max-tool-calls: 10` is **15 LLM calls**), and does it
   return **valid J4 JSON**? Safe to retry — a
   genuine non-convergence degrades gracefully rather than 500ing.

## Note on the operator's premise
"Copilot CLI already has the planning, so we don't build it" is true — but we **already
built and tested** that layer. The lasting value on offer is the **subscription as the
token source**, which C1–C2 capture without discarding working, controllable code.
