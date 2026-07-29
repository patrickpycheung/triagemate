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
