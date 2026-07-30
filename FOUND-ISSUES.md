# Found issues

**Backlog: 9 open** — 2 deferred design decisions (FND-43/44), 2 from `/doc-test dds`
2026-07-30 (FND-45/46), and **5 from `/doc-test cds` 2026-07-31 (FND-47…51), which unlike
the earlier ones ARE real defects in shipped code** — most notably FND-48 (a bad incident
number renders a raw JS TypeError, live demo risk) and FND-47 (`environment` always null
against a real ServiceNow instance). FND-1…FND-42 resolved; detail in
`docs/audit/found-issues-archive.md`.

Queue of findings that need a decision or a fix and are not yet tracked elsewhere.
Resolved entries move to [`docs/audit/found-issues-archive.md`](docs/audit/found-issues-archive.md)
with two added lines: a **Resolution** (`fixed:<sha>` or `promoted:<card>`) and an
**Escape** (which *process layer* should have caught it — the input a future retrospective
clusters on).

Format: `## FND-<n> — <one-line title> · **HIGH|MEDIUM|LOW**`, then **Where** / **What** /
**Why it matters**. Add new entries at the bottom.

## When to log here vs. fix directly

- **Log it** when resolving it means a design decision, a behaviour change, or a data /
  API contract change — anything where picking the fix is itself the hard part.
- **Fix it directly** (no entry) when it is a genuinely small correction with an easy undo
  and no money / legal / data / governance / deploy surface. Don't force ceremony onto
  trivia.

---

## FND-45 — The C6 unattended-use gate is documented but not enforced in code · **MEDIUM**

**Where**: `application.yml` (`triage.trigger.poll.enabled`, `triage.engine`),
`J10-incident-poller/README.md:116`.
**What**: J10 states "Unattended running is gated on C6 (the Copilot ToS ruling)". Both
relevant flags default safe (`poll.enabled: false`, `engine: deterministic`) — but there is
**no coupling in code**. Setting `triage.trigger.poll.enabled=true` together with
`triage.engine=adk` produces exactly the unattended, programmatic LLM use that C6 gates,
and nothing warns or refuses. Found by `/doc-test dds` (Gemini flagged it as a CRITICAL
conflict; verification showed the *docs* are coherent, so the conflict was a false positive
— but the gate being advisory-only is real).
**Why it matters**: this is this repo's most-repeated defect class — *documented but not
enforced* (FND-8, FND-16, FND-25, FND-38 were all instances). The cheap fix is a startup
check that refuses (or loudly warns) when `poll.enabled && engine==adk` unless an explicit
`triage.trigger.poll.unattended-llm-ack=true` is set, which turns a prose gate into a
decision someone has to actually make. Deferred rather than built because "refuse vs warn"
touches gate policy, and the operator owns C6.

## FND-46 — D2's "offline, cannot fail" safety claim is broader than the code supports · **LOW**

**Where**: `orchestrator-vs-copilot-cli/4-decide/concepts-extracted.md` (D2),
`J7-demo-ui-and-dataset/README.md`, `DeterministicDiagnosisEngine`.
**What**: D2 is described as the demo's guaranteed floor — "offline… cannot fail the way a
model can". Three things narrow that: (a) connector mode is **independent** of engine, so
the deterministic engine makes real HTTP calls under `triage.connectors.*=real`;
(b) gateway exceptions in that engine are **not** caught per-tool, and J7's "any tool
failure becomes omitted evidence" promise is only true for the gateways that
degrade internally; (c) when deterministic is the *active* engine its failures propagate by
design (FND-7). Found by `/doc-test dds` (Codex).
**Why it matters**: the claim is true of the *launcher configuration* the runbook actually
uses (`run-deterministic.sh`, all-mock), not of the engine in general. Fix is wording plus
possibly pinning the connectors in that launcher — small, but it touches the D2 stage
guarantee, so it is worth doing deliberately rather than in passing.

## FND-47 — `RealServiceNowGateway` reads `u_environment` but never requests it · **MEDIUM**

**Where**: `RealServiceNowGateway.java:65-66` (`sysparm_fields`) vs `:77`.
**What**: the field list omits `u_environment`, but `getIncident` reads it. ServiceNow
returns only requested fields, so `IncidentContext.environment` is **always null against a
real instance** → J4's `environment` (rendered at `index.html:121`) is blank on every real
run. Invisible to mock-only tests. Found independently by two architecture reviews
(`/doc-test cds`, 2026-07-31).
**Why deferred not fixed**: one-word fix, but it needs a real-instance verification we can't
do offline, and it should land with FND-48's other real-connector gaps in one pass.

## FND-48 — No API error contract; a bad incident number shows a JS TypeError on stage · **MEDIUM**

**Where**: `src/main/java/com/company/triage/api/` (no `@ControllerAdvice` anywhere),
`index.html:52-53`.
**What**: `DiagnosisTimeoutException`, `DiagnosisReportInvalidException` and
`IllegalStateException("incident not found")` all fall through to bare Spring 500s, and the
UI never checks `res.ok` — so a mistyped incident number renders
`Error: TypeError: Cannot read properties of undefined` instead of a message. Found by two
architecture reviews. **This is the one with live demo risk.**
**Fix shape**: ~20-line `@ControllerAdvice` mapping the three to 404/504/502 with a JSON
body, plus a `res.ok` check in the UI.

## FND-49 — `triage.engine=adk` without `-Padk` silently runs deterministic, unannounced · **MEDIUM**

**Where**: `application.yml` (`triage.engine`), `DiagnosisOrchestrator.java:79-89`.
**What**: the ADK bean doesn't exist off-profile, so the property matches nothing and
deterministic wins silently. Nothing logs the active engine at startup, and the UI only
banners `DEGRADED_TO_DETERMINISTIC` — so the *misconfiguration* path is uncovered while the
*runtime-failure* path is covered. This is FND-8's exact failure class (narrating "frontier
model" over a scripted run) via a different route. Found by two architecture reviews.
**Fix shape**: log the active engine class at startup, or fail fast when `triage.engine=adk`
resolves to a non-ADK bean.

## FND-50 — FND-37's normalization is incomplete: K1 bypasses it · **LOW**

**Where**: `DiagnosisController.java:34` vs `IncidentPoller.java` (passes the raw gateway
value straight to `orchestrator.run`).
**What**: FND-37 added `trim().toUpperCase()` in the *controller* only, so K1 and K3 can
still fail to coalesce on a case difference — defeating FND-31 for exactly the mixed-trigger
case it was built for. **A gap in my own 2026-07-30 fix**, found by an architecture review.
**Fix shape**: move the normalization to the top of `DiagnosisOrchestrator.run()` so every
trigger normalizes identically.

## FND-51 — `triage.servicenow.write-field` is interpolated into PATCH JSON unvalidated · **LOW**

**Where**: `RealServiceNowGateway.java:44`, `:131`.
**What**: the configured field name is concatenated directly into the PATCH body with no
restriction to `work_notes`/`comments`, so a typo or hostile config could write to another
incident field — violating J8's "only comment, never reassign/close/re-prioritise"
invariant at the config layer rather than the model layer. Also `:241`'s hand-rolled JSON
escaping covers only `\`, `"` and `\n` (a `\r` or tab in evidence text yields invalid JSON;
Jackson is already on the classpath).
**Fix shape**: bind the field to a two-value enum; use `ObjectMapper.writeValueAsString`.

## FND-43 — Poller cursor can skip a batch-limit's worth of same-timestamp incidents · **LOW**

> **Re-confirmed 2026-07-31** by two independent architecture reviews in `/doc-test cds`,
> which found it from the code without knowing it was logged. Still deferred, but the
> independent re-discovery raises confidence that it is real rather than theoretical.


**Where**: `IncidentPoller.pollOnce()`.
**What**: if more than `triage.trigger.poll.batch-limit` (default 10) incidents share
the exact same `sys_created_on` second, the cursor's "unbroken handled prefix"
advance could move past ones never actually fetched (they'd be beyond the query's
`sysparm_limit`). Needs either a strictly-greater-than tie-break key (e.g. `sys_id`)
or a documented acceptance of the edge case.
**Why it matters**: real-world likelihood is low (needs a true creation-time
collision at second granularity within one poll tick) but the fix shape is a design
choice (extra query field vs. accepted limitation), not obviously mechanical.

## FND-44 — Several ADK guardrails are prompt-only, not code-enforced · **LOW**

**Where**: `AdkDiagnosisEngine`'s `INSTRUCTION` — "ONE bounded Sumo Logic search",
result/citation ordering, "only for pages/files you already cited."
**What**: these are asked of the model via the system prompt, not structurally
enforced the way the Sumo scope/window/GitLab-project allowlists (FND-20/38) are. A
model that ignores the instruction (or is prompt-injected into ignoring it) could
call `search_logs` more than once, or cite a page/file it never actually searched.
**Why it matters**: whether/how to enforce this in code (e.g. a per-tool call count
inside `BoundsCallback`, or validating citations against the actual tool-call trace
in `DiagnosisReportValidator`) is a real design fork with more than one reasonable
answer — logged for a future round rather than picked here.

## History

**FND-1…FND-8** — `/doc-test dds` (2026-07-29). Two were worth the trip on their own:
the trigger the DDS specified would have re-diagnosed every incident forever (its own
writes bumped the cursor it polled on), and the FND-7 fallback being correct but
*silent* — a run that never called the model looked exactly like success, and cost a
spike cycle before anyone noticed.

**FND-9…FND-32** — `/doc-test cds` (2026-07-30). 40 Phase-2 gaps + 27 raw conflicts
(Codex/Gemini/Claude), deduplicated to 24 entries; 5 more were fixed immediately without
an entry (the J8 allowlist that was documented but not implemented, J4's contact fields,
the silent mini-model default — see `b8b2dd0`). One systemic cause explained most of what
remained: J-cards were written at design time and the implementation moved past them
without the cards following — a process fix, not 24 independent mistakes.

The two most consequential, both real bugs rather than doc drift:

- **FND-14 / FND-31** — a real ServiceNow connector that didn't dedupe identical notes
  (only the mock did), and the manual trigger bypassing the poller's own duplicate-run
  guard — exactly the demo shape (polling on, presenter also clicks). Both fixed via the
  same mechanism: concurrent-call coalescing + idempotent writes at the one place K1 and
  K3 both call through, `DiagnosisOrchestrator`.
- **FND-17 / FND-19 / FND-20** — three guardrail claims that were prose, not code: no J4
  validator existed (a model could return an empty candidate list or a dangling evidence
  ref and the UI would render it), no test existed for the prompt-injection defense
  claim, and the Sumo log-search window/result-count bound was taken from the model with
  no server-side check at all. All three are now enforced and tested.

Full detail on all 32 resolved entries: `docs/audit/found-issues-archive.md`.
