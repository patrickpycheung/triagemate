# Found-issues archive

Append-only record of resolved `FND-*` entries from `/FOUND-ISSUES.md`, newest first.

Each entry keeps its original text plus two lines added at resolution time:

- **Resolution** — `fixed:<sha>` (fixed directly) or `promoted:<card path>` (now tracked
  by a design card).
- **Escape** — which **process layer** should have caught it. Not what was wrong in the
  code: what was wrong in how we work. A future retrospective clusters on this field, so
  it is written while the context is fresh.

Drained 2026-07-30 by `/found-issues-resolve`.

---

## FND-14 — J5 claims `addWorkNote` is idempotent; only the mock actually dedupes · **MEDIUM**

**Where**: `J5/README.md:34,50` vs
`src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java:104-115` and
`MockServiceNowGateway:105`.

**What**: `MockServiceNowGateway` skips an identical note. `RealServiceNowGateway.addWorkNote`
PATCHes unconditionally — no "does an identical AI note already exist?" check.

**Why it matters**: J5 cites this idempotency as a guardrail, and the J10 poller's FND-1
write-up lists it as the *fourth* layer against duplicate work. Against a real instance that
layer is absent — a retried or re-triggered run posts duplicate advisory comments onto a
real customer-visible ticket. This is the entry I'd fix first: it is the only one where a
claimed safety layer is missing on the **real** connector rather than in prose.

**Found by**: Phase 2 (agent A).

- **Resolution**: fixed:this-commit (RealServiceNowGateway checks sys_journal_field for an exact-match existing entry before PATCHing; RealServiceNowGatewayTest via MockRestServiceServer)
- **Escape**: test-coverage — the real connector's HTTP behavior had no regression test at all; only the mock's dedupe was ever exercised, so the two connectors silently diverged on a claimed safety property.

---

## FND-15 — J1 claims a wall-clock timeout and max-tool-calls it does not enforce · **MEDIUM**

**Where**: `J1/README.md:19-20,50` ("Enforces a hard wall-clock timeout + max-tool-calls",
"honored (inject a slow mock)") vs
`src/main/java/com/company/triage/orchestration/DiagnosisOrchestrator.java`.

**What**: the orchestrator enforces neither. It only measures elapsed time for a log line.
Max-tool-calls exists solely in `-Padk` `BoundsCallback`, so **the default deterministic
path has no tool bound at all**, and no timeout exists anywhere in `src/main`. The stated
verification ("inject a slow mock") was never performed.

**Why it matters**: a hung gateway hangs the request indefinitely — including on the K1
poller's thread, where nobody is watching. The deterministic path being unbounded is
tolerable (no LLM, fixed work) but is not what J1 says.

**Found by**: Phase 2 (both agents), Claude conflict.

- **Resolution**: fixed:this-commit (every engine call now runs on a virtual thread bounded by triage.orchestrator.timeout-ms=45000; a timeout on the primary feeds the normal FND-7 fallback)
- **Escape**: contract-drift — J1 stated a guarantee ('honored, inject a slow mock') that was never actually tested, so the gap between claim and code was invisible until doc-vs-code validation looked for the promised test and found none.

---

## FND-31 — The manual endpoint bypasses the poller's in-flight/completed state · **MEDIUM**

**Where**: `src/main/java/com/company/triage/api/DiagnosisController.java` →
`DiagnosisOrchestrator.run()` vs
`src/main/java/com/company/triage/orchestration/IncidentPoller.java` (`inFlight`,
`completed`).

**What**: the poller's duplicate-suppression sets live **inside the poller**. A manual
`POST /api/diagnose/{number}` calls the orchestrator directly, so it neither consults nor
updates them. With polling enabled, a manual trigger can diagnose an incident the poller is
mid-run on, or one it has already completed — two concurrent diagnoses of the same ticket,
and four advisory comments (or two, plus a duplicate the real gateway won't dedupe — see
FND-14).

**Why it matters**: this is precisely the demo shape — polling on, presenter triggers
manually to show the flow. It's also the one finding Gemini produced, and neither Codex nor
Claude found it. Cheapest fix is probably to move the guard out of the poller into the
orchestrator, where both entry points meet.

**Found by**: Gemini conflict (HIGH) — sole source.

- **Resolution**: fixed:this-commit (DiagnosisOrchestrator.run() coalesces concurrent calls for the same incident number via a ConcurrentHashMap<String,CompletableFuture> — the second caller awaits the first's result instead of starting a duplicate; two latch-forced concurrency tests)
- **Escape**: design-review — K1 (poller) and K3 (manual) were designed and built in separate sessions without a round asking 'what happens when both fire on the same incident at once?'. The guard existed in exactly one of the two entry points.

---

## FND-8 — A degraded run is indistinguishable from a live one at a glance · **HIGH**

**Where**: `src/main/resources/static/index.html` (trace rendering) and the
`DiagnosisOrchestrator` fallback introduced for FND-7.

**What**: the FND-7 fallback works — but it is *quiet*. On degradation the app still
returns **HTTP 200** with a complete, plausible, genuinely-correct report; the only signal
is one line at the top of the tool-call trace, rendered as grey monospace in a card at the
**bottom** of the page.

**This is not hypothetical — it already happened, to the project's own operator.** During
spike C2 (2026-07-30) the ADK engine threw immediately on a blank
`triage.integrations.llm.api-key`, degraded to the deterministic engine, and returned a
correct `Payments Platform Support` assignment with a real log↔code citation and two posted
comments. The run was reported as *"successfully ran C2"*. **No LLM was called at all.**
Evidence: `bin/spike-output.log`, trace line 1.

**Why it matters**: the demo's central claim is *"this is a high Copilot model reasoning,
on rails"* (D1) and D3's contrast asserts the model is the same frontier one Copilot CLI
runs. Presenting a silently-degraded run makes both statements false on stage — the exact
failure mode we removed on the *model-tier* axis (a mini model masquerading as frontier)
reappearing on the *engine* axis. It also cost a wasted spike cycle: C2's real question
(does a frontier model converge in the 14-call budget, and emit valid J4 JSON?) remains
unanswered, while looking answered.

**Mitigated 2026-07-30, not fully closed.** Two changes:
1. The UI now renders a **prominent amber banner** above the report when any trace line
   matches `degraded to the deterministic engine` — "⚠ Degraded run — this is NOT the live
   agent … no LLM was involved — do not describe it as model reasoning."
2. `secrets.properties.example` no longer ships a blank `llm.api-key` (the specific trigger
   here), with a comment explaining that blank ≠ optional.

**Still open for CDS**: the API response itself carries no machine-readable engine field —
a consumer (the ServiceNow work note, a future caller) still cannot distinguish a live from
a degraded run without string-matching the trace. Options: add an `engine`/`degraded` field
to `DiagnosisResult` (touches the J4 contract, hence a design call) · label the posted work
note when degraded · return a distinct HTTP status. Worth deciding before the poller (K1)
runs unattended, where nobody is watching a UI at all.

- **Resolution**: fixed:d1f0866 + 694ec08 (UI banner + DiagnosisResult.engine field)
- **Escape**: observability — the FND-7 fallback was correct but silent, and 'looks like success' is the most expensive kind of wrong. It cost a spike cycle before being noticed.

---

## FND-7 — `LlmCallsLimitExceededException` is unhandled: the J8 safety cap crashes
instead of degrading · **HIGH** · ✅ **RESOLVED 2026-07-30**

> **Resolution**: `DiagnosisOrchestrator` now catches any exception from the primary
> engine and falls back to the deterministic engine (chose that option from the three
> listed below — it reuses proven, tested, network-free code instead of fabricating a
> partial J4 report). `DeterministicDiagnosisEngine` is registered unconditionally (no
> longer gated on `triage.engine`) so it's always available as the fallback;
> `AdkDiagnosisEngine` is `@Primary` so it still wins as the active engine when both
> beans exist. The fallback is disclosed as the first trace line, never silent. When the
> active engine already IS the deterministic one, failures propagate normally — no
> self-fallback masking a real bug. Verified: `mvn test` and `mvn -Padk test` both pass
> (two new tests cover the fallback firing and the propagate-when-already-fallback case);
> re-ran the live `-Padk` app against the stub proxy configured to never converge — was a
> 500, now `HTTP 200` with `trace[0]` reading "⚠ primary engine did not converge
> (RuntimeException: …LlmCallsLimitExceededException…) — degraded to the deterministic
> engine". See `src/main/java/com/company/triage/orchestration/DiagnosisOrchestrator.java`.

Original finding (kept for record):

**Where**: `src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java` —
`runAgent()` (the `blockingForEach` call, ~line 158) has no `try/catch`; `diagnose()`'s
only `catch` (line 173, inside `parse()`) is for JSON parse failures, not this.

**What**: confirmed by actually running the `-Padk` build on 2026-07-30 (first time the
build has been executable — a JDK/Maven weren't available earlier in the project).
Pointed `triage.integrations.llm.*` at a local stub proxy (`bin/fake-openai-proxy.py`,
extended to fill tool-call arguments by JSON-schema type so it wouldn't produce its own
false failures) that always answers with a `tool_calls` response, never a final prose/JSON
answer — which is a legitimate model behavior a poorly-prompted or struggling real model
can also produce. The agent loop keeps calling tools until the `RunConfig.setMaxLlmCalls`
backstop (`maxToolCalls + 4` = 14 by default) trips
`com.google.adk.models.LlmCallsLimitExceededException`, which propagates uncaught through
`diagnose()` → the controller → an **unhandled 500** with a raw stack trace.

**Why it matters**: this is precisely the J8 "hard backstop on top of the tool-call
bounds" (the code comment's own words) — the mechanism that exists so a model that won't
converge can't run forever. It works as a *limiter*. It does not work as *demo-safety*:
tripping it crashes the request instead of returning a bounded, honest advisory
("investigation did not converge within its budget; partial evidence: …"), which is
exactly the failure mode D2 (deterministic fallback) and the whole "advisory-only,
bounded, never fails ugly" pitch are supposed to prevent. On stage, a model that stalls or
loops (rate limiting, an ambiguous incident, a proxy hiccup) would 500 instead of
gracefully degrading — and the runbook's fallback flip (switch browser tabs to :8081) only
helps if someone notices the crash and reacts; it doesn't make T2 itself safe.

**Options to weigh in CDS** (not decided): catch the exception in `runAgent()`/`diagnose()`
and synthesize a partial `DiagnosisReport` from whatever evidence the tool-call trace
already gathered · catch and fall back to invoking `DeterministicDiagnosisEngine` for that
request (auto-flip, not just the manual one in the runbook) · catch and return a plain
advisory-text response distinct from the strict J4 JSON shape, with `confidenceOverall`
forced to the lowest tier.

**What's still unverified** (out of scope for this stub, deliberately not fabricated): a
*real* model, given the actual tool schemas and incident context, may converge well within
the 14-call budget and never hit this path at all — the corp-laptop E2 spike against the
real Copilot-served model is the only way to know. This finding is about the missing
safety net, not a claim that the cap will trip in practice.

- **Resolution**: fixed:d1f0866 (orchestrator degrades to the deterministic engine)
- **Escape**: test-coverage — the J8 backstop was implemented and never exercised, so nobody saw that tripping it produced an unhandled 500 rather than a graceful degrade.

---

## FND-6 — `D1`–`D5` means two different things in two DDS · **LOW**

**Where**: `docs/discovery/servicenow-triage-java/STATUS.md` (D1–D5 = agent-engine forks)
vs `docs/discovery/orchestrator-vs-copilot-cli/4-decide/concepts-extracted.md` (D1–D4 =
demo paths).

**What**: the same ids label unrelated things in two live workspaces. Everything in this
file and in `DEMO-RUNBOOK.md` uses the *demo-path* namespace (D1 = our orchestration on a
high model, D2 = deterministic fallback), but a reader arriving from the Java DDS will
resolve them to the engine forks.

**Why it matters**: pre-existing and low-impact, but it is a live ambiguity in the demo
docs. Cheapest fix is renaming one set at the next CDS round.

- **Resolution**: fixed:this-commit (namespace notes on both DDS STATUS files; not renamed)
- **Escape**: naming-convention — two workspaces minted the same id prefix independently; nothing reserves or namespaces card ids across DDS workspaces.

---

## FND-5 — J6 claims the log↔code citation needs no deterministic engine · **MEDIUM**

**Where**: `docs/design-java/concepts/J6-knowledge-tools/README.md:37-41` ("no
deterministic engine") vs
`src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java:112-116`.

**What**: `DeterministicDiagnosisEngine` produces the same `file:line` citation with no
LLM at all, and it is both the app default and D2's on-stage fallback. J6's claim is a
statement about the *agentic* path that reads as a statement about the system.

**Why it matters**: it undercuts D2 — if the deterministic engine can't do log↔code
citation, the fallback doesn't preserve the evidence trail. It can; the doc says otherwise.

- **Resolution**: fixed:this-commit (J6's claim scoped to the agentic path)
- **Escape**: doc-precision — a true statement about one code path written as a statement about the system. It undercut D2 by implying the fallback loses the evidence trail.

---

## FND-4 — LLM config surface: C2 vs J2 disagree · **MEDIUM**

**Where**: `docs/discovery/copilot-cli-runtime/4-decide/concepts-extracted.md:14-16`
(`triage.integrations.llm.{base-url,api-key,model}`) vs
`docs/design-java/concepts/J2-adk-agent-loop/README.md:15-19` (`env("LLM_BASE_URL")` /
`env("LLM_API_KEY")`).

**What**: the code (`AdkModelFactory`) actually resolves *both*, most-specific-first
(system property → env → `secrets.properties`), so nothing is broken — but J2 documents
only the older env-var form, and C2/`secrets.properties` is now the documented route.

- **Resolution**: fixed:this-commit (J2 documents the full most-specific-first resolution order)
- **Escape**: doc-drift — config surface changed (env vars -> secrets.properties) and the card was not updated with it. Cheap to catch, and it later cost a whole spike cycle when a blank api-key silently degraded C2.

---

## FND-3 — The two-engine split (D2) is owned by no J-card · **MEDIUM**

**Where**: `docs/discovery/orchestrator-vs-copilot-cli/4-decide/concepts-extracted.md`
(D2 = deterministic engine as the guaranteed fallback) vs
`docs/design-java/concepts/J1-spring-boot-orchestrator/README.md:16-18` and `J2`.

**What**: D2 makes the deterministic/ADK split load-bearing for the demo, but J1 describes
a single path ("invokes the ADK agent (J2)"), and neither J1 nor J2 mentions the
`triage.engine` switch — even though `deterministic` is the **default**
(`matchIfMissing = true`). The demo's stage safety net exists only in code and in the
runbook, not in the design.

- **Resolution**: fixed:this-commit (J2 documents the two-engine split; J1 done in d1f0866)
- **Escape**: design-review — D2 was decided in a DDS and implemented in code, but no CDS card owned it, so the demo's stage safety net existed only in the runbook.

---

## FND-2 — `suggestedContacts` (J9) absent from the J4 report contract · **MEDIUM**

**Where**: `docs/design-java/concepts/J9-contact-suggestion/README.md:29-30` vs
`docs/design-java/concepts/J4-diagnosis-report/README.md:12-39,50`.

**What**: J9 adds `suggestedContacts` to the report and the Java record carries it, but
J4 — marked **🟢 Stable** and declared "the single source" that both the ServiceNow
comments and the UI derive from — does not list the field. The apparent intent (contact
names must not reach the ServiceNow work note) is stated nowhere in the contract.

**Why it matters**: J4 is the schema of record. An unstated carve-out is exactly the kind
of thing that leaks personal names into a customer-visible ticket.

- **Resolution**: fixed:this-commit (J4 contract documents suggestedContacts + the UI-only carve-out; locked by DiagnosisReportNoteTest)
- **Escape**: contract-review — the carve-out WAS documented on the Contact record but never propagated into J4, the declared schema of record. A privacy invariant enforced only by 'no code happens to reference the field' is not enforced.

---

## FND-1 — Poller re-trigger loop: J5's own writes bump the K2 cursor · **HIGH** · ✅ **RESOLVED 2026-07-30**

> **Resolution**: the poller was built (CDS `J10-incident-poller`) querying
> **`sys_created_on`**, not `sys_updated_on`. Creation time is immutable, so J5's work-note
> writes cannot resurface a ticket — a structural fix rather than a filter, and what
> `C-T3: insert-only` always intended. Three further layers: an in-flight claim set, a
> bounded completed set, and J5's existing note-level idempotency.
>
> Two additional bugs were found and fixed while implementing it, both of which would have
> caused **silent incident loss** (the opposite failure to the one this finding describes):
> advancing the cursor to `now()` after a batch drops anything created *during* processing;
> and a "newest handled" high-water mark drops an early failure whenever a later incident in
> the same batch succeeds. The cursor now advances only across an unbroken run of handled
> incidents, oldest first. Both are asserted as tests
> (`incidentCreatedDuringProcessingIsNotSkipped`, `failedIncidentIsRetriedAndDoesNotStopTheBatch`).
>
> **Still open, tracked on the J10 card**: cursor + completed set are in-process only, so a
> restart skips incidents created while the app was down.

Original finding (kept for record):

**Where**: `docs/discovery/servicenow-local-trigger/4-decide/concepts-extracted.md`
(K1 `sysparm_query` is *updated-since*, K2 cursor is max `sys_updated_on`) vs
`docs/design-java/concepts/J5-servicenow-gateway/README.md` (two automatic work-note
writes per run) vs `docs/discovery/servicenow-auto-trigger/4-decide/decision.md`
(constraint **C-T3: insert-only** — search the file for `C-T3`; line numbers shift).

**What**: K1 selects incidents by `sys_updated_on > cursor`. J5 then posts two work notes
to the incident, which **updates** it, advancing `sys_updated_on` past the cursor. On the
next poll the same incident is selected again → diagnose → post → re-select.

J5's idempotency guard ("skip if an identical AI note already exists") probably prevents
*duplicate comments*, but not the repeated LLM run behind them — which is the expensive
part, and on a Copilot seat also the ToS-sensitive part (C6).

C-T3 "insert-only" was the original defence against exactly this, and the K1/K2 design
does not carry it forward. Neither DDS references the other's trigger constraint.

**Why it matters**: an unattended poller loops on every incident it touches.

Demo-day exposure depends on which trigger actually runs. K1 polling is now the *current
decision* (it supersedes `servicenow-auto-trigger`'s manual-only deferral), so **if the
demo runs K1, this is live on stage** — the loop would re-diagnose the incident seconds
after the work notes post, burning Copilot calls in front of the audience. If the demo
runs the K3 manual trigger, it is latent. Either way it must be resolved before any
unattended run.

**Options to weigh in CDS** (not decided): filter the poll query to exclude records whose
last update was by the triage service account · track a processed-incident set keyed by
`sys_id` + content hash · make the cursor advance on `sys_created_on` instead · make J5's
write suppress the cursor bump explicitly.

- **Resolution**: fixed:694ec08 (poller built on sys_created_on; CDS card J10-incident-poller)
- **Escape**: design-review — the DDS specified an 'updated-since' cursor and no round asked 'what does our own write do to this query?'. A self-triggering trigger is a predictable class, not a surprise.

---
