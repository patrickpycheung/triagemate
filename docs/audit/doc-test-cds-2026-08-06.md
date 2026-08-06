# /doc-test cds — 2026-08-06

Run from `worktree-hack-111` at the end of a `/cds` session over `docs/design-java/`
(30 cards). **This is a partial run — see "Phases not run" at the bottom. Do not read it
as a clean bill of health.**

---

## CRITICAL — G1: the fallback engine can still 500 on one failing connector

> ### ⚠️ CORRECTED 2026-08-06, after implementing J14/FRI-6
>
> **The mechanism below is WRONG. The conclusion is right, for a simpler and worse reason.**
> I built the FRI-6 fixture corpus specifically to test this analytically-derived chain, and
> the test disproved my own mechanism while confirming the outcome.
>
> **What I predicted** (uncited candidate → `uncitedCandidates()` → `DiagnosisReportInvalidException`
> → 500): **NOT REPRODUCED.** Probed every single-call failure point. `sumo.search`,
> `confluence.search` and `gitlab.searchCode` all degrade cleanly — FRI-5 holds, the report is
> assembled and passes the validator. ECI-6 and FRI-5 do **not** conflict. hack-222 owes me
> nothing on that.
>
> **What is actually broken**: `serviceNow.findSimilarIncidents` (`DeterministicDiagnosisEngine:193`)
> and `serviceNow.findOwnership` (`:210`) are called **bare** — no try/catch. FRI-5's
> degradation exists at `:235`, `:348`, `:419`, `:795` but not around those two. So a
> `GatewayUnavailableException` from ServiceNow escapes `diagnose()` entirely, **no report is
> ever assembled**, the validator is never reached, and
> `DiagnosisApiExceptionHandler` has **no `@ExceptionHandler` for `GatewayUnavailableException`**
> (contrast `ResourceAccessException`:78 → 504, `DiagnosisReportInvalidException`:91 → 500) —
> so it surfaces as a bare **HTTP 500 out of the FND-7 fallback engine**.
>
> **FRI-5 is incomplete, not conflicting.** Its own spec named all four sites verbatim:
> *"wrap the three aborting call sites (`sumo.search:180`, `gitLab.searchCode:214`,
> `serviceNow.findSimilarIncidents:151` / `findOwnership:139`)"*. Two of the four were never
> wrapped, and the new exception type has no handler.
>
> Now proved by a reproducible red test rather than an argument:
> `DeterministicRealShapedInputTest#aFailingServiceNowCallStillProducesAValidReport`,
> `@Disabled` with the reason inline. Re-enable it as the fix's acceptance criterion.
>
> *Lesson for this report: the original G1 was a plausible chain assembled by reading code,
> and it was wrong. It took an executable fixture to find that out — which is exactly what
> FRI-6 exists for, and exactly the failure class (reasoning over real shapes without running
> them) that FND-47, FND-61, J24 and J29 all belong to.*

### Original (superseded) analysis — FRI-5 and ECI-6 together can 500 the fallback engine

**Confidence: HIGH** (found by the Claude conflict pass, then verified against code).
**Both halves shipped today, hours apart, by different worktrees. Neither card mentions the other.**

The chain, every link verified:

| # | Fact | Where |
|---|---|---|
| 1 | The deterministic (fallback) engine validates its own report | `DeterministicDiagnosisEngine.java:696` |
| 2 | ECI-6 added `uncitedCandidates()` as a **hard** rule — a candidate with no `evidenceRefs` throws | `DiagnosisReportValidator.java:56` |
| 3 | `DiagnosisReportInvalidException` maps to **HTTP 500** | `DiagnosisApiExceptionHandler.java:91-93` |
| 4 | FRI-5 makes a failing connector degrade to an **empty** result | `GatewayUnavailableException` + per-call degradation |
| 5 | Candidate refs are filtered by `refsThatExist(...)`, so evidence that never materialised leaves a candidate with **empty refs** | `DeterministicDiagnosisEngine` (candidate assembly) |

So: a degraded connector (4) produces no evidence, which empties a candidate's refs (5),
which the validator now hard-rejects (2), which returns a 500 (3) — **from the safety-net
engine**, on the path FND-7 exists to protect.

The codebase already names this exact shape as the worst thing that can happen. From the
comment beside the `openedAt` null guard:

> *"the orchestrator would degrade to it and then get a 500 out of it, which is the single
> worst failure shape this app has."*

That guard was added to close this shape for a **null date**. ECI-6 has re-opened it for a
**missing citation**, and FRI-5 made missing citations more likely, not less.

**Neither card is wrong on its own.** ECI-6's reasoning ("a contract violation is repaired,
not degraded") is sound. FRI-5's reasoning ("degraded means a weaker report, never a 500")
is sound. They are individually correct and jointly unsafe, which is precisely the class of
defect cross-card conflict analysis exists to find and which no single-card review would
have caught.

**Not fixed here.** ECI-6 and FRI-5 are both `worktree-hack-222`'s work and were in flight
during this run. Editing their cards mid-flight is how the `_loglevel` regression happened
earlier today. This needs their author.

**Suggested resolution (for whoever picks it up)**: the validator's hard rules should
distinguish *"the engine produced a self-inconsistent report"* (our bug → 500 is honest)
from *"a connector degraded, so a candidate legitimately has nothing to cite"* (expected →
the candidate should be dropped or the report should disclose it, per the FRI-5 /
`missingInformation` idiom already in the engine). The J13 "repair, not degrade" rule and
the J14 "never a 500" rule need one shared statement of which is which.

---

## G2 — 33 non-reciprocal `Amends` references (MEDIUM)

Phase 2b. Every `Depends on:` / `Amends:` target resolves (no dangling refs), but 33
`Amends` are one-directional: J12 amends J11 and J11 never mentions J12; J16 amends J11,
same; J13 amends J4 and J8; J14 amends J2/J3/J5; and so on.

Consequence: a reader who opens J11 has no way to discover that J12, J16 and J23 have since
amended it. The amendment is only visible from the amending side. This is how J17 came to be
designed against LT4 rule 4 after J16/RTR-1 had retired it (G3 below).

---

## G3 — J17 is designed on an invariant that J16 deleted (HIGH)

`J17-poller-completion-semantics/README.md:221` states K1 "registers no buffer **by design**
(LT4 rule 4); this card does not change that." **J16/RTR-1 retired LT4 rule 4** — every run,
including a K1-owned one, now mints a server-side `runId` and registers a buffer.

J17 is 🔴 Designed-not-built, so nothing is broken in code; but PCS-1…PCS-5 were reasoned
from a premise that no longer holds and should be re-read before implementation. This is my
own change invalidating someone else's card, and G2 is why it was invisible.

---

## Other findings, by source

**Codex (`gpt-5.6-sol`, 5 most collision-prone cards) — 9 conflicts, 5 HIGH.** Highlights not
already covered: J25/KQR-2 enforces relevance only in the deterministic engine while the ADK
`search_confluence` tool returns gateway results directly, so J28 cannot safely open
`KNOWN_ERROR_DOC` on the strength of "J25 is built"; J28 carries four parallel identifiers
(`citedArtifacts`, `evidenceRefs`, `quotedFinding`, `ResolutionStep.citedArtifact`) that are
not required to agree; J28's `resolutionCode` is an unrestricted string rendered to the user
while only `ResolutionVerb` is a closed enum.

**Claude (independent, all 30 cards) — 19 conflicts.** Highlights not already covered:
J13/ECI-4 and J18/GEC-6 define two different caps (3 vs 5) with two different knobs for the
same GitLab fan-out; `StepState` is declared without `ABANDONED` while three cards use it as
a real state; J2 names `AdkDiagnosisEngine.ALLOWED_TOOLS` as the tool authority while J11/J18/J19
all say it moved to `guardrails/ToolRegistry`; four cards (J12, J16, J23, J28) all claim edit
rights over the same `index.html` live-trace block.

**Gemini — SKIPPED, and its result must not be counted.** `agy 1.1.10` ran and exited 0, but
it resolves paths against its own scratch workspace rather than the shell cwd, so it found no
concept files and wrote *"0 conflicts"*. That zero means "0 files read", not "no conflicts".
Recorded here explicitly because a summary line reading `Gemini: 0 conflicts` next to
`Codex: 9` would look like disagreement to be adjudicated, when it is simply an unrun tool.
(The `--yolo` flag in the global CLI contract is also stale for agy 1.1.10 — it prints help.)

---

## Phases not run

| Phase | Status |
|---|---|
| 2 CDS validation | RUN — G2 found; structure PASS (README-only is this workspace's convention, not a gap) |
| 3 Scenario simulation | **NOT RUN** |
| 4 Conflicts — Codex | RUN — 9 conflicts |
| 5 Conflicts — Gemini | SKIPPED — tool could not read the inputs (above) |
| 6 Conflicts — Claude | RUN — 19 conflicts |
| 7/8/9 Architecture triple | **NOT RUN** |
| 10 CDS coverage audit | **NOT RUN** |
| 11 Verification loop | **NOT RUN** — findings are open, not closed |

Overall: **FAIL** (Phase 11 did not reach ALL CLEAN). The conflict phases alone produced more
than enough to act on, and three worktrees were committing to the same cards during the run,
which makes an architecture pass over a moving codebase low-value and a fix-loop actively
unsafe. Re-run 3/7/8/9/10 when the worktrees are quiet.
