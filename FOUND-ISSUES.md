# Found issues

Design-level findings that need a **design decision**, not a doc edit. Raised by
`/doc-test dds` on 2026-07-29 (triple-perspective conflict analysis: Codex + Gemini +
Claude). Doc-truth findings from that run were fixed directly; these are logged here
because resolving them means changing behaviour or a data contract, which belongs in a
CDS round with the operator present.

Format: `FND-<n>` · severity · where · what · why it matters.

---

## FND-1 — Poller re-trigger loop: J5's own writes bump the K2 cursor · **HIGH**

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

---

## FND-4 — LLM config surface: C2 vs J2 disagree · **MEDIUM**

**Where**: `docs/discovery/copilot-cli-runtime/4-decide/concepts-extracted.md:14-16`
(`triage.integrations.llm.{base-url,api-key,model}`) vs
`docs/design-java/concepts/J2-adk-agent-loop/README.md:15-19` (`env("LLM_BASE_URL")` /
`env("LLM_API_KEY")`).

**What**: the code (`AdkModelFactory`) actually resolves *both*, most-specific-first
(system property → env → `secrets.properties`), so nothing is broken — but J2 documents
only the older env-var form, and C2/`secrets.properties` is now the documented route.

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

---

## FND-7 — `LlmCallsLimitExceededException` is unhandled: the J8 safety cap crashes
instead of degrading · **HIGH**

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
