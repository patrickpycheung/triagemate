# J14 — Fallback Robustness on Real Input

**State**: 🟡 Partly built (2026-08-05) — **FRI-1** (opened_at parsed tolerantly across the
display formats ServiceNow actually emits, with a WARN when none match) and **FRI-2** (a
missing window anchor skips the log search and says so, instead of NPE-ing inside the
fallback engine) landed in `0dd4005`. J24/SFF-3 also delivered FRI-2's sibling case for an
unmatched scope. **Still open**: FRI-3 (logger means emitter), FRI-4 (hyphens inside words),
FRI-5 (per-call connector degradation), FRI-6 (real-shaped fixture corpus) · **Complexity**: Moderate · **Priority**: HIGH ·
**Depends on**: J2, J3, J5 · **Amends**: J2 (the deterministic engine's FND-63 guarantee),
J5 (`getIncident`'s field contract), J3 (what a gateway may put in `LogEvidence.logger`)
**Source**: application review 2026-08-05 (multi-agent + Codex + Gemini), 4 confirmed
findings + 1 unverified

## Essence

The deterministic engine is the **FND-7 fallback** — the thing that runs when the agent
fails on stage. FND-63 already established the guarantee: *it must produce an honest report
for an incident it has never seen*. That guarantee was verified against **mock-shaped
input**. This card extends it to the input shapes only the **real connectors** produce —
a null `openedAt`, a `_sourceCategory` in the `logger` field, a hyphen inside the first
word of a subject line, a connector that returns 401 — so that "degraded" always means a
weaker report, never a 500.

## Why this is a concept, not four bug fixes

The four confirmed findings are in three different files and look unrelated. They are one
defect with one cause: **every derivation in the deterministic engine was validated against
`MockServiceNowGateway`/`MockSumoGateway`, and the real gateways return differently-shaped
values for the same fields.** Fixed piecemeal, each patch closes one field and leaves the
class open — which is exactly what happened to FND-67, whose own fix
(`leadingPhrase`, `IncidentSignals.java:223-228`) introduced the hyphen truncation this
card now has to close. Fixed as a concept, the deliverable is a **contract on what the
engine may assume about its inputs** plus a fixture corpus that keeps the next real-shaped
field from re-opening it (FRI-6).

The stakes are asymmetric and that is why this is HIGH: these inputs never occur on the
seeded demo path, and *every one of them occurs on the first arbitrary real incident* —
i.e. only in the situation the fallback exists for.

## Evidence — what the review found

| # | What | Where | Severity | Failure |
|---|---|---|---|---|
| 1 | `inc.openedAt()` dereferenced twice with no null guard to build the Sumo window | [`DeterministicDiagnosisEngine.java:179`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L179) | HIGH | `openedAt == null` ⇒ NPE **before any log search**; `diagnose()` throws; the orchestrator has no further fallback (`DiagnosisOrchestrator.java:290-291, 316`), so the run 500s with no report and no advisory comments |
| 2 | `getIncident` reads `opened_at` through `rows()`, which sets `sysparm_display_value=true` | [`RealServiceNowGateway.java:94`](../../../../src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java#L94) + [`:260`](../../../../src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java#L260) + [`:280`](../../../../src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java#L280) | HIGH | `parseTime` accepts only `yyyy-MM-dd HH:mm:ss` and hardcodes `+00:00`. Non-default display format ⇒ `null` on **every** incident ⇒ finding 1. Default format ⇒ an instance-local time tagged UTC ⇒ the ±10 m window is silently shifted by the instance offset and the log↔code citation — the demo's headline feature — quietly returns nothing |
| 3 | Candidate derivation and the contradiction check key on `LogEvidence.logger`, which the real Sumo gateway fills with `_sourcecategory` | [`DeterministicDiagnosisEngine.java:282-290`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L282), [`:338-346`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L338), [`RealSumoGateway.java:95`](../../../../src/main/java/com/company/triage/gateway/real/RealSumoGateway.java#L95) | MEDIUM | The query pins `_sourceCategory` to one composed value (`application.yml:145`), so **every returned line carries the same `logger` by construction**. The log-derived shortlist collapses to one candidate named `prettifySystem` of the category tail — `AppEvt Order Portal`, a category label — and the "the failure looks downstream of it" contradiction fires on **every** real run that has ownership + logs, because a category tail can never equal a CMDB application name. `knownSystemNames` (`:249`) is polluted the same way, weakening J9's person filter |
| 4 | `leadingPhrase` splits on `[-:\|—]` with no whitespace requirement | [`IncidentSignals.java:225`](../../../../src/main/java/com/company/triage/orchestration/IncidentSignals.java#L225) | MEDIUM | `"E-commerce checkout down"` ⇒ `app = "E"`. That value is load-bearing in four places: `projectSlug("E")="e"` composes a `_sourceCategory` that exists nowhere (guaranteed zero lines), `tokens()` drops it (<3 chars, `:209`) so `rankAllowlist` gets zero signal, the Confluence query gains a bare `"E"`, and the always-present fallback candidate (`DeterministicDiagnosisEngine.java:306-311`) renders a row literally named **"E" at 0.30** in the UI *and in the ServiceNow advisory note* |
| 5 | ⚠️ **unverified** (from the review tail, one-look check needed) — the engine's external calls have no per-call degradation | [`DeterministicDiagnosisEngine.java:155,180,214,453,467`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L155) | MEDIUM | A single connector error aborts the whole safety-net run |

**Verifier notes that sharpen the claims** (kept because they change what to build):

- On finding 2 the verifier established that the **default-settings case is the dangerous
  one**: it does not crash, it silently shifts the window. ServiceNow PDIs commonly default
  the API user's timezone to US/Pacific, so this occurs with **zero customization** — an
  outright crash on a non-default date format is the *loud* version of the same bug.
- On finding 2 the verifier also found the reasoning already exists in this very file:
  `findIncidentsCreatedSince` deliberately sets `sysparm_display_value=false` with the
  comment *"Raw values, NOT display values: sys_created_on must come back in the parseable
  UTC form"* (`RealServiceNowGateway.java:216-218`). The hazard was known; it was simply
  never applied to `getIncident`.
- On finding 3 the verifier narrowed one overstatement: the CMDB owner still appears as a
  second candidate, so the report is not *literally* one row. The collapse of the
  **log-derived** shortlist and the false contradiction both stand.
- On finding 4 the verifier narrowed two: with zero tokens the GitLab sweep order is stable
  **config order**, not "arbitrary" (`rankAllowlist` is a stable sort, `IncidentSignals.java:160`);
  and "possibly the only candidate" holds only when nothing else was observed.

**Finding 5 does not survive my read as stated, and the card corrects it.** The tail claim's
headline scenario — *"Confluence returns 401 ⇒ the run 500s"* — is **false**:
`RealConfluenceGateway.search` already catches `Exception` and returns an empty list
(`RealConfluenceGateway.java:60-61`), as do `contributors` (`:102`) and
`RealGitLabGateway.recentCommitters` (`:121`). The gap is real but has a different shape.
Actually-aborting calls, read directly:

| Engine call site | Real gateway | Behaviour on error |
|---|---|---|
| `confluence.search` (`:155`) | `RealConfluenceGateway:44-62` | ✅ degrades to empty |
| `confluence.contributors` (`:453`) | `RealConfluenceGateway:102` | ✅ degrades to empty |
| `gitLab.recentCommitters` (`:467`) | `RealGitLabGateway:121` | ✅ degrades to empty |
| `sumo.search` (`:180`) | `RealSumoGateway:45-105` | ⛔ **aborts** — only `InterruptedException` is caught, and the job-create POST (`:60`) sits outside the `try` entirely |
| `gitLab.searchCode` (`:214`) | `RealGitLabGateway:47-63` | ⛔ **aborts** — no `try` at all |
| `serviceNow.findSimilarIncidents` (`:151`) / `findOwnership` (`:139`) | `RealServiceNowGateway:130,145` | ⛔ **aborts** |

So the codebase's own convention (J9: *"the real connectors return an empty list on any
error, so the run degrades gracefully"*) is applied to the **contact** side and to
Confluence, and skipped on the three calls that carry the **primary evidence**. That is the
finding worth designing against — and it is a sharper statement than the original claim.

## Design

### FRI-1 — `opened_at` is fetched as a raw UTC value, not a display value

**Rule**: `RealServiceNowGateway.getIncident` must read `opened_at` in the parseable UTC
form, while keeping reference fields (assignment group, CI, caller) readable.

**Mechanism**: give `getIncident` its own fetch using `sysparm_display_value=all`, which
returns every field as `{"display_value": …, "value": …}` — `opened_at` is then read from
`.value` (raw `yyyy-MM-dd HH:mm:ss` UTC) and the reference fields from `.display_value`.
One request, no added latency, and `parseTime`'s hardcoded `+00:00` becomes *correct*
rather than *accidentally survivable*.

**Rejected — flipping `rows()` to `sysparm_display_value=false` globally**: `rows()` is
shared by `findSimilarIncidents`, `findOwnership` and `alreadyPosted`, all of which depend
on readable names (`assignment_group`, `cmdb_ci`, `support_group`). Flipping it would turn
the CMDB owner into a sys_id and break J5's routing signal to fix a date.
**Rejected — a second raw-mode GET just for `opened_at`**: an extra round-trip per
diagnosis for a field the same request can already carry both ways.

### FRI-2 — a missing window anchor degrades the search; it never throws

**Rule**: `openedAt` is a **nullable** input to the engine. FRI-1 removes the *systematic*
null, but a ticket with a blank `opened_at` is still legal, so line 179 must not be able to
NPE regardless.

**Mechanism**: anchor the window on `inc.openedAt()` when present and `OffsetDateTime.now()`
otherwise — the recent-incident assumption the K1 poller already makes — and **say so** in
both channels the report has for admitting what it does not know:
a trace line and a `missingInformation` entry (`"opened_at missing or unparseable — log
window anchored on now"`, alongside the existing `:350` window line).

**Rejected — skipping the Sumo call when the anchor is unknown**: it removes evidence
silently, and a now-anchored window over a freshly-polled incident is usually the right
window anyway.
**Rejected — throwing a typed exception**: that is today's behaviour with a better message.
The engine is the last line; it has nothing to hand the failure to (`DiagnosisOrchestrator.java:290-291`).

### FRI-3 — `logger` means *emitter*, and a connector that cannot supply one says nothing

**Rule (contract, J3-level)**: `LogEvidence.logger` names the **component that emitted the
line**. A connector that cannot determine one must leave it **blank** — it must never
substitute the value the search was scoped by, because that value is a property of the
*query*, not of the *line*, and downstream code reads it as evidence.

**Mechanism, gateway side**: `RealSumoGateway` populates `logger` best-effort from a
per-message field (`_sourcehost`, or a logger token parsed out of `_raw`), and blank when
none is available — never `_sourcecategory`, which `LogSearchRequest.toSumoQuery()` pins to
a single composed value for the whole search.

**Mechanism, engine side** (needed even so — a blank logger is now the honest common case):

- Log lines with a blank `logger` produce **no log-derived candidate**. They remain
  `e-log` evidence; they just cannot *name* a system, which is the truth.
- The "looks downstream" contradiction (`:338-346`) is **suppressed when there is no
  emitter diversity to reason from** — i.e. when every returned line shares one `logger`,
  or all are blank. That check's premise is *"the CMDB owner is absent from the set of
  systems seen emitting"*; with one emitter by construction the premise is unavailable, and
  asserting the conclusion anyway is the FND-8 class (narrating something that did not
  happen) in its most damaging place — the engine visibly contradicting itself on stage.
- `knownSystemNames` (`:249`) stops ingesting blank/collapsed loggers, so J9's person
  filter is not fed a category label.

**Rejected — teaching `prettifySystem` to strip `AppEvt_`**: it makes the label prettier
and leaves the substance wrong (still one candidate, still a false contradiction), and it
hardcodes today's `source-category-pattern` into a formatter, re-opening exactly the
FND-40/FND-62 hardcoding class this engine was rebuilt to escape.

### FRI-4 — separators are separators; hyphens inside words are not

**Rule**: `leadingPhrase` splits the subject line only on a **whitespace-flanked** `-`/`—`,
and on a bare `:` or `|`.

**Mechanism**: `symptom.split("\\s+[-\\u2014]\\s+|[:|]", 2)[0].trim()`, keeping the
existing 4-word cap. `"Delivery Hazards - All hazards are no longer present"` still yields
`"Delivery Hazards"` (the FND-67 case that motivated the method); `"E-commerce checkout
down"` now yields `"E-commerce checkout down"`, capped at 4 words; `"PROD:Checkout down"`
still yields `"PROD"`, because a bare colon really is a separator in ticket subjects while a
bare hyphen is not.

**Plus a floor**: if the resulting head has no token of ≥3 characters it carries **zero**
ranking signal — `tokens()` discards shorter strings (`IncidentSignals.java:207-210`) — so
fall back to the first four words of the whole symptom instead. This closes the residual
class (`"X: archive unavailable"` ⇒ `"X"`) that the split rule alone leaves open, and it is
the same defect as finding 4, not a new one: a one-character `app` poisons the Sumo
category, the allowlist ranking, the Confluence query and the visible candidate row.

**Rejected — dropping `-` from the separator class entirely**: `" - "` is the dominant
separator in real ServiceNow subject lines and is precisely what FND-67 was fixed for.

### FRI-5 — the safety net degrades per call, and the trace shows it

> **Now field-proven, not just review-derived (2026-08-06).** This item was written from a
> code review — "a connector that returns 401" was hypothetical. It has since been **observed
> live**: a `run-deterministic-real.sh` against the real GitLab instance returned
> `404 Project Not Found` from `RealGitLabGateway.searchCode:68`, the exception propagated
> through `DeterministicDiagnosisEngine.diagnose` uncaught, and **the run returned a 500 to
> the UI** — the exact outcome this rule exists to prevent, on the fallback engine, on the
> demo path. Field report: [`docs/Patrick_gitlab-call-failed-issue.md`](../../../Patrick_gitlab-call-failed-issue.md)
> (cheungp, `934fb0a`).
>
> Two consequences for planning:
> - **Raises this item's urgency above the rest of J14.** The others degrade a report; this
>   one takes the whole run down, and it is reachable from the demo incident today.
> - **It gates [J30](../J30-gitlab-estate-binding/README.md).** J30 adds entries to the GitLab
>   allowlist, and the engine sweeps that list calling `searchCode` per entry — so until this
>   lands, a longer allowlist means more chances to hit the aborting path, not more chances to
>   find code. FRI-5 first, J30 second.
>
> The asymmetry inside the gateway is worth keeping as evidence: `recentCommitters` in the
> same class already catches and returns an empty list. The resilience was understood — it
> just was not applied to `searchCode`, which is why a per-call *rule* beats per-site care.

**Rule**: every external call the deterministic engine makes **except `getIncident`**
degrades to its empty result on failure. `getIncident` keeps propagating — with no incident
there is nothing to diagnose, and `IncidentNotFoundException` is a deliberately typed
outcome (FND-53).

**Mechanism**: wrap the three aborting call sites (`sumo.search:180`,
`gitLab.searchCode:214`, `serviceNow.findSimilarIncidents:151` / `findOwnership:139`) so a
failure yields the empty result and produces three consistent signals:

1. a `trace` line naming the call and the error (`sumo.search → failed (429) — continuing
   without log evidence`),
2. a `missingInformation` entry, so the *report* admits the gap and not just the trace,
3. the step's `TraceStep` resolves **`FAILED`**, not `DONE` — J11 LT1 defines that state and
   LT5 maps it to a `fail` visual, so the live trace shows the degradation as it happens.
   *(The originating suggestion said "DONE with the error text"; `FAILED` exists precisely
   for this and using `DONE` would make the trace assert a step succeeded when it did not —
   the honesty contract J11 is built on.)*

**Where the wrap lives**: in the **engine**, not in each real gateway. Three reasons: the
engine is the only place that can also write the `missingInformation` line; the mock
gateways must be able to *simulate* a failing connector for FRI-6 without gaining
error-swallowing behaviour of their own; and one wrapper is one place to keep the three
signals consistent, versus three gateways drifting apart — which is the state the review
found (Confluence degrades, GitLab half-degrades, Sumo does not).

No retries, no backoff, no timeout policy — those stay the known post-hackathon item. This
is a `try`/`catch` per step.

### FRI-6 — a real-shaped fixture corpus, so the class stays closed

**Rule**: the deterministic engine is exercised against a fixture set built from the shapes
the *real* connectors emit, not just `MockServiceNowGateway`'s.

**Mechanism**: one test-scoped fixture holder with the five shapes this review found —
`openedAt == null`; blank `cmdb_ci` with a hyphenated first word; all log lines sharing one
`logger`; all log lines with a blank `logger`; a gateway stub that throws on one call — and
a test class that asserts the engine produces a **J4-valid report** for each. That last
assertion is the actual guarantee (`DiagnosisReportValidator.validate`, already called at
`DeterministicDiagnosisEngine.java:386`), and it is the one FND-63 established.

This is what makes J14 a card rather than four commits: the fixture corpus is the artefact
that stops finding number six.

## Verification

All of these run under bare `mvn test` — the deterministic engine and both gateways live in
`src/main/java/`, so **nothing here needs `-Padk`**. Baseline to hold: **152 default / 201
adk, both green.**

- `DeterministicDiagnosisEngineTest#nullOpenedAtAnchorsTheWindowOnNowAndSaysSo` — FRI-2: no
  throw, a `missingInformation` entry naming the missing anchor, and the report still passes
  `DiagnosisReportValidator`. Today this is an NPE (`:179`), so the test fails first.
- `RealServiceNowGatewayTest#openedAtIsReadAsARawUtcValue` — FRI-1, via the existing
  `MockRestServiceServer` binding this class already has (the `RestClient.Builder` injection
  at `RealServiceNowGateway.java:45-55` exists for exactly this). Serve a
  `display_value=all` payload whose `opened_at` display form is `03/08/2026 14:16:40` and
  whose `.value` is `2026-08-03 04:16:40`; assert the parsed `openedAt` is the UTC instant,
  and that `assignment_group` still arrives as a readable name.
- `DeterministicDiagnosisEngineTest#collapsedLoggersProduceNoFalseDownstreamContradiction` —
  FRI-3: log lines all carrying one `logger`, plus an `ownership` whose application differs;
  assert `contradictingEvidence` contains no "looks downstream" line and the candidate list
  is not named after the category tail.
- `RealSumoGatewayTest#logsMapToAnEmitterNotTheSearchedCategory` — FRI-3 gateway side.
  ⚠️ This needs `RealSumoGateway` to take an injected `RestClient.Builder`
  (`RealSumoGateway.java:32-42` builds its own), the same one-line change
  `RealServiceNowGateway` already carries. **Do the injection rather than extracting a
  static mapper to unit-test** — FND-14's recorded lesson in this repo is that a real
  request/response-shape regression test is what caught the bug, and "a unit test of an
  extracted predicate" is named there as the weaker option that missed it.
- `IncidentSignalsTest#leadingPhraseKeepsIntraWordHyphens` — FRI-4:
  `"E-commerce checkout down for EU users"` ⇒ four words, not `"E"`; plus the two regression
  cases `"Delivery Hazards - All hazards…"` ⇒ `"Delivery Hazards"` and `"PROD:Checkout down"`
  ⇒ `"PROD"`, so the fix cannot re-break FND-67.
- `DeterministicDiagnosisEngineTest#aFailingConnectorDegradesInsteadOfAbortingTheRun` —
  FRI-5: a stub `SumoGateway` that throws; assert the run completes, the report validates,
  `missingInformation` names the failure, and the emitted `TraceStep` for `sumo.search` is
  `FAILED`. Repeat for `gitLab.searchCode` and `serviceNow.findOwnership`.
- `DeterministicRealShapedInputTest` — FRI-6: the corpus, one case per shape, each asserting
  a J4-valid report.

## Out of scope

- **Which evidence ids the report cites, and whether a citation is honest** — duplicate
  `e-code` ids, `evidenceRefs` pointing at another system's log line, the unchecked 0.86
  log↔code confidence, and the code-hit fan-out cap: **J13-evidence-citation-integrity**.
  J14 stops at *the engine survives the input and validates*; J13 owns *what it claims*.
- **The `identifiers` field duplicating one id as both `correlationId` and `orderId`**
  (`DeterministicDiagnosisEngine.java:119`) — J13.
- **Null `triage.gitlab.allowed-projects` NPE-ing inside `rankAllowlist`, and the other
  boot-time validation gaps** — **J20-startup-truth-and-validation**. J14 assumes config is
  already valid at boot; J20 makes that true.
- **Timeouts, retries and backoff policy for the real connectors** — the known
  post-hackathon item. FRI-5 is `try`/`catch`, deliberately nothing more.
- **Guardrail/allowlist enforcement on tool arguments** (GitLab allowlist bypass, ServiceNow
  encoded-query injection) — **J18-guardrail-enforcement-completeness**.
- **How `FAILED` steps reach the browser during a live run** — **J12-live-trace-delivery**.
  FRI-5 only guarantees the step is *emitted* with the right state.
- **Contract tests against the real connectors' actual HTTP shapes as a suite** —
  **J22-real-gateway-contract-tests**. The two gateway tests named above are the minimum
  this card needs, not that card's programme.
