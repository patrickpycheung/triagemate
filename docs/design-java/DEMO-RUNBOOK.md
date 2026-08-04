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

> **After the first-time setup below, `./run-adk.sh` starts the proxy for you** —
> it checks `http://localhost:4000/v1/models`, and if nothing answers, runs
> `copilot-api start --port 4000 --proxy-env` itself (falling back to `npx
> copilot-api@latest` if `copilot-api` isn't on PATH) and waits up to 30s for it to
> come up. The GitHub OAuth device-flow (step a below) only has to happen once —
> the token is cached, so later runs just reuse it. Steps (a)-(d) are still the
> reference for first-time setup, corp-laptop npm/proxy issues
> (`./bin/setup-copilot-api.sh`), and picking/validating the model.

### a) Stand up the Copilot → OpenAI proxy (E2)

> **Validate with `./bin/e2-proxy-spike.sh` once it's up** — in particular check 4,
> tool-calling. Whether a Copilot proxy passes `tools` through to the model is the single
> highest-risk unknown in D1, and it cannot be tested off the corp laptop.
>
> **Rehearse the spike anywhere, without a Copilot seat**, using the stub proxy — so a
> broken *spike* is never mistaken for a broken *proxy* on demo morning:
> ```bash
> ./bin/fake-openai-proxy.py &                 # compliant  -> spike exits 0
> ./bin/fake-openai-proxy.py --drop-tools &    # broken     -> check 4 fails, exit 1
> ./bin/e2-proxy-spike.sh http://localhost:4000/v1 claude-opus-4.6
> ```
> `--drop-tools` reproduces the exact silent failure that matters: a proxy that accepts
> `tools` and ignores them. Add `--log req.json` to see the precise request shape our app
> sends — that shape is the contract a real proxy has to satisfy.

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
# pick a HIGH tier (names change — use what's listed)
```
> **Verified 2026-07-30 on the corp laptop**: the seat exposes **31 models**, including
> `claude-opus-4.6`, `claude-sonnet-5`, `gpt-5.3-codex`, `gpt-5.4`, `gemini-3.5-flash`.
> **Demo on `claude-opus-4.6`.** Raw list: `bin/spikes/spike-output.log`.
>
> ⚠️ **Do not leave this on `gpt-4o-mini`.** It's served, so the spike's check 2 passes —
> but it is a *mini* model, and both the step-2 narration ("a high Copilot model
> reasoning, on rails") and D3's contrast ("the frontier model is the same one we just
> used") become false on stage. The `secrets.properties.example` default is now
> `claude-opus-4.6` for exactly this reason.

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
./run-adk.sh &
curl -s -X POST http://localhost:8080/api/diagnose/INC0010005 | jq '.report.suggestedAssignment, .trace'
```

You should see advisory output **and** a `trace` proving bounded, real tool calls.

---

## 1. Demo-day terminal layout

| # | What | Command | Port |
|---|------|---------|------|
| T1 | Copilot proxy | `npx copilot-api@latest` (or `litellm --config config.yaml`) | 4000 |
| T2 | **Primary (D1)** — our loop + high model | `./run-adk.sh` | 8080 |
| T3 | **Fallback (D2)** — deterministic, offline | `./run-deterministic.sh -Dspring-boot.run.arguments=--server.port=8081` | 8081 |
| T4 | **Contrast (D3, optional)** — Copilot CLI, no tools | `copilot -p "Here is incident INC0010005: <paste the summary>. Diagnose it."` | — |
| — | Browser | `http://localhost:8080` (primary) · `http://localhost:8081` (standby) | — |

Start T1 → T2 → T3 **before** you present, so both instances are warm. T3 needs **no
network** (default deterministic engine), so it is your guaranteed floor.

---

## 2. Running the demo

1. **Lead with the value + evidence trail (D4).** Open `http://localhost:8080`, trigger
   `INC0010005`, and narrate: sources consulted → first-pass diagnosis → the **log↔code
   citation** (`payment_service.py:44`) → "who to talk to" → the **trace** showing it
   really called ServiceNow/Sumo/Confluence/GitLab. Emphasise **advisory-only + bounded**.
2. **This is a high Copilot model reasoning, on rails.** Same quality ceiling as a fully
   autonomous agent, but controlled, repeatable, and auditable.
3. **(Optional) D3 contrast** — *only if the network is healthy* (C6 risk acceptance is the
   same one D1 is already running under — see Notes): switch to T4
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
- If you prefer one port: stop T2 and run `./run-deterministic.sh` on 8080.

Because D2 is already running, the flip is a single browser-tab switch. **Never** debug on
stage — flip and continue.

---

## 4. Pre-flight checklist (10 min before)

- [ ] **`./bin/e2-proxy-spike.sh` exits 0.** This replaces the two manual checks below —
      it verifies the proxy is up, lists models, confirms your configured id is served, and
      (critically) that the proxy **returns `tool_calls`** rather than dropping the `tools`
      field. A proxy that drops `tools` silently degrades D1 to a single-shot answer with
      **no evidence trail** — which is the whole demo. Run it before anything else.
- [ ] JDK + Maven present (`mvn -v`) — a JRE alone cannot build `-Padk`.
- [ ] T2 (8080) answered one warm-up `POST /api/diagnose/INC0010005` with a real `trace`.
- [ ] T3 (8081) deterministic standby answered the same incident offline.
- [ ] Decide **now** whether D3 runs — network ok? If unsure, **skip it**. (No MCP
      setup needed: D3 is tool-less by design — have the incident summary on the clipboard.)
- [ ] Browser tabs open on 8080 and 8081.

---

## Notes & guardrails
- **ToS applies to D1, not only D3.** Driving the corporate Copilot seat through a proxy
  is *programmatic* use, which is exactly what C6 ([[copilot-cli-runtime]]) gates — so the
  ruling governs the **primary** path. Operating position: fine at human-present demo
  scale; required before anything unattended. If the ruling is "no programmatic use",
  D1 is off and **D2 becomes the demo**.
  The gate's id is **C6** (`copilot-cli-runtime`); E3 is the exploration that produced it.
- **Guardrails intact**: D1 keeps advisory-only writes, `BoundsCallback` max-tool-calls,
  and allowlisted Sumo scopes — the model can't reassign/close/re-prioritise.
- **Why not Copilot CLI as the primary path**: non-determinism + the ≥7-tool headless MCP
  bug + network/ToS exposure make it a stage-failure risk. See DDS
  `orchestrator-vs-copilot-cli`.
