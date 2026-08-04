# Exploration B — Pacing & honesty

**Bias**: the timing/perception problem and the ethics of synthetic pacing.
**Out of scope here**: transport (SSE/WebSocket/poll) and iconography — other explorations own those.

---

## 0. The reframe that does most of the work

The ask ("animate the thinking") bundles two things that are separable, and the whole
honesty question turns on separating them:

| | What it is | Is it real? |
|---|---|---|
| **Step sequence** | which platform was consulted, in what order, with what result | **Real in both engines.** `DeterministicDiagnosisEngine`'s own javadoc: it runs "the SAME ordered steps the ADK agent runs — fetch → clarify → similar+ownership → knowledge → bounded logs → targeted code → assemble". 8 trace lines, real gateway calls, real results. |
| **Reveal timing** | when each step *appears on the viewer's screen* | A **display property**. Currently: all at once, after the response. |

A **display pace is not a claim about work duration** — unless the screen says or implies
it is. A football replay at 0.5× is not a lie; a replay with a live clock overlay is.
So the honesty question is never "did we add delay?" — it is **"what does the screen
assert?"**

That gives a precise test, sharper than "no artificial delays":

> **The B-test**: for every pixel on screen, is there a real field behind it? A spinner
> asserts *"this step is executing right now."* Present-tense progressive text
> ("Checking ServiceNow…") asserts the same. A number in milliseconds asserts a measured
> duration. An ordered list asserts ordering. Reveal *cadence*, on its own, asserts
> nothing — **provided the frame around it says what it is.**

## 1. What the FND record actually forbids

FND-8, FND-16 and FND-25 are one bug class, but read them precisely — they are not
"don't animate". Every one is **the UI producing a fact by inference or reconstruction
when the backend already knew the truth and was never asked**:

- **FND-25** — writeback card reconstructed *client-side from the report*, so it claimed
  posted comments with `writeback.enabled=false`. Fix: `writebackPosted`, set by the
  orchestrator "AFTER the write decision — the only place that actually knows."
- **FND-8** — a degraded run looked identical to a live one; cost a whole spike cycle
  because "looks like success is the most expensive kind of wrong."
- **FND-16** — the fix for FND-8 was *itself* wired to a regex over trace prose. Fix:
  read `data.engine`. The escape note is the load-bearing sentence for this exploration:
  *"A field that exists specifically to replace a string-match should be used by the
  FIRST thing that needs the distinction, not retrofitted after."*

**The established fix pattern is: add a real field, read the real field.** Applied to
pacing, the pattern-conformant answer is not "no animation" — it is **capture real
per-step timings server-side and display them**. Option 3 is not merely permitted by the
project's value; it is the value's own idiom. That single observation drives the
recommendation.

Two corollaries that bind the rest of this document:

- **C-1 (no new string-matching).** Timings must NOT be appended into the existing
  `List<String> trace` and regexed out in the browser. That is FND-16 committed
  deliberately, with the escape note already written. Per-step data needs a structured
  field.
- **C-2 (no fabricated interiority).** The deterministic engine does not reason. Any
  "thinking" copy invented for it ("weighing whether the discount applies before tax…")
  is fabrication of the single most demo-critical claim ("a high Copilot model
  reasoning"), i.e. FND-8's exact failure mode with better art direction.

---

## 2. The option space

### Option 1 — Animate only real timing; accept that D2 flashes

**What the screen does.** 8 rows paint in one frame. For a 19 ms run, the browser cannot
show anything else — layout + paint alone exceeds the run.

**Honesty**: ✅ perfect. It is the current behaviour, plus structure.

**Is it acceptable on stage?** Yes — *if the number is on screen*. "It was instant" is a
presenter's assertion; "19 ms" is evidence. Without the number, an instant render reads
as *nothing happened / it was pre-baked*, which is a credibility loss in the opposite
direction: the audience's default suspicion of a demo is that it's canned, and a
zero-latency result feeds that suspicion.

**Can instant be a selling point?** Genuinely yes, and it is under-exploited. Frontier
UIs animate because they must — the spinner is an *apology for latency*. "Eight sources
correlated, log→code citation resolved, in 19 milliseconds — the slow part of this
problem is the model, not the evidence gathering" is a stronger line than any animation,
and it sets up D3's contrast (the tool-less frontier model takes 30 s to produce a
*worse* answer). But it only lands as a *number*, not as an absence.

**Verdict**: the zero-risk floor. Ship-order position #1. Underdelivers the operator's
ask on the D2 path, but it is the only option with literally no new failure mode on
stage, and D2 is the stage safety net.

### Option 2 — Synthetic minimum step duration (each step visible ≥ ~400 ms)

Two sharply different designs get conflated under this heading, and they land on opposite
sides of the bar.

**2a — Unlabelled synthetic delay + per-step spinner + present-tense text.**
Screen shows `⟳ Checking ServiceNow…` for 400 ms on a step that finished 19 ms ago.

**Honesty**: ❌ **RULED OUT.** Every element is a false assertion: the spinner asserts
in-flight work; the present progressive asserts *now*; the dwell implies duration. There
is no real field behind any of it. This is FND-8 in a new costume — a screen that
"looks like" a process that already ended — and it is worse than FND-8 because FND-8 was
an *omission* (silent degradation) whereas this is a *fabrication*. It also breaks under
`connectors=real`, where a fixed 400 ms dwell can move the UI *past* a step that is still
running.

**2b — Labelled replay: reveal-only, past tense, no spinner, disclosed frame.**
Rows appear one at a time, each **already resolved**, at a stated display cadence, under
a header that says it is a replay of a finished run and gives the real total.

**Honesty**: ✅ passes, but *conditionally* — the label is doing all the work, so the
label has to be unmissable, always-rendered (not a hover tooltip), and derived from a
real field ("was the run complete before the first paint?"), never hardcoded. Delete the
header and 2b becomes 2a.

The one-label difference between the lie and the affordance:

| ❌ Lie | ✅ Honest affordance |
|---|---|
| `⟳ Checking ServiceNow…` | `ServiceNow · getIncident → CI=Order Portal   12 ms` (revealed, past tense, no spinner) |
| `Step 3 of 8 · working…` | `Step 3 of 8 · replaying` |
| (no frame) | `↺ Replaying a completed run — 8 steps in 19 ms total. Stepped for readability; timings are real.` |

**Verdict**: viable as a *display control* layered on Option 3. Not viable alone —
without real timings the header has no number to be honest about, and "shown stepped"
begs the question "how long did it really take?"

### Option 3 — Real per-step timing, captured and displayed

Each step carries its measured elapsed ms; the UI shows it.

**Honesty**: ✅✅ the strongest option, and **unfalsifiable** — the number either matches
reality or it doesn't, and it's checkable from `docs`-free evidence (the orchestrator
already logs `completed in {} ms`). It converts the awkward fact (D2 is instant) into the
feature (D2 is *fast*). It is also the FND-approved shape: a real field, read directly.

**Does it work for both engines?** Yes, and it's the only option that does, because it is
*derived* rather than *designed for one case*:

| Config | What the screen shows |
|---|---|
| deterministic + mock | 8 rows, 0–12 ms each, total 19 ms |
| deterministic + `connectors=real` | same 8 rows, 200 ms–4 s each, total seconds — **naturally animatable, zero extra work** |
| ADK + proxy | ~10 rows, seconds each, LLM steps dominating |
| ADK → degraded | ADK rows with real times, then the amber banner, then deterministic rows |

**Cost / implementation shape (C-1 compliant).** `trace` is `List<String>`. Do **not**
append `(12 ms)` to the strings. Introduce a structured step —
`record TraceStep(String label, String platform, long ms)` — on `DiagnosisResult`, with
`trace()` retained as a derived `List<String>` view so the ServiceNow work notes, the K1
poller and every existing test keep working. Timing is stamped by a single helper the
engines call instead of `trace.add(...)`, so there is one place that measures and it
cannot drift from what's displayed.

**The bonus finding it forces** — see §4. This is the best reason to do it.

**Verdict**: **mandatory substrate.** Every other option is either a subset of this or
depends on it for its honest label.

### Option 4 — Animate only the ADK path; deterministic renders as a completed list

**Honesty**: ✅ passes cleanly. Two modes, each truthful about its own engine.

**But it fails on stagecraft**, which is why I rule it out as a *primary* design. §3 of
the runbook is the fallback flip: *"just move the browser to 8081 … Say nothing broke;
it's the offline mode."* The flip's entire value is that the UI is **identical**
("identical UI and output"). Option 4 guarantees that the moment the presenter is most
exposed — mid-failure, mid-sentence — the audience sees a *visibly different UI*, right
after being told nothing broke. That is a self-inflicted tell, and it's also a soft
honesty problem: it makes the presenter's "nothing broke" line harder to defend.

The correct shape is **same layout, same rows, same labels — different numbers, and the
cadence falls out of the numbers.** Option 3 delivers exactly that. Note that Option 3
*subsumes* Option 4's real content: with real timings, the ADK path animates and the
deterministic path snaps, automatically, from one code path.

**Verdict**: ruled out as designed (as an *explicit two-mode branch*); its intent is
absorbed by Option 3.

### Option 5 — hybrids and additions

**5a — Option 3 + opt-in labelled replay (the recommendation).** Real timings always;
when the run finished faster than perception (a real, computed condition — total < ~250 ms
*or* the response arrived complete before first paint), offer a **stepped replay** with
the disclosing header. Default it **on** for demo-wow, keep a visible one-click
`Show all at once`, and never spin.

**5b — Turn instantness into the contrast argument.** The runbook's D3 beat is "the
frontier model can't name `payment_service.py:44`." A second beat is available for free:
*"and the offline script did the whole evidence trail in 19 ms."* D2 stops being the
embarrassing fallback and becomes a point. Zero code.

**5c — Duration bars per step (log scale).** A hairline bar whose width ∝ real ms. On
deterministic: eight hairlines — visually reads "instant" without a word. On ADK: LLM
round-trips visibly dominate, which is *itself* the insight the operator wants animated
("look where the thinking happens"). Honest by construction, and it makes the trace card
feel alive with no timing fiction at all. Recommended add-on.

**5d — Ruled out extras**, for the record: fabricated inter-step "reasoning" copy (C-2);
a progress bar with estimated time remaining (asserts an estimate nobody computes);
`Step 4 of 8` on the ADK path (the agent loop is bounded at
`max-tool-calls=10`, not fixed at 8 — a denominator that pretends to know the plan);
easing/typewriter animation *of result text* (implies the result was produced
incrementally when it arrived atomically — fine for the ADK path if the transport really
streams tokens, a fabrication otherwise).

---

## 3. Does `triage.connectors.*=real` change the analysis?

Yes — decisively, and it is the strongest argument against every synthetic-pace design.

`DiagnosisOrchestrator`'s own javadoc says it: the wall-clock timeout applies to *either*
engine "since the deterministic path also makes real HTTP calls once
`triage.connectors.*=real`". So **"D2 is instant" is not a property of the engine — it is
a property of the connector config.** Three consequences:

1. **A fixed synthetic pace is wrong in both directions.** With mock connectors 400 ms is
   ~20× too slow; with real connectors it can be too *fast*, sliding the UI onto step 4
   while step 3's HTTP call is still open. That second failure is FND-8-grade: the screen
   showing a step as finished when it hasn't finished.
2. **Static copy is banned.** A hardcoded `Offline mode — instant` string would be false
   under `connectors=real`, and would be a *third* instance of "the UI asserts a fact it
   didn't read from a field". Every duration word on screen must be derived from a
   measurement. This is exactly why Option 3 has to be the substrate rather than a nice
   extra.
3. **The animation problem partly solves itself.** Under `connectors=real` the
   deterministic engine is genuinely seconds-slow, so the same real-timing renderer
   animates naturally. The sub-perceptible case is *narrow*: deterministic **and** mock.
   Designing the whole UI around that narrow case (Option 2a/4) is over-fitting to one
   config.

---

## 4. Byproduct finding: instant timings expose a latent C-3 gap

Doing Option 3 surfaces a problem that exists **today** and gets worse with visible
numbers. The trace says `servicenow.getIncident(INC0012345) → CI=Order Portal, env=prod`.
On the default config that call went to `MockServiceNowGateway` — a fixture, no network.
Nothing on screen says so. J7 does insist that "everything runs on the default (`mock`)
connector config with no network" — but that's in a design doc, not on the audience's
screen.

Today the ambiguity is survivable because a presenter narrates it. Put `4 ms` next to
that line and a viewer draws one of two conclusions: *ServiceNow answered in 4 ms* (false)
or *that wasn't ServiceNow* (true, but discovered as a catch rather than disclosed). The
first is a C-3 violation the UI is currently one design change away from committing.

**Fix, cheap, and it strengthens the demo rather than weakening it**: one derived chip in
the trace header, from real config —

```
connectors: mock (fixtures, no network)      ·  engine: deterministic  ·  total 19 ms
connectors: real (live HTTP)                 ·  engine: ADK            ·  total 8.4 s
```

"No network — this is a fixture dataset, and it still finds `payment_service.py:44`" is a
*better* stage line than an unqualified one, because RC6 demo-safety is a deliberate
design choice the audience should be told about, not a thing to be caught at. Worth
raising as a candidate FND independently of whether this DDS ships.

---

## 5. Recommendation

**Option 3 as substrate + 5a's labelled replay + 5c's duration bars.** Ship in that
order; each stage is independently shippable and stage-safe.

1. **Real per-step timings** (`TraceStep{label, platform, ms}` on `DiagnosisResult`,
   `trace()` kept as a derived view), the derived config/engine/total chip, timings
   rendered. This alone satisfies the operator's "show the process" intent on the ADK path
   and under `connectors=real`, and it is the only stage that is strictly required.
2. **Labelled stepped replay** for the sub-perceptible case, condition computed not
   assumed, reveal-only, no spinner, dismissible.
3. **Duration bars**, log scale.

### Exact on-screen wording

**Header, live run** (steps arriving as they actually complete — ADK, or deterministic on
real connectors):

```
● Live run — steps appear as they complete.
```

**Header, replayed run** (the run was already over before the first paint):

```
↺ Replaying a completed run — 8 steps in 19 ms total.
   Nothing is running now; steps are revealed at 0.4 s each so they're readable.
   The order and the timings below are real.                    [Show all at once]
```

The four load-bearing words are **Replaying**, **completed**, **Nothing is running now**,
and **real**. That header is the entire difference between this and FND-8.

**Step row — resolved** (the only state a replay ever shows):

```
✓  ServiceNow   servicenow.getIncident(INC0012345) → CI=Order Portal, env=prod      4 ms  ▏
✓  Sumo         sumo.search(scope=prod/payment, window=±10m, max=20) → 6 lines      3 ms  ▏
✓  GitLab       gitlab.searchCode('PAYMENT_RECONCILE_MISMATCH') → 1 hit             2 ms  ▏
```

**Step row — genuinely in flight** (permitted *only* while a real call is open; the
elapsed counter is a live measurement, so present tense is true here):

```
⟳  Sumo         sumo.search(scope=prod/payment, …) — running                       1.4 s
```

**Footer chip** (all three values derived, none hardcoded):

```
engine: deterministic · connectors: mock (fixtures, no network) · 8 steps · 19 ms total
```

**Unchanged and untouched**: the amber degraded banner and the writeback card. Both are
FND-won and neither should be restyled by this work — the banner in particular must keep
rendering *above* the trace, immediately, never gated behind a replay animation. **A
replay must never delay a disclosure.**

### Ruled out by the honesty principle

- **2a — spinners / present-tense / fixed dwell on completed work.** Asserts in-flight
  work that isn't happening; no field behind any of it. FND-8 recommitted deliberately.
- **Fabricated reasoning copy for the deterministic engine.** It has no reasoning to
  narrate, and the demo's central claim is precisely that a frontier model is reasoning.
- **Estimated time remaining / progress bar to completion.** Asserts a prediction nobody
  computes.
- **`Step N of 8` on the ADK path.** Implies a known plan; the loop is bounded, not fixed.
- **Any hardcoded duration copy** (`Offline mode — instant`). False under
  `connectors=real`, and it is the "assert without reading a field" move all three FNDs
  were about.

**Not ruled out on honesty, ruled out on stagecraft**: Option 4's explicit two-mode
branch — it makes the runbook's fallback flip visibly different at the worst possible
moment. Its content survives inside Option 3, where mode falls out of the numbers instead
of being branched on.

### Honesty verdict in one line

Animating a finished run is **not** dishonest; a *spinner* over a finished run is. The
project's own three-times-paid-for fix pattern — add a real field, read the real field —
points at real per-step timings, which turn D2's 19 ms from a thing to hide behind
synthetic delay into the most impressive number on the screen.
