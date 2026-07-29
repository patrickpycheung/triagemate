# Live demo runbook — Copilot-backed triage (Option 1: D1 + D2 + D3)

The demo strategy chosen in DDS `orchestrator-vs-copilot-cli`:

- **D1 (primary)** — our orchestration (`-Padk` `LlmAgent` loop) driving a **high model
  served by your Copilot subscription** through a local OpenAI-compatible proxy.
- **D2 (fallback)** — the **deterministic offline engine**, kept hot on a second port; the
  demo cannot hard-fail on stage.
- **D3 (optional)** — a 30-second **Copilot CLI contrast** run: the same incident handed to
  Copilot CLI with **no access to our systems**, to show what the evidence trail buys.
  Droppable with zero impact on the story.

> **D3 is deliberately a no-tools contrast, not an autonomous triage.** A genuinely
> autonomous Copilot CLI run would need MCP servers fronting ServiceNow / Sumo / Confluence
> / GitLab — that build-out is concept **C5** in DDS `copilot-cli-runtime`, explicitly
> deferred off the hackathon path, and none exist in this repo. Copilot CLI also has a
> known ≥7-tool headless MCP bug. So D3 runs tool-less by design: it costs nothing to
> prepare and it *strengthens* D4 instead of competing with it.

> Run all of this on the **corporate laptop** (the one that reaches ServiceNow). The
> Copilot proxy + high models require your corporate Copilot seat.

---

## 0. One-time prep (before demo day)

### a) Stand up the Copilot → OpenAI proxy (E2)
Pick one:

```bash
# Option A: copilot-api (built-in usage dashboard + rate-limit guard)
npx copilot-api@latest        # completes GitHub OAuth device-flow (uses your Copilot seat)
                              # serves http://localhost:4000

# Option B: LiteLLM (first-class github_copilot provider)
pip install "litellm[proxy]"
# minimal config.yaml:
#   model_list:
#     - model_name: copilot-high
#       litellm_params: { model: github_copilot/claude-opus-4.6 }   # no api_key needed (OAuth)
litellm --config config.yaml   # serves http://localhost:4000
```

### b) Discover the exact high-model id your seat exposes
```bash
curl -s http://localhost:4000/v1/models | jq -r '.data[].id'
# pick a HIGH tier, e.g. claude-opus-4.6 or gpt-5.3-codex (names change — use what's listed)
```

### c) Wire the app to the proxy (config-only — no code change)
`secrets.properties` at the repo root:
```properties
triage.integrations.llm.base-url=http://localhost:4000/v1
triage.integrations.llm.api-key=anything          # proxy ignores it (OAuth-backed)
triage.integrations.llm.model=claude-opus-4.6     # the high id from step (b)
```

### d) Smoke-test the whole chain once
```bash
# proxy answers:
curl -s http://localhost:4000/v1/chat/completions \
  -H 'content-type: application/json' \
  -d '{"model":"claude-opus-4.6","messages":[{"role":"user","content":"say hi"}]}' | head

# app + high model, one incident, live agent path:
mvn -Padk spring-boot:run -Dspring-boot.run.arguments=--triage.engine=adk &
curl -s -X POST http://localhost:8080/api/diagnose/INC0012345 | jq '.report.suggestedAssignment, .trace'
```

You should see advisory output **and** a `trace` proving bounded, real tool calls.

---

## 1. Demo-day terminal layout

| # | What | Command | Port |
|---|------|---------|------|
| T1 | Copilot proxy | `npx copilot-api@latest` (or `litellm --config config.yaml`) | 4000 |
| T2 | **Primary (D1)** — our loop + high model | `mvn -Padk spring-boot:run -Dspring-boot.run.arguments=--triage.engine=adk` | 8080 |
| T3 | **Fallback (D2)** — deterministic, offline | `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081` | 8081 |
| T4 | **Contrast (D3, optional)** — Copilot CLI, no tools | `copilot -p "Here is incident INC0012345: <paste the summary>. Diagnose it."` | — |
| — | Browser | `http://localhost:8080` (primary) · `http://localhost:8081` (standby) | — |

Start T1 → T2 → T3 **before** you present, so both instances are warm. T3 needs **no
network** (default deterministic engine), so it is your guaranteed floor.

---

## 2. Running the demo

1. **Lead with the value + evidence trail (D4).** Open `http://localhost:8080`, trigger
   `INC0012345`, and narrate: sources consulted → first-pass diagnosis → the **log↔code
   citation** (`payment_service.py:44`) → "who to talk to" → the **trace** showing it
   really called ServiceNow/Sumo/Confluence/GitLab. Emphasise **advisory-only + bounded**.
2. **This is a high Copilot model reasoning, on rails.** Same quality ceiling as a fully
   autonomous agent, but controlled, repeatable, and auditable.
3. **(Optional) D3 contrast** — *only if network is healthy and ToS is clear*: switch to T4
   and paste the same incident into Copilot CLI with no access to our systems. Let it
   answer for ~30s, then land the point: it produces a *plausible* diagnosis, but it cannot
   name `payment_service.py:44`, cannot say which deploy correlates, and cannot tell you
   who to talk to — because it never read the logs, the wiki or the repo. **The frontier
   model is the same one we just used; the difference on screen is the evidence trail.**
   Then stop and return to the 8080 result.

---

## 3. The fallback flip (rehearse this)

If the model/proxy misbehaves live (slow, error, network drop):

- **Just move the browser to `http://localhost:8081`** (the deterministic standby) and
  keep going — identical UI and output, **no LLM, no network**. Say nothing broke; it's
  the offline mode.
- If you prefer one port: stop T2 and run `mvn spring-boot:run` (default engine =
  deterministic) on 8080.

Because D2 is already running, the flip is a single browser-tab switch. **Never** debug on
stage — flip and continue.

---

## 4. Pre-flight checklist (10 min before)

- [ ] T1 proxy up; `curl /v1/models` lists your high model.
- [ ] `secrets.properties` model id matches an id from `/v1/models`.
- [ ] T2 (8080) answered one warm-up `POST /api/diagnose/INC0012345` with a real `trace`.
- [ ] T3 (8081) deterministic standby answered the same incident offline.
- [ ] Decide **now** whether D3 runs — network + ToS ok? If unsure, **skip it**. (No MCP
      setup needed: D3 is tool-less by design — have the incident summary on the clipboard.)
- [ ] Browser tabs open on 8080 and 8081.

---

## Notes & guardrails
- **ToS**: D3 (Copilot CLI) and driving the seat via a proxy are the
  ToS-sensitive parts — fine at human-present demo scale; get IT/legal sign-off before any
  **unattended** production use (DDS `copilot-cli-runtime`, E3).
- **Guardrails intact**: D1 keeps advisory-only writes, `BoundsCallback` max-tool-calls,
  and allowlisted Sumo scopes — the model can't reassign/close/re-prioritise.
- **Why not Copilot CLI as the primary path**: non-determinism + the ≥7-tool headless MCP
  bug + network/ToS exposure make it a stage-failure risk. See DDS
  `orchestrator-vs-copilot-cli`.
