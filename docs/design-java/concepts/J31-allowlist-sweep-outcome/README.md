# J31 — Allowlist sweep outcome (one bad entry must not speak for the whole sweep)

**State**: 🔵 **Proposed** (2026-08-06) · **Complexity**: Simple
**Depends on**: J30 (estate binding), J14/FRI-5 (connector degradation), J6 (GitLab gateway)
**Amends**: J30/GEB-3 (which stated the honesty rule but not the aggregation rule)
**Source**: derived while reviewing [`docs/Patrick_gitlab-update_allowed-projects.md`](../../../Patrick_gitlab-update_allowed-projects.md)
(cheungp) against the current tree on 2026-08-06. Patrick's file is **already implemented** —
J30/GEB-1 shipped the real path. This card is the defect that implementation left behind.

## Essence

`triage.gitlab.allowed-projects` now holds two entries, deliberately (J30/GEB-1): the real
estate project `enterprise/parcel-systems/applications/delivery-hazards`, and the offline demo
fixture `order-payments/payment-service`, which **does not exist in the real GitLab estate**.

`IncidentSignals.rankAllowlist` **sorts, it does not filter** (`IncidentSignals.java:266` —
`ranked.sort(...)`, returning the whole list). So in real mode the phantom project is not a
dormant config line; it is a live candidate the engine will call GitLab with. Two things then
go wrong, both in `DeterministicDiagnosisEngine.java:416-429`:

```java
for (String project : projectsToTry) {
    try { codeHits = gitLab.searchCode(project, errorToken); }
    catch (GatewayUnavailableException e) {
        codeSearchFailed = true;   // J30/GEB-3
        …
        break;                     // ← ends the sweep
    }
    if (!codeHits.isEmpty()) break;
}
```

**ASO-A — a failure aborts the sweep, so a phantom entry can prevent the real search.**
`break` in the catch ends the loop for every remaining candidate. Ranking is by token overlap
with the incident's app, so on a "Delivery Hazards" incident the real project sorts first and
this stays hidden — but any incident whose tokens do not favour it puts the phantom first, its
404 breaks the loop, and **the real project is never searched at all**. The allowlist's order
of attempt becomes load-bearing, and nothing says so.

**ASO-B — the last attempt overwrites the outcome of a successful one.** When the real project
IS searched first and legitimately returns zero hits, the loop does not break (it only breaks
on non-empty hits), continues to the phantom, 404s, and sets `codeSearchFailed = true`. The run
then reports **"could not search"** for a code search that ran successfully and truthfully
found nothing.

That second one is the [FND-8 class](../../../audit/found-issues-archive.md) the codebase
guards everywhere else — and it is precisely the distinction **J30/GEB-3** demanded ("`0 hit(s)`
means both *searched, found nothing* and *never successfully searched anything*… those lead a
triager to opposite conclusions"). GEB-3 shipped the *flag*; what it did not specify is how the
flag aggregates over **several** attempts. With one allowlist entry the question could not
arise. GEB-1 added the second entry, and made it arise.

## Why this is not J30 re-litigated

J30 is 🟢 Built and its four rules are all satisfied as written. This card does not reopen it:

| J30 rule | Status | Relationship |
|---|---|---|
| GEB-1 — allowlist names the real estate, entries labelled | ✅ shipped | **Cause.** Adding the second entry is correct and stays; it is what makes a *sweep* real rather than a one-element loop |
| GEB-2 — a project that cannot resolve is skipped, not fatal | ✅ shipped (J14/FRI-5) | Holds at the level of the RUN. This card is the level below: skipped-project semantics *within* the sweep |
| GEB-3 — trace distinguishes "no code matched" from "could not search" | ✅ shipped | **Amended.** The distinction is recorded; the aggregation across attempts is not specified, so a later failure silently overwrites an earlier success |
| GEB-4 — an unresolvable entry is visible before a demo | 🔵 proposed (J20 home) | Complementary: GEB-4 warns ahead of time, this card makes the run correct when it was not heeded |

## Evidence — verified against the tree 2026-08-06

| Claim | How it was checked |
|---|---|
| Both entries present, phantom included | `application.yml:262-266` — labelled exactly as GEB-1 specifies |
| Ranking never filters | `IncidentSignals.rankAllowlist` sorts by token overlap and returns `List.copyOf(ranked)` — every entry survives |
| A 404 becomes `GatewayUnavailableException` | `RealGitLabGateway.searchCode` wraps `catch (Exception e)` → `throw new GatewayUnavailableException("GitLab", e)` |
| The allowlist check passes for the phantom | `requireAllowlisted` tests membership of the configured list, not existence in the estate — an allowlisted-but-absent project is exactly the gap |
| Failure ends the sweep | the `break` inside the catch at `DeterministicDiagnosisEngine.java:427` |

**Not verified live** — `gitlab.cd.auspost.com.au` is behind the same perimeter 403 J30
documents, so this is derived from the code path, not observed on the wire. The reachability
argument does not depend on the network: it is ordinary control flow.

## Design

### ASO-1 — a failed project is skipped, the sweep continues

Replace the `break` in the catch with `continue`. One unresolvable entry costs its own attempt,
not the candidates behind it — the same principle J14/FRI-5 applies one level up (one
unreachable connector costs its evidence, not the run), applied per allowlist entry.

Failures are still recorded; a run where **every** attempt failed is still a failed search.

### ASO-2 — the outcome aggregates honestly across attempts

`codeSearchFailed` must mean *"no attempt succeeded"*, not *"the last attempt failed"*. A
sweep where any project was searched successfully — including one that returned zero hits — is
a **search that happened**, and the report must say so. Concretely: only set the failure state
if no attempt returned normally, and keep the per-project failures in `gatewayFailures` so the
note can still say which entries could not be reached.

This makes the three outcomes distinct, which is what GEB-3 was after:

| What happened | Report should say |
|---|---|
| Some project searched, hits found | the citations |
| Some project searched, no hits anywhere | searched, found nothing (**not** a degradation) |
| No project could be searched | could not search — with which ones failed and why |

### ASO-3 — the demo fixture should not be a real-mode candidate

The deeper fix, and the one Patrick's file implied by saying *update to* rather than *add*: an
entry that exists only for the offline fixture has no business being called against the real
estate. J30 kept it for a good reason (`MockGitLabGateway` cites it, and the stage walkthrough
must work with no network) — but "kept in config" need not mean "swept in real mode".

Scope it to the mock profile, or mark it in config as demo-only and have the real gateway's
sweep skip entries so marked. Either removes the phantom 404 at the source instead of handling
it — ASO-1/2 are then defence in depth rather than the only guard.

**Decide between the two mechanisms at implementation time** (ADM-1, reversible, local): the
profile split is cleaner but moves config into two places; the marker keeps one list at the
cost of a field. Prefer whichever leaves the labelled single list J30/GEB-1 argued for.

## Verification

| Check | Passes when |
|---|---|
| Sweep with a failing FIRST entry | remaining projects are still attempted; a hit in a later project is still cited |
| Sweep where a real search returns empty and a later entry 404s | report says *searched, found nothing* — `codeSearchFailed` is false |
| Sweep where every entry fails | report says *could not search*, listing each failure |
| Real-mode run | the demo fixture is never called against the real estate (ASO-3) |
| Offline demo | `run-deterministic.sh` walkthrough unchanged — the fixture still resolves in mock mode |
| `mvn test` | green, with a regression test per ASO-1/ASO-2 that fails before the change |

## Out of scope

- **The allowlist's contents** — J30/GEB-1 owns them, and they are correct.
- **`searchCode`'s error handling** — J14/FRI-5 owns it; this card only changes what the
  *caller* does with the exception.
- **Startup detection of unresolvable entries** — J30/GEB-4, whose home is J20.
- **Whether the search term is right** — J29, shipped.
