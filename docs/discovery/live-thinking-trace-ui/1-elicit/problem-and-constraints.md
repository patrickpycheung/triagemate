# Phase 1 — Elicit: animated "thinking trace" UI

## The ask (operator, 2026-07-30)

Visualise the copilot's *process of thinking* during a run — the way Rovo and frontier
model UIs do it. Not a progress bar, not the word "investigating": a **live log of steps**
where each line carries the **logo of the platform it touched** (ServiceNow, Confluence,
GitLab, Sumo Logic), animates while in progress, and then **resolves in place** — the
"checking…" text is replaced by the result when that step completes, then the next line
starts. Audience should see *something happened*, per step, per platform.

Purpose is explicitly **presentation/demo quality**: people should not sit watching a
spinner. This is a demo-wow concern (J7's territory), not a functional requirement of
the diagnosis itself.

> Terminology note: the operator said "LendChain" — read as **the live agent path**
> (LangChain4j / Google ADK `LlmAgent`, i.e. D1 / `triage.engine=adk`), as distinct from
> the deterministic engine. The ask is about visualising *that* reasoning loop, though see
> C-2 below: the constraint cuts the other way from what that implies.

## Verified facts this must be designed against

All measured/read in-session 2026-07-30 against the current tree (`32e2b47`), not assumed:

- **F-1 — The trace is returned only at the end.** `DiagnosisResult.trace` is a
  `List<String>`, accumulated during the run and serialised with the response.
  `POST /api/diagnose/{n}` is synchronous: the browser gets all 11 trace lines at once,
  after everything is already finished. **There is no incremental channel today.**
- **F-2 — The deterministic engine completes in 2–19 ms.** Measured across the live
  end-to-end run (`completed in 2 ms`, `3 ms`, `19 ms`). This is the **default** engine,
  and it is **D2 — the demo's guaranteed offline floor**. A real-time animation of a
  19 ms run is not perceptible: the run is over before the browser paints a frame.
- **F-3 — The ADK path is genuinely slow enough to animate**, but is the *optional* one:
  needs `-Padk` + a live Copilot proxy, up to `triage.agent.max-tool-calls=10` real LLM
  round trips, bounded by `triage.orchestrator.timeout-ms=90000`. It is also the path
  that can fail and degrade to the deterministic engine (FND-7).
- **F-4 — Trace lines already encode their platform**, as a prefix:
  `servicenow.getIncident`, `servicenow.findSimilarIncidents`, `servicenow.findOwnership`,
  `confluence.search`, `sumo.search`, `gitlab.searchCode`, `servicenow.addWorkNote` (×2),
  plus non-platform lines: `understand:`, `contacts:`, `report assembled:`, and on the ADK
  path `adk tool call: <tool>` / `adk: DENIED <tool> — <why>`.
  So platform attribution is largely derivable from data that already exists.
- **F-5 — The UI is a single 138-line static `index.html`**, plain HTML + two `fetch`
  calls, no framework, **no `package.json`, no build step**.
- **F-6 — Offline is a hard requirement (RC6).** The demo runs with `triage.connectors.*=mock`
  and **no network**. Logos therefore cannot be CDN-fetched — they must be inlined
  (SVG / data URI) or not used.
- **F-7 — `DiagnosisOrchestrator.run()` is the single rendezvous point** for both triggers
  (K1 poller, K3 manual), and FND-31 coalesces concurrent same-incident calls into one
  run — relevant if more than one viewer watches the same incident's stream.

## Constraints

- **C-1 — Don't break what's built.** J7's degraded-run banner (FND-16, reads
  `data.engine`) and writeback card (FND-25, reads `data.writebackPosted`) are recent
  fixes for exactly this class of UI bug. 34/34 + 50/50 tests currently green.
- **C-2 — The animation problem is the *opposite* of what it looks like.** The instinct is
  "make the slow agent legible." The real difficulty is that the path the demo is
  *guaranteed* to fall back to (D2, deterministic) is **too fast to see** (F-2). A design
  that only works on the ADK path leaves the safety-net path with a blank or
  instantaneous UI — precisely when the presenter is already recovering from a failure.
- **C-3 — Honesty is a documented project value, not a nicety.** FND-8, FND-16 and FND-25
  were *all* the same bug class: **the UI telling the audience something that did not
  happen.** Each was fixed by reading a real field instead of inferring/faking. So
  "insert artificial delays and animate a fake sequence" is not a neutral implementation
  choice here — it directly contradicts a principle this codebase has already paid to
  establish three times. Any pacing/synthetic-timing approach must be explicit about what
  it claims on screen. **This is the crux of this DDS.**
- **C-4 — Hackathon rigor.** No frontend build step, no framework adoption, no
  multi-week refactor. Must survive being demoed on a corp laptop.
- **C-5 — Trademark/asset reality.** Real ServiceNow / Confluence / GitLab / Sumo Logic
  logos are third-party marks; they also need to be embedded offline (F-6) and be
  legible at small size on a projector.

## Success criteria

1. On the **primary demo path**, an audience member with no context can see, per step,
   *which system* was consulted and *what it found* — without the presenter narrating it.
2. On the **fallback path (D2)**, the same UI is still coherent and still honest —
   whatever it does about F-2, it doesn't claim a 19 ms run took 8 seconds of "thinking".
3. Nothing on screen asserts something that did not happen (C-3).
4. No new build step; still boots and runs fully offline.
5. Degraded-run banner and writeback card keep working (C-1).

## Out of scope

- Changing what the diagnosis actually *does* (engines, guardrails, report contract).
- Persisting traces, multi-user dashboards, replay of historical runs.
- The K1 poller's unattended path — it has no UI by definition (FND-8's whole point).
