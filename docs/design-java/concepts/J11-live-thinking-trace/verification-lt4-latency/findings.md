# LT4 — Real ADK per-step latency

**Status**: ✅ DONE (2026-08-01) · **Trust**: 🔬 Spiked — measured, not reasoned
**Run by**: operator, on the corporate laptop · **Raw**: `spike-run-2.json`,
`proxy-output.log`, `adk-output.log` (repo root at time of run)

Answers the question `STATUS.md` parked J11 on: *how long does a real ADK step actually
take, and does LT4 (live streaming of the trace) therefore matter?*

## Setup

| | |
|---|---|
| Model | Copilot-served, via local `copilot-api` proxy on :4000 |
| Connectors | **ServiceNow real, Confluence real**, Sumo + GitLab mock |
| Incident | `INC0010005` — "Delivery Hazards - All hazards are no longer present" |
| Build | `-Padk`, `triage.engine=adk` |

This is the **first end-to-end agentic run against a real Copilot-served model.** The
previous attempt (`spike-run-1.json`) degraded to the deterministic engine before the agent
produced anything — see FND-66; three defects had to be fixed before a measurement was even
possible.

## The numbers

Per-step, from the `[t=…ms, +…ms]` trace (FND-65 instrumentation):

| Step | Elapsed since start | **Gap** |
|---|---|---|
| `get_incident` | 6 680 ms | **6.7 s** |
| `find_similar_incidents` | 18 692 ms | **12.0 s** |
| `search_confluence` | 25 124 ms | **6.4 s** |
| final report produced | 39 731 ms | **14.6 s** |
| *+ 2 ServiceNow write-backs* | 44 129 ms | ~4.4 s |

Proxy-side LLM time for the same run: **6 s + 6 s + 3 s + 13 s = 28 s** across 4 calls.

So of the 39.7 s agent time, **~28 s is model latency and ~12 s is real tool execution**
(live ServiceNow + Confluence HTTP). The mock connectors contribute ~0.

- **Total wall clock: 44.1 s** for a **3-tool-call** run.
- **Mean per-tool-call gap: ~8.4 s.** Worst single gap: **14.6 s** (final report generation).

## What this decides

### 1. LT4 is required, not a nice-to-have — J11's core question, answered

A **three**-call run leaves the screen blank for **40 seconds**, with a **14.6 s** stretch of
nothing during final report generation. The documented investigation flow uses up to **8**
tools. Extrapolating at the measured mean:

```
N tool calls ≈ N × 8.4 s + 14.6 s   (final report)
   3 calls  →  ~40 s   ← measured
   6 calls  →  ~65 s
   8 calls  →  ~82 s
  10 calls  →  ~99 s   ← exceeds the 90 s timeout
```

A 40–80 s blank screen mid-demo is not survivable, and no amount of spinner polish fixes
"nothing has visibly happened for 15 seconds". **The reveal cadence question is settled by
this too**: with ~8 s between real steps, a fixed artificial floor is unnecessary — the real
gaps are already far longer than any pacing floor we discussed. Reveal each step as it lands.

### 2. `timeout-ms: 90000` was a lucky guess, and it is marginal

The 90 s was self-documented as a guess. Measured, it is **the right order of magnitude but
not comfortable**: a full-budget 10-call run against real connectors lands at ~99 s and would
be **killed by its own timeout**, degrading to deterministic mid-demo — which on stage looks
exactly like the agent failing.

The two bounds contradict each other: `triage.agent.max-tool-calls: 10` permits a run that
`triage.orchestrator.timeout-ms: 90000` will not allow to finish. Logged as **FND-69** and
raised to 120 s — see that entry for the reasoning (and for why lowering the tool budget
instead was rejected).

Caveat worth keeping: the stage demo runs **mock** connectors, where tool execution is ~0 and
only the ~28 s of model latency applies. Mock-path 10 calls ≈ 74 s, comfortably inside 90 s.
The squeeze is real-connector-only.

### 3. Real tool latency is not negligible

~12 s of the 40 s was live ServiceNow + Confluence HTTP — roughly 30%. `find_similar_incidents`
alone cost ~6 s of non-model time. Any latency model that counts only LLM calls understates
the real thing by about a third.

## Agent behaviour observed (not the spike's question, but worth recording)

The run was genuinely good, and two things are worth noting because they validate earlier fixes:

- It skipped `search_logs` and said why: *"Application not in allowlisted log scopes
  (prod/payment, prod/order-api) so log search skipped"*. That is **FND-60 working as
  designed** — the agent could see the allowlist, saw nothing matched, and declined to burn a
  tool call on a guaranteed rejection.
- It extracted `environment: "prod (ap-cooi-prod-02)"` from a URL in the ticket body — a field
  the deterministic engine reported as `null`, since `u_environment` was unset. A real point
  for the agentic path over the scripted one.
- It reported `confidenceOverall: LOW` with six specific `missingInformation` entries rather
  than overclaiming on thin evidence. Correct behaviour for a ticket where Confluence returned
  zero pages.

## Follow-on

- **Projector legibility (LT5)** is still unmeasured — needs an actual projector, unrelated to
  this spike.
- Only **one** run was captured. The numbers above are a single sample; the ±  is unknown.
  Worth 2–3 more runs before treating the 8.4 s mean as stable, though the *conclusion* (LT4
  required) holds under any plausible variance.
- All three connectors real (Sumo + GitLab too) would push tool time higher still.
