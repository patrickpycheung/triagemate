# LT4 — Real ADK per-step latency

**Status**: ✅ DONE (2026-08-01) · **Trust**: 🔬 Spiked — measured, not reasoned
**Run by**: operator, on the corporate laptop · **Samples**: 3 successful ADK runs
**Raw**: `spike-run-{2,3,4}.json`, `proxy-output.log`, `adk-output.log` (repo root at run time)

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

Three successful runs (`spike-run-2/3/4.json`), per-step from the `[t=…ms, +…ms]` trace
(FND-65 instrumentation):

| Step | run 2 | run 3 | run 4 | **mean** |
|---|---|---|---|---|
| `get_incident` | 6.7 s | 6.0 s | 5.9 s | **6.2 s** |
| `find_similar_incidents` | 12.0 s | 12.7 s | 6.9 s | **10.6 s** |
| `search_confluence` | 6.4 s | 7.3 s | 7.7 s | **7.1 s** |
| final report generated | 14.6 s | 12.4 s | 12.4 s | **13.1 s** |
| **agent total (3 calls)** | 39.7 s | 38.4 s | 33.0 s | **37.0 s** |
| **incl. write-backs** | 44.1 s | 44.7 s | 38.2 s | **42.3 s** |

- **Per-tool-call gap: 8.0 s ± 2.6 s** (n=9, range 5.9–12.7 s)
- **Final report generation: 13.1 s ± 1.3 s** — consistently the single longest gap
- Proxy-side LLM time per run: **26–28 s across 4 calls**, very stable

Two things the spread shows. `get_incident` (6.2 s) and `search_confluence` (7.1 s) are
tight; **`find_similar_incidents` is the volatile one** (6.9–12.7 s) — it is the real
ServiceNow `LIKE` query, so the variance is upstream, not ours. And of the ~37 s agent time,
~27 s is model latency and **~10 s is real tool HTTP** — about 30%, which any LLM-only
latency estimate would miss entirely.

**Output was byte-identical across all three runs** — same single candidate (*Delivery
Hazards Portal*, 0.70), same `LOW` overall confidence, same single contact (*Steve Taylor*),
same evidence count. Useful for a stage demo: the agent is not going to say something
different on the day.

## What this decides

### 1. LT4 is required, not a nice-to-have — J11's core question, answered

A **three**-call run leaves the screen blank for **37 s**, with a **13 s** stretch of nothing
during final report generation. The documented investigation flow uses up to **8** tools.
Modelling N independent calls (so the spread grows as `√N`, not `N`):

```
mean ≈ N × 8.0 s + 13.1 s        sd ≈ √(N × 2.6² + 1.3²)
   3 calls  →  37.0 s ± 4.6      ← measured, matches
   6 calls  →  60.9 s ± 6.4
   8 calls  →  76.8 s ± 7.4
  10 calls  →  92.7 s ± 8.2      (the full max-tool-calls budget)
```

A 40–80 s blank screen mid-demo is not survivable, and no amount of spinner polish fixes
"nothing has visibly happened for 15 seconds". **The reveal cadence question is settled by
this too**: with ~8 s between real steps, a fixed artificial floor is unnecessary — the real
gaps are already far longer than any pacing floor we discussed. Reveal each step as it lands.

### 2. `timeout-ms: 90000` was a lucky guess, and it is marginal

The 90 s was self-documented as a guess. Measured, it is **the right order of magnitude but
too tight**: a full-budget 10-call run against real connectors lands at **92.7 s ± 8.2** and
would be **killed by its own timeout**, degrading to deterministic mid-demo — which on stage
looks exactly like the agent failing.

At the corrected **120 s**, that same worst case sits **3.3 standard deviations** clear, and
the realistic 8-call case **5.9 sd** clear. (An earlier draft of this note reasoned "+1 sd on
every call" and got ~118 s for 10 calls, which would have left almost no margin — that was
wrong: it assumed the calls are perfectly correlated. They are independent draws, so the
spread grows with `√N`. The corrected figure is what the table above shows. Recorded because
the wrong version nearly argued for a 150 s timeout that isn't needed.)

The two bounds contradict each other: `triage.agent.max-tool-calls: 10` permits a run that
`triage.orchestrator.timeout-ms: 90000` will not allow to finish. Logged as **FND-69** and
raised to 120 s — see that entry for the reasoning (and for why lowering the tool budget
instead was rejected).

Caveat worth keeping: the stage demo runs **mock** connectors, where tool execution is ~0 and
only model latency applies (~27 s of the ~37 s here, so ~5.9 s per call). Mock-path 10 calls
≈ 72 s, comfortably inside even the old 90 s. **The squeeze is real-connector-only.**

### 3. Real tool latency is not negligible

~10 s of the ~37 s was live ServiceNow + Confluence HTTP — roughly 30%, consistent across all
three runs (agent 33–40 s vs proxy-side LLM 26–28 s). `find_similar_incidents` is where most
of it lands, and it is also the most variable step (6.9–12.7 s). Any latency model that counts
only LLM calls understates the real thing by about a third.

## Agent behaviour observed (not the spike's question, but worth recording)

The runs were genuinely good, and identical to each other. Three things are worth recording
because they validate earlier fixes:

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
- ~~Only one run was captured~~ — **3 runs captured 2026-08-01**, mean 8.0 s ± 2.6 s per call.
  Still a small sample (n=9 gaps) and all against the same incident on the same afternoon, so
  it does not capture time-of-day proxy load or a heavier ticket. The *conclusion* (LT4
  required) is robust well beyond that uncertainty.
- Calls are modelled as independent. A slow proxy interval would correlate consecutive calls
  and widen the real spread beyond `√N`; the 3.3 sd headroom at 10 calls absorbs a fair
  amount of that, but it is an assumption, not a measurement.
- All three connectors real (Sumo + GitLab too) would push tool time higher still.
