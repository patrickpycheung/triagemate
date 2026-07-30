# Found issues

**Backlog: 4 open** — 2 deferred design decisions (FND-43/44) + 2 raised by `/doc-test dds`
on 2026-07-30 (FND-45/46). None are bugs in shipped behaviour; see each entry. FND-1…FND-42
resolved; full detail in `docs/audit/found-issues-archive.md`.

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

## FND-43 — Poller cursor can skip a batch-limit's worth of same-timestamp incidents · **LOW**

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
