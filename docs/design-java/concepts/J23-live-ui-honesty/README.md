# J23 — Live UI Honesty (the honesty contract holds in every UI state)

**State**: 🔴 Designed, not built · **Complexity**: Simple ·
**Priority**: LOW ·
**Depends on**: J7 (UI), J11 (LT3/LT4/LT7) ·
**Amends**: J11 (LT4's caption is not terminal-state-correct; LT7's chips are final-render-only and
one branch short) ·
**Source**: application review 2026-08-05 (multi-agent + Codex + Gemini), 3 confirmed findings

## Essence

J11's honesty contract — *never assert on screen something that did not happen* — is currently
discharged **per renderer**, and every renderer that existed when a rule was written obeys it.
The three states that were added *later* do not: the live window has no provenance, the finished
live run keeps a present-tense caption, and the degraded run gets an engine chip written for a
run that never touched the network. This card makes the contract **per UI state** instead of per
renderer: at every instant a trace card is on screen, its caption describes the state it is
actually in, and its provenance chips describe the run that is actually producing those rows.

## Why one card

All three are the same omission with three faces, and fixing them piecemeal re-creates it.
The J11 contract was written against two *renderers* (`renderLt3Replay`, `renderLt4Final`) and one
*render site* (`render()`), so its rules attach to functions. But `index.html` actually has **four
states** a trace card passes through — replay, live-in-progress, live-finished, and degraded — and
two of them (live-in-progress, live-finished) have no owning renderer with a caption of its own.
Patch the caption in `renderLt4Final` alone and `startLt4Poll`'s card still says "Live run" after
`data.done` arrives, which is the same defect one poll tick earlier. Add chips to
`renderLt4Final` alone and the 37–93 s window LT4 exists for is still the only part of the demo
with no provenance. The unifying rule — *state, not renderer, owns the claim* — is what makes all
three fixes fall out at once, and is what stops the next state (a future "cancelled" or "timed
out" card) from re-opening it.

## Evidence — what the review found

| # | Finding | Where | Sev | Failure |
|---|---|---|---|---|
| 1 | No provenance disclosure during the live window — the LT7 chips exist only in the final render | [`index.html:1123`](../../../../src/main/resources/static/index.html#L1123) (render site) vs [`index.html:836`](../../../../src/main/resources/static/index.html#L836) (live shell) | LOW | For the full 37–93 s live window the screen shows `servicenow · in progress` rows with nothing saying servicenow is a fixture, or that the model is reached over the network via the Copilot proxy — while the caption on that same card asserts "the order and the results below are real" ([`index.html:781-782`](../../../../src/main/resources/static/index.html#L781)). This is LT7's own stated motivation ("that call hit a fixture and nothing on screen says so") at its most acute, in the one window LT4 was built for. |
| 2 | The settle-render of a finished ADK run keeps the present-tense "Live run" caption | [`index.html:915`](../../../../src/main/resources/static/index.html#L915) | LOW | `renderLt4Final` runs **only after** the POST's 200 — i.e. only when the run is over — and re-uses `LT4_FRAME_TEXT`: *"▶ Live run — each step appears the moment that call returns."* That caption then sits on screen for the entire post-run discussion and in every screenshot. LT3's replay caption is held to four load-bearing words including *"nothing is running now"* ([`index.html:703-714`](../../../../src/main/resources/static/index.html#L703)); the live path's terminal state gets the opposite claim. |
| 3 | The engine chip claims `deterministic · offline` for a `DEGRADED_TO_DETERMINISTIC` run that used the network | [`index.html:967-969`](../../../../src/main/resources/static/index.html#L967) | LOW (verifier-corrected down from MEDIUM) | `engineChipText` maps *everything* non-`ADK` to `deterministic · offline`, including `DEGRADED_TO_DETERMINISTIC`. A degraded run's attempt 0 genuinely reached (or tried to reach) the Copilot proxy — [`DiagnosisOrchestrator.java:300-323`](../../../../src/main/java/com/company/triage/orchestration/DiagnosisOrchestrator.java#L300) degrades only *after* a real ADK attempt, and keeps that attempt's rows visible as `ABANDONED`. So the chip's bare text can be read as a run-level "no network" claim while struck-through ADK rows sit right below it. |

**Where the verifier narrowed the claims — both narrowings are load-bearing here:**

- On finding 3, the original report said the chip and the degraded banner "disagree on the same
  screen". **They do not.** The banner ([`index.html:1041-1050`](../../../../src/main/resources/static/index.html#L1041))
  itself calls the fallback the "**offline deterministic engine**" and loudly discloses the failed
  ADK attempt directly above the chips, and the chip's `title` attribute already scopes its claim
  to *"Which engine produced this report"* — which is true. The code comment at
  [`index.html:962-966`](../../../../src/main/resources/static/index.html#L962) shows the degraded
  case was considered, not overlooked. What survives is narrow and real: **the visible chip text,
  read without the hover and without the banner (a cropped screenshot, a glance), is ambiguous.**
  That is why this is LOW and why the fix is one branch, not a redesign.
- On finding 2, during the run the steps genuinely *did* appear live via the poller — only the
  settle-render is a bulk redraw. The defect is the **terminal caption**, not a claim that nothing
  was ever live. The replacement wording below has to be honest about both facts at once.

## Design

### LUH-1 — The claim belongs to the *state*, not the renderer

Enumerate the states a trace card can be in, and give each one exactly one caption and one
provenance set. This is the whole card; LUH-2..4 are its three instances.

| State | Entered when | Header word | Caption must assert |
|---|---|---|---|
| `replay` | `renderLt3Replay` (non-ADK, settled) | `replay` | replaying · completed · **nothing is running now** · real |
| `live` | `startLt4Poll` opens the shell | `live` | appearing now · unpaced · real |
| `live · finished` | poll sees `data.done`, **or** `renderLt4Final` runs | `live · finished` | completed · **nothing is running now** · appeared live · settled · real |
| *(degraded)* | not a card state — a **chip** state (LUH-4) | — | — |

**Mechanism**: `traceCardShell` ([`index.html:663`](../../../../src/main/resources/static/index.html#L663))
already takes `(cardEl, mode, captionText)` and returns `{rowsEl, countEl}`. It gains
`captionEl` in that returned handle so a caption can be **flipped in place** without rebuilding
the card and losing the rows. That one extra field is what makes `live → live · finished`
expressible at all from inside `startLt4Poll`.

⛔ **Rejected: give `startLt4Poll` its own second `traceCardShell` call on completion.** It would
re-`innerHTML` the card and drop every row already rendered — the exact "un-draw rows" problem
J11's LT1 invariant 1 refuses on the degrade path, for the same reason.

⛔ **Rejected: leave the terminal caption to `renderLt4Final` only.** The poll response's `done`
flag can arrive before the POST resolves ([`RunStepsController.java:118`](../../../../src/main/java/com/company/triage/api/RunStepsController.java#L118)
returns `collector.isDone()`), so there is a real window — bounded by one poll interval plus the
POST's own return trip — where the run is over and the card still says it is live. Both edges get
the flip; whichever fires first wins and the second is a no-op.

### LUH-2 — The `live · finished` caption

```
▶ Live run — completed, N steps. Nothing is running now; each step above was shown as its
call returned, and this is their final settled state. The order and the results are real.
```

Four load-bearing phrases, chosen to be true of **both** the poll-flipped card (rows arrived one
at a time) and `renderLt4Final`'s fresh one-shot card (rows drawn together): *completed*,
*nothing is running now*, *was shown as its call returned* (past tense — the run, not this DOM
node), *final settled state* (which is honest about the settle-render *being* a redraw, per
`renderLt4Final`'s own "self-healing" contract).

⛔ **Rejected: reuse LT3's replay caption for the finished live run.** It says "revealed at ~0.4 s
each so they're readable", which is false here — LT4 never paces (J11's pacing table). Two
finished states, two different truths.

### LUH-3 — Provenance in the live window

**Render the connector chip immediately when the live shell opens; add the engine chip the moment
the first step arrives.** Never guess either.

- **Connector chip**: connector modes are *static config*, known before the run starts.
  ✅ **Decision — expose them on `/api/ui-config`**, which the page already fetches on load
  ([`index.html:1135`](../../../../src/main/resources/static/index.html#L1135)).
  `UiConfigController` ([`UiConfigController.java:21-25`](../../../../src/main/java/com/company/triage/api/UiConfigController.java#L21))
  today returns `DemoUiProperties` **directly**, so this needs a small response DTO —
  `UiConfigResponse(defaultIncidentNumber, publicHostname, connectors)` — composing that record
  with `ConnectorModeProvider.modes()`
  ([`ConnectorModeProvider.java:29-58`](../../../../src/main/java/com/company/triage/config/ConnectorModeProvider.java#L29)),
  which is already a bean and already the server-side source of truth `DiagnosisResult.connectors`
  uses. Purely additive: the two existing JSON field names are unchanged, so the existing
  `cfg.defaultIncidentNumber` read keeps working.
  ⛔ **Do NOT add connector modes to `DemoUiProperties`.** That record is
  `@ConfigurationProperties(prefix = "triage.ui")` — cosmetic demo settings. Connector selection is
  `triage.connectors.*`, and `ConnectorModeProvider`'s javadoc already explains why it reads the
  `Environment` rather than joining a properties record. Widening `triage.ui` to smuggle it in
  would put the same value in two config namespaces.
  ⛔ **Do NOT put connector modes in the poll response.** They are static per boot; shipping them
  on every 750 ms tick is waste, and it would make LT7's disclosure depend on LT4's transport
  (J12's territory) for no gain.
  **Failure mode is already handled honestly**: `/api/ui-config` is fetched fire-and-forget
  ("never block on this"). If it fails, pass no connector map — `connectorChipText` already
  returns `connectors: unknown` for an empty map ([`index.html:942-943`](../../../../src/main/resources/static/index.html#L942)),
  which is the truthful answer. Do not fall back to "fixtures".
- **Engine chip**: derived from `TraceStep.engine`, which every step already carries
  ([`TraceStep.java:39-51`](../../../../src/main/java/com/company/triage/orchestration/trace/TraceStep.java#L39)).
  ✅ **Decision — the live engine chip reflects the engine of the most recently received step, and
  is simply absent until the first step lands.** This falls out correctly on the degrade path for
  free: attempt 0's steps carry `ADK`, and after `abandonAndStartFallback` attempt 1's carry
  `DEGRADED_TO_DETERMINISTIC`, so the chip re-labels itself at the same instant the struck-through
  boundary row appears.
  ⛔ **Rejected: infer the engine client-side from "we started a live poll".** The client mints a
  `runId` and polls unconditionally for *both* engines ([`index.html:472-484`](../../../../src/main/resources/static/index.html#L472)) —
  it does not know which engine answered until the data says so. Guessing here would be the
  FND-16 string-inference mistake in a new costume.
  ⛔ **Rejected: a placeholder like `engine: resolving…`.** An absent chip claims nothing; a
  placeholder claims the app knows a resolution is coming. Absence is cheaper and truer.

### LUH-4 — One chip branch per engine, and no default-to-offline

`engineChipText` ([`index.html:967-969`](../../../../src/main/resources/static/index.html#L967))
becomes an explicit three-way with a non-asserting fallback:

| `data.engine` | Chip text |
|---|---|
| `ADK` | `ADK · via Copilot proxy` *(unchanged)* |
| `DETERMINISTIC` | `deterministic · offline` *(unchanged)* |
| `DEGRADED_TO_DETERMINISTIC` | `degraded → deterministic · report offline, ADK attempt used the proxy` |
| anything else / absent | `engine: unknown` |

Two things are being fixed, not one. The degraded branch is the finding; **the fallback arm is the
class of the finding.** Today *any* unrecognised or missing `engine` value silently renders
`deterministic · offline` — a positive no-network assertion produced by a default. A UI default
must never be an assertion. The `title` tooltip stays as-is (it is already correct) and the
existing `data.engine` read is unchanged, so this adds no plumbing.

⛔ **Rejected: dropping "offline" from the degraded text entirely** (i.e. just
`degraded → deterministic`). The word is *true of the report* and it is the thing the audience
needs — the banner directly above already says "no LLM was involved". Removing it would make the
chip agree with nothing on screen. Scope the word, don't delete it.

⛔ **Rejected: collapsing the two chips into one now that a third case exists.** LT7's "two chips,
never one" holds unchanged and is the reason this card is small: connector network and engine
network are independent axes (FND-10), and a single label cannot represent
`servicenow=real, others=fixtures` on a degraded run.

## Verification

**There is no JavaScript test harness in this repo** — nothing under `src/test/` references
`index.html` today, and adding a browser/JS runner for three caption strings is disproportionate
to a LOW card. So the guarantees are pinned at the two layers that *do* have tests, plus one
text-level guard:

- `UiConfigResponseTest` (new, `src/test/java/com/company/triage/api/`) — asserts
  `/api/ui-config` returns all four connector keys with the modes `ConnectorModeProvider` reports,
  **and** that `defaultIncidentNumber` / `publicHostname` are still present under their existing
  names (the additive guarantee LUH-3 depends on). Runs under the default profile.
- `LiveUiHonestyTest` (new, `src/test/java/com/company/triage/ui/`) — loads
  `static/index.html` off the classpath (the `PromptInjectionGuardrailTest` fixture-loading idiom)
  and asserts the state matrix as string invariants: a `live · finished` caption constant exists
  and contains "Nothing is running now"; `engineChipText` contains a
  `DEGRADED_TO_DETERMINISTIC` branch; no reachable branch maps an unknown engine to `offline`.
  Crude, but it is a real regression fence for exactly the words the honesty contract makes
  load-bearing, and it costs one file. Default profile.
- **Manual, in the runbook, under `-Padk`**: one live ADK run watched end to end — chips visible
  from the first row, caption flips at completion, and one forced degrade (stop the proxy
  mid-run) showing the engine chip re-labelling as the `ABANDONED` boundary row appears. The
  degrade path has no automated UI coverage and this card does not pretend otherwise.

Baseline before this card: **152 default / 201 `-Padk`, both green.** The two new tests are
default-profile only; the `-Padk` count should be unchanged by this card.

## Out of scope

- **Getting steps to the client at all** — `since`-index correctness, position stability, parallel
  dispatch: **J12-live-trace-delivery**. This card assumes rows arrive correctly and only governs
  what is *claimed* about them.
- **Whether the buffer exists to poll** — registry aliasing, TTL, eviction: **J16** and **J17**.
  A run whose buffer 404s shows no card at all, which is out of this card's reach.
- **Evidence/citation truthfulness inside the report body** — **J13-evidence-citation-integrity**.
  J23 governs captions and chips, not report content.
- **`connectors=real` correctness** (what a "real" chip is asserting is true *of*) —
  **J22-real-gateway-contract-tests**.
- **Other `index.html` defects that are not honesty claims** — the Enter-key handler and the
  unescaped `catch` path in `run()` are routed to the direct-fix bucket, not here.
  ⚠️ **Unverified** (from the review's LOW tail — one-look check before acting), though the
  `esc()` half is visible at [`index.html:511`](../../../../src/main/resources/static/index.html#L511)
  (`Error: ${e}` interpolated raw).
- **A styling/layout pass on the chips.** `.lt7-chips` ([`index.html:309-310`](../../../../src/main/resources/static/index.html#L309))
  is reused as-is inside the trace-card shell; if two chips crowd the live header on the demo
  screen, that is a J7 concern, not a contract change.
