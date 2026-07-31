# Found issues

**Backlog: 2 open** (FND-55, FND-56). FND-57/58 (also from the second `/doc-test cds`
pass on 2026-07-31) were fixed the same day via the `TriageProperties` refactor.
FND-52/53/54 were fixed in the pass itself; FND-1…FND-54, FND-57, FND-58 resolved.
Full detail in `docs/audit/found-issues-archive.md`.

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

## FND-55 — FND-34's HTTP timeout pre-empts FND-15's engine timeout; the 504 is mostly unreachable · **MEDIUM**

**Where**: `application.yml` (`spring.http.client.read-timeout: 20s`),
`DiagnosisOrchestrator` (`timeout-ms: 90000`), `DiagnosisApiExceptionHandler`.
**What**: the two bounds overlap and the HTTP one wins. `read-timeout` applies to
`RealServiceNowGateway`'s injected builder — *including* `getIncident` inside
`engine.diagnose()` — so a hung real ServiceNow fails at ~20s with `ResourceAccessException`,
not at 90s with `DiagnosisTimeoutException`. That type isn't mapped, so it surfaces as a bare
500. The documented 504 is reachable only via Confluence/Sumo/GitLab (own unconfigured
`RestClient`, no HTTP timeout) or an ADK run genuinely exceeding 90s. Found by scenario
simulation.
**Why deferred**: the precedence is now documented in J1, which removes the misleading part.
Actually *fixing* it means choosing a policy — map `ResourceAccessException` to 504 too, or
align the two budgets — and that's a design call better made with the real-latency data the
J11 spike will produce.

## FND-56 — The K1 C6 warning is config-triggered, not capability-triggered · **LOW**

**Where**: `IncidentPoller` constructor (FND-45's check).
**What**: it reads the `triage.engine` string without checking that an ADK bean exists, so on
a non-`-Padk` build with `engine=adk` it claims "unattended, programmatic LLM use" for a run
that will never contact a model — while J1's FND-49 warning simultaneously says the opposite
(deterministic only). Both fire; only one is true. Found by two reviews.
**Why deferred**: now documented in J10 as expected-and-explained. The code fix wants the same
engine-identity check FND-49 uses, which is really an argument for FND-57's single validator
rather than a second ad-hoc check.

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
