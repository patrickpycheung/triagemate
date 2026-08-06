# J30 — GitLab estate binding (the allowlist must name projects that exist)

**State**: 🔴 Designed, not built — HIGH, **one input blocked** (see Blocker) · **Complexity**: Simple
**Depends on**: J6 (GitLab gateway), J3 (gateway contracts), **J14/FRI-5 (hard prerequisite — see below)**
**Amends**: J8 (what `triage.gitlab.allowed-projects` is *for*), J7 (the demo dataset's reach into real-mode config)
**Source**: teammate field report — [`docs/Patrick_gitlab-call-failed-issue.md`](../../../Patrick_gitlab-call-failed-issue.md),
by **cheungp** (patrick.cheung@auspost.com.au), commit `934fb0a`, from a live
`run-deterministic-real.sh` against the real GitLab instance.
Re-verified against the current tree and the network on 2026-08-06 before this card was written.

## Essence

`triage.gitlab.allowed-projects` contains exactly one entry — `order-payments/payment-service`
— which is the **seeded demo project for the offline flow**. It does not exist in the real
GitLab estate. So in real mode the engine ranks an allowlist of one, searches a project that
cannot resolve, and GitLab answers `404 Project Not Found`.

The allowlist is doing its job as an allowlist. What it has never been is a statement about
the **real estate**, and nothing in the config says which of the two it is meant to be.

## Scope — what this card is NOT

Patrick's report contains three faults. Two are already handled and are **out of scope here**;
recording that explicitly so this card is not re-litigated into a duplicate:

| Fault | Disposition |
|---|---|
| **A** — `errorToken=GNAF_FRONTAGE` instead of the exception class | ✅ **Already shipped** (J29/LLF-3, `5e188d3`). `searchTermFor` prefers `EXCEPTION_CLASS` when the line carries a thrown FQN; the real line now yields `DataIntegrityViolationException`. See the note below on why the shipped shape differs from the report's proposal. |
| **B2** — `searchCode` has no error handling, so a 404 crashes the run with a 500 | 📋 **Already carded** as **J14/FRI-5**, which names `gitLab.searchCode` as one of its three aborting call sites. Not duplicated here. This card *depends* on it. |
| **B1** — the allowlist names only a project that does not exist | ⬅️ **This card.** |

### On Fault A's shipped shape

The report proposes swapping the priority unconditionally — `EXCEPTION_CLASS` first,
`ERROR_TOKEN` as fallback. What shipped is **detector-gated**: the class pattern wins only when
the message carries a genuine thrown FQN (`\b\w+(?:\.\w+){2,}\.\w*Exception\b`), otherwise the
snake-case token is kept. The difference matters for a line that mentions an exception in prose
while its real signal is a code like `PAYMENT_RECONCILE_MISMATCH` — the mock demo's exact
shape. Unconditional swapping would regress it. The outcome on the reported line is identical.

## Why this is a concept, not a one-line YAML edit

Because **adding an entry, on its own, makes the failure worse.**

The engine sweeps the ranked allowlist
([`DeterministicDiagnosisEngine.java:332-337`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L332)):

```java
for (String project : projectsToTry) {
    codeHits = gitLab.searchCode(project, errorToken);   // ← throws on 404
    if (!codeHits.isEmpty()) break;
}
```

`searchCode` currently propagates. So with two entries, a 404 on the **first** aborts the run
before the second is ever tried — and ranking (`IncidentSignals.rankAllowlist`, which orders by
token overlap with the incident's app) does not guarantee the reachable one sorts first. A
longer allowlist means *more* chances to hit the aborting path, not more chances to find code.

**J14/FRI-5 is therefore a hard prerequisite, not a nice-to-have.** Ship FRI-5 first, then this.
Ship this first and the demo gets strictly less reliable.

That coupling is the concept: an allowlist is only a *preference list* if the sweep survives a
miss. Today it is a list of one thing that must work.

## Evidence — verified 2026-08-06

| # | What | Where | Status |
|---|---|---|---|
| 1 | The allowlist holds only the demo project | [`application.yml:233-234`](../../../../src/main/resources/application.yml#L233) | **CONFIRMED** — single entry, `order-payments/payment-service` |
| 2 | Real GitLab answers 404 for it | field report `934fb0a` | **Reported**, not re-observed here (see Blocker) |
| 3 | The 404 propagates as a 500 | [`RealGitLabGateway.java:63-68`](../../../../src/main/java/com/company/triage/gateway/real/RealGitLabGateway.java#L63) | **CONFIRMED** — bare `.retrieve()`, no try/catch, while `recentCommitters` in the same class *is* guarded |
| 4 | Project selection IS incident-derived | [`IncidentSignals.java:231`](../../../../src/main/java/com/company/triage/orchestration/IncidentSignals.java#L231) | **CONFIRMED** — `rankAllowlist` sorts by token overlap with the app. The ranking is not the bug; the list's contents are. |

## Blocker — the correct project path cannot be discovered from a dev machine

The fix needs one fact this repo does not contain: **the real GitLab path for the
delivery-hazards application**. It cannot be looked up from here, and the reason is worth
recording precisely, because it will be misdiagnosed as a credential problem:

```
GET https://gitlab.cd.auspost.com.au/api/v4/version   with token -> 403, Server: awselb/2.0
GET https://gitlab.cd.auspost.com.au/api/v4/version   NO token   -> 403, Server: awselb/2.0
GET https://gitlab.cd.auspost.com.au/                 (web root) -> 403, Server: awselb/2.0
```

Identical 403 **with and without** the token, and on the plain web root, served by an AWS load
balancer. That is a **network-perimeter block**, not an auth failure — the request never
reaches GitLab. The configured token is therefore neither validated nor invalidated by this;
it may be perfectly good. This matches the corp-network constraint the K1 poller design
already works around.

**Consequence for planning**: the *code* items below are implementable now; item **GEB-1**
needs one line of information from someone on a network that can reach GitLab (Patrick's
environment can — his 404 is genuine GitLab behaviour, which is itself the proof that the
project genuinely does not exist rather than being perimeter noise).

This is an **information gap, not an operator-authority gap** — it needs a lookup, not a
ruling, so it is tracked here rather than parked in `/OPERATOR-ACTIONS.md`.

## Design

### GEB-1 — the allowlist names the real estate, and says which entries are which

Add the real delivery-hazards project path, and **label** both entries so the next reader can
tell a demo fixture from an estate binding. Today they are indistinguishable, which is how a
demo-only value came to be the sole real-mode configuration.

```yaml
allowed-projects:
  - order-payments/payment-service   # OFFLINE DEMO fixture — not in the real estate
  - <group>/<delivery-hazards>       # REAL estate — path pending (see Blocker)
```

Blocked on the Blocker above. Everything else here ships without it.

### GEB-2 — a project that cannot resolve is a skipped project, not a failed run

Delivered by **J14/FRI-5** (`searchCode` degrades to an empty list, with a trace line). Named
here only as the dependency edge. **Do not implement it in this card** — one owner per rule.

### GEB-3 — the trace distinguishes "no code matched" from "could not search"

Once the sweep survives a miss, `0 hit(s)` becomes ambiguous: it means both "searched, found
nothing" and "never successfully searched anything". Those lead a triager to opposite
conclusions. The trace must separate them — e.g. a project that errored is reported as
attempted-and-failed, distinct from attempted-and-empty.

This is the same honesty rule J25/KQR-4 applies to a failed-vs-empty Confluence search, and
the same one J29/LLF-2 applies to an unreadable log level. Third instance of the pattern —
worth stating once as a rule if a fourth appears.

### GEB-4 — an unresolvable allowlist entry is visible before a demo, not during one

A 404 on an allowlisted project is a **configuration** fault: it is true before the run
starts, and discoverable without an incident. Surface it as a startup or diagnostic check
(advisory — this must not become a boot-blocking gate, since the perimeter above means a
correct config can legitimately fail to verify from a dev machine).

The J20 startup-truth work is the natural home for the mechanism; this card supplies the rule.

## Verification

| Check | Passes when |
|---|---|
| Config review | Both entries labelled; the demo fixture is not the only real-mode option |
| A 404 on the first ranked project | The sweep continues to the next; the run completes with a degraded report (needs FRI-5) |
| Trace on a failed search | Distinguishable from a search that legitimately found nothing |
| Live `run-deterministic-real.sh` on delivery-hazards | Completes; GitLab step either cites code or says why not — never a 500 |
| `mvn test` | green |

## Out of scope

- **`searchCode`'s error handling** — J14/FRI-5 owns it.
- **The token's validity.** Cannot be established from here (perimeter 403) and there is no
  evidence against it. Do not "fix" a credential on the strength of a network block.
- **Whether the GitLab search term is right** — J29 owns that, and it is shipped.
